package tormozit;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.graphics.Image;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.resource.XtextLiveScopeResourceSetProvider;

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

    /** Только фон: AST даёт границы метода, TEXT_OFFSET — позицию проблемы в этом модуле. */
    static MarkerSeverity methodSeverity(IFile file, String methodName, EObject owner)
    {
        if (file == null || !file.exists())
            return null;
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        var provider = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        var sets = provider != null ? provider.get(XtextLiveScopeResourceSetProvider.class) : null;
        IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
        if (sets == null || markers == null)
            return null;
        var resource = sets.get(file.getProject()).getResource(uri, true);
        try
        {
            Module module = resource.getContents().stream().filter(Module.class::isInstance)
                .map(Module.class::cast).findFirst().orElse(null);
            if (module == null)
                return null;
            Method method = module.allMethods().stream()
                .filter(m -> methodName.equalsIgnoreCase(m.getName())).findFirst().orElse(null);
            var node = method != null ? NodeModelUtils.getNode(method) : null;
            if (node == null)
                return null;
            Set<Object> ids = new LinkedHashSet<>();
            ids.add(file.getFullPath().toString());
            ids.add(uri.toPlatformString(true));
            ids.add(uri.toPlatformString(false));
            ids.add(uri.toString());
            var reader = markers.createReader(file.getProject());
            MarkerSeverity result = MarkerSeverity.NONE;
            try (var stream = reader.markers(MarkerFilter.createObjectFilter(file.getProject(), ids)))
            {
                for (Marker marker : stream.toList())
                    if (inMethod(marker, uri, resource, node.getOffset(), node.getEndOffset()))
                        result = MarkerSeverity.moreSevere(result, marker.getSeverity());
            }
            if (owner instanceof IBmObject bm && bm.bmGetId() != -1)
                try (var stream = reader.nestedMarkers(file.getProject(), Long.valueOf(bm.bmGetId())))
                {
                    for (Marker marker : stream.toList())
                        if (inMethod(marker, uri, resource, node.getOffset(), node.getEndOffset()))
                            result = MarkerSeverity.moreSevere(result, marker.getSeverity());
                }
            return result;
        }
        finally
        {
            resource.unload();
        }
    }

    private static boolean inMethod(Marker marker, URI uri, org.eclipse.emf.ecore.resource.Resource resource,
        int start, int end)
    {
        URI markerUri = marker instanceof PlainEObjectMarker plain ? plain.getURI() : null;
        String textUri = StandardExtraInfo.TEXT_URI_TO_PROBLEM.get(marker);
        if (textUri != null && !textUri.isBlank())
            markerUri = URI.createURI(textUri);
        if (markerUri == null || !uri.equals(markerUri.trimFragment()))
            return false;
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
