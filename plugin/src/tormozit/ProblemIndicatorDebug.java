package tormozit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerUpdateListener;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;

/** Временная безусловная диагностика рассинхронизации индикаторов проблем. */
final class ProblemIndicatorDebug
{
    private static final String TOPIC = "problem-indicators"; //$NON-NLS-1$
    private static final String KEY = "tormozit.problemIndicators.diag"; //$NON-NLS-1$
    private static final AtomicLong EVENTS = new AtomicLong();
    private static boolean installed;
    private static final Map<Image, String> IMAGE_FILES = new WeakHashMap<>();
    private static final Path IMAGE_DIR = Path.of("C:\\VC\\EDT.Comfort", ".tmp", "problem-indicators", "images",
        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

    private ProblemIndicatorDebug() {}

    static void log(String text)
    {
        Global.tempLog(TOPIC, "thread=" + Thread.currentThread().getName() + " " + text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String id(Object object)
    {
        return object == null ? "null" : object.getClass().getName() + "#" //$NON-NLS-1$ //$NON-NLS-2$
            + Integer.toHexString(System.identityHashCode(object));
    }

    static String image(Image image)
    {
        if (image == null || image.isDisposed())
            return id(image) + (image != null ? " disposed" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (Display.getCurrent() == null)
            return id(image) + " not-ui";
        String file = IMAGE_FILES.get(image);
        if (file == null)
        {
            try
            {
                Files.createDirectories(IMAGE_DIR);
                Path path = IMAGE_DIR.resolve(Integer.toHexString(System.identityHashCode(image)) + ".png");
                ImageLoader loader = new ImageLoader();
                loader.data = new ImageData[] { image.getImageData() };
                loader.save(path.toString(), SWT.IMAGE_PNG);
                file = path.toString();
                IMAGE_FILES.put(image, file);
            }
            catch (Exception ex)
            {
                Global.tempLogException(TOPIC, "image capture " + id(image), ex);
            }
        }
        return id(image) + " file=" + file;
    }

    static String elements(Object[] elements)
    {
        if (elements == null)
            return "broadcast"; //$NON-NLS-1$
        StringBuilder text = new StringBuilder("count=" + elements.length); //$NON-NLS-1$
        for (Object element : elements)
        {
            text.append(' ').append(id(element));
            if (element instanceof IBmObject bm)
                text.append(" bmId=").append(bm.bmGetId()); //$NON-NLS-1$
        }
        return text.toString();
    }

    static void install()
    {
        if (installed)
            return;
        IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
        log("install markerManager=" + id(markers)); //$NON-NLS-1$
        if (markers == null)
            return;
        installed = true;
        IMarkerUpdateListener listener = event ->
        {
            long sequence = EVENTS.incrementAndGet();
            log("markers event=" + sequence + " projects=" + event.getChangedProjects()); //$NON-NLS-1$ //$NON-NLS-2$
            Display display = Display.getDefault();
            if (!display.isDisposed())
                display.asyncExec(() ->
                {
                    editorSnapshot("markers event=" + sequence); //$NON-NLS-1$
                    // Второй снимок наблюдает результат отложенных обновлений EDT, ничего не обновляя сам.
                    display.timerExec(1000, () -> editorSnapshot("markers settled event=" + sequence)); //$NON-NLS-1$
                });
        };
        markers.addListener(listener);
        Display.getDefault().disposeExec(() -> markers.removeListener(listener));
        var decorators = PlatformUI.getWorkbench().getDecoratorManager();
        ILabelProviderListener decoratorListener = event ->
            log("decorators source=" + id(event.getSource()) + " elements=" + elements(event.getElements())); //$NON-NLS-1$ //$NON-NLS-2$
        decorators.addListener(decoratorListener);
        Display.getDefault().disposeExec(() -> decorators.removeListener(decoratorListener));
    }

    static void watchNavigator(CommonViewer viewer)
    {
        install();
        Tree tree = viewer.getTree();
        if (tree.getData(KEY) == Boolean.TRUE)
            return;
        tree.setData(KEY, Boolean.TRUE);
        var labels = viewer.getLabelProvider();
        log("navigator install viewer=" + id(viewer) + " labels=" + id(labels)); //$NON-NLS-1$ //$NON-NLS-2$
        ILabelProviderListener listener = event ->
        {
            long sequence = EVENTS.incrementAndGet();
            log("navigator labels event=" + sequence + " source=" + id(event.getSource()) //$NON-NLS-1$ //$NON-NLS-2$
                + " elements=" + elements(event.getElements())); //$NON-NLS-1$
            Display display = tree.getDisplay();
            if (!display.isDisposed())
                display.asyncExec(() -> navigatorSnapshot(viewer, "labels event=" + sequence)); //$NON-NLS-1$
        };
        labels.addListener(listener);
        tree.addDisposeListener(event -> labels.removeListener(listener));
        IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
        if (markers != null)
        {
            IMarkerUpdateListener markerListener = event ->
            {
                long sequence = EVENTS.incrementAndGet();
                log("navigator markers event=" + sequence + " projects=" + event.getChangedProjects()); //$NON-NLS-1$ //$NON-NLS-2$
                Display display = tree.getDisplay();
                if (!display.isDisposed())
                    display.asyncExec(() ->
                    {
                        navigatorSnapshot(viewer, "markers event=" + sequence); //$NON-NLS-1$
                        display.timerExec(1000, () -> navigatorSnapshot(viewer, "markers settled event=" + sequence)); //$NON-NLS-1$
                    });
            };
            markers.addListener(markerListener);
            tree.addDisposeListener(event -> markers.removeListener(markerListener));
        }
        tree.addListener(SWT.Selection, event -> navigatorSnapshot(viewer, "selection")); //$NON-NLS-1$
        tree.addListener(SWT.Expand, event -> tree.getDisplay().asyncExec(() -> navigatorSnapshot(viewer, "expand"))); //$NON-NLS-1$
        navigatorSnapshot(viewer, "install"); //$NON-NLS-1$
    }

    private static void navigatorSnapshot(CommonViewer viewer, String reason)
    {
        Tree tree = viewer.getTree();
        if (tree.isDisposed())
        {
            log("navigator snapshot skipped disposed reason=" + reason); //$NON-NLS-1$
            return;
        }
        try
        {
            log("navigator snapshot reason=" + reason + " labels=" + id(viewer.getLabelProvider())); //$NON-NLS-1$ //$NON-NLS-2$
            // Обходим только уже созданные видимые строки: не раскрываем узлы и не вызываем getImage провайдера.
            TreeItem item = tree.getTopItem();
            int bottom = tree.getClientArea().height;
            while (item != null && item.getBounds().y < bottom)
            {
                log("navigator row text=" + item.getText() + " element=" + elements(new Object[] { item.getData() }) //$NON-NLS-1$ //$NON-NLS-2$
                    + " native=" + nativeState(item.getData()) + " image=" + image(item.getImage())); //$NON-NLS-1$
                if (item.getExpanded() && item.getItemCount() > 0)
                    item = item.getItem(0);
                else
                {
                    while (item != null)
                    {
                        TreeItem parent = item.getParentItem();
                        int index = parent == null ? tree.indexOf(item) : parent.indexOf(item);
                        int count = parent == null ? tree.getItemCount() : parent.getItemCount();
                        if (index + 1 < count)
                        {
                            item = parent == null ? tree.getItem(index + 1) : parent.getItem(index + 1);
                            break;
                        }
                        item = parent;
                    }
                }
            }
        }
        catch (RuntimeException ex)
        {
            Global.tempLogException(TOPIC, "navigator snapshot reason=" + reason, ex); //$NON-NLS-1$
        }
    }

    private static void editorSnapshot(String reason)
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
        {
            log("editor snapshot skipped closing reason=" + reason); //$NON-NLS-1$
            return;
        }
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (var page : window.getPages())
                for (var reference : page.getEditorReferences())
                {
                    var editor = reference.getEditor(false);
                    if (!(editor instanceof DtGranularEditor<?> granular))
                        continue;
                    try
                    {
                        log("editor snapshot reason=" + reason + " editor=" + id(editor) //$NON-NLS-1$ //$NON-NLS-2$
                            + " title=" + editor.getTitle() + " model=" + elements(new Object[] { granular.getModel() }) //$NON-NLS-1$ //$NON-NLS-2$
                            + " titleImage=" + image(editor.getTitleImage()) //$NON-NLS-1$
                            + " stored=" + id(Global.getField(editor, "titleImage")) //$NON-NLS-1$ //$NON-NLS-2$
                            + " held=" + image(MdEditorTitleNavigatorMenuHook.heldWorkbenchOverlay(editor))); //$NON-NLS-1$
                        log("editor native reason=" + reason + " editor=" + id(editor) + " " + nativeState(granular.getModel()));
                        EditorTabIconDiagHook.logProblemIndicatorSnapshot(editor, reason);
                        var model = granular.getModel();
                        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
                        IMarkerManager markers = Global.getOsgiService(IMarkerManager.class);
                        if (model instanceof IBmObject bm && lookup != null && markers != null)
                        {
                            var project = lookup.getProject(model);
                            if (project != null)
                            {
                                Long objectId = Long.valueOf(bm.bmGetId());
                                log("editor markers reason=" + reason + " editor=" + id(editor) //$NON-NLS-1$ //$NON-NLS-2$
                                    + " project=" + project.getName() + " bmId=" + objectId //$NON-NLS-1$ //$NON-NLS-2$
                                    + " direct=" + severities(markers.getMarkers(project, objectId)) //$NON-NLS-1$
                                    + " nested=" + severities(markers.getNestedMarkers(project, objectId))); //$NON-NLS-1$
                                IMarkerManagerV2 markersV2 = Global.getOsgiService(IMarkerManagerV2.class);
                                log("editor markers-v2 reason=" + reason + " editor=" + id(editor)
                                    + " max=" + (markersV2 != null
                                        ? markersV2.createReader(project).getMaxSeverity(project, objectId) : "no-manager"));
                            }
                            else
                                log("editor markers no-project editor=" + id(editor)); //$NON-NLS-1$
                        }
                        else
                            log("editor markers unavailable editor=" + id(editor) + " lookup=" + id(lookup) //$NON-NLS-1$ //$NON-NLS-2$
                                + " manager=" + id(markers)); //$NON-NLS-1$
                        Object container = Global.invoke(granular, "getContainer"); //$NON-NLS-1$
                        if (container instanceof CTabFolder folder && !folder.isDisposed())
                            for (CTabItem item : folder.getItems())
                                log("inner snapshot reason=" + reason + " editor=" + id(editor) //$NON-NLS-1$ //$NON-NLS-2$
                                    + " tab=" + item.getText() + " image=" + image(item.getImage())); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    catch (RuntimeException ex)
                    {
                        Global.tempLogException(TOPIC, "editor snapshot reason=" + reason + " editor=" + id(editor), ex); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
    }

    private static String severities(Marker[] markers)
    {
        if (markers == null)
            return "null"; //$NON-NLS-1$
        EnumMap<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        for (Marker marker : markers)
            if (marker != null && marker.getSeverity() != null)
                counts.merge(marker.getSeverity(), Integer.valueOf(1), Integer::sum);
        return "count=" + markers.length + " " + counts; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Читаем подтверждённые поля CachingProblemsLabelDecorator, не сбрасывая и не заполняя кэш. */
    private static String nativeState(Object element)
    {
        try
        {
            var decorator = PlatformUI.getWorkbench().getDecoratorManager().getBaseLabelProvider(
                "com._1c.g5.v8.dt.navigator.ui.navigatorProblemsLabelDecorator");
            Object cache = decorator != null ? Global.getField(decorator, "cache") : null;
            Object severity = cache != null ? Global.invoke(cache, "getIfPresent", element) : "no-cache";
            String state = "decorator=" + id(decorator) + " cachedSeverity=" + severity;
            if (element instanceof IBmObject bm)
                state += " top=" + bm.bmIsTop() + " fqn=" + (bm.bmGetId() != -1 ? bm.bmGetFqn() : "unattached")
                    + " engineActive=" + (bm.bmGetEngine() != null && bm.bmGetEngine().isActive());
            return state;
        }
        catch (RuntimeException ex)
        {
            Global.tempLogException(TOPIC, "native state element=" + id(element), ex);
            return "failed=" + ex;
        }
    }
}
