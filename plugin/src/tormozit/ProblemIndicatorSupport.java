package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.graphics.Image;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.ui.validation.ProblemsDecorationHelper;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.PlainEObjectMarker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;

/** Общий расчёт критичности и штатный уголок EDT для значков плагина. */
final class ProblemIndicatorSupport
{
    private ProblemIndicatorSupport() {}

    static MarkerSeverity severity(EObject object)
    {
        if (!(object instanceof IBmObject bm) || bm.bmGetId() == -1)
            return null;
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
        var project = lookup != null ? lookup.getProject(object) : null;
        return project != null && markers != null
            ? markers.createReader(project).getMaxSeverity(project, Long.valueOf(bm.bmGetId())) : null;
    }

    static Image decorate(Image base, MarkerSeverity severity)
    {
        return base != null && !base.isDisposed() && severity != null
            ? ProblemsDecorationHelper.decorateImage(base, severity) : base;
    }

    /**
     * Только фон. Модуль сам не загружается и не строится: границы метода берутся из модели
     * открытого редактора ({@code document}), без него — критичность по всему модулю.
     * TEXT_OFFSET — позиция проблемы в этом модуле.
     */
    static MarkerSeverity methodSeverity(IFile file, String methodName, EObject owner, IXtextDocument document)
    {
        if (file == null || !file.exists())
            return null;
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
        if (markers == null)
            return null;
        Set<Object> ids = new LinkedHashSet<>();
        ids.add(file.getFullPath().toString());
        ids.add(uri.toPlatformString(true));
        ids.add(uri.toPlatformString(false));
        ids.add(uri.toString());
        var reader = markers.createReader(file.getProject());
        List<Marker> moduleMarkers = new ArrayList<>();
        try (var stream = reader.markers(MarkerFilter.createObjectFilter(file.getProject(), ids)))
        {
            stream.forEach(moduleMarkers::add);
        }
        if (owner instanceof IBmObject bm && bm.bmGetId() != -1)
            try (var stream = reader.nestedMarkers(file.getProject(), Long.valueOf(bm.bmGetId())))
            {
                stream.forEach(moduleMarkers::add);
            }
        moduleMarkers.removeIf(marker -> !inModule(marker, uri));
        if (moduleMarkers.isEmpty())
            return MarkerSeverity.NONE;
        if (document == null)
            return maxSeverity(moduleMarkers);
        MarkerSeverity inMethod = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
        {
            Module module = resource.getContents().stream().filter(Module.class::isInstance)
                .map(Module.class::cast).findFirst().orElse(null);
            Method method = module != null ? module.allMethods().stream()
                .filter(m -> methodName.equalsIgnoreCase(m.getName())).findFirst().orElse(null) : null;
            var node = method != null ? NodeModelUtils.getNode(method) : null;
            if (node == null)
                return null;
            MarkerSeverity result = MarkerSeverity.NONE;
            for (Marker marker : moduleMarkers)
                if (inMethod(marker, resource, node.getOffset(), node.getEndOffset()))
                    result = MarkerSeverity.moreSevere(result, marker.getSeverity());
            return result;
        });
        return inMethod != null ? inMethod : maxSeverity(moduleMarkers);
    }

    private static MarkerSeverity maxSeverity(List<Marker> markers)
    {
        MarkerSeverity result = MarkerSeverity.NONE;
        for (Marker marker : markers)
            result = MarkerSeverity.moreSevere(result, marker.getSeverity());
        return result;
    }

    private static URI markerUri(Marker marker)
    {
        URI markerUri = marker instanceof PlainEObjectMarker plain ? plain.getURI() : null;
        String textUri = StandardExtraInfo.TEXT_URI_TO_PROBLEM.get(marker);
        if (textUri != null && !textUri.isBlank())
            markerUri = URI.createURI(textUri);
        return markerUri;
    }

    private static boolean inModule(Marker marker, URI uri)
    {
        URI markerUri = markerUri(marker);
        return markerUri != null && uri.equals(markerUri.trimFragment());
    }

    private static boolean inMethod(Marker marker, org.eclipse.emf.ecore.resource.Resource resource,
        int start, int end)
    {
        URI markerUri = markerUri(marker);
        Integer offset = StandardExtraInfo.TEXT_OFFSET.get(marker);
        if (offset == null && markerUri.hasFragment())
        {
            EObject target = resource.getEObject(markerUri.fragment());
            var node = target != null ? NodeModelUtils.getNode(target) : null;
            if (node != null)
                offset = Integer.valueOf(node.getOffset());
        }
        return offset != null && offset.intValue() >= start && offset.intValue() < end;
    }
}
