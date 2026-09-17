package tormozit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.common.ui.controls.ImageViewer;
import com._1c.g5.v8.dt.platform.pictures.PictureVariantScreenDensity;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureContent;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureManifestEntry;
import com._1c.g5.v8.dt.platform.pictures.zip.ZipPictureContentStore;

/**
 * Единое поведение поля картинки во всех окнах (редактор, мастер «Новая общая
 * картинка», «Выбор картинки»): Ctrl+колёсико, контекстное меню масштаба,
 * подпись формат·размер·глубина.
 *
 * <p>Окна поля — любое изменение вида сверять по каждому:
 * <ul>
 * <li>редактор общей картинки — {@link ImageViewer};</li>
 * <li>мастер «Новая общая картинка» — {@link ImageViewer}, в т.ч. архив набора (ZIP);</li>
 * <li>«Выбор картинки», вкладка «Из библиотеки» — SWT {@link Label};</li>
 * <li>«Выбор картинки», вкладка «Из файла» — SWT {@link Label}, в т.ч. архив набора.</li>
 * </ul>
 * Общее для всех: подпись — {@code formatMeta(SourceBytes, …)} (по центру), размещение
 * панели проигрывателя — {@code GifPlayer.placeBar} (картинка → панель → подпись).
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
        // Проигрыватель GIF — SWT Canvas, FX колёсико не видит.
        if (session.control instanceof ImageViewer && session.player == null)
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
        java.util.function.Supplier<SourceBytes> sourceBytes)
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
                    if (session.zoom != 1.0 || session.player != null)
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

    /**
     * Исходные байты показанной картинки. {@code setVariants > 0} — картинка взята из набора
     * (ZIP с манифестом) с таким числом вариантов, {@code bytes} — байты показанного варианта.
     */
    public record SourceBytes(byte[] bytes, int setVariants)
    {
        public static SourceBytes single(byte[] bytes)
        {
            return bytes != null ? new SourceBytes(bytes, 0) : null;
        }
    }

    /** Подпись формата с пометкой набора — одна для всех полей картинки. */
    private static String formatMeta(SourceBytes source, String fileNameOrExt, ImageData fallback)
    {
        String meta = formatMeta(source != null ? source.bytes() : null, fileNameOrExt, fallback);
        if (source == null || source.setVariants() <= 0)
            return meta;
        return "Набор · " + source.setVariants() + " " + variantsWord(source.setVariants()) //$NON-NLS-1$ //$NON-NLS-2$
            + (meta.isEmpty() ? "" : " · " + meta); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String variantsWord(int n)
    {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14)
            return "вариантов"; //$NON-NLS-1$
        if (mod10 == 1)
            return "вариант"; //$NON-NLS-1$
        if (mod10 >= 2 && mod10 <= 4)
            return "варианта"; //$NON-NLS-1$
        return "вариантов"; //$NON-NLS-1$
    }

    /** Число вариантов набора картинок в архиве; 0 — архив не набор. */
    public static int countSetVariants(IZipPictureContent content)
    {
        if (content == null)
            return 0;
        java.util.Collection<IZipPictureManifestEntry> entries = content.getPictureEntries();
        return entries != null ? entries.size() : 0;
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
                if (child instanceof Label || child.getData(GifPlayer.PART_KEY) != null)
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

    /**
     * {@code equals}/{@code hashCode}/{@code toString} прокси-слушателя: JavaFX сравнивает
     * слушателей при снятии, {@code null} вместо {@code boolean}/{@code int} — исключение.
     */
    private static Object objectMethod(Object proxy, Method method, Object[] args)
    {
        return switch (method.getName())
        {
            case "equals" -> Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]); //$NON-NLS-1$
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
            default -> "PictureFieldEnhance listener@" //$NON-NLS-1$
                + Integer.toHexString(System.identityHashCode(proxy));
        };
    }

    private static Object fxHandler(Class<?> eventHandler, FxEventAction action)
        throws Exception
    {
        return java.lang.reflect.Proxy.newProxyInstance(
            eventHandler.getClassLoader(),
            new Class<?>[] { eventHandler },
            (proxy, method, args) ->
            {
                if (method.getDeclaringClass() == Object.class)
                    return objectMethod(proxy, method, args);
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
        // По центру — как подпись размера в «Выборе картинки».
        Label meta = new Label(host, SWT.CENTER);
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
                GifPlayer.update(session, null, false);
                return;
            }
            byte[] bytes = readUrlBytes(url);
            // Мастер общей картинки отдаёт WebView сам архив набора — показать его вариант.
            PictureSetVariant variant = "ZIP".equals(sniffFormat(bytes)) //$NON-NLS-1$
                ? PictureSetVariant.read(bytes) : null;
            if (variant != null)
            {
                setMetaText(session, formatMeta(new SourceBytes(variant.bytes, variant.count),
                    variant.name, null));
                GifPlayer.update(session, variant.bytes, true);
                return;
            }
            setMetaText(session, formatMeta(bytes, url.getPath()));
            GifPlayer.update(session, bytes, false);
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
            GifPlayer.update(session, null, false);
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
            return session.meta;
        SourceBytes source = null;
        if (session.sourceBytesSupplier != null)
        {
            try
            {
                source = session.sourceBytesSupplier.get();
            }
            catch (RuntimeException e)
            {
                Global.logError(LOG_TAG, "sourceMeta", e); //$NON-NLS-1$
            }
        }
        byte[] bytes = source != null ? source.bytes() : null;
        String meta = formatMeta(source, null, image.getImageData());
        session.meta = meta;
        session.metaImage = image;
        GifPlayer.update(session, bytes, false);
        return meta;
    }

    private static void setMetaText(Session session, String text)
    {
        if (session.metaLabel == null || session.metaLabel.isDisposed())
            return;
        session.metaLabel.setText(text != null ? text : ""); //$NON-NLS-1$
    }

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
        if (session.player != null)
        {
            session.player.showFrame();
            refreshMetaFromSwt(session);
            return;
        }
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
            // Классы воркера и свойства непубличные (WebEngine$LoadWorker в модуле javafx.web):
            // метод брать у публичного интерфейса, иначе IllegalAccessException.
            ClassLoader fxLoader = worker.getClass().getClassLoader();
            Class<?> workerType = Class.forName("javafx.concurrent.Worker", false, fxLoader); //$NON-NLS-1$
            Object stateProperty = workerType.getMethod("stateProperty").invoke(worker); //$NON-NLS-1$

            Class<?> changeListener = Class.forName("javafx.beans.value.ChangeListener"); //$NON-NLS-1$
            Class<?> observableValue = Class.forName("javafx.beans.value.ObservableValue", false, //$NON-NLS-1$
                changeListener.getClassLoader());
            Display display = viewer.getDisplay();
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                changeListener.getClassLoader(),
                new Class<?>[] { changeListener },
                (proxy, method, args) ->
                {
                    if (method.getDeclaringClass() == Object.class)
                        return objectMethod(proxy, method, args);
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
            observableValue
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
        java.util.function.Supplier<SourceBytes> sourceBytesSupplier;
        /** Образ, для которого посчитана {@link #meta}. */
        Image metaImage;
        String meta;
        double zoom = 1.0;
        int baseHeightHint = -1;
        Image scaledImage;
        /** Проигрыватель многокадрового GIF; {@code null} — картинка статичная. */
        GifPlayer player;
        /** Ключ байтов, уже проверенных на GIF-анимацию, — не разбирать повторно. */
        int gifCheckedKey;
        /** Пользователь поставил анимацию на паузу — следующая картинка поля тоже на паузе. */
        boolean gifPaused;

        Session(Control control, Label metaLabel,
            java.util.function.Supplier<Image> sourceImageSupplier)
        {
            this.control = control;
            this.metaLabel = metaLabel;
            this.sourceImageSupplier = sourceImageSupplier;
            control.addDisposeListener(e ->
            {
                if (player != null)
                {
                    player.dispose(false);
                    player = null;
                }
                disposeScaled(this);
            });
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
            {
                applyWebViewZoom(this);
                if (player != null)
                    player.showFrame();
            }
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

    /**
     * Вариант из архива набора картинок (ZIP с манифестом) для показа в поле: 100% без варианта
     * интерфейса и темы, иначе ближайший по масштабу. У мастера «Новая общая картинка» проекта
     * ещё нет, запрос варианта по его настройкам не построить.
     */
    private static final class PictureSetVariant
    {
        final int count;
        final String name;
        final byte[] bytes;

        private PictureSetVariant(int count, String name, byte[] bytes)
        {
            this.count = count;
            this.name = name;
            this.bytes = bytes;
        }

        /** {@code null} — не набор картинок (обычный архив) или вариант не прочитан. */
        static PictureSetVariant read(byte[] zip)
        {
            try
            {
                IZipPictureContent content = ZipPictureContentStore.INSTANCE.read(new ByteArrayInputStream(zip));
                if (countSetVariants(content) == 0)
                    return null;
                java.util.Collection<IZipPictureManifestEntry> entries = content.getPictureEntries();
                IZipPictureManifestEntry best = null;
                int bestScore = Integer.MIN_VALUE;
                for (IZipPictureManifestEntry entry : entries)
                {
                    int score = score(entry);
                    if (score > bestScore)
                    {
                        best = entry;
                        bestScore = score;
                    }
                }
                byte[] data = content.getInputStreamByName(best.getName())
                    .map(ByteArrayInputStream::readAllBytes).orElse(null);
                if (data == null)
                    return null;
                return new PictureSetVariant(entries.size(), best.getName(), data);
            }
            catch (IOException | RuntimeException e)
            {
                // Обычный архив, не набор картинок, — подпись останется «ZIP».
                return null;
            }
        }

        private static int score(IZipPictureManifestEntry entry)
        {
            int score = 0;
            PictureVariantScreenDensity density = entry.getScreenDensity();
            int base = PictureVariantScreenDensity.LDPI.getScale();
            score -= density == null ? 1000 : Math.abs(density.getScale() - base) * 10;
            if (entry.getInterfaceVariant() != null)
                score -= 5;
            if (entry.getTheme() != null)
                score -= 5;
            if (entry.getPictureDirection() != null)
                score -= 2;
            if (entry.isTemplate())
                score -= 2;
            return score;
        }

    }

    /**
     * Тонкий неконтрастный ползунок кадров GIF: системный {@code Scale} на Windows не
     * перекрашивается и на широком поле слишком заметен. Шаг — {@link #STEP_PX} на кадр,
     * по ширине — сколько нужно кадрам; в узкой ячейке шаг сжимается до её ширины.
     */
    private static final class FrameSlider extends Canvas
    {
        private static final int STEP_PX = 5;
        private static final int THUMB_W = 5;
        private static final int THUMB_H = 13;
        private static final int TRACK_H = 2;
        private static final int PAD = 2;

        private final int count;
        private final java.util.function.IntConsumer onSeek;
        private int frame;
        private boolean dragging;
        private boolean hot;

        FrameSlider(Composite parent, int count, java.util.function.IntConsumer onSeek)
        {
            super(parent, SWT.DOUBLE_BUFFERED);
            this.count = count;
            this.onSeek = onSeek;
            setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
            addListener(SWT.Paint, this::paint);
            addListener(SWT.MouseDown, e ->
            {
                if (e.button != 1)
                    return;
                setFocus();
                dragging = true;
                pick(e.x);
            });
            addListener(SWT.MouseMove, e ->
            {
                if (dragging)
                    pick(e.x);
            });
            addListener(SWT.MouseUp, e -> dragging = false);
            addListener(SWT.MouseEnter, e -> setHot(true));
            addListener(SWT.MouseExit, e -> setHot(false));
            addListener(SWT.FocusIn, e -> redraw());
            addListener(SWT.FocusOut, e -> redraw());
            addListener(SWT.Traverse, e ->
            {
                switch (e.detail)
                {
                    case SWT.TRAVERSE_ARROW_NEXT, SWT.TRAVERSE_ARROW_PREVIOUS -> e.doit = false;
                    case SWT.TRAVERSE_TAB_NEXT, SWT.TRAVERSE_TAB_PREVIOUS,
                        SWT.TRAVERSE_RETURN, SWT.TRAVERSE_ESCAPE -> e.doit = true;
                    default -> { }
                }
            });
            addListener(SWT.KeyDown, e ->
            {
                int page = Math.max(1, count / 10);
                switch (e.keyCode)
                {
                    case SWT.ARROW_LEFT, SWT.ARROW_DOWN -> seek(frame - 1);
                    case SWT.ARROW_RIGHT, SWT.ARROW_UP -> seek(frame + 1);
                    case SWT.PAGE_UP -> seek(frame - page);
                    case SWT.PAGE_DOWN -> seek(frame + page);
                    case SWT.HOME -> seek(0);
                    case SWT.END -> seek(count - 1);
                    default -> { }
                }
            });
        }

        void setFrame(int value)
        {
            if (frame == value || isDisposed())
                return;
            frame = value;
            redraw();
        }

        private void setHot(boolean value)
        {
            hot = value;
            redraw();
        }

        private void seek(int value)
        {
            onSeek.accept(Math.max(0, Math.min(count - 1, value)));
        }

        @Override
        public Point computeSize(int wHint, int hHint, boolean changed)
        {
            int w = wHint != SWT.DEFAULT ? wHint : (count - 1) * STEP_PX + THUMB_W + 2 * PAD;
            int h = hHint != SWT.DEFAULT ? hHint : THUMB_H + 2 * PAD;
            return new Point(w, h);
        }

        private double step()
        {
            if (count < 2)
                return 0;
            int usable = getClientArea().width - THUMB_W - 2 * PAD;
            return Math.max(0, Math.min(STEP_PX, usable / (double) (count - 1)));
        }

        private void pick(int x)
        {
            double step = step();
            seek(step > 0 ? (int) Math.round((x - PAD - THUMB_W / 2) / step) : 0);
        }

        private void paint(org.eclipse.swt.widgets.Event e)
        {
            GC gc = e.gc;
            Rectangle r = getClientArea();
            Color back = getParent().getBackground();
            Color fore = getParent().getForeground();
            gc.setBackground(back);
            gc.fillRectangle(r);

            double step = step();
            int x0 = PAD + THUMB_W / 2;
            int x1 = x0 + (int) Math.round(step * (count - 1));
            int thumbX = x0 + (int) Math.round(step * frame);
            int cy = r.height / 2;

            gc.setBackground(blend(fore, back, 0.15));
            gc.fillRectangle(x0, cy - TRACK_H / 2, x1 - x0 + 1, TRACK_H);
            gc.setBackground(blend(fore, back, 0.3));
            gc.fillRectangle(x0, cy - TRACK_H / 2, thumbX - x0 + 1, TRACK_H);

            boolean active = hot || dragging || isFocusControl();
            gc.setAntialias(SWT.ON);
            gc.setBackground(blend(fore, back, active ? 0.6 : 0.4));
            gc.fillRoundRectangle(thumbX - THUMB_W / 2, cy - THUMB_H / 2, THUMB_W, THUMB_H, 2, 2);
        }

        /** {@code amount} — доля цвета {@code fore} поверх {@code back}. */
        private Color blend(Color fore, Color back, double amount)
        {
            return new Color(getDisplay(),
                mix(fore.getRed(), back.getRed(), amount),
                mix(fore.getGreen(), back.getGreen(), amount),
                mix(fore.getBlue(), back.getBlue(), amount));
        }

        private static int mix(int fore, int back, double amount)
        {
            return (int) Math.round(back + (fore - back) * amount);
        }
    }

    /**
     * Проигрыватель многокадрового GIF: под картинкой «Пауза/Играть», ползунок и номер кадра.
     * Кадры раскладывает {@link ImageLoader}; склейка (смещение кадра, прозрачность, способ
     * очистки) — здесь, поэтому каждый кадр — полный снимок экрана анимации.
     *
     * <p>ImageViewer показывает картинку в WebView, а тот паузы и перемотки не даёт: его
     * FXCanvas скрывается ({@code exclude}), на его месте рисует свой {@link Canvas}.
     * SWT-превью «Выбора картинки» — кадры ставятся в тот же {@link Label}.
     */
    private static final class GifPlayer
    {
        /** Метка наших контролов внутри ImageViewer — {@link #bindSession} их не трогает. */
        static final String PART_KEY = INSTALLED_KEY + ".gifPart"; //$NON-NLS-1$
        /** Предел пикселей всех кадров вместе: сверх него картинка остаётся статичной. */
        private static final long MAX_TOTAL_PIXELS = 20_000_000L;
        /** Задержка кадра, когда в файле 0 или 1 (как в браузерах). */
        private static final int DEFAULT_DELAY_MS = 100;
        private static final int MAX_LABEL_PX = 2048;
        private static final String PAUSE_TEXT = "Пауза"; //$NON-NLS-1$
        private static final String PLAY_TEXT = "Играть"; //$NON-NLS-1$

        final Session session;
        final int key;
        final int width;
        final int height;
        final ImageData[] frames;
        final int[] delays;
        final Image[] images;
        /** Кадры, вписанные в Label, для размера {@link #scaledW}×{@link #scaledH}. */
        Image[] scaled;
        int scaledW;
        int scaledH;
        int index;
        boolean playing = true;
        Canvas canvas;
        Control hiddenFx;
        Composite bar;
        Button playButton;
        FrameSlider slider;
        Label frameLabel;
        final Runnable tick = this::tick;

        private GifPlayer(Session session, int key, int width, int height, ImageData[] frames,
            int[] delays)
        {
            this.session = session;
            this.key = key;
            this.width = width;
            this.height = height;
            this.frames = frames;
            this.delays = delays;
            this.images = new Image[frames.length];
        }

        /**
         * Привести проигрыватель к исходным байтам картинки: {@code null} или не многокадровый
         * GIF — убрать, тот же GIF — только перерисовать текущий кадр.
         * {@code showStatic} — показывать и однокадровую картинку любого формата SWT (вариант
         * из набора картинок: WebView архив не покажет), панель управления тогда не нужна.
         */
        static void update(Session session, byte[] bytes, boolean showStatic)
        {
            int key = bytes != null
                ? (java.util.Arrays.hashCode(bytes) ^ bytes.length) * 31 + (showStatic ? 1 : 0) : 0;
            GifPlayer old = session.player;
            if (old != null && bytes != null && old.key == key)
            {
                old.showFrame();
                return;
            }
            if (old == null && (bytes == null || key == session.gifCheckedKey))
                return;
            session.gifCheckedKey = key;
            if (old != null)
            {
                session.player = null;
                old.dispose(true);
            }
            if (bytes == null || session.control.isDisposed()
                || !showStatic && !"GIF".equals(sniffFormat(bytes))) //$NON-NLS-1$
                return;
            GifPlayer player = decode(session, key, bytes, showStatic ? 1 : 2);
            if (player == null || !player.attach())
                return;
            session.player = player;
            // Пауза — свойство поля: новая картинка/вариант не запускает анимацию сама.
            player.playing = !session.gifPaused;
            player.showFrame();
            player.schedule();
        }

        private static GifPlayer decode(Session session, int key, byte[] bytes, int minFrames)
        {
            ImageLoader loader = new ImageLoader();
            ImageData[] raw;
            try
            {
                raw = loader.load(new ByteArrayInputStream(bytes));
            }
            catch (RuntimeException e)
            {
                return null;
            }
            if (raw == null || raw.length < minFrames)
                return null;
            int w = loader.logicalScreenWidth;
            int h = loader.logicalScreenHeight;
            for (ImageData f : raw)
            {
                w = Math.max(w, f.x + f.width);
                h = Math.max(h, f.y + f.height);
            }
            if (w <= 0 || h <= 0 || (long) w * h * raw.length > MAX_TOTAL_PIXELS)
                return null;

            PaletteData direct = new PaletteData(0xFF0000, 0xFF00, 0xFF);
            int[] screen = new int[w * h];
            ImageData[] out = new ImageData[raw.length];
            int[] delays = new int[raw.length];
            for (int i = 0; i < raw.length; i++)
            {
                ImageData f = raw[i];
                int[] previous = f.disposalMethod == SWT.DM_FILL_PREVIOUS ? screen.clone() : null;
                drawFrame(screen, w, h, f);
                out[i] = snapshot(screen, w, h, direct);
                delays[i] = f.delayTime <= 1 ? DEFAULT_DELAY_MS : f.delayTime * 10;
                if (f.disposalMethod == SWT.DM_FILL_BACKGROUND)
                    clearRect(screen, w, h, f);
                else if (previous != null)
                    screen = previous;
            }
            return new GifPlayer(session, key, w, h, out, delays);
        }

        /** Кадр поверх экрана анимации (ARGB); прозрачные пиксели кадра не трогают экран. */
        private static void drawFrame(int[] screen, int w, int h, ImageData f)
        {
            PaletteData palette = f.palette;
            int[] lut = null;
            if (!palette.isDirect)
            {
                RGB[] rgbs = palette.getRGBs();
                lut = new int[rgbs.length];
                for (int k = 0; k < rgbs.length; k++)
                    lut[k] = argb(rgbs[k]);
            }
            int[] row = new int[f.width];
            for (int y = 0; y < f.height; y++)
            {
                int sy = f.y + y;
                if (sy < 0 || sy >= h)
                    continue;
                f.getPixels(0, y, f.width, row, 0);
                for (int x = 0; x < f.width; x++)
                {
                    int sx = f.x + x;
                    int p = row[x];
                    if (sx < 0 || sx >= w || p == f.transparentPixel)
                        continue;
                    // Альфа-канал бывает у PNG-варианта набора; у GIF его нет.
                    int alpha = f.alphaData != null ? f.getAlpha(x, y) : 255;
                    if (alpha == 0)
                        continue;
                    int color;
                    if (lut != null)
                    {
                        if (p < 0 || p >= lut.length)
                            continue;
                        color = lut[p];
                    }
                    else
                        color = argb(palette.getRGB(p));
                    screen[sy * w + sx] = color & 0xFFFFFF | alpha << 24;
                }
            }
        }

        private static int argb(RGB rgb)
        {
            return 0xFF000000 | rgb.red << 16 | rgb.green << 8 | rgb.blue;
        }

        private static void clearRect(int[] screen, int w, int h, ImageData f)
        {
            for (int y = Math.max(0, f.y); y < Math.min(h, f.y + f.height); y++)
            {
                for (int x = Math.max(0, f.x); x < Math.min(w, f.x + f.width); x++)
                    screen[y * w + x] = 0;
            }
        }

        private static ImageData snapshot(int[] screen, int w, int h, PaletteData direct)
        {
            ImageData data = new ImageData(w, h, 24, direct);
            byte[] alpha = new byte[w * h];
            int[] row = new int[w];
            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int v = screen[y * w + x];
                    row[x] = v & 0xFFFFFF;
                    alpha[y * w + x] = (byte) (v >>> 24);
                }
                data.setPixels(0, y, w, row, 0);
            }
            data.alphaData = alpha;
            return data;
        }

        private boolean attach()
        {
            if (session.control instanceof ImageViewer viewer)
                return attachViewer(viewer);
            if (session.control instanceof Label label && frames.length > 1 && label.getParent() != null
                && label.getParent().getLayout() instanceof GridLayout)
            {
                createBar(label.getParent());
                placeBar();
                return true;
            }
            return false;
        }

        private boolean attachViewer(ImageViewer viewer)
        {
            Control fx = null;
            for (Control child : viewer.getChildren())
            {
                if (child.getClass().getName().endsWith(".FXCanvas")) //$NON-NLS-1$
                {
                    fx = child;
                    break;
                }
            }
            if (fx == null || !(fx.getLayoutData() instanceof GridData fxData)
                || !(viewer.getLayout() instanceof GridLayout))
                return false;

            canvas = new Canvas(viewer, SWT.DOUBLE_BUFFERED | SWT.H_SCROLL | SWT.V_SCROLL);
            canvas.setData(PART_KEY, Boolean.TRUE);
            canvas.setData(SESSION_KEY, session);
            GridData gd = new GridData(fxData.horizontalAlignment, fxData.verticalAlignment,
                fxData.grabExcessHorizontalSpace, fxData.grabExcessVerticalSpace);
            gd.widthHint = fxData.widthHint;
            gd.heightHint = fxData.heightHint;
            canvas.setLayoutData(gd);
            canvas.setBackground(viewer.getBackground());
            canvas.moveAbove(fx);
            canvas.addListener(SWT.Paint, this::paint);
            canvas.addListener(SWT.Resize, e -> showFrame());
            canvas.addListener(SWT.MouseDown, e -> canvas.setFocus());
            canvas.addListener(SWT.KeyDown, e ->
            {
                if (e.character == ' ')
                    togglePlay();
            });
            canvas.getHorizontalBar().addListener(SWT.Selection, e -> canvas.redraw());
            canvas.getVerticalBar().addListener(SWT.Selection, e -> canvas.redraw());
            canvas.getHorizontalBar().setVisible(false);
            canvas.getVerticalBar().setVisible(false);
            attachZoomMenu(canvas, session);

            fxData.exclude = true;
            fx.setVisible(false);
            hiddenFx = fx;
            if (frames.length > 1)
            {
                createBar(viewer);
                placeBar();
            }
            viewer.layout(true, true);
            return true;
        }

        /**
         * Единое размещение панели во всех окнах: картинка → панель → подпись формата,
         * панель прижата к подписи снизу. Подпись в том же контейнере — панель сразу над ней
         * (в «Выборе картинки» между ними и картинкой — штатная распорка диалога); подпись
         * снаружи (родитель вьювера) — панель остаётся последней строкой контейнера картинки
         * (создана последней), то есть тоже вплотную над подписью.
         */
        private void placeBar()
        {
            Label meta = session.metaLabel;
            if (meta != null && !meta.isDisposed() && meta.getParent() == bar.getParent())
                bar.moveAbove(meta);
            bar.getParent().layout(true, true);
        }

        private void createBar(Composite parent)
        {
            String sign = Global.pluginSignForTooltip();
            bar = new Composite(parent, SWT.NONE);
            bar.setData(PART_KEY, Boolean.TRUE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 0;
            layout.marginHeight = 2;
            bar.setLayout(layout);
            bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            // Кнопка, ползунок и номер кадра — одной группой по центру панели.
            Composite group = new Composite(bar, SWT.NONE);
            GridLayout groupLayout = new GridLayout(3, false);
            groupLayout.marginWidth = 0;
            groupLayout.marginHeight = 0;
            group.setLayout(groupLayout);
            group.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

            playButton = new Button(group, SWT.PUSH);
            playButton.setText(PAUSE_TEXT);
            int pauseW = playButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
            playButton.setText(PLAY_TEXT);
            int playW = playButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
            GridData buttonData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            buttonData.widthHint = Math.max(pauseW, playW);
            playButton.setLayoutData(buttonData);
            playButton.setToolTipText(TooltipText.wrap(playButton,
                "Остановить или продолжить анимацию. На картинке — клавиша «Пробел»" + sign)); //$NON-NLS-1$
            playButton.addListener(SWT.Selection, e -> togglePlay());

            slider = new FrameSlider(group, frames.length, this::seek);
            slider.setToolTipText(TooltipText.wrap(slider,
                "Кадр анимации. Перемотка останавливает анимацию" + sign)); //$NON-NLS-1$

            frameLabel = new Label(group, SWT.LEFT);
            frameLabel.setText(frames.length + " / " + frames.length); //$NON-NLS-1$
            GridData labelData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            // Ширина под самый длинный номер — группа не дёргается при смене кадра.
            labelData.widthHint = frameLabel.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 4;
            frameLabel.setLayoutData(labelData);
        }

        private void seek(int frame)
        {
            if (frame == index || isDisposed())
                return;
            setPlaying(false);
            index = frame;
            showFrame();
        }

        private boolean isDisposed()
        {
            return session.control.isDisposed() || session.player != this
                || bar != null && bar.isDisposed() || canvas != null && canvas.isDisposed();
        }

        void togglePlay()
        {
            setPlaying(!playing);
        }

        private void setPlaying(boolean value)
        {
            if (playing == value || frames.length < 2 || isDisposed())
                return;
            playing = value;
            session.gifPaused = !value;
            session.control.getDisplay().timerExec(-1, tick);
            if (playing)
                schedule();
            updateControls();
        }

        private void schedule()
        {
            if (playing && frames.length > 1 && !isDisposed())
                session.control.getDisplay().timerExec(delays[index], tick);
        }

        private void tick()
        {
            if (!playing || isDisposed())
                return;
            index = (index + 1) % frames.length;
            showFrame();
            schedule();
        }

        private void updateControls()
        {
            if (bar == null)
                return;
            playButton.setText(playing ? PAUSE_TEXT : PLAY_TEXT);
            slider.setFrame(index);
            frameLabel.setText((index + 1) + " / " + frames.length); //$NON-NLS-1$
        }

        void showFrame()
        {
            if (isDisposed())
                return;
            updateControls();
            if (canvas != null)
            {
                if (canvas.isDisposed())
                    return;
                updateScrollBars();
                canvas.redraw();
            }
            else if (session.control instanceof Label label && label.isVisible())
                renderLabel(label);
        }

        private Image image(int i)
        {
            Image image = images[i];
            if (image == null || image.isDisposed())
                images[i] = image = new Image(session.control.getDisplay(), frames[i]);
            return image;
        }

        /** Как у WebView: крупное вписывается, мелкое — в натуральную величину; сверху — зум. */
        private double canvasScale()
        {
            Point size = canvas.getSize();
            double base = 1.0;
            if (size.x > 0 && size.y > 0)
                base = Math.min(1.0, Math.min(size.x / (double) width, size.y / (double) height));
            return base * session.zoom;
        }

        private void updateScrollBars()
        {
            double scale = canvasScale();
            int dw = (int) Math.round(width * scale);
            int dh = (int) Math.round(height * scale);
            Point size = canvas.getSize();
            ScrollBar hb = canvas.getHorizontalBar();
            ScrollBar vb = canvas.getVerticalBar();
            int hbH = hb.getSize().y;
            int vbW = vb.getSize().x;
            boolean needH = dw > size.x;
            boolean needV = dh > size.y;
            if (needH && !needV)
                needV = dh > size.y - hbH;
            if (needV && !needH)
                needH = dw > size.x - vbW;
            configureBar(hb, needH, dw, size.x - (needV ? vbW : 0));
            configureBar(vb, needV, dh, size.y - (needH ? hbH : 0));
        }

        private static void configureBar(ScrollBar sb, boolean need, int content, int client)
        {
            if (need)
            {
                int thumb = Math.max(1, client);
                sb.setValues(sb.getSelection(), 0, content, thumb, Math.max(1, thumb / 10), thumb);
            }
            else
                sb.setSelection(0);
            if (sb.getVisible() != need)
                sb.setVisible(need);
        }

        private void paint(org.eclipse.swt.widgets.Event e)
        {
            GC gc = e.gc;
            Rectangle area = canvas.getClientArea();
            gc.setBackground(canvas.getBackground());
            gc.fillRectangle(area);
            double scale = canvasScale();
            int dw = Math.max(1, (int) Math.round(width * scale));
            int dh = Math.max(1, (int) Math.round(height * scale));
            int x = dw <= area.width ? (area.width - dw) / 2 : -canvas.getHorizontalBar().getSelection();
            int y = dh <= area.height ? (area.height - dh) / 2 : -canvas.getVerticalBar().getSelection();
            gc.setInterpolation(scale < 1.0 ? SWT.HIGH : SWT.NONE);
            gc.drawImage(image(index), 0, 0, width, height, x, y, dw, dh);
        }

        /** Кадр в Label «Выбора картинки»: вписывание как у applySwtZoom. */
        private void renderLabel(Label label)
        {
            Composite host = label.getParent();
            int barH = bar != null ? bar.computeSize(SWT.DEFAULT, SWT.DEFAULT).y : 0;
            double scale = session.zoom;
            if (scale == 1.0 && host != null && !host.isDisposed())
            {
                Rectangle area = host.getClientArea();
                int metaH = session.metaLabel != null && !session.metaLabel.isDisposed()
                    ? session.metaLabel.computeSize(SWT.DEFAULT, SWT.DEFAULT).y : 0;
                int availH = area.height - barH - metaH;
                if (area.width > 0 && availH > 0)
                    scale = Math.min(1.0, Math.min(area.width / (double) width, availH / (double) height));
            }
            int w = Math.max(1, (int) Math.round(width * scale));
            int h = Math.max(1, (int) Math.round(height * scale));
            if (w > MAX_LABEL_PX || h > MAX_LABEL_PX)
            {
                double clamp = Math.min(MAX_LABEL_PX / (double) width, MAX_LABEL_PX / (double) height);
                if (session.zoom != 1.0)
                    session.zoom = clamp;
                w = Math.max(1, (int) Math.round(width * clamp));
                h = Math.max(1, (int) Math.round(height * clamp));
            }

            Image[] old = null;
            boolean resized = scaled == null || scaledW != w || scaledH != h;
            if (resized)
            {
                old = scaled;
                scaled = new Image[frames.length];
                scaledW = w;
                scaledH = h;
            }
            Image image = scaled[index];
            if (image == null || image.isDisposed())
            {
                ImageData data = w == width && h == height ? frames[index] : frames[index].scaledTo(w, h);
                scaled[index] = image = new Image(label.getDisplay(), data);
            }
            label.setImage(image);
            if (!resized)
                return;
            disposeAll(old);
            expandPictureHost(host, session, h + barH);
        }

        private static void disposeAll(Image[] images)
        {
            if (images == null)
                return;
            for (Image image : images)
            {
                if (image != null && !image.isDisposed())
                    image.dispose();
            }
        }

        /**
         * {@code widgets=false} — поле само закрывается: свои контролы уйдут вместе с ним,
         * трогать макет закрываемого родителя нельзя; освободить только образы и таймер.
         */
        void dispose(boolean widgets)
        {
            playing = false;
            if (!session.control.isDisposed())
                session.control.getDisplay().timerExec(-1, tick);
            if (!widgets)
            {
                disposeAll(scaled);
                disposeAll(images);
                return;
            }
            Composite parent = bar != null && !bar.isDisposed() ? bar.getParent()
                : canvas != null && !canvas.isDisposed() ? canvas.getParent() : null;
            if (bar != null && !bar.isDisposed())
                bar.dispose();
            if (canvas != null && !canvas.isDisposed())
                canvas.dispose();
            if (hiddenFx != null && !hiddenFx.isDisposed())
            {
                if (hiddenFx.getLayoutData() instanceof GridData fxData)
                    fxData.exclude = false;
                hiddenFx.setVisible(true);
            }
            if (session.control instanceof Label label && !label.isDisposed() && scaled != null
                && java.util.Arrays.asList(scaled).contains(label.getImage()))
                label.setImage(null);
            disposeAll(scaled);
            disposeAll(images);
            if (parent != null && !parent.isDisposed())
                parent.layout(true, true);
        }
    }
}
