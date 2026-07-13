package tormozit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import com._1c.g5.v8.dt.core.format.Locale;
import com._1c.g5.v8.dt.form.naming.FormLocalizerUtil;
import com._1c.g5.v8.dt.form.naming.FormSymbolicLinkLocalizer;
import com._1c.g5.v8.dt.md.naming.MdLocalizerUtil;
import com._1c.g5.v8.dt.md.naming.MdSymbolicLinkLocalizer;
import com._1c.g5.v8.dt.md.naming.MdTypesTranslationIntoRussian;
import com._1c.g5.v8.dt.md.resource.StandardAttributeUtil;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.Event;

import org.osgi.framework.Bundle;

/** SWT-обёртка для AEF/LWT-контролов ({@code LightLabel}, {@code SwtLightControl}). */
final class PropertySheetControlInterop
{
    private static final String SWT_LIGHT_CONTROL = "com._1c.g5.lwt.interop.SwtLightControl"; //$NON-NLS-1$
    private static final String SWT_LIGHT_COMPOSITE = "com._1c.g5.lwt.interop.SwtLightComposite"; //$NON-NLS-1$

    private PropertySheetControlInterop() {}

    // #region agent log
    private static final java.nio.file.Path AGENT_LOG_PATH =
            java.nio.file.Path.of("C:\\VC\\EDT.Comfort\\debug-db8c17.log"); //$NON-NLS-1$

    static void agentHitLog(String hypothesisId, String location, String message,
            java.util.Map<String, Object> data)
    {
        try
        {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"sessionId\":\"db8c17\",\"hypothesisId\":\"").append(hypothesisId) //$NON-NLS-1$
                    .append("\",\"location\":\"").append(location) //$NON-NLS-1$
                    .append("\",\"message\":\"").append(message) //$NON-NLS-1$
                    .append("\",\"data\":{"); //$NON-NLS-1$
            boolean first = true;
            if (data != null)
            {
                for (java.util.Map.Entry<String, Object> e : data.entrySet())
                {
                    if (!first)
                        sb.append(',');
                    first = false;
                    sb.append('"').append(e.getKey()).append("\":");
                    Object v = e.getValue();
                    if (v == null)
                        sb.append("null"); //$NON-NLS-1$
                    else if (v instanceof Number || v instanceof Boolean)
                        sb.append(v);
                    else
                        sb.append('"').append(String.valueOf(v).replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                                .replace("\"", "\\\"")).append('"'); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            sb.append("},\"timestamp\":").append(System.currentTimeMillis()).append("}\n"); //$NON-NLS-1$ //$NON-NLS-2$
            java.nio.file.Files.writeString(AGENT_LOG_PATH, sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
            // debug session only
        }
    }
    // #endregion

    static Control unwrapToSwtControl(Object value)
    {
        return unwrapToSwtControl(value, 0);
    }

    private static Control unwrapToSwtControl(Object value, int depth)
    {
        if (value instanceof Control)
            return (Control) value;
        if (value == null || depth > 5)
            return null;

        Object bridge = Global.invoke(value, "getSwtLightControl"); //$NON-NLS-1$
        if (bridge != null && bridge != value)
        {
            Control nested = unwrapToSwtControl(bridge, depth + 1);
            if (nested != null)
                return nested;
        }

        Object swt = Global.invoke(value, "getSwtControl"); //$NON-NLS-1$
        if (swt instanceof Control)
            return (Control) swt;

        swt = Global.invoke(value, "getControl"); //$NON-NLS-1$
        if (swt instanceof Control)
            return (Control) swt;
        if (swt != null && swt != value)
        {
            Control nested = unwrapToSwtControl(swt, depth + 1);
            if (nested != null)
                return nested;
        }

        if (!isLightControl(value))
        {
            swt = Global.invoke(value, "getSwtComposite"); //$NON-NLS-1$
            if (swt instanceof Control)
                return (Control) swt;

            swt = Global.getField(value, "swtComposite"); //$NON-NLS-1$
            if (swt != null && swt != value)
            {
                Control nested = unwrapToSwtControl(swt, depth + 1);
                if (nested != null)
                    return nested;
            }
        }

        if (isLightControl(value))
        {
            Control fromStatic = bridgeSwtLightControlOnly(value);
            if (fromStatic != null)
                return fromStatic;
        }

        return null;
    }

    /** После {@code bindNativeControl}: LightLabel → SwtLightControl → SWT widget. */
    static Control unwrapBoundLightControl(Object renderer, Object light, String propertyName)
    {
        if (light == null)
            return null;
        tryBindNativeControl(renderer, light);
        Object bridge = Global.invoke(light, "getSwtLightControl"); //$NON-NLS-1$
        if (bridge == null)
            return null;
        Object swt = Global.invoke(bridge, "getSwtControl"); //$NON-NLS-1$
        if (!(swt instanceof Control))
            return null;
        Control narrowed = narrowNameControl((Control) swt, propertyName);
        if (narrowed != null)
            PropertySheetDebug.resolve("bound " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                    + " → " + PropertySheetDebug.controlBrief(narrowed)); //$NON-NLS-1$
        return narrowed;
    }

    private static boolean isLightControl(Object value)
    {
        if (value == null)
            return false;
        String cn = value.getClass().getName();
        return cn.contains("LightLabel") || cn.contains("LightText") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("Checkbox") || cn.contains("CheckBox") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("ILightControl") || cn.contains(".lwt."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Только {@link SwtLightControl}, без getHost* (возвращает общий composite). */
    private static Control bridgeSwtLightControlOnly(Object lightControl)
    {
        if (lightControl == null)
            return null;
        try
        {
            Class<?> bridgeClass = Class.forName(SWT_LIGHT_CONTROL, false,
                    lightControl.getClass().getClassLoader());
            for (Method method : bridgeClass.getDeclaredMethods())
            {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1)
                    continue;
                if (method.getName().contains("Host")) //$NON-NLS-1$
                    continue;
                if (!method.getParameterTypes()[0].isInstance(lightControl))
                    continue;
                if (!method.getReturnType().getName().contains("SwtLightControl")) //$NON-NLS-1$
                    continue;
                method.setAccessible(true);
                Object bridge = method.invoke(null, lightControl);
                Control nested = unwrapToSwtControl(bridge, 0);
                if (nested != null)
                {
                    PropertySheetDebug.resolve("bridge " + method.getName() //$NON-NLS-1$
                            + " → " + PropertySheetDebug.controlBrief(nested)); //$NON-NLS-1$
                    return nested;
                }
            }
        }
        catch (Throwable e)
        {
            PropertySheetDebug.resolve("bridge SwtLightControl error: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static void tryBindNativeControl(Object renderer, Object lightControl)
    {
        if (renderer == null || lightControl == null || !isLightControl(lightControl))
            return;
        Global.invoke(renderer, "bindNativeControl", lightControl); //$NON-NLS-1$
    }


    static Control resolveNameControl(Object scene, Object viewModel, Object view, String propertyName)
    {
        if (scene == null)
        {
            logResolveFail(propertyName, view, "scene=null", null, null, null); //$NON-NLS-1$
            return null;
        }
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        if (renderer == null)
        {
            logResolveFail(propertyName, view, "renderer=null", null, null, null); //$NON-NLS-1$
            return null;
        }

        if (view == null && viewModel != null)
        {
            Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
            if (mapObj instanceof java.util.Map)
                view = ((java.util.Map<?, ?>) mapObj).get(viewModel);
        }

        if (view != null)
        {
            Control fromView = resolveFromView(renderer, scene, view, propertyName);
            if (fromView != null)
            {
                PropertySheetDebug.resolve("OK view " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                        + " → " + PropertySheetDebug.controlBrief(fromView)); //$NON-NLS-1$
                return fromView;
            }
        }

        if (viewModel != null)
        {
            Control fromRenderer = resolveViaRenderer(renderer, scene, viewModel, view, propertyName);
            if (fromRenderer != null)
            {
                PropertySheetDebug.resolve("OK renderer " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                        + " → " + PropertySheetDebug.controlBrief(fromRenderer)); //$NON-NLS-1$
                return fromRenderer;
            }
        }

        Object composite = lightComposite(renderer, scene, view, propertyName);
        Object nativeObj = lightNativeFromView(view);
        Object light = lightControlFromView(view);
        logResolveFail(propertyName, view, "all paths failed", light, composite, nativeObj); //$NON-NLS-1$
        return null;
    }

    private static Object lightNativeFromView(Object view)
    {
        if (view == null)
            return null;
        Object nativeObj = Global.invoke(view, "getNativeControl"); //$NON-NLS-1$
        if (nativeObj != null)
            return nativeObj;
        return Global.getField(view, "nativeControl"); //$NON-NLS-1$
    }

    static Object lightControlFromView(Object view)
    {
        if (view == null)
            return null;
        Object light = Global.getField(view, "lightControl"); //$NON-NLS-1$
        if (light == null)
            light = Global.getField(view, "checkbox"); //$NON-NLS-1$
        if (light == null)
            light = Global.getField(view, "lightLabel"); //$NON-NLS-1$
        if (light == null)
        {
            for (String method : new String[] { "getControl", "getLightControl", "getCheckbox" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                light = Global.invoke(view, method);
                if (light != null)
                    break;
            }
        }
        if (light == null)
            light = lightNativeFromView(view);
        return light;
    }


    private static void logResolveFail(String propertyName, Object view, String step,
            Object light, Object composite, Object nativeObj)
    {
        if (propertyName == null)
            return;
        Object swtBridge = nativeObj != null ? Global.invoke(nativeObj, "getSwtLightControl") : null; //$NON-NLS-1$
        String nativeSwt = nativeObj != null
                ? PropertySheetDebug.safe(Global.invoke(nativeObj, "getSwtComposite")) : "<null>"; //$NON-NLS-1$ //$NON-NLS-2$
        String nativeText = nativeObj != null
                ? PropertySheetDebug.quote(String.valueOf(Global.invoke(nativeObj, "getText"))) : ""; //$NON-NLS-1$ //$NON-NLS-2$
        PropertySheetDebug.resolve("FAIL " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                + " step=" + step //$NON-NLS-1$
                + " view=" + PropertySheetDebug.safe(view) //$NON-NLS-1$
                + " native=" + PropertySheetDebug.safe(nativeObj) //$NON-NLS-1$
                + " native.swtBridge=" + PropertySheetDebug.safe(swtBridge) //$NON-NLS-1$
                + (nativeText.isEmpty() ? "" : " native.text=" + nativeText) //$NON-NLS-1$ //$NON-NLS-2$
                + " light=" + PropertySheetDebug.safe(light) //$NON-NLS-1$
                + " composite=" + PropertySheetDebug.safe(composite)); //$NON-NLS-1$
    }

    private static Control resolveViaRenderer(Object renderer, Object scene, Object viewModel,
            Object view, String propertyName)
    {
        Object nativeOne = Global.invoke(renderer, "getNativeControl", viewModel); //$NON-NLS-1$
        if (isLightControl(nativeOne))
        {
            Control fromBound = unwrapBoundLightControl(renderer, nativeOne, propertyName);
            if (fromBound != null)
                return fromBound;
        }
        tryBindNativeControl(renderer, nativeOne);
        Control fromRenderer = narrowNameControl(unwrapToSwtControl(nativeOne), propertyName);
        if (fromRenderer != null)
            return fromRenderer;

        Object composite = lightComposite(renderer, scene, view, propertyName);
        if (composite != null)
        {
            Object nativeTwo = Global.invoke(renderer, "getNativeControl", composite, viewModel); //$NON-NLS-1$
            if (isLightControl(nativeTwo))
            {
                Control fromBound = unwrapBoundLightControl(renderer, nativeTwo, propertyName);
                if (fromBound != null)
                    return fromBound;
            }
            tryBindNativeControl(renderer, nativeTwo);
            fromRenderer = narrowNameControl(unwrapToSwtControl(nativeTwo), propertyName);
            if (fromRenderer != null)
                return fromRenderer;
        }
        return null;
    }

    private static Object lightComposite(Object renderer, Object scene, Object view, String propertyName)
    {
        Object composite = Global.invoke(renderer, "getSwtComposite"); //$NON-NLS-1$
        if (composite == null)
            composite = Global.getField(renderer, "lightComposite"); //$NON-NLS-1$
        if (composite == null)
            composite = Global.getField(renderer, "scrolledComposite"); //$NON-NLS-1$
        if (composite == null)
            composite = Global.getField(renderer, "containerComposite"); //$NON-NLS-1$
        if (composite == null && view != null)
            composite = Global.getField(view, "lightComposite"); //$NON-NLS-1$
        if (composite != null)
        {
            Object swt = Global.invoke(composite, "getSwtComposite"); //$NON-NLS-1$
            if (swt != null)
                composite = swt;
        }
        if (composite == null && scene != null && propertyName != null)
            PropertySheetDebug.resolveVerbose("lightComposite miss " + PropertySheetDebug.quote(propertyName)); //$NON-NLS-1$
        return composite;
    }

    private static Control resolveFromView(Object renderer, Object scene, Object view, String propertyName)
    {
        Object light = lightControlFromView(view);
        Control fromBound = unwrapBoundLightControl(renderer, light, propertyName);
        if (fromBound != null)
            return fromBound;

        Object swt = Global.invoke(view, "getSwtControl"); //$NON-NLS-1$
        if (swt instanceof Control)
            return narrowNameControl((Control) swt, propertyName);

        Object nativeObj = lightNativeFromView(view);
        if (nativeObj != null && nativeObj != light)
        {
            fromBound = unwrapBoundLightControl(renderer, nativeObj, propertyName);
            if (fromBound != null)
                return fromBound;
        }

        Control fromNative = narrowNameControl(unwrapToSwtControl(nativeObj), propertyName);
        if (fromNative != null)
            return fromNative;

        Control fromView = narrowNameControl(unwrapToSwtControl(Global.invoke(view, "getControl")), propertyName); //$NON-NLS-1$
        if (fromView != null)
            return fromView;

        for (String field : new String[] { "lightLabel", "lightControl", "nativeControl", "swtControl" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object fieldVal = Global.getField(view, field);
            fromBound = unwrapBoundLightControl(renderer, fieldVal, propertyName);
            if (fromBound != null)
                return fromBound;
            fromView = narrowNameControl(unwrapToSwtControl(fieldVal), propertyName);
            if (fromView != null)
                return fromView;
        }

        Object composite = lightComposite(renderer, scene, view, propertyName);
        if (composite != null && view != null)
        {
            Object vm = Global.getField(view, "viewModel"); //$NON-NLS-1$
            if (vm == null)
                vm = Global.invoke(view, "getViewModel"); //$NON-NLS-1$
            if (vm != null)
            {
                Object nativeTwo = Global.invoke(renderer, "getNativeControl", composite, vm); //$NON-NLS-1$
                fromBound = unwrapBoundLightControl(renderer, nativeTwo, propertyName);
                if (fromBound != null)
                    return fromBound;
                fromView = narrowNameControl(unwrapToSwtControl(nativeTwo), propertyName);
                if (fromView != null)
                    return fromView;
            }
        }
        return null;
    }

    /** Если unwrap вернул composite — дочерний Label с нужным текстом или левый узкий виджет. */
    static Control narrowNameControl(Control control, String propertyName)
    {
        if (control == null || control.isDisposed())
            return null;
        if (propertyName == null || propertyName.isEmpty())
            return control;

        String visible = controlText(control);
        if (propertyName.equals(visible))
            return control;

        if (control instanceof Composite)
        {
            Control byText = findChildByText((Composite) control, propertyName);
            if (byText != null)
                return byText;
            Control left = leftmostNameCandidate((Composite) control);
            if (left != null)
                return left;
        }

        return visible.isEmpty() ? control : null;
    }

    /** DtLayoutComposite — строка поля (33px), в т.ч. вложенная в body Section. */
    static boolean isLwtFieldRowComposite(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        String cn = composite.getClass().getName();
        if (!cn.contains("DtLayoutComposite") && !cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        Point size = composite.getSize();
        if (size.y < 20 || size.y > 80 || size.x < 100)
            return false;
        if (containsNestedFieldRow(composite))
            return false;
        Composite parent = composite.getParent();
        if (parent == null)
            return false;
        String pn = parent.getClass().getName();
        return pn.contains("Section") || pn.contains("ExpandableComposite") //$NON-NLS-1$ //$NON-NLS-2$
                || pn.contains("DtLayoutComposite") || pn.contains("LayoutComposite"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean containsNestedFieldRow(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (!(child instanceof Composite))
                continue;
            Composite cc = (Composite) child;
            String cn = cc.getClass().getName();
            if (!cn.contains("DtLayoutComposite") && !cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            Point s = cc.getSize();
            if (s.y >= 20 && s.y <= 80 && s.x >= 100)
                return true;
        }
        return false;
    }

    /** SWT-хост строки/тела секции для LWT view (через LightLabel или Y-сопоставление). */
    static Composite resolveLwtFieldRowHost(Object view, Composite paletteRoot)
    {
        Composite fromLight = fieldRowFromLightHost(view);
        if (fromLight != null)
            return fromLight;
        return fieldRowFromSectionBody(view, paletteRoot);
    }

    /** Наименьший paint-host, в котором origin LightLabel попадает в bounds. */
    static Composite findPaintHostForView(Object view, Composite paletteRoot)
    {
        Composite leaf = fieldRowFromLightHost(view);
        if (leaf != null && !leaf.isDisposed())
            return leaf;

        Composite sectionBody = findSectionBodyForView(view, paletteRoot);
        if (sectionBody != null && !sectionBody.isDisposed())
            return sectionBody;

        Composite resolved = resolveLwtFieldRowHost(view, paletteRoot);
        if (paletteRoot == null || paletteRoot.isDisposed())
            return resolved != null && !resolved.isDisposed() ? resolved : null;

        List<Composite> hosts = collectLwtPaintHosts(paletteRoot);
        if (resolved != null && !resolved.isDisposed() && !hosts.contains(resolved))
            hosts.add(resolved);
        if (hosts.isEmpty())
            return null;

        int labelY = labelDisplayYForView(view, paletteRoot);
        List<Composite> containing = new ArrayList<>();
        if (labelY != Integer.MAX_VALUE)
        {
            for (Composite host : hosts)
            {
                if (host == null || host.isDisposed())
                    continue;
                int top = host.toDisplay(0, 0).y;
                int bottom = top + host.getSize().y;
                if (labelY >= top && labelY < bottom)
                    containing.add(host);
            }
        }
        if (!containing.isEmpty())
        {
            for (Composite host : containing)
            {
                if (isLwtFieldRowComposite(host))
                    return host;
            }
            Composite bestContained = null;
            int bestHeight = Integer.MAX_VALUE;
            for (Composite host : containing)
            {
                int height = host.getSize().y;
                if (height < bestHeight)
                {
                    bestHeight = height;
                    bestContained = host;
                }
            }
            if (bestContained != null)
                return bestContained;
        }

        Composite best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Composite host : hosts)
        {
            int top = host.toDisplay(0, 0).y;
            int bottom = top + host.getSize().y;
            if (labelY != Integer.MAX_VALUE && (labelY < top || labelY >= bottom))
                continue;
            Point origin = lwtLabelPaintOrigin(view, host);
            if (!acceptsOrigin(host, origin))
                continue;
            int area = host.getSize().x * host.getSize().y;
            int score = area;
            if (labelY != Integer.MAX_VALUE)
            {
                score += Math.abs(labelY - (top + origin.y)) * 1000;
                if (labelY >= top && labelY < bottom)
                    score -= 500_000;
            }
            if (score < bestScore)
            {
                bestScore = score;
                best = host;
            }
        }
        if (PropertySheetDebug.isTrace())
            PropertySheetDebug.scanVerbose("findPaintHost labelY=" + labelY + " best=" //$NON-NLS-1$ //$NON-NLS-2$
                    + PropertySheetDebug.controlBrief(best != null ? best : resolved));
        return best != null ? best : resolved;
    }

    static int labelDisplayYForView(Object view, Composite paletteRoot)
    {
        Rectangle lightBounds = liveLightDisplayBounds(view);
        if (lightBounds != null)
            return lightBounds.y + Math.max(1, lightBounds.height / 2);

        Object light = lightControlFromView(view);
        if (light != null)
        {
            Point display = lightDisplayOrigin(light);
            if (display != null)
                return display.y;
        }
        return lwtLabelDisplayY(view, paletteRoot);
    }

    /** Leaf field-row внутри section body под displayY (одна строка ~33px). */
    static Composite leafFieldRowAtDisplayY(Composite sectionBody, int displayY)
    {
        if (sectionBody == null || sectionBody.isDisposed() || displayY <= 0)
            return null;
        return findLeafRowAtDisplayY(sectionBody, displayY, 0);
    }

    private static Composite findLeafRowAtDisplayY(Composite composite, int displayY, int depth)
    {
        if (composite == null || composite.isDisposed() || depth > 5)
            return null;
        for (Control child : composite.getChildren())
        {
            if (!(child instanceof Composite))
                continue;
            Composite c = (Composite) child;
            int top = c.toDisplay(0, 0).y;
            int bottom = top + c.getSize().y;
            if (displayY < top || displayY >= bottom)
                continue;
            if (isLwtFieldRowComposite(c))
                return c;
            Composite nested = findLeafRowAtDisplayY(c, displayY, depth + 1);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static final String PS_SECTION_AREAS_KEY = "tormozit.ps.sectionAreas"; //$NON-NLS-1$
    private static final String PS_SECTION_BANDS_KEY = "tormozit.ps.sectionBands"; //$NON-NLS-1$
    private static final String PS_CANVAS_CAPTURE_KEY = "tormozit.ps.canvasCapture"; //$NON-NLS-1$
    private static final String PS_SECTION_AREAS_CAPTURE_KEY = "tormozit.ps.sectionAreasCapture"; //$NON-NLS-1$
    private static final String PS_SECTION_ROW_LABELS_KEY = "tormozit.ps.sectionRowLabels"; //$NON-NLS-1$
    private static final int MIN_BACK_COLOR_BAND_HEIGHT = 2;
    private static final int MAX_SEPARATOR_BAND_HEIGHT = 8;
    private static final int MAX_BLURRED_SEPARATOR_RUN = 24;
    private static final int MAX_FIELD_END_SEPARATOR_RUN = 72;
    private static final int MIN_PROPERTY_AREA_HEIGHT = 12;
    private static final int MIN_ROW_GAP_CANVAS = 14;
    /** Минимум между bottom соседних band; меньше — дубль одной строки (319,337). */
    private static final int MIN_BAND_BOTTOM_GAP = 20;
    private static final int MIN_AREA_SLIVER_CANVAS = 18;
    private static final int SEP_BAND_CLUSTER_RADIUS = 22;
    private static final int SEP_BAND_CLUSTER_RADIUS_WIDE = 20;
    /** Не сканировать верх секции — chrome заголовка ExpandableComposite даёт ложные band@32. */
    private static final int SECTION_BODY_EDGE_EXCLUDE = 20;
    private static final int BACK_COLOR_SCAN_SAMPLE_STEP = 4;
    private static final int BACK_COLOR_LINE_THRESHOLD_PERCENT = 85;
    private static final int BACK_COLOR_RGB_TOLERANCE = 12;
    private static final int GUTTER_SEPARATOR_SCAN_X1 = 14;

    /** EDT-группа свойств: Section + body (DtLayoutComposite). */
    static final class LwtPropertySection
    {
        Composite sectionWidget;
        Composite body;
        int sectionTopDisplay;
        int bodyTopDisplay;
        int bodyBottomDisplay;
        String titleHint;
        int headerTopCanvas;
        int headerBottomCanvas;
        int bodyTopCanvas;
        int bodyBottomCanvas;
        boolean expanded;
    }

    /** Горизонтальная полоса разделителя цвета фона (canvas Y). */
    static final class BackColorBand
    {
        final int topCanvas;
        final int bottomCanvas;

        BackColorBand(int topCanvas, int bottomCanvas)
        {
            this.topCanvas = topCanvas;
            this.bottomCanvas = bottomCanvas;
        }
    }

    /** Область одного свойства между разделителями (canvas Y). */
    static final class PropertyArea
    {
        final int topCanvas;
        final int bottomCanvas;

        PropertyArea(int topCanvas, int bottomCanvas)
        {
            this.topCanvas = topCanvas;
            this.bottomCanvas = bottomCanvas;
        }

        int height()
        {
            return bottomCanvas - topCanvas;
        }

        boolean containsCanvasY(int canvasY)
        {
            return canvasY >= topCanvas && canvasY < bottomCanvas;
        }

        int[] toDisplayBand(Composite content)
        {
            if (content == null || content.isDisposed())
                return new int[] { topCanvas, bottomCanvas };
            return new int[] {
                    content.toDisplay(0, topCanvas).y,
                    content.toDisplay(0, bottomCanvas).y
            };
        }
    }

    /** Результат resolve под кликом. */
    static final class PropertyClickHit
    {
        final String displayName;
        final Object lwtView;
        final int areaTopDisplay;
        final int areaBottomDisplay;
        final int areaTopCanvas;
        final int areaBottomCanvas;
        final int propertyIndex;
        final String via;

        PropertyClickHit(String displayName, Object lwtView, int areaTopDisplay,
                int areaBottomDisplay, int areaTopCanvas, int areaBottomCanvas, int propertyIndex,
                String via)
        {
            this.displayName = displayName;
            this.lwtView = lwtView;
            this.areaTopDisplay = areaTopDisplay;
            this.areaBottomDisplay = areaBottomDisplay;
            this.areaTopCanvas = areaTopCanvas;
            this.areaBottomCanvas = areaBottomCanvas;
            this.propertyIndex = propertyIndex;
            this.via = via;
        }
    }

    static List<LwtPropertySection> collectPropertySections(Composite paletteRoot)
    {
        List<LwtPropertySection> out = new ArrayList<>();
        if (paletteRoot == null || paletteRoot.isDisposed())
            return out;
        collectPropertySectionsDeep(paletteRoot, out);
        refreshSectionDisplayBounds(out);
        normalizeSectionBodyBounds(out);
        return out;
    }

    private static void refreshSectionDisplayBounds(List<LwtPropertySection> sections)
    {
        for (LwtPropertySection section : sections)
        {
            if (section.sectionWidget == null || section.sectionWidget.isDisposed()
                    || section.body == null || section.body.isDisposed())
                continue;
            section.sectionTopDisplay = section.sectionWidget.toDisplay(0, 0).y;
            section.bodyTopDisplay = section.body.toDisplay(0, 0).y;
            section.bodyBottomDisplay = section.bodyTopDisplay + section.body.getSize().y;
            if (section.titleHint == null || section.titleHint.isEmpty())
                section.titleHint = sectionTitleHint(section.sectionWidget);
        }
        sections.sort(java.util.Comparator
                .comparingInt((LwtPropertySection s) -> s.bodyTopDisplay)
                .thenComparingInt(s -> s.sectionTopDisplay));
    }

    /** Обрезка bodyBottom: тело секции не заходит в заголовок/тело следующей. */
    private static void normalizeSectionBodyBounds(List<LwtPropertySection> sections)
    {
        for (int i = 0; i < sections.size(); i++)
        {
            LwtPropertySection section = sections.get(i);
            int clip = Integer.MAX_VALUE;
            for (int j = i + 1; j < sections.size(); j++)
            {
                LwtPropertySection next = sections.get(j);
                if (next.sectionTopDisplay > section.bodyTopDisplay)
                {
                    clip = Math.min(clip, next.sectionTopDisplay);
                    if (next.bodyTopDisplay > section.bodyTopDisplay)
                        clip = Math.min(clip, next.bodyTopDisplay);
                    break;
                }
            }
            if (clip != Integer.MAX_VALUE && section.bodyBottomDisplay > clip)
                section.bodyBottomDisplay = clip;
        }
    }

    private static String sectionTitleHint(Composite sectionWidget)
    {
        if (sectionWidget == null || sectionWidget.isDisposed())
            return ""; //$NON-NLS-1$
        Object text = Global.invoke(sectionWidget, "getText"); //$NON-NLS-1$
        if (text instanceof String && !((String) text).isEmpty())
            return (String) text;
        for (Control child : sectionWidget.getChildren())
        {
            if (child instanceof Label && !child.isDisposed())
            {
                String labelText = ((Label) child).getText();
                if (labelText != null && !labelText.isEmpty())
                    return labelText.trim();
            }
        }
        return sectionWidget.getClass().getSimpleName();
    }

    private static void collectPropertySectionsDeep(Composite composite, List<LwtPropertySection> out)
    {
        if (composite == null || composite.isDisposed()
                || PropertySheetUiContext.isFilterAreaControl(composite))
            return;
        String cn = composite.getClass().getName();
        if (cn.contains("Section") || cn.contains("ExpandableComposite")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Composite body = findSectionBodyChild(composite);
            if (body != null && !body.isDisposed())
            {
                LwtPropertySection section = new LwtPropertySection();
                section.sectionWidget = composite;
                section.body = body;
                section.titleHint = sectionTitleHint(composite);
                section.sectionTopDisplay = composite.toDisplay(0, 0).y;
                section.bodyTopDisplay = body.toDisplay(0, 0).y;
                section.bodyBottomDisplay = section.bodyTopDisplay + body.getSize().y;
                out.add(section);
            }
        }
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectPropertySectionsDeep((Composite) child, out);
        }
    }

    private static Composite findSectionBodyChild(Composite sectionWidget)
    {
        if (sectionWidget == null || sectionWidget.isDisposed())
            return null;
        Composite best = null;
        int bestHeight = 0;
        for (Control child : sectionWidget.getChildren())
        {
            if (!(child instanceof Composite))
                continue;
            Composite c = (Composite) child;
            String cn = c.getClass().getName();
            if (!(cn.contains("DtLayoutComposite") || cn.contains("LayoutComposite"))) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            Point size = c.getSize();
            if (size.x < 100 || size.y < 20)
                continue;
            if (size.y > bestHeight)
            {
                bestHeight = size.y;
                best = c;
            }
        }
        return best;
    }

    /** Группа под кликом: body содержит clickY; при нескольких — max bodyTop (вложенная/нижняя). */
    static LwtPropertySection findActiveSectionAtClick(Composite paletteRoot, int displayY)
    {
        if (paletteRoot == null || paletteRoot.isDisposed() || displayY <= 0)
            return null;
        java.util.List<LwtPropertySection> sections = collectPropertySections(paletteRoot);
        LwtPropertySection best = null;
        int matchCount = 0;
        for (LwtPropertySection section : sections)
        {
            if (displayY < section.bodyTopDisplay || displayY >= section.bodyBottomDisplay)
                continue;
            matchCount++;
            if (best == null || section.bodyTopDisplay > best.bodyTopDisplay
                    || (section.bodyTopDisplay == best.bodyTopDisplay
                            && section.sectionTopDisplay > best.sectionTopDisplay))
                best = section;
        }
        // #region agent log
        {
            java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("clickY", displayY); //$NON-NLS-1$
            d.put("sectionCount", sections.size()); //$NON-NLS-1$
            d.put("matchCount", matchCount); //$NON-NLS-1$
            if (best != null)
            {
                d.put("title", best.titleHint != null ? best.titleHint : ""); //$NON-NLS-1$ //$NON-NLS-2$
                d.put("sectionTop", best.sectionTopDisplay); //$NON-NLS-1$
                d.put("bodyTop", best.bodyTopDisplay); //$NON-NLS-1$
                d.put("bodyBottom", best.bodyBottomDisplay); //$NON-NLS-1$
            }
            if (sections.size() <= 8)
            {
                java.util.List<String> frames = new java.util.ArrayList<>();
                for (LwtPropertySection s : sections)
                {
                    frames.add((s.titleHint != null ? s.titleHint : "?") //$NON-NLS-1$
                            + "@" + s.bodyTopDisplay + ".." + s.bodyBottomDisplay); //$NON-NLS-1$ //$NON-NLS-2$
                }
                d.put("frames", String.join("|", frames)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            agentHitLog(best != null ? "H1ok" : "H1", "ControlInterop.findActiveSectionAtClick", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    best != null ? "found" : "miss", d); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // #endregion
        return best;
    }

    /** Строка свойства внутри body (leaf ~33px или многострочная). */
    private static boolean isSectionPropertyRow(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        if (isLwtFieldRowComposite(composite))
            return true;
        String cn = composite.getClass().getName();
        if (!cn.contains("DtLayoutComposite") && !cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        Point size = composite.getSize();
        if (size.x < 100 || size.y < 18)
            return false;
        return !containsNestedFieldRow(composite);
    }

    /** LWT view по подписи из renderer (LabelViewModel), без обхода FieldComponent. */
    static Object findLwtViewByDisplayName(Object scene, String displayName)
    {
        if (scene == null || displayName == null || displayName.isEmpty())
            return null;
        for (java.util.Map.Entry<?, ?> entry : labelEntriesFromScene(scene))
        {
            String text = labelTextOfViewModel(entry.getKey());
            if (displayName.equals(text))
                return entry.getValue();
        }
        return null;
    }

    /**
     * View РЕДАКТОРА значения для подписи {@code displayName} — в отличие от
     * {@link #findLwtViewByDisplayName}, которая возвращает view самой подписи (тоже
     * {@code LabelViewModel}-запись, отсюда её {@code getText()} — это текст подписи, а не
     * значения). В карте {@code renderer.viewModelToView} (insertion-order) редактор — запись,
     * непосредственно следующая за подписью — см. {@code PropertyNameIdentifierHook.findValueViewAfterLabel}
     * для того же приёма на поле «Имя».
     */
    static Object findEditorViewByDisplayName(Object scene, String displayName)
    {
        if (scene == null || displayName == null || displayName.isEmpty())
            return null;
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map))
            return null;
        boolean foundLabel = false;
        for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) mapObj).entrySet())
        {
            if (foundLabel)
                return entry.getValue();
            Object key = entry.getKey();
            if (key != null && key.getClass().getName().contains("LabelViewModel") //$NON-NLS-1$
                    && displayName.equals(labelTextOfViewModel(key)))
                foundLabel = true;
        }
        return null;
    }

    private static Object resolveFieldByDisplayName(Object page, int groupIndex, String displayName)
    {
        if (page == null || displayName == null || displayName.isEmpty())
            return null;
        java.util.List<Object> sections = modelSectionDefinitions(page);
        if (groupIndex < 0 || groupIndex >= sections.size())
            return null;
        for (Object field : modelFieldDefinitions(sections.get(groupIndex)))
        {
            if (displayName.equals(labeledDefinitionText(field)))
                return field;
        }
        return null;
    }

    /** Leaf-row host для LWT view; не возвращает section-body (>120px). */
    static Composite resolveRowHostForView(Object view, Composite paletteRoot)
    {
        Composite row = leafFieldRowHostForView(view);
        if (row != null && !row.isDisposed() && row.getSize().y <= 120)
            return row;
        row = resolveLwtFieldRowHost(view, paletteRoot);
        if (row != null && !row.isDisposed() && row.getSize().y <= 120)
            return row;
        row = findPaintHostForView(view, paletteRoot);
        if (row != null && !row.isDisposed() && row.getSize().y <= 120)
            return row;
        return null;
    }

    static Composite resolvePaletteRowHost(PropertySheetPaletteRow row)
    {
        return rowHostForPaletteRow(row);
    }

    /** Leaf-row host (~≤120px); не section-body. */
    static Composite rowHostForPaletteRow(PropertySheetPaletteRow row)
    {
        if (row == null || !row.isAlive())
            return null;
        if (row.lwtView != null)
        {
            Composite leaf = leafFieldRowHostForView(row.lwtView);
            if (leaf != null && !leaf.isDisposed() && leaf.getSize().y <= 120)
                return leaf;
        }
        if (row.rowComposite != null && !row.rowComposite.isDisposed()
                && row.rowComposite.getSize().y <= 120)
            return row.rowComposite;
        if (row.nameControl instanceof Composite)
        {
            Composite nc = (Composite) row.nameControl;
            if (!nc.isDisposed() && nc.getSize().y <= 120)
                return nc;
        }
        return null;
    }

    /** Host для рамки: leaf → scanner controls → leaf@Y → section.body. */
    static Composite paintHostForPaletteRow(PropertySheetPaletteRow row, Composite sectionBody,
            int displayY)
    {
        Composite host = rowHostForPaletteRow(row);
        if (host != null)
            return host;
        if (row != null && row.lwtView != null && sectionBody != null && !sectionBody.isDisposed())
        {
            Composite leaf = leafFieldRowHostForView(row.lwtView);
            if (leaf == null)
                leaf = resolveRowHostForView(row.lwtView, sectionBody);
            if (leaf != null && !leaf.isDisposed() && isDescendantOf(leaf, sectionBody)
                    && leaf.getSize().y <= 120)
                return leaf;
        }
        if (row != null && row.nameControl instanceof Composite)
        {
            Composite nc = (Composite) row.nameControl;
            if (!nc.isDisposed() && nc.getSize().y <= 120
                    && (sectionBody == null || isDescendantOf(nc, sectionBody)))
                return nc;
        }
        if (row != null && row.rowComposite != null && !row.rowComposite.isDisposed()
                && row.rowComposite.getSize().y <= 120
                && (sectionBody == null || isDescendantOf(row.rowComposite, sectionBody)))
            return row.rowComposite;
        if (sectionBody != null && !sectionBody.isDisposed() && displayY > 0)
        {
            Composite leaf = leafFieldRowAtDisplayY(sectionBody, displayY);
            if (leaf != null && !leaf.isDisposed())
                return leaf;
        }
        if (sectionBody != null && !sectionBody.isDisposed())
            return sectionBody;
        return null;
    }

    /** Canvas-полоса строки: leaf-host в секции, не stale light-bounds. */
    private static int[] paletteRowCanvasBand(PropertySheetPaletteRow row, Composite content,
            LwtPropertySection section)
    {
        if (row == null || content == null || content.isDisposed())
            return null;
        Composite sectionBody = section != null ? section.body : null;
        Composite host = rowHostForPaletteRow(row);
        if (host != null && !host.isDisposed()
                && (sectionBody == null || isDescendantOf(host, sectionBody)))
        {
            Point topLeft = content.toControl(host.toDisplay(0, 0));
            int top = topLeft.y;
            return new int[] { top, top + Math.max(1, host.getSize().y) };
        }
        if (row.lwtView != null && sectionBody != null && !sectionBody.isDisposed())
        {
            Composite leaf = leafFieldRowHostForView(row.lwtView);
            if (leaf == null)
                leaf = resolveRowHostForView(row.lwtView, content);
            if (leaf != null && !leaf.isDisposed() && isDescendantOf(leaf, sectionBody))
            {
                Point topLeft = content.toControl(leaf.toDisplay(0, 0));
                int top = topLeft.y;
                return new int[] { top, top + Math.max(1, leaf.getSize().y) };
            }
        }
        if (row.nameControl instanceof Composite)
        {
            Composite nc = (Composite) row.nameControl;
            if (!nc.isDisposed() && nc.getSize().y <= 120
                    && (sectionBody == null || isDescendantOf(nc, sectionBody)))
            {
                Point topLeft = content.toControl(nc.toDisplay(0, 0));
                int top = topLeft.y;
                return new int[] { top, top + Math.max(1, nc.getSize().y) };
            }
        }
        if (row.rowComposite != null && !row.rowComposite.isDisposed()
                && row.rowComposite.getSize().y <= 120
                && (sectionBody == null || isDescendantOf(row.rowComposite, sectionBody)))
        {
            Point topLeft = content.toControl(row.rowComposite.toDisplay(0, 0));
            int top = topLeft.y;
            return new int[] { top, top + Math.max(1, row.rowComposite.getSize().y) };
        }
        return null;
    }

    /** @deprecated use {@link #rowHostForPaletteRow} */
    static Composite rowHostFromPaletteRow(PropertySheetPaletteRow row)
    {
        return rowHostForPaletteRow(row);
    }

    static boolean paletteRowInSection(PropertySheetPaletteRow row, LwtPropertySection section,
            Composite content)
    {
        if (row == null || section == null || section.body == null || section.body.isDisposed())
            return false;
        if (row.lwtView != null && viewInSectionBody(row.lwtView, section.body, content))
            return true;
        int[] band = paletteRowCanvasBand(row, content, section);
        if (band == null)
            return false;
        int centerCanvas = (band[0] + band[1]) / 2;
        int centerY = content.toDisplay(0, centerCanvas).y;
        return centerY >= section.bodyTopDisplay && centerY < section.bodyBottomDisplay;
    }

    private static boolean isDescendantOf(Control control, Composite ancestor)
    {
        if (control == null || control.isDisposed() || ancestor == null || ancestor.isDisposed())
            return false;
        for (Composite parent = control.getParent(); parent != null && !parent.isDisposed();
                parent = parent.getParent())
        {
            if (parent == ancestor)
                return true;
        }
        return false;
    }

    private static Composite paletteRootAncestor(Composite start)
    {
        if (start == null || start.isDisposed())
            return null;
        Composite best = start;
        for (Composite c = start; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c.getSize().y >= 400)
                best = c;
        }
        return best;
    }

    /** Строки свойств секции → canvas-areas (границы LWT layout, не print-scan). */
    static List<PropertyArea> buildPropertyAreasFromPropertyRows(Composite content,
            LwtPropertySection section, Object scene, Object page)
    {
        if (content == null || content.isDisposed() || section == null
                || section.body == null || section.body.isDisposed())
            return java.util.Collections.emptyList();
        java.util.List<LwtPropertySection> sections = collectPropertySections(content);
        refreshSectionDisplayBounds(sections);
        normalizeSectionBodyBounds(sections);
        for (LwtPropertySection s : sections)
        {
            if (s.body == section.body)
            {
                section.bodyTopDisplay = s.bodyTopDisplay;
                section.bodyBottomDisplay = s.bodyBottomDisplay;
                break;
            }
        }
        if (Boolean.TRUE.equals(section.body.getData("tormozit.ps.rowAreasStale"))) //$NON-NLS-1$
        {
            section.body.setData(PS_SECTION_AREAS_KEY, null);
            section.body.setData(PS_SECTION_AREAS_CAPTURE_KEY, null);
            section.body.setData(PS_SECTION_ROW_LABELS_KEY, null);
            section.body.setData("tormozit.ps.rowAreasStale", null); //$NON-NLS-1$
        }
        Object areasToken = section.body.getData(PS_SECTION_AREAS_CAPTURE_KEY);
        Object cached = section.body.getData(PS_SECTION_AREAS_KEY);
        if ("rowLayout".equals(areasToken) && cached instanceof List) //$NON-NLS-1$
        {
            @SuppressWarnings("unchecked")
            java.util.List<PropertyArea> cachedAreas = (java.util.List<PropertyArea>) cached;
            if (page != null)
            {
                int groupIndex = resolveGroupIndex(page, section, sections);
                int visible = visibleFieldDefinitionsInSection(page, groupIndex, section, scene,
                        content).size();
                if (visible <= 0 || cachedAreas.size() <= visible + 2)
                    return cachedAreas;
            }
            else
                return cachedAreas;
        }
        List<PropertyArea> areas = new ArrayList<>();
        List<String> rowLabels = new ArrayList<>();
        String rowSource = "none"; //$NON-NLS-1$
        int ctxRowsInSection = 0;
        if (page != null)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx != null && !ctx.rows.isEmpty())
            {
                int groupIndex = resolveGroupIndex(page, section, sections);
                java.util.List<PropertySheetPaletteRow> sectionRows = ctxRowsForSection(ctx,
                        section, content, page, groupIndex, scene);
                ctxRowsInSection = sectionRows.size();
                sectionRows.sort(java.util.Comparator.comparingInt(r -> {
                    int[] band = paletteRowCanvasBand(r, content, section);
                    return band != null ? band[0] : Integer.MAX_VALUE;
                }));
                java.util.Set<String> seenBands = new java.util.LinkedHashSet<>();
                for (PropertySheetPaletteRow paletteRow : sectionRows)
                {
                    int[] band = paletteRowCanvasBand(paletteRow, content, section);
                    if (band == null || band[1] <= band[0])
                        continue;
                    String key = band[0] + ":" + band[1]; //$NON-NLS-1$
                    if (!seenBands.add(key))
                        continue;
                    areas.add(new PropertyArea(band[0], band[1]));
                    rowLabels.add(paletteRow.propertyName);
                }
                if (!areas.isEmpty())
                    rowSource = "ctx"; //$NON-NLS-1$
            }
        }
        if (areas.isEmpty())
        {
            List<Composite> rows = new ArrayList<>();
            collectPropertyRowHostsRelaxed(section.body, section.bodyTopDisplay,
                    section.bodyBottomDisplay, rows);
            if (!rows.isEmpty())
                rowSource = "relaxed"; //$NON-NLS-1$
            for (Composite row : rows)
            {
                if (row.isDisposed())
                    continue;
                Point topLeft = content.toControl(row.toDisplay(0, 0));
                int top = topLeft.y;
                int bottom = top + row.getSize().y;
                if (bottom <= top)
                    continue;
                areas.add(new PropertyArea(top, bottom));
            }
        }
        if (!areas.isEmpty())
        {
            section.body.setData(PS_SECTION_AREAS_KEY, areas);
            section.body.setData(PS_SECTION_AREAS_CAPTURE_KEY, "rowLayout"); //$NON-NLS-1$
            section.body.setData(PS_SECTION_ROW_LABELS_KEY,
                    rowLabels.isEmpty() ? null : new ArrayList<>(rowLabels));
        }
        // #region agent log
        StringBuilder areaRanges = new StringBuilder();
        for (int i = 0; i < areas.size() && i < 10; i++)
        {
            if (i > 0)
                areaRanges.append(',');
            PropertyArea a = areas.get(i);
            areaRanges.append(a.topCanvas).append('-').append(a.bottomCanvas);
        }
        agentHitLog("H19", "ControlInterop.buildPropertyAreasFromPropertyRows", "rowAreas", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                java.util.Map.of("title", section.titleHint != null ? section.titleHint : "", //$NON-NLS-1$ //$NON-NLS-2$
                        "rowCount", areas.size(), "areas", areas.size(), //$NON-NLS-1$ //$NON-NLS-2$
                        "rowSource", rowSource, "labelCount", rowLabels.size(), //$NON-NLS-1$ //$NON-NLS-2$
                        "ctxRowsInSection", ctxRowsInSection, //$NON-NLS-1$
                        "areaRanges", areaRanges.toString())); //$NON-NLS-1$
        // #endregion
        return areas;
    }

    /** Обход body без {@link #containsNestedFieldRow} — leaf DtLayoutComposite ~18–120px. */
    private static void collectPropertyRowHostsRelaxed(Composite body, int bodyTopDisplay,
            int bodyBottomDisplay, List<Composite> out)
    {
        if (body == null || body.isDisposed())
            return;
        collectPropertyRowHostsRelaxedDeep(body, body, bodyTopDisplay, bodyBottomDisplay, out);
        out.sort(java.util.Comparator.comparingInt(PropertySheetControlInterop::fieldRowDisplayY));
    }

    private static void collectPropertyRowHostsRelaxedDeep(Composite body, Composite composite,
            int bodyTopDisplay, int bodyBottomDisplay, List<Composite> out)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (composite != body)
        {
            String cn = composite.getClass().getName();
            if (cn.contains("DtLayoutComposite") || cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Point size = composite.getSize();
                if (size.x >= 100 && size.y >= 18 && size.y <= 120)
                {
                    int y = composite.toDisplay(0, 0).y;
                    if (y >= bodyTopDisplay && y < bodyBottomDisplay && !out.contains(composite))
                        out.add(composite);
                }
            }
        }
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectPropertyRowHostsRelaxedDeep(body, (Composite) child, bodyTopDisplay,
                        bodyBottomDisplay, out);
        }
    }

    static List<Object> visibleFieldDefinitionsInSection(Object page, int groupIndex,
            LwtPropertySection section, Object scene, Composite content)
    {
        java.util.List<Object> sections = modelSectionDefinitions(page);
        if (groupIndex < 0 || groupIndex >= sections.size())
            return java.util.Collections.emptyList();
        java.util.List<Object> all = modelFieldDefinitions(sections.get(groupIndex));
        java.util.List<Object> visible = new ArrayList<>();
        for (Object field : all)
        {
            String label = labeledDefinitionText(field);
            if (label == null || label.isEmpty())
                continue;
            Object view = findLwtViewByDisplayName(scene, label);
            if (view == null)
                view = findFieldComponentByDisplayName(scene, label);
            if (view == null)
                continue;
            if (section != null && section.body != null && content != null
                    && !viewInSectionBody(view, section.body, content))
                continue;
            visible.add(field);
        }
        if (section != null && section.body != null && content != null)
        {
            visible.sort(java.util.Comparator.comparingInt(f -> {
                Object view = findLwtViewByDisplayName(scene, labeledDefinitionText(f));
                if (view == null)
                    view = findFieldComponentByDisplayName(scene, labeledDefinitionText(f));
                if (view == null)
                    return Integer.MAX_VALUE;
                return labelLightCenterDisplayY(view, section.body, content);
            }));
        }
        return visible;
    }

    private static void collectPropertyRowsInBody(Composite body, List<Composite> out)
    {
        if (body == null || body.isDisposed())
            return;
        collectPropertyRowsInBodyDeep(body, body, out);
        out.sort(java.util.Comparator.comparingInt(PropertySheetControlInterop::fieldRowDisplayY));
    }

    private static void collectPropertyRowsInBodyDeep(Composite body, Composite composite,
            List<Composite> out)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (composite != body && isSectionPropertyRow(composite))
        {
            if (!out.contains(composite))
                out.add(composite);
            return;
        }
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectPropertyRowsInBodyDeep(body, (Composite) child, out);
        }
    }

    static void invalidateSectionAreaCache(Composite body)
    {
        if (body != null && !body.isDisposed())
        {
            body.setData(PS_SECTION_AREAS_KEY, null);
            body.setData(PS_SECTION_BANDS_KEY, null);
            body.setData(PS_SECTION_AREAS_CAPTURE_KEY, null);
            body.setData(PS_SECTION_ROW_LABELS_KEY, null);
            body.setData("tormozit.ps.rowAreasStale", Boolean.TRUE); //$NON-NLS-1$
        }
    }

    static void invalidatePaletteGeometryCache(Composite content)
    {
        if (content == null || content.isDisposed())
            return;
        content.setData(PS_CANVAS_CAPTURE_KEY, null);
        content.setData(PS_SECTION_AREAS_KEY, null);
        content.setData(PS_SECTION_BANDS_KEY, null);
        for (LwtPropertySection section : collectPropertySections(content))
        {
            if (section.body == null || section.body.isDisposed())
                continue;
            section.body.setData(PS_SECTION_AREAS_KEY, null);
            section.body.setData(PS_SECTION_BANDS_KEY, null);
            section.body.setData(PS_SECTION_AREAS_CAPTURE_KEY, null);
            section.body.setData(PS_SECTION_ROW_LABELS_KEY, null);
        }
    }

    private static boolean isExpandableSectionWidget(Composite sectionWidget)
    {
        return sectionWidget != null && !sectionWidget.isDisposed()
                && sectionWidget.getClass().getName().contains("ExpandableComposite"); //$NON-NLS-1$
    }

    private static boolean sectionExpanded(Composite sectionWidget)
    {
        if (sectionWidget == null || sectionWidget.isDisposed())
            return false;
        Object expanded = Global.invoke(sectionWidget, "getExpanded"); //$NON-NLS-1$
        if (expanded instanceof Boolean)
            return (Boolean) expanded;
        return true;
    }

    private static int sectionHeaderBottomCanvas(Composite sectionWidget, Composite content,
            int headerTopCanvas)
    {
        if (sectionWidget == null || sectionWidget.isDisposed() || content == null)
            return headerTopCanvas + 24;
        if (isExpandableSectionWidget(sectionWidget))
        {
            Object clientAreaObj = Global.invoke(sectionWidget, "getClientArea"); //$NON-NLS-1$
            if (clientAreaObj instanceof Rectangle)
            {
                Rectangle client = (Rectangle) clientAreaObj;
                Point clientScreen = sectionWidget.toDisplay(client.x, client.y);
                return content.toControl(clientScreen).y;
            }
        }
        int probeY = Math.min(28, Math.max(8, sectionWidget.getSize().y / 3));
        return content.toControl(sectionWidget.toDisplay(0, probeY)).y;
    }

    static void refreshSectionCanvasBounds(java.util.List<LwtPropertySection> sections,
            Composite content)
    {
        if (content == null || content.isDisposed() || sections == null)
            return;
        for (LwtPropertySection section : sections)
        {
            if (section.sectionWidget == null || section.sectionWidget.isDisposed())
                continue;
            Point headerTop = content.toControl(section.sectionWidget.toDisplay(0, 0));
            section.headerTopCanvas = headerTop.y;
            section.expanded = sectionExpanded(section.sectionWidget);
            section.headerBottomCanvas = sectionHeaderBottomCanvas(section.sectionWidget, content,
                    section.headerTopCanvas);
            if (section.expanded && section.body != null && !section.body.isDisposed())
            {
                Point bodyTop = content.toControl(section.body.toDisplay(0, 0));
                section.bodyTopCanvas = bodyTop.y;
                section.bodyBottomCanvas = bodyTop.y + section.body.getSize().y;
            }
            else
            {
                section.bodyTopCanvas = section.headerBottomCanvas;
                section.bodyBottomCanvas = section.headerBottomCanvas;
            }
        }
        sections.sort(java.util.Comparator.comparingInt(s -> s.headerTopCanvas));
        normalizeSectionBodyCanvasBounds(sections);
    }

    private static void normalizeSectionBodyCanvasBounds(java.util.List<LwtPropertySection> sections)
    {
        for (int i = 0; i < sections.size(); i++)
        {
            LwtPropertySection section = sections.get(i);
            int clip = Integer.MAX_VALUE;
            for (int j = i + 1; j < sections.size(); j++)
            {
                LwtPropertySection next = sections.get(j);
                if (next.headerTopCanvas > section.bodyTopCanvas)
                {
                    clip = Math.min(clip, next.headerTopCanvas);
                    if (next.bodyTopCanvas > section.bodyTopCanvas)
                        clip = Math.min(clip, next.bodyTopCanvas);
                    break;
                }
            }
            if (clip != Integer.MAX_VALUE && section.bodyBottomCanvas > clip)
                section.bodyBottomCanvas = clip;
        }
    }

    /** Группа под canvasY: ближайший заголовок выше (включая свёрнутые). */
    static LwtPropertySection findActiveSectionAtCanvasY(
            java.util.List<LwtPropertySection> sections, int canvasY)
    {
        if (sections == null || canvasY < 0)
            return null;
        LwtPropertySection best = null;
        for (LwtPropertySection section : sections)
        {
            if (canvasY < section.headerTopCanvas)
                continue;
            if (best == null || section.headerTopCanvas >= best.headerTopCanvas)
                best = section;
        }
        return best;
    }

    private static String normalizeSectionTitle(String title)
    {
        if (title == null)
            return ""; //$NON-NLS-1$
        return title.replace("\u25b6", "").replace("\u25bc", "").replace("\u25be", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace("\u25b8", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isSectionDefinitionNode(Object node)
    {
        return node != null && node.getClass().getName().contains("SectionDefinition"); //$NON-NLS-1$
    }

    private static boolean isFieldDefinitionNode(Object node)
    {
        return node != null && node.getClass().getName().contains("FieldDefinition"); //$NON-NLS-1$
    }

    private static boolean isSeparatorDefinitionNode(Object node)
    {
        return node != null && node.getClass().getName().contains("SeparatorDefinition"); //$NON-NLS-1$
    }

    static java.util.List<Object> modelSectionDefinitions(Object page)
    {
        java.util.List<Object> out = new ArrayList<>();
        Object paletteModel = page != null ? Global.invoke(page, "getPaletteModel") : null; //$NON-NLS-1$
        Object definition = paletteModel != null ? Global.invoke(paletteModel, "getDefinition") //$NON-NLS-1$
                : null;
        if (definition == null)
            return out;
        Object children = Global.invoke(definition, "getChildren"); //$NON-NLS-1$
        if (!(children instanceof Iterable))
            return out;
        for (Object child : (Iterable<?>) children)
        {
            if (isSectionDefinitionNode(child))
                out.add(child);
        }
        return out;
    }

    private static java.util.List<Object> modelFieldDefinitions(Object sectionDef)
    {
        java.util.List<Object> out = new ArrayList<>();
        if (sectionDef == null)
            return out;
        Object children = Global.invoke(sectionDef, "getChildren"); //$NON-NLS-1$
        if (!(children instanceof Iterable))
            return out;
        for (Object child : (Iterable<?>) children)
        {
            if (isFieldDefinitionNode(child) && !isSeparatorDefinitionNode(child))
                out.add(child);
        }
        return out;
    }

    static int resolveGroupIndex(Object page, LwtPropertySection section,
            java.util.List<LwtPropertySection> sections)
    {
        java.util.List<Object> modelSections = modelSectionDefinitions(page);
        if (section == null)
            return -1;
        String title = normalizeSectionTitle(section.titleHint);
        for (int i = 0; i < modelSections.size(); i++)
        {
            String label = normalizeSectionTitle(labeledDefinitionText(modelSections.get(i)));
            if (!title.isEmpty() && title.equals(label))
                return i;
        }
        if (sections != null)
        {
            for (int i = 0; i < sections.size(); i++)
            {
                if (sections.get(i) == section)
                {
                    // #region agent log
                    agentHitLog("H13", "ControlInterop.resolveGroupIndex", "fallbackIndex", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            java.util.Map.of("lwtIndex", i, "title", title, "modelSections", modelSections.size())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    // #endregion
                    return i;
                }
            }
        }
        return -1;
    }

    static Object resolveFieldByIndices(Object page, int groupIndex, int propertyIndex)
    {
        java.util.List<Object> sections = modelSectionDefinitions(page);
        if (groupIndex < 0 || groupIndex >= sections.size())
            return null;
        java.util.List<Object> fields = modelFieldDefinitions(sections.get(groupIndex));
        if (propertyIndex < 0 || propertyIndex >= fields.size())
            return null;
        return fields.get(propertyIndex);
    }

    private static org.eclipse.swt.custom.ScrolledComposite findParentScrolledComposite(
            Composite content)
    {
        for (Composite parent = content != null ? content.getParent() : null; parent != null;
                parent = parent.getParent())
        {
            if (parent instanceof org.eclipse.swt.custom.ScrolledComposite)
                return (org.eclipse.swt.custom.ScrolledComposite) parent;
        }
        return null;
    }

    /** Полный canvas через {@link Composite#print(GC)} — без прокрутки viewport. */
    private static ImageData captureCanvasImageData(Composite content)
    {
        if (content == null || content.isDisposed())
            return null;
        Point size = content.getSize();
        if (size.x < 40 || size.y < MIN_PROPERTY_AREA_HEIGHT)
            return null;
        Display display = content.getDisplay();
        Image image = null;
        GC gc = null;
        org.eclipse.swt.custom.ScrolledComposite scrolled = findParentScrolledComposite(content);
        org.eclipse.swt.graphics.Point origin = scrolled != null ? scrolled.getOrigin() : null;
        int oldOriginY = origin != null ? origin.y : 0;
        int oldOriginX = origin != null ? origin.x : 0;
        boolean redrawOff = false;
        try
        {
            if (scrolled != null && !scrolled.isDisposed())
            {
                scrolled.setRedraw(false);
                content.setRedraw(false);
                redrawOff = true;
                scrolled.setOrigin(0, 0);
            }
            image = new Image(display, size.x, size.y);
            gc = new GC(image);
            Color bg = content.getBackground();
            if (bg != null && !bg.isDisposed())
            {
                gc.setBackground(bg);
                gc.fillRectangle(0, 0, size.x, size.y);
            }
            if (!content.print(gc))
            {
                PropertySheetDebug.problem("canvasCapture print=false size=" + size.x + "x" //$NON-NLS-1$ //$NON-NLS-2$
                        + size.y);
                // #region agent log
                agentHitLog("H10", "ControlInterop.captureCanvasImageData", "printFail", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("w", size.x, "h", size.y)); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return null;
            }
            ImageData data = image.getImageData();
            if (!captureImageLooksValid(data, bg != null ? bg.getRGB() : null))
            {
                PropertySheetDebug.problem("canvasCapture print blank size=" + size.x + "x" //$NON-NLS-1$ //$NON-NLS-2$
                        + size.y);
                // #region agent log
                agentHitLog("H10", "ControlInterop.captureCanvasImageData", "printBlank", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("w", size.x, "h", size.y)); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return null;
            }
            // #region agent log
            agentHitLog("H10", "ControlInterop.captureCanvasImageData", "printOk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("w", size.x, "h", size.y)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return data;
        }
        catch (Exception ex)
        {
            PropertySheetDebug.problem("canvasCapture " + ex.getMessage()); //$NON-NLS-1$
            return null;
        }
        finally
        {
            if (scrolled != null && !scrolled.isDisposed())
                scrolled.setOrigin(oldOriginX, oldOriginY);
            if (redrawOff)
            {
                content.setRedraw(true);
                scrolled.setRedraw(true);
            }
            if (gc != null)
                gc.dispose();
            if (image != null)
                image.dispose();
        }
    }

    private static boolean captureImageLooksValid(ImageData data, RGB referenceBackColorRgb)
    {
        if (data == null || data.width < 4 || data.height < 4)
            return false;
        int diffRows = 0;
        int samples = 0;
        RGB prev = null;
        for (int y = 2; y < data.height - 2; y += Math.max(8, data.height / 32))
        {
            RGB px = pixelRgb(data, data.width / 3, y);
            if (px == null)
                continue;
            if (prev != null && !colorNear(px, prev, 6))
                diffRows++;
            if (referenceBackColorRgb != null && !colorNear(px, referenceBackColorRgb, BACK_COLOR_RGB_TOLERANCE + 4))
                diffRows++;
            prev = px;
            samples++;
        }
        return samples >= 2 && diffRows >= 1;
    }

    static ImageData cachedCanvasCapture(Composite content)
    {
        if (content == null || content.isDisposed())
            return null;
        Object cached = content.getData(PS_CANVAS_CAPTURE_KEY);
        if (cached instanceof ImageData)
            return (ImageData) cached;
        ImageData data = captureCanvasImageData(content);
        if (data != null)
            content.setData(PS_CANVAS_CAPTURE_KEY, data);
        return data;
    }

    private static RGB pixelRgb(ImageData data, int x, int y)
    {
        if (data == null || x < 0 || y < 0 || x >= data.width || y >= data.height)
            return null;
        int pixel = data.getPixel(x, y);
        PaletteData palette = data.palette;
        if (palette == null)
            return null;
        return palette.getRGB(pixel);
    }

    private static RGB referenceBackColorRgb(ImageData data, Composite content)
    {
        RGB contentBackColor = contentBackColorRgb(content);
        if (data == null || data.width < 4 || data.height < 2)
            return contentBackColor;
        RGB corner = pixelRgb(data, 2, 0);
        if (corner != null && colorNear(corner, contentBackColor, BACK_COLOR_RGB_TOLERANCE + 6))
            return corner;
        return contentBackColor;
    }

    private static int backColorLinePercent(ImageData data, int y, int x0, int x1, RGB backColorRgb)
    {
        if (data == null || y < 0 || y >= data.height || x1 <= x0)
            return 0;
        int backColorCount = 0;
        int samples = 0;
        for (int x = x0; x < x1; x += BACK_COLOR_SCAN_SAMPLE_STEP)
        {
            RGB px = pixelRgb(data, x, y);
            if (px != null && colorNear(px, backColorRgb, BACK_COLOR_RGB_TOLERANCE))
                backColorCount++;
            samples++;
        }
        return samples > 0 ? backColorCount * 100 / samples : 0;
    }

    private static RGB contentBackColorRgb(Composite body)
    {
        Color bg = body.getBackground();
        if (bg == null || bg.isDisposed())
            bg = body.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        return bg.getRGB();
    }

    private static boolean isBackColorSeparatorLine(ImageData data, int y, int midX, RGB backColorRgb,
            int thresholdPercent)
    {
        if (data == null || y < 0 || y >= data.height)
            return false;
        int labelEnd = Math.max(midX, Math.max(40, data.width / 3));
        int left = backColorLinePercent(data, y, 0, labelEnd, backColorRgb);
        int right = backColorLinePercent(data, y, midX, data.width, backColorRgb);
        int full = backColorLinePercent(data, y, 0, data.width, backColorRgb);
        if (left < thresholdPercent - 5)
            return false;
        if (right < thresholdPercent - 20 && full < thresholdPercent - 10)
            return false;
        if (y >= 3 && y < data.height - 3)
        {
            int above = backColorLinePercent(data, y - 3, 0, labelEnd, backColorRgb);
            int below = backColorLinePercent(data, y + 3, 0, labelEnd, backColorRgb);
            if (left >= thresholdPercent - 5 && above < left - 8 && below < left - 8)
                return true;
        }
        return left >= thresholdPercent && right >= thresholdPercent - 15;
    }

    private static boolean isGutterBackColorSeparatorLine(ImageData data, int y, RGB backColorRgb)
    {
        if (data == null || y < 4 || y >= data.height - 4)
            return false;
        int x1 = Math.min(GUTTER_SEPARATOR_SCAN_X1, data.width);
        if (x1 < 4)
            return false;
        int line = backColorLinePercent(data, y, 0, x1, backColorRgb);
        if (line < 92)
            return false;
        int above = backColorLinePercent(data, y - 4, 0, x1, backColorRgb);
        int below = backColorLinePercent(data, y + 4, 0, x1, backColorRgb);
        return above < line - 10 && below < line - 10;
    }

    private static boolean isSeparatorScanline(ImageData data, int y, int midX, RGB backColorRgb,
            int thresholdPercent)
    {
        if (isGutterBackColorSeparatorLine(data, y, backColorRgb))
            return true;
        return isBackColorSeparatorLine(data, y, midX, backColorRgb, thresholdPercent);
    }

    /** Слить только перекрывающиеся полосы одного разделителя; gap<14 не трогать — там граница строки. */
    private static List<BackColorBand> consolidateSeparatorBands(List<BackColorBand> bands,
            int minContentGap)
    {
        if (bands == null || bands.size() <= 1)
            return bands != null ? bands : new ArrayList<>();
        List<BackColorBand> out = new ArrayList<>();
        BackColorBand current = bands.get(0);
        for (int i = 1; i < bands.size(); i++)
        {
            BackColorBand next = bands.get(i);
            if (next.topCanvas < current.bottomCanvas)
                current = new BackColorBand(current.topCanvas,
                        Math.max(current.bottomCanvas, next.bottomCanvas));
            else
            {
                out.add(current);
                current = next;
            }
        }
        out.add(current);
        return out;
    }

    /** Слить дубли одного разделителя (bandTops в логе: 42,45 или 593,601,609). */
    private static List<BackColorBand> clusterSeparatorBands(List<BackColorBand> bands)
    {
        return clusterSeparatorBands(bands, SEP_BAND_CLUSTER_RADIUS);
    }

    private static List<BackColorBand> clusterSeparatorBands(List<BackColorBand> bands, int clusterRadius)
    {
        if (bands == null || bands.isEmpty())
            return new ArrayList<>();
        List<BackColorBand> sorted = new ArrayList<>(bands);
        sorted.sort(java.util.Comparator.comparingInt(b -> b.topCanvas));
        List<BackColorBand> out = new ArrayList<>();
        int clusterTop = sorted.get(0).topCanvas;
        int clusterBottom = sorted.get(0).bottomCanvas;
        for (int i = 1; i < sorted.size(); i++)
        {
            BackColorBand band = sorted.get(i);
            if (band.topCanvas - clusterTop < clusterRadius)
                clusterBottom = Math.max(clusterBottom, band.bottomCanvas);
            else
            {
                out.add(thinBandFromCluster(clusterTop, clusterBottom));
                clusterTop = band.topCanvas;
                clusterBottom = band.bottomCanvas;
            }
        }
        out.add(thinBandFromCluster(clusterTop, clusterBottom));
        return out;
    }

    /** Слить band, если bottom ближе minGap — лишний разделитель одной строки. */
    private static List<BackColorBand> mergeBandsByMinBottomGap(List<BackColorBand> bands,
            int minGap)
    {
        if (bands == null || bands.size() <= 1)
            return bands != null ? new ArrayList<>(bands) : new ArrayList<>();
        List<BackColorBand> sorted = new ArrayList<>(bands);
        sorted.sort(java.util.Comparator.comparingInt(b -> b.bottomCanvas));
        List<BackColorBand> out = new ArrayList<>();
        BackColorBand current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++)
        {
            BackColorBand next = sorted.get(i);
            if (next.bottomCanvas - current.bottomCanvas < minGap)
                current = new BackColorBand(Math.min(current.topCanvas, next.topCanvas),
                        Math.max(current.bottomCanvas, next.bottomCanvas));
            else
            {
                out.add(current);
                current = next;
            }
        }
        out.add(current);
        return out;
    }

    private static BackColorBand thinBandFromCluster(int clusterTop, int clusterBottom)
    {
        int clusterH = clusterBottom - clusterTop;
        if (clusterH <= MAX_SEPARATOR_BAND_HEIGHT)
            return new BackColorBand(clusterTop, clusterBottom);
        return new BackColorBand(clusterTop, clusterTop + MIN_BACK_COLOR_BAND_HEIGHT);
    }

    /** Print() размывает разделитель; для runH>8 — нижний край полосы (граница строки). */
    private static BackColorBand bandFromSeparatorRun(int runStart, int runEnd)
    {
        int runH = runEnd - runStart;
        if (runH < MIN_BACK_COLOR_BAND_HEIGHT)
            return null;
        if (runH <= MAX_SEPARATOR_BAND_HEIGHT)
            return new BackColorBand(runStart, runEnd);
        if (runH <= MAX_BLURRED_SEPARATOR_RUN)
            return new BackColorBand(runEnd - MIN_BACK_COLOR_BAND_HEIGHT, runEnd);
        if (runH <= MAX_FIELD_END_SEPARATOR_RUN)
            return new BackColorBand(runEnd - MIN_BACK_COLOR_BAND_HEIGHT, runEnd);
        return null;
    }

    private static void appendSeparatorRunBand(List<BackColorBand> bands, int runStart, int runEnd,
            boolean tail)
    {
        BackColorBand band = bandFromSeparatorRun(runStart, runEnd);
        if (band != null)
        {
            bands.add(band);
            return;
        }
        int runH = runEnd - runStart;
        if (runH > MAX_FIELD_END_SEPARATOR_RUN)
        {
            // #region agent log
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("runStart", runStart); //$NON-NLS-1$
            data.put("runH", runH); //$NON-NLS-1$
            if (tail)
                data.put("tail", true); //$NON-NLS-1$
            agentHitLog("H12", "ControlInterop.mergeSeparatorRunsCanvas", "runTooTall", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    data);
            // #endregion
        }
    }

    private static List<BackColorBand> mergeSeparatorRunsCanvas(int sliceTop, int sliceBottom,
            ImageData data, int midX, RGB backColorRgb, int thresholdPercent)
    {
        List<BackColorBand> bands = new ArrayList<>();
        if (data == null || sliceBottom <= sliceTop)
            return bands;
        int runStart = -1;
        int yEnd = Math.min(sliceBottom, data.height);
        for (int y = Math.max(0, sliceTop); y < yEnd; y++)
        {
            boolean sepLine = isSeparatorScanline(data, y, midX, backColorRgb, thresholdPercent);
            if (sepLine)
            {
                if (runStart < 0)
                    runStart = y;
            }
            else if (runStart >= 0)
            {
                appendSeparatorRunBand(bands, runStart, y, false);
                runStart = -1;
            }
        }
        if (runStart >= 0)
            appendSeparatorRunBand(bands, runStart, yEnd, true);
        return bands;
    }

    private static boolean colorNear(RGB a, RGB b, int tolerance)
    {
        if (a == null || b == null)
            return false;
        return Math.abs(a.red - b.red) <= tolerance
                && Math.abs(a.green - b.green) <= tolerance
                && Math.abs(a.blue - b.blue) <= tolerance;
    }

    static List<PropertyArea> buildPropertyAreasFromBandsCanvas(int bodyTopCanvas,
            int bodyBottomCanvas, List<BackColorBand> bands)
    {
        List<PropertyArea> areas = new ArrayList<>();
        if (bodyBottomCanvas <= bodyTopCanvas)
            return areas;
        if (bands == null || bands.isEmpty())
        {
            areas.add(new PropertyArea(bodyTopCanvas, bodyBottomCanvas));
            return areas;
        }
        int prev = bodyTopCanvas;
        for (BackColorBand band : bands)
        {
            // Граница строки — нижний край разделителя; иначе каждая area короче
            // на толщину band и propertyIndex «отстаёт» от визуала накопительно.
            if (band.bottomCanvas > prev)
                areas.add(new PropertyArea(prev, band.bottomCanvas));
            prev = band.bottomCanvas;
        }
        if (bodyBottomCanvas > prev)
            areas.add(new PropertyArea(prev, bodyBottomCanvas));
        return normalizePropertyAreas(areas, bodyTopCanvas, bodyBottomCanvas);
    }

    /** Закрыть дыры между областями и слить слишком узкие полосы. */
    private static List<PropertyArea> normalizePropertyAreas(List<PropertyArea> areas,
            int bodyTopCanvas, int bodyBottomCanvas)
    {
        if (areas == null || areas.isEmpty())
            return java.util.List.of(new PropertyArea(bodyTopCanvas, bodyBottomCanvas));
        List<PropertyArea> sorted = new ArrayList<>(areas);
        sorted.sort(java.util.Comparator.comparingInt(a -> a.topCanvas));
        List<PropertyArea> out = new ArrayList<>();
        int prevBottom = bodyTopCanvas;
        for (PropertyArea area : sorted)
        {
            int top = Math.max(area.topCanvas, bodyTopCanvas);
            int bottom = Math.min(area.bottomCanvas, bodyBottomCanvas);
            if (bottom <= top)
                continue;
            if (top > prevBottom)
            {
                if (!out.isEmpty())
                {
                    PropertyArea last = out.remove(out.size() - 1);
                    out.add(new PropertyArea(last.topCanvas, top));
                }
                else
                    top = prevBottom;
            }
            if (!out.isEmpty() && bottom - top < MIN_AREA_SLIVER_CANVAS)
            {
                PropertyArea last = out.remove(out.size() - 1);
                out.add(new PropertyArea(last.topCanvas, bottom));
            }
            else
                out.add(new PropertyArea(top, bottom));
            prevBottom = bottom;
        }
        if (prevBottom < bodyBottomCanvas)
        {
            if (!out.isEmpty())
            {
                PropertyArea last = out.remove(out.size() - 1);
                out.add(new PropertyArea(last.topCanvas, bodyBottomCanvas));
            }
            else
                out.add(new PropertyArea(bodyTopCanvas, bodyBottomCanvas));
        }
        return out;
    }

    /**
     * Строит области свойств секции по РЕАЛЬНОЙ геометрии LWT-меток
     * ({@code LabelViewModel} → {@link #liveLightDisplayBounds}: {@code getBounds()} /
     * {@code getAbsoluteBounds()} нативного light-контрола), без анализа цвета пикселей.
     * Верх строки — Y её подписи (переведённый в canvas-координаты {@code content});
     * низ — Y следующей по порядку подписи в этой же секции (или низ секции для последней).
     * Так строки с произвольной высотой (многострочный «Комментарий»/«Представление» и т.п.)
     * получают точные границы без хрупкого сравнения RGB. Возвращает {@code null}, если
     * геометрию меток получить не удалось — тогда вызывающий код обязан использовать
     * пиксельный fallback.
     */
    private static List<PropertyArea> buildPropertyAreasForSectionGeometry(Composite content,
            LwtPropertySection section, Object scene, Object page, int groupIndex)
    {
        String title = section != null && section.titleHint != null ? section.titleHint : ""; //$NON-NLS-1$
        if (content == null || content.isDisposed() || scene == null || section == null)
            return geometryBail(title, "noContentOrScene", -1, -1); //$NON-NLS-1$

        // Геометрию отдельных меток можно резолвить не для всех строк (составные редакторы
        // вроде «Тип», чекбоксы, комбо) — если строка «выпала», её область молча сливается
        // с соседней. Поэтому доверяем геометрии только когда нашли РОВНО столько строк,
        // сколько модель считает видимыми в этой группе; иначе — откат на пиксельный fallback.
        // groupIndex приходит от вызывающего кода — там уже есть ПРАВИЛЬНЫЙ (совпадающий по
        // ссылке) список секций для resolveGroupIndex; пересчитывать его здесь заново со
        // свежим collectPropertySections(content) бессмысленно — новые LwtPropertySection
        // не совпадут по {@code ==} с уже имеющимся {@code section}, и fallback всегда даст -1.
        if (groupIndex < 0)
            return geometryBail(title, "noGroupIndex", -1, -1); //$NON-NLS-1$
        List<Object> visibleFields = visibleFieldDefinitionsInSection(page, groupIndex, section,
                scene, content);
        int expectedCount = visibleFields.size();
        if (expectedCount <= 0)
            return geometryBail(title, "noExpectedFields", -1, 0); //$NON-NLS-1$

        // Раньше брали ВСЕ метки сцены (labelEntriesFromScene) и фильтровали по попаданию Y
        // в диапазон секции — но у скрытых/непрорисованных строк других групп bounds могут
        // быть устаревшими и случайно попадать в диапазон ЭТОЙ секции («протечка» между
        // группами, см. H30 incomplete found>expected). Вместо этого ищем метку АДРЕСНО по
        // имени для каждого известного видимого поля этой группы — тем же способом
        // (findLwtViewByDisplayName), что уже проверен в fillRowContextMenu/GoToDefinition.
        List<Integer> tops = new ArrayList<>(expectedCount);
        int unresolved = 0;
        for (Object field : visibleFields)
        {
            String displayName = labeledDefinitionText(field);
            Object labelView = displayName != null && !displayName.isEmpty()
                    ? findLwtViewByDisplayName(scene, displayName) : null;
            Rectangle displayBounds = labelView != null ? liveLightDisplayBounds(labelView) : null;
            if ((displayBounds == null || displayBounds.height <= 0) && labelView != null)
                displayBounds = liveLightDisplayBoundsOnHost(labelView, content);
            if (displayBounds == null)
            {
                unresolved++;
                continue;
            }
            tops.add(content.toControl(displayBounds.x, displayBounds.y).y);
        }
        if (unresolved > 0 || tops.size() != expectedCount)
        {
            // #region agent log
            agentHitLog("H30", "ControlInterop.buildPropertyAreasForSectionGeometry", "unresolvedFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("title", title, "expected", expectedCount, //$NON-NLS-1$ //$NON-NLS-2$
                            "found", tops.size(), "unresolved", unresolved)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return null;
        }
        java.util.Collections.sort(tops);

        List<PropertyArea> areas = new ArrayList<>();
        for (int i = 0; i < tops.size(); i++)
        {
            int top = tops.get(i);
            int bottom = i + 1 < tops.size() ? tops.get(i + 1) : section.bodyBottomCanvas;
            if (bottom > top)
                areas.add(new PropertyArea(top, bottom));
        }
        if (areas.isEmpty())
            return null;

        // Две проверки на правдоподобность — на КАЖДУЮ область, не только последнюю:
        // 1) слишком маленькая (<8px) — резолвинг двух соседних меток «слипся» на почти
        //    одинаковый Y (напр. коллизия findLwtViewByDisplayName/liveLightDisplayBoundsOnHost),
        //    из-за чего следующая область незаконно поглощает лишний визуальный ряд;
        // 2) аномально большая (>80px и >3x медианы) — «видимых» полей (expectedCount) меньше
        //    реального числа строк в длинных виртуализированных секциях (напр. «Представление» —
        //    модель отдаёт только текущее окно рендера), и хвост растягивается на всю секцию.
        // В обоих случаях результату геометрии нельзя доверять целиком — откат на пиксельный скан.
        if (areas.size() >= 2)
        {
            List<Integer> sortedHeights = new ArrayList<>();
            for (PropertyArea a : areas)
                sortedHeights.add(a.height());
            java.util.Collections.sort(sortedHeights);
            int median = sortedHeights.get(sortedHeights.size() / 2);
            for (int i = 0; i < areas.size(); i++)
            {
                int h = areas.get(i).height();
                boolean tooSmall = h < 8;
                boolean tooLarge = median > 0 && h > 80 && h > median * 3;
                if (tooSmall || tooLarge)
                {
                    // #region agent log
                    agentHitLog("H30", "ControlInterop.buildPropertyAreasForSectionGeometry", //$NON-NLS-1$ //$NON-NLS-2$
                            "implausibleArea", java.util.Map.of("title", title, "index", i, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                    "height", h, "median", median, "expected", expectedCount)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    // #endregion
                    return null;
                }
            }
        }
        return areas;
    }

    private static List<PropertyArea> geometryBail(String title, String reason, int found, int expected)
    {
        // #region agent log
        agentHitLog("H30", "ControlInterop.buildPropertyAreasForSectionGeometry", reason, //$NON-NLS-1$ //$NON-NLS-2$
                java.util.Map.of("title", title, "found", found, "expected", expected)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        return null;
    }

    static List<PropertyArea> buildPropertyAreasForSectionCanvas(Composite content,
            LwtPropertySection section, Object scene, Object page)
    {
        return buildPropertyAreasForSectionCanvas(content, section, scene, page, -1);
    }

    static List<PropertyArea> buildPropertyAreasForSectionCanvas(Composite content,
            LwtPropertySection section, Object scene, Object page, int knownGroupIndex)
    {
        if (content == null || section == null || !section.expanded
                || section.bodyBottomCanvas <= section.bodyTopCanvas)
            return java.util.Collections.emptyList();

        List<PropertyArea> geometryAreas = buildPropertyAreasForSectionGeometry(content, section, scene,
                page, knownGroupIndex);
        if (geometryAreas != null)
        {
            // #region agent log
            agentHitLog("H10", "ControlInterop.buildPropertyAreasForSectionCanvas", "scanAreasGeometry", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("title", section.titleHint != null ? section.titleHint : "", //$NON-NLS-1$ //$NON-NLS-2$
                            "areas", geometryAreas.size(), "bodyTop", section.bodyTopCanvas, //$NON-NLS-1$ //$NON-NLS-2$
                            "bodyBottom", section.bodyBottomCanvas)); //$NON-NLS-1$
            // #endregion
            return geometryAreas;
        }

        // Fallback: пиксельный скан фона — только если геометрию меток получить не удалось.
        if (section.body != null && !section.body.isDisposed())
        {
            Object captureToken = content.getData(PS_CANVAS_CAPTURE_KEY);
            Object areasToken = section.body.getData(PS_SECTION_AREAS_CAPTURE_KEY);
            Object cached = section.body.getData(PS_SECTION_AREAS_KEY);
            if (captureToken != null && captureToken == areasToken
                    && cached instanceof List)
                return (List<PropertyArea>) cached;
        }
        ImageData capture = cachedCanvasCapture(content);
        if (capture == null)
            return java.util.Collections.emptyList();
        RGB backColorRgb = referenceBackColorRgb(capture, content);
        int midX = Math.max(1, capture.width / 2);
        int scanTop = section.bodyTopCanvas + SECTION_BODY_EDGE_EXCLUDE;
        if (scanTop >= section.bodyBottomCanvas)
            scanTop = section.bodyTopCanvas;
        List<BackColorBand> bands = mergeSeparatorRunsCanvas(scanTop,
                section.bodyBottomCanvas, capture, midX, backColorRgb, BACK_COLOR_LINE_THRESHOLD_PERCENT);
        if (bands.isEmpty())
        {
            bands = mergeSeparatorRunsCanvas(scanTop, section.bodyBottomCanvas,
                    capture, midX, backColorRgb, 70);
        }
        bands = clusterSeparatorBands(bands);
        if (bands.size() > 40)
            bands = clusterSeparatorBands(bands, SEP_BAND_CLUSTER_RADIUS_WIDE);
        bands = mergeBandsByMinBottomGap(bands, MIN_BAND_BOTTOM_GAP);
        bands = consolidateSeparatorBands(bands, 0);
        List<PropertyArea> areas = buildPropertyAreasFromBandsCanvas(section.bodyTopCanvas,
                section.bodyBottomCanvas, bands);
        if (section.body != null && !section.body.isDisposed())
        {
            section.body.setData(PS_SECTION_AREAS_KEY, areas);
            section.body.setData(PS_SECTION_AREAS_CAPTURE_KEY,
                    content.getData(PS_CANVAS_CAPTURE_KEY));
        }
        // #region agent log
        StringBuilder bandTops = new StringBuilder();
        for (int i = 0; i < bands.size() && i < 24; i++)
        {
            if (i > 0)
                bandTops.append(',');
            bandTops.append(bands.get(i).topCanvas);
        }
        StringBuilder areaRanges = new StringBuilder();
        for (int i = 0; i < areas.size() && i < 8; i++)
        {
            if (i > 0)
                areaRanges.append(',');
            PropertyArea a = areas.get(i);
            areaRanges.append(a.topCanvas).append('-').append(a.bottomCanvas);
        }
        agentHitLog("H10", "ControlInterop.buildPropertyAreasForSectionCanvas", "scanAreas", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                java.util.Map.of("title", section.titleHint != null ? section.titleHint : "", //$NON-NLS-1$ //$NON-NLS-2$
                        "bands", bands.size(), "areas", areas.size(), //$NON-NLS-1$ //$NON-NLS-2$
                        "bodyTop", section.bodyTopCanvas, "bodyBottom", section.bodyBottomCanvas, //$NON-NLS-1$ //$NON-NLS-2$
                        "scanTop", scanTop, "captureH", capture.height, "bandTops", bandTops.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "firstAreaH", areas.isEmpty() ? 0 : areas.get(0).bottomCanvas - areas.get(0).topCanvas, //$NON-NLS-1$
                        "areaRanges", areaRanges.toString())); //$NON-NLS-1$
        // #endregion
        return areas;
    }

    static PropertyArea findPropertyAreaAtCanvas(Object page, int canvasY)
    {
        PropertySheetUiContext.PaletteCanvasSpace space =
                PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
        if (space == null || canvasY < 0)
            return null;
        Composite content = space.content;
        java.util.List<LwtPropertySection> sections = collectPropertySections(content);
        refreshSectionCanvasBounds(sections, content);
        LwtPropertySection section = findActiveSectionAtCanvasY(sections, canvasY);
        if (section == null || !section.expanded || canvasY < section.bodyTopCanvas
                || canvasY >= section.bodyBottomCanvas)
            return null;
        Object scene = null;
        PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
        if (ctx != null)
            scene = ctx.scene;
        int groupIndex = resolveGroupIndex(page, section, sections);
        for (PropertyArea area : buildPropertyAreasForSectionCanvas(content, section, scene, page, groupIndex))
        {
            if (area.containsCanvasY(canvasY))
                return area;
        }
        return null;
    }

    /** Слушатели scroll/resize/expand для сброса canvas capture. */
    static void installPaletteCanvasListeners(Object page)
    {
        Composite content = PropertySheetUiContext.findPaletteContent(page);
        if (content == null || content.isDisposed())
            return;
        if (Boolean.TRUE.equals(content.getData("tormozit.ps.canvasHooksInstalled"))) //$NON-NLS-1$
            return;
        content.setData("tormozit.ps.canvasHooksInstalled", Boolean.TRUE); //$NON-NLS-1$
        Runnable invalidate = () -> invalidatePaletteGeometryCache(content);
        content.addListener(SWT.Resize, e -> invalidate.run());
        org.eclipse.swt.custom.ScrolledComposite scrolled =
                PropertySheetUiContext.findPaletteScrolledComposite(page);
        if (scrolled != null && !scrolled.isDisposed() && scrolled.getVerticalBar() != null)
            scrolled.getVerticalBar().addListener(SWT.Selection, e -> invalidate.run());
        installExpandInvalidators(content, invalidate);
    }

    private static void installExpandInvalidators(Composite composite, Runnable invalidate)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (composite.getClass().getName().contains("ExpandableComposite")) //$NON-NLS-1$
        {
            composite.addListener(SWT.Expand, e -> invalidate.run());
            composite.addListener(SWT.Collapse, e -> invalidate.run());
        }
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                installExpandInvalidators((Composite) child, invalidate);
        }
    }

    static java.util.List<java.util.Map.Entry<?, ?>> labelEntriesFromScene(Object scene)
    {
        if (scene == null)
            return java.util.Collections.emptyList();
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map))
            return java.util.Collections.emptyList();
        java.util.List<java.util.Map.Entry<?, ?>> out = new ArrayList<>();
        for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) mapObj).entrySet())
        {
            Object vm = entry.getKey();
            if (vm != null && vm.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                out.add(entry);
        }
        return out;
    }

    private static int[] normalizedBodyDisplayBounds(Composite sectionBody, Composite paletteRoot)
    {
        if (sectionBody == null || sectionBody.isDisposed())
            return new int[] { 0, 0 };
        if (paletteRoot != null && !paletteRoot.isDisposed())
        {
            for (LwtPropertySection section : collectPropertySections(paletteRoot))
            {
                if (section.body == sectionBody)
                    return new int[] { section.bodyTopDisplay, section.bodyBottomDisplay };
            }
        }
        int top = sectionBody.toDisplay(0, 0).y;
        return new int[] { top, top + sectionBody.getSize().y };
    }

    static boolean viewInSectionBody(Object view, Composite sectionBody, Composite paletteRoot)
    {
        if (view == null || sectionBody == null || sectionBody.isDisposed())
            return false;
        Composite leaf = leafFieldRowHostForView(view);
        if (leaf == null && paletteRoot != null)
            leaf = resolveRowHostForView(view, paletteRoot);
        if (leaf != null && isDescendantOf(leaf, sectionBody))
            return true;
        return labelCenterInSectionBounds(view, sectionBody, paletteRoot);
    }

    /** Строки scanner ctx, принадлежащие секции (модель + leaf-host в body). */
    private static java.util.List<PropertySheetPaletteRow> ctxRowsForSection(
            PropertySheetUiContext ctx, LwtPropertySection section, Composite content,
            Object page, int groupIndex, Object scene)
    {
        java.util.List<PropertySheetPaletteRow> out = new ArrayList<>();
        if (ctx == null || section == null || section.body == null || section.body.isDisposed())
            return out;
        java.util.Set<String> visibleNames = new java.util.LinkedHashSet<>();
        for (Object field : visibleFieldDefinitionsInSection(page, groupIndex, section, scene,
                content))
        {
            String label = labeledDefinitionText(field);
            if (label != null && !label.isEmpty())
                visibleNames.add(label);
        }
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
                continue;
            if (!visibleNames.isEmpty() && !visibleNames.contains(row.propertyName))
                continue;
            if (!paletteRowInSection(row, section, content))
                continue;
            if (paletteRowCanvasBand(row, content, section) == null)
                continue;
            out.add(row);
        }
        // #region agent log
        agentHitLog("H23", "ControlInterop.ctxRowsForSection", "filtered", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                java.util.Map.of("title", section.titleHint != null ? section.titleHint : "", //$NON-NLS-1$ //$NON-NLS-2$
                        "visibleNames", visibleNames.size(), "ctxRows", out.size())); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        return out;
    }

    static boolean labelCenterInSectionBounds(Object view, Composite sectionBody)
    {
        return labelCenterInSectionBounds(view, sectionBody, null);
    }

    static boolean labelCenterInSectionBounds(Object view, Composite sectionBody,
            Composite paletteRoot)
    {
        if (view == null || sectionBody == null || sectionBody.isDisposed())
            return false;
        Rectangle bounds = liveLightDisplayBounds(view);
        if (bounds == null || bounds.height <= 0)
            bounds = liveLightDisplayBoundsOnHost(view, sectionBody);
        if (bounds == null || bounds.height <= 0)
            return false;
        int centerY = bounds.y + Math.max(1, bounds.height / 2);
        int[] bodyBounds = normalizedBodyDisplayBounds(sectionBody, paletteRoot);
        return centerY >= bodyBounds[0] && centerY < bodyBounds[1];
    }

    private static java.util.List<java.util.Map.Entry<?, ?>> labelEntriesInSection(
            Composite sectionBody, java.util.List<java.util.Map.Entry<?, ?>> labelEntries,
            Composite paletteRoot)
    {
        java.util.List<java.util.Map.Entry<?, ?>> out = new ArrayList<>();
        if (sectionBody == null || sectionBody.isDisposed() || labelEntries == null)
            return out;
        for (java.util.Map.Entry<?, ?> entry : labelEntries)
        {
            if (viewInSectionBody(entry.getValue(), sectionBody, paletteRoot))
                out.add(entry);
        }
        return out;
    }

    private static int labelLightCenterDisplayY(Object view, Composite sectionBody,
            Composite paletteRoot)
    {
        Rectangle bounds = liveLightDisplayBounds(view);
        if (bounds != null && bounds.height > 0)
            return bounds.y + Math.max(1, bounds.height / 2);
        bounds = liveLightDisplayBoundsOnHost(view, sectionBody);
        if (bounds != null && bounds.height > 0)
            return bounds.y + Math.max(1, bounds.height / 2);
        int y = labelDisplayYForView(view, paletteRoot);
        return y != Integer.MAX_VALUE ? y : Integer.MAX_VALUE;
    }

    private static String labelDisplayTextFromViewModel(Object viewModel)
    {
        if (viewModel == null)
            return ""; //$NON-NLS-1$
        Object text = Global.invoke(viewModel, "getText"); //$NON-NLS-1$
        if (text instanceof String)
            return (String) text;
        return SmartTreeElementLabels.resolve(viewModel, null);
    }

    /**
     * Hit-test по scanner ctx: canvas Y в bounds leaf-row, без pixel-scan.
     */
    private static PropertyClickHit resolvePropertyAtClickFromCtxRows(Composite content,
            int canvasY, LwtPropertySection section, int groupIndex, Object page, Object scene)
    {
        PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
        if (ctx == null || ctx.rows.isEmpty() || content == null || section == null)
            return null;
        java.util.List<PropertySheetPaletteRow> sectionRows = ctxRowsForSection(ctx, section,
                content, page, groupIndex, scene);
        if (sectionRows.isEmpty())
            return null;
        sectionRows.sort(java.util.Comparator.comparingInt(r -> {
            int[] band = paletteRowCanvasBand(r, content, section);
            return band != null ? band[0] : Integer.MAX_VALUE;
        }));
        for (int i = 0; i < sectionRows.size(); i++)
        {
            PropertySheetPaletteRow paletteRow = sectionRows.get(i);
            int[] bandCanvas = paletteRowCanvasBand(paletteRow, content, section);
            if (bandCanvas == null)
                continue;
            int topCanvas = bandCanvas[0];
            int bottomCanvas = bandCanvas[1];
            if (canvasY < topCanvas || canvasY >= bottomCanvas)
                continue;
            Object fieldDef = resolveFieldByDisplayName(page, groupIndex, paletteRow.propertyName);
            String displayName = fieldDef != null ? labeledDefinitionText(fieldDef)
                    : paletteRow.propertyName;
            if (displayName == null || displayName.isEmpty())
                continue;
            Object lwtView = paletteRow.lwtView;
            if (lwtView == null)
                lwtView = findLwtViewByDisplayName(scene, displayName);
            int[] band = new int[] {
                    content.toDisplay(0, topCanvas).y,
                    content.toDisplay(0, bottomCanvas).y
            };
            Composite host = rowHostForPaletteRow(paletteRow);
            int hostH = host != null ? host.getSize().y : bottomCanvas - topCanvas;
            // #region agent log
            agentHitLog("H22", "ControlInterop.resolvePropertyAtClickFromCtxRows", "ctxHit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("prop", displayName, "propertyIndex", i, //$NON-NLS-1$ //$NON-NLS-2$
                            "sectionRows", sectionRows.size(), "hostH", hostH, //$NON-NLS-1$ //$NON-NLS-2$
                            "topCanvas", topCanvas, "bottomCanvas", bottomCanvas)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return new PropertyClickHit(displayName, lwtView, band[0], band[1], topCanvas,
                    bottomCanvas, i, "ctxRow"); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolve по canvas: полная прокручиваемая область → groupIndex + propertyIndex → модель.
     */
    static PropertyClickHit resolvePropertyAtClick(org.eclipse.swt.graphics.Point display,
            java.util.List<java.util.Map.Entry<?, ?>> labelEntries, Composite paletteRoot,
            Object page, Object scene)
    {
        if (display == null || page == null)
        {
            // #region agent log
            agentHitLog("H5", "ControlInterop.resolvePropertyAtClick", "earlyNull", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("displayNull", display == null, "pageNull", page == null)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return null;
        }
        PropertySheetUiContext.PaletteCanvasSpace space =
                PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
        if (space == null)
        {
            // #region agent log
            agentHitLog("H5", "ControlInterop.resolvePropertyAtClick", "noCanvas", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Collections.emptyMap());
            // #endregion
            return null;
        }
        Composite content = space.content;
        if (scene == null)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx != null)
                scene = ctx.scene;
        }
        Point canvas = space.displayToCanvas(display);
        int canvasY = canvas.y;
        java.util.List<LwtPropertySection> sections = collectPropertySections(content);
        refreshSectionCanvasBounds(sections, content);
        refreshSectionDisplayBounds(sections);
        normalizeSectionBodyBounds(sections);
        LwtPropertySection section = findActiveSectionAtCanvasY(sections, canvasY);
        if (section == null)
        {
            // #region agent log
            agentHitLog("H1", "ControlInterop.resolvePropertyAtClick", "noSection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("canvasY", canvasY, "sectionCount", sections.size())); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return null;
        }
        int groupIndex = resolveGroupIndex(page, section, sections);
        if (canvasY < section.headerBottomCanvas)
        {
            // #region agent log
            agentHitLog("H1h", "ControlInterop.resolvePropertyAtClick", "headerClick", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("canvasY", canvasY, "groupIndex", groupIndex, //$NON-NLS-1$ //$NON-NLS-2$
                            "title", section.titleHint != null ? section.titleHint : "")); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return null;
        }
        if (!section.expanded || canvasY < section.bodyTopCanvas
                || canvasY >= section.bodyBottomCanvas)
        {
            // #region agent log
            agentHitLog("H1c", "ControlInterop.resolvePropertyAtClick", "collapsedOrOutsideBody", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("canvasY", canvasY, "groupIndex", groupIndex, //$NON-NLS-1$ //$NON-NLS-2$
                            "expanded", section.expanded)); //$NON-NLS-1$
            // #endregion
            return null;
        }
        java.util.List<PropertyArea> areas = buildPropertyAreasForSectionCanvas(content, section,
                scene, page, groupIndex);
        PropertyArea area = null;
        int propertyIndex = -1;
        for (int i = 0; i < areas.size(); i++)
        {
            if (areas.get(i).containsCanvasY(canvasY))
            {
                area = areas.get(i);
                propertyIndex = i;
                break;
            }
        }
        if (area == null || propertyIndex < 0)
        {
            // #region agent log
            agentHitLog("H2", "ControlInterop.resolvePropertyAtClick", "noArea", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("canvasY", canvasY, "groupIndex", groupIndex, //$NON-NLS-1$ //$NON-NLS-2$
                            "areaCount", areas.size(), "bodyTop", section.bodyTopCanvas, //$NON-NLS-1$ //$NON-NLS-2$
                            "bodyBottom", section.bodyBottomCanvas)); //$NON-NLS-1$
            // #endregion
            return null;
        }
        Object fieldDef = null;
        Object rowLabelsObj = section.body != null ? section.body.getData(PS_SECTION_ROW_LABELS_KEY)
                : null;
        if (rowLabelsObj instanceof List)
        {
            @SuppressWarnings("unchecked")
            java.util.List<String> rowLabels = (java.util.List<String>) rowLabelsObj;
            if (propertyIndex >= 0 && propertyIndex < rowLabels.size())
                fieldDef = resolveFieldByDisplayName(page, groupIndex, rowLabels.get(propertyIndex));
        }
        java.util.List<Object> visibleFields = visibleFieldDefinitionsInSection(page, groupIndex,
                section, scene, content);
        if (fieldDef == null && propertyIndex >= 0 && propertyIndex < visibleFields.size())
            fieldDef = visibleFields.get(propertyIndex);
        if (fieldDef == null)
            fieldDef = resolveFieldByIndices(page, groupIndex, propertyIndex);
        String displayName;
        Object lwtView = null;
        if (fieldDef != null)
        {
            displayName = labeledDefinitionText(fieldDef);
            if (displayName == null || displayName.isEmpty())
                displayName = "scan#" + propertyIndex; //$NON-NLS-1$
            lwtView = findLwtViewByDisplayName(scene, displayName);
            if (lwtView == null)
                lwtView = findFieldComponentByDisplayName(scene, displayName);
        }
        else
        {
            // Модель «видимых полей» статична и не знает про поля, появляющиеся динамически
            // (напр. Длина/ДопустимаяДлина/НеограниченнаяДлина для «Основные», когда Тип=Строка).
            // Строка при этом РЕАЛЬНАЯ (area найдена пиксельным сканом) — просто её нет в списке
            // модели. Ищем подпись геометрически: чья live-позиция (тем же способом, что и
            // в buildPropertyAreasForSectionGeometry) попадает в диапазон этой area.
            String geomName = null;
            Object geomView = null;
            for (java.util.Map.Entry<?, ?> entry : labelEntriesFromScene(scene))
            {
                Object candidateView = entry.getValue();
                Rectangle b = liveLightDisplayBounds(candidateView);
                if (b == null || b.height <= 0)
                    b = liveLightDisplayBoundsOnHost(candidateView, content);
                if (b == null)
                    continue;
                int labelTopCanvas = content.toControl(b.x, b.y).y;
                if (labelTopCanvas >= area.topCanvas && labelTopCanvas < area.bottomCanvas)
                {
                    String text = labelTextOfViewModel(entry.getKey());
                    if (text != null && !text.isEmpty())
                    {
                        geomName = text;
                        geomView = candidateView;
                        break;
                    }
                }
            }
            if (geomName != null)
            {
                displayName = geomName;
                lwtView = geomView;
            }
            else
            {
                // #region agent log
                java.util.List<Object> modelSecs = modelSectionDefinitions(page);
                int fieldsCount = groupIndex >= 0 && groupIndex < modelSecs.size()
                        ? modelFieldDefinitions(modelSecs.get(groupIndex)).size() : -1;
                agentHitLog("H6", "ControlInterop.resolvePropertyAtClick", "modelMiss", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("groupIndex", groupIndex, "propertyIndex", propertyIndex, //$NON-NLS-1$ //$NON-NLS-2$
                                "fieldsCount", fieldsCount, "areasCount", areas.size(), //$NON-NLS-1$ //$NON-NLS-2$
                                "sectionTitle", section.titleHint != null ? section.titleHint : "")); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                displayName = "scan#" + propertyIndex; //$NON-NLS-1$
            }
        }
        int[] band = area.toDisplayBand(content);
        // #region agent log
        java.util.List<Object> modelSecs = modelSectionDefinitions(page);
        int fieldsCount = groupIndex >= 0 && groupIndex < modelSecs.size()
                ? modelFieldDefinitions(modelSecs.get(groupIndex)).size() : -1;
        if (areas.size() != fieldsCount)
        {
            StringBuilder fieldLabels = new StringBuilder();
            if (groupIndex >= 0 && groupIndex < modelSecs.size())
            {
                java.util.List<Object> fields = visibleFields.isEmpty()
                        ? modelFieldDefinitions(modelSecs.get(groupIndex)) : visibleFields;
                for (int i = 0; i < fields.size() && i < 12; i++)
                {
                    if (i > 0)
                        fieldLabels.append('|');
                    fieldLabels.append(labeledDefinitionText(fields.get(i)));
                }
            }
            agentHitLog("H18", "ControlInterop.resolvePropertyAtClick", "areasFieldsMismatch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("areasCount", areas.size(), "fieldsCount", fieldsCount, //$NON-NLS-1$ //$NON-NLS-2$
                            "visibleCount", visibleFields.size(), "propertyIndex", propertyIndex, //$NON-NLS-1$ //$NON-NLS-2$
                            "prop", displayName, "fieldLabels", fieldLabels.toString())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        agentHitLog("OK", "ControlInterop.resolvePropertyAtClick", "hit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                java.util.Map.of("prop", displayName, "groupIndex", groupIndex, //$NON-NLS-1$ //$NON-NLS-2$
                        "propertyIndex", propertyIndex, "areaTop", band[0], "areaBottom", band[1], //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "areaTopCanvas", area.topCanvas, "areaBottomCanvas", area.bottomCanvas, //$NON-NLS-1$ //$NON-NLS-2$
                        "canvasY", canvasY, "fieldsCount", fieldsCount, "areasCount", areas.size())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        PropertySheetDebug.feature("resolve via=scan g=" + groupIndex //$NON-NLS-1$
                + " p=" + propertyIndex + " " + PropertySheetDebug.quote(displayName)); //$NON-NLS-1$ //$NON-NLS-2$
        return new PropertyClickHit(displayName, lwtView, band[0], band[1], area.topCanvas,
                area.bottomCanvas, propertyIndex, "scan"); //$NON-NLS-1$
    }

    /** @deprecated display-Y; для совместимости — делегирует canvas через page. */
    static PropertyArea findPropertyAreaAtClick(Composite body, int displayY,
            java.util.List<java.util.Map.Entry<?, ?>> labelEntries, Composite paletteRoot,
            Object page)
    {
        if (page == null || displayY <= 0)
            return null;
        PropertySheetUiContext.PaletteCanvasSpace space =
                PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
        if (space == null)
            return null;
        Composite content = space.content;
        Point probe = new Point(content.toDisplay(0, 0).x + 8, displayY);
        int canvasY = content.toControl(probe).y;
        return findPropertyAreaAtCanvas(page, canvasY);
    }

    /** Section body с наименьшей area, содержащий displayY (секция под курсором). */
    static Composite findSectionBodyAtDisplayY(Composite paletteRoot, int displayY)
    {
        if (paletteRoot == null || paletteRoot.isDisposed() || displayY <= 0)
            return null;
        Composite best = null;
        int bestArea = Integer.MAX_VALUE;
        for (Composite body : collectLwtSectionBodies(paletteRoot))
        {
            if (body == null || body.isDisposed())
                continue;
            int top = body.toDisplay(0, 0).y;
            int bottom = top + body.getSize().y;
            if (displayY < top || displayY >= bottom)
                continue;
            int area = body.getSize().x * body.getSize().y;
            if (area < bestArea)
            {
                bestArea = area;
                best = body;
            }
        }
        return best;
    }

    /** Display-bounds LightLabel через band в координатах host. */
    static Rectangle liveLightDisplayBoundsOnHost(Object view, Control host)
    {
        if (view == null || host == null || host.isDisposed() || !(host instanceof Composite))
            return null;
        Object light = lightControlFromView(view);
        if (light == null)
            return null;
        Rectangle local = lightBoundsInHost(light, (Composite) host);
        if (local == null)
            return null;
        Point tl = host.toDisplay(local.x, local.y);
        return new Rectangle(tl.x, tl.y, local.width, local.height);
    }

    /** Section body, в display-bounds которого попадает LightLabel (не первый host по умолчанию). */
    static Composite findSectionBodyForView(Object view, Composite paletteRoot)
    {
        if (paletteRoot == null || paletteRoot.isDisposed())
            return null;
        Rectangle targetDisplay = liveLightDisplayBounds(view);
        int probeY = targetDisplay != null
                ? targetDisplay.y + Math.max(1, targetDisplay.height / 2)
                : labelDisplayYForView(view, paletteRoot);
        if (probeY == Integer.MAX_VALUE)
            return null;

        Object light = lightControlFromView(view);
        Composite bestInside = null;
        int bestArea = Integer.MAX_VALUE;
        for (Composite body : collectLwtSectionBodies(paletteRoot))
        {
            if (body == null || body.isDisposed())
                continue;
            int top = body.toDisplay(0, 0).y;
            int bottom = top + body.getSize().y;
            if (probeY < top || probeY >= bottom)
                continue;
            if (light != null && targetDisplay != null)
            {
                Rectangle localBand = lightBoundsInHost(light, body);
                if (localBand == null)
                    continue;
                Point bandDisplay = body.toDisplay(localBand.x, localBand.y);
                if (Math.abs(bandDisplay.y - targetDisplay.y) > 12)
                    continue;
            }
            int area = body.getSize().x * body.getSize().y;
            if (area < bestArea)
            {
                bestArea = area;
                bestInside = body;
            }
        }
        return bestInside;
    }

    /** Актуальные bounds LightLabel в display-координатах (SwtLightComposite → toDisplay). */
    static Rectangle liveLightDisplayBounds(Object view)
    {
        Object light = lightControlFromView(view);
        if (light == null)
            return null;
        Object bounds = Global.invoke(light, "getBounds"); //$NON-NLS-1$
        if (!(bounds instanceof Rectangle))
            return null;
        Rectangle lwtBounds = (Rectangle) bounds;

        Class<?> hostClass = swtLightCompositeClass();
        Object hostSwtLc = hostClass != null
                ? Global.invoke(hostClass, "getHostSwtLightComposite", light) : null; //$NON-NLS-1$
        if (hostSwtLc != null)
        {
            Object swtRectObj = Global.invoke(hostSwtLc, "translateRectangleFromControl", light, bounds); //$NON-NLS-1$
            Rectangle swtRect = swtRectObj instanceof Rectangle ? (Rectangle) swtRectObj : lwtBounds;
            Object hostComposite = Global.invoke(hostSwtLc, "getSwtComposite"); //$NON-NLS-1$
            if (hostComposite instanceof Control && !((Control) hostComposite).isDisposed())
            {
                Control swtHost = (Control) hostComposite;
                Point tl = swtHost.toDisplay(swtRect.x, swtRect.y);
                return new Rectangle(tl.x, tl.y, Math.max(1, swtRect.width), Math.max(1, swtRect.height));
            }
        }

        Object abs = Global.invoke(light, "getAbsoluteBounds"); //$NON-NLS-1$
        if (abs instanceof Rectangle)
        {
            Rectangle r = (Rectangle) abs;
            if (r.width > 0 && r.height > 0)
                return r;
        }
        Point origin = lightDisplayOrigin(light);
        if (origin != null)
            return new Rectangle(origin.x, origin.y, Math.max(1, lwtBounds.width), Math.max(1, lwtBounds.height));
        return null;
    }

    /** Полоса строки в display-координатах — для hit-test без привязки к host секции. */
    static Rectangle liveRowDisplayBand(Object view, Object page)
    {
        Rectangle light = liveLightDisplayBounds(view);
        if (light == null)
            return null;
        Composite root = page != null ? PropertySheetUiContext.findPaletteRoot(page) : null;
        int width = root != null && !root.isDisposed() ? root.getSize().x : Math.max(light.width, 400);
        Point rootOrigin = root != null && !root.isDisposed() ? root.toDisplay(0, 0) : new Point(light.x, 0);
        int rowH = Math.min(36, Math.max(22, light.height + 4));
        int rowY = light.y - 2;
        return new Rectangle(rootOrigin.x, rowY, width, rowH);
    }


    private static boolean acceptsOrigin(Composite host, Point origin)
    {
        if (host == null || host.isDisposed() || origin == null)
            return false;
        int lineH = 18;
        if (origin.y < -2)
            return false;
        return origin.y + lineH <= host.getSize().y + 2;
    }

    /** Leaf field rows и section bodies — потенциальные LWT paint hosts. */
    static List<Composite> collectLwtPaintHosts(Composite root)
    {
        List<Composite> out = new ArrayList<>();
        if (root == null || root.isDisposed())
            return out;
        collectLwtPaintHosts(root, out);
        out.sort(java.util.Comparator.comparingInt(PropertySheetControlInterop::fieldRowDisplayY));
        return out;
    }

    private static void collectLwtPaintHosts(Composite composite, List<Composite> out)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (PropertySheetUiContext.isFilterAreaControl(composite))
            return;

        if (isLwtFieldRowComposite(composite) || isLwtSectionBodyComposite(composite))
        {
            if (!out.contains(composite))
                out.add(composite);
            if (isLwtFieldRowComposite(composite))
                return;
        }

        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectLwtPaintHosts((Composite) child, out);
        }
    }

    /** Тело Section с несколькими LWT-строками (высота > leaf row). */
    static boolean isLwtSectionBodyComposite(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        Composite parent = composite.getParent();
        if (parent == null)
            return false;
        String pn = parent.getClass().getName();
        if (!pn.contains("Section") && !pn.contains("ExpandableComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        String cn = composite.getClass().getName();
        if (!cn.contains("DtLayoutComposite") && !cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        Point size = composite.getSize();
        return size.x >= 100 && size.y > 80;
    }

    /** Высота полосы выделения одной LWT-строки (~33px). */
    static int lwtRowBandHeight(Control host, String propertyName)
    {
        Rectangle band = lwtRowBand(host, propertyName);
        if (band != null && band.height > 0)
            return band.height;

        Object light = rowLight(host, propertyName);
        if (light != null)
        {
            Object cached = Global.getField(light, "cachedExtent"); //$NON-NLS-1$
            if (cached instanceof Point && ((Point) cached).y > 0)
                return ((Point) cached).y + 4;
        }
        // Для leaf row (y <= 80) — возвращаем host.getSize().y.
        // Для section body (y > 80) — фиксированные ~33px.
        if (host instanceof Composite && isLwtFieldRowComposite((Composite) host))
            return host.getSize().y;
        return 33;
    }

    /** Границы одной LWT-строки в координатах paint-host (по bounds LightLabel). */
    static Rectangle lwtRowBand(Control host, String propertyName)
    {
        if (!(host instanceof Composite) || host.isDisposed())
            return null;
        if (propertyName != null && !propertyName.isEmpty())
        {
            Object stored = host.getData(LWT_BAND_KEY + '.' + propertyName); //$NON-NLS-1$
            if (stored instanceof Rectangle)
                return (Rectangle) stored;
        }
        Object light = rowLight(host, propertyName);
        if (light == null)
            return null;
        return lightBoundsInHost(light, (Composite) host);
    }

    /**
     * Имя для копирования: только английское EMF-имя ({@code codeLength}), не подпись.
     */
    static String resolveCopyPropertyName(Object page, Object scene, Object lwtView, String displayName)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object field = null;
        boolean viewResolved = false;
        if (lwtView != null)
        {
            field = findFieldComponentForView(scene, renderer, lwtView);
            viewResolved = field != null;
        }
        if (field == null && displayName != null && !displayName.isEmpty())
            field = findFieldComponentByDisplayName(scene, displayName);
        EmfBinding binding = emfBindingFromField(field);
        if (binding == null || binding.featureName == null || binding.featureName.isEmpty())
        {
            String fromFieldDef = featureNameFromFieldDefinition(field);
            if (fromFieldDef != null && !fromFieldDef.isEmpty())
                binding = new EmfBinding(null, null, fromFieldDef);
        }
        if (!viewResolved)
            binding = mergeEmfBinding(binding, emfBindingFromPaletteDefinition(page, displayName));
        String english = binding != null ? binding.featureName : null;
        if (english == null || english.isEmpty())
            english = featureNameFromFieldComponent(field);
        if ((english == null || english.isEmpty()) && !viewResolved)
            english = featureNameFromPaletteDefinition(page, displayName);
        return english != null ? english : ""; //$NON-NLS-1$
    }

    /** Английское EMF-имя признака (внутреннее). */
    static String resolveModelPropertyName(Object page, Object scene, Object lwtView, String displayName)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (lwtView != null)
        {
            Object field = findFieldComponentForView(scene, renderer, lwtView);
            if (field == null)
                return null;
            String name = featureNameFromFieldComponent(field);
            if (name == null || name.isEmpty())
                name = featureNameFromFieldDefinition(field);
            return name;
        }
        Object field = null;
        if (displayName != null && !displayName.isEmpty())
            field = findFieldComponentByDisplayName(scene, displayName);
        String name = featureNameFromFieldComponent(field);
        if (name == null || name.isEmpty())
            name = featureNameFromFieldDefinition(field);
        if (name == null || name.isEmpty())
            name = featureNameFromPaletteDefinition(page, displayName);
        return name;
    }

    /** EMF-имя из palette definition, привязанного к FieldComponent (без глобального поиска по подписи). */
    private static String featureNameFromFieldDefinition(Object fieldComponent)
    {
        if (fieldComponent == null)
            return null;
        Object def = Global.invoke(fieldComponent, "getFieldDefinition"); //$NON-NLS-1$
        if (def == null)
            def = Global.invoke(fieldComponent, "getDefinition"); //$NON-NLS-1$
        if (def == null)
            return null;
        Object paths = Global.invoke(def, "getFeaturePaths"); //$NON-NLS-1$
        return featureNameFromFeaturePaths(paths);
    }

    private static final class EmfBinding
    {
        final EObject owner;
        final EStructuralFeature feature;
        final String featureName;

        EmfBinding(EObject owner, EStructuralFeature feature, String featureName)
        {
            this.owner = owner;
            this.feature = feature;
            this.featureName = featureName;
        }
    }

    private static EmfBinding emfBindingFromField(Object fieldComponent)
    {
        if (fieldComponent == null)
            return null;
        Object model = Global.invoke(fieldComponent, "getModel"); //$NON-NLS-1$
        return emfBindingFromModel(model);
    }

    private static EmfBinding emfBindingFromModel(Object model)
    {
        if (model == null)
            return null;
        String cn = model.getClass().getName();
        if (cn.contains("EmfValue")) //$NON-NLS-1$
        {
            Object ownerObj = Global.invoke(model, "getObject"); //$NON-NLS-1$
            Object featureObj = Global.invoke(model, "getProperty"); //$NON-NLS-1$
            EObject owner = ownerObj instanceof EObject ? (EObject) ownerObj : null;
            EStructuralFeature feature = featureObj instanceof EStructuralFeature
                    ? (EStructuralFeature) featureObj : null;
            String name = feature != null ? feature.getName() : null;
            return name != null ? new EmfBinding(owner, feature, name) : null;
        }
        if (cn.contains("EObjectFeature")) //$NON-NLS-1$
        {
            Object ownerObj = Global.invoke(model, "getObject"); //$NON-NLS-1$
            Object featureObj = Global.invoke(model, "getTargetFeature"); //$NON-NLS-1$
            if (featureObj == null)
                featureObj = Global.invoke(model, "getFeature"); //$NON-NLS-1$
            EObject owner = ownerObj instanceof EObject ? (EObject) ownerObj : null;
            EStructuralFeature feature = featureObj instanceof EStructuralFeature
                    ? (EStructuralFeature) featureObj : null;
            String name = feature != null ? feature.getName() : null;
            return name != null ? new EmfBinding(owner, feature, name) : null;
        }
        return null;
    }

    private static String resolveRussianPropertyName(String english, String symbolicPath,
            EStructuralFeature[] featurePath, EmfBinding binding, String displayName, Object page)
    {
        if ((english == null || english.isEmpty())
                && (displayName == null || displayName.isEmpty()))
            return null;
        if (english == null)
            english = ""; //$NON-NLS-1$
        EObject selection = selectionEObject(page);
        EObject owner = binding != null ? binding.owner : null;
        EStructuralFeature feature = binding != null ? binding.feature : null;
        if (owner == null && selection != null)
            owner = selection;
        if (feature == null && featurePath != null && featurePath.length > 0)
            feature = featurePath[featurePath.length - 1];
        if (feature == null && owner != null && english != null && !english.isEmpty())
            feature = owner.eClass().getEStructuralFeature(english);
        if (feature == null && owner != null && symbolicPath != null && !symbolicPath.isEmpty())
            feature = resolveFeatureBySymbolicPath(owner, symbolicPath);

        String ru = russianFromFeatureNames(owner, english, symbolicPath, featurePath, displayName);
        if (isRussianCopyCandidate(ru, english, displayName))
            return ru;

        ru = resolveEventNameRu(selection != null ? selection : owner, displayName, english);
        if (isRussianCopyCandidate(ru, english, displayName))
            return ru;

        if (!english.isEmpty())
        {
            ru = nameRuFromDuallyNamed(owner, english);
            if (isRussianCopyCandidate(ru, english, displayName))
                return ru;

            ru = nameRuFromDuallyNamedField(owner, feature);
            if (isRussianCopyCandidate(ru, english, displayName))
                return ru;

            ru = russianFromStandardAttribute(owner, english);
            if (isRussianCopyCandidate(ru, english, displayName))
                return ru;

            ru = russianFromMdTranslationMaps(english);
            if (isRussianCopyCandidate(ru, english, displayName))
                return ru;
        }

        if (owner != null)
        {
            for (String link : symbolicLinksToTry(english, symbolicPath, owner, feature, featurePath))
            {
                ru = tryLocalizeSymbolicLink(link, owner, feature, true);
                if (isRussianCopyCandidate(ru, english, displayName))
                    return ru;
                ru = tryLocalizeSymbolicLink(link, owner, feature, false);
                if (isRussianCopyCandidate(ru, english, displayName))
                    return ru;
            }
        }
        return null;
    }

    private static String russianFromStandardAttribute(EObject owner, String english)
    {
        if (owner == null || english == null || english.isEmpty())
            return null;
        for (EObject cur = owner; cur != null; cur = cur.eContainer())
        {
            try
            {
                if (!StandardAttributeUtil.hasStandardAttributes(cur))
                    continue;
                java.util.Optional<?> opt = StandardAttributeUtil.getStandardAttribute(cur, english);
                if (opt == null || !opt.isPresent())
                    continue;
                Object sa = opt.get();
                Object nameRu = Global.invoke(sa, "getNameRu"); //$NON-NLS-1$
                if (nameRu instanceof String && !((String) nameRu).isEmpty())
                    return (String) nameRu;
            }
            catch (Exception ignored)
            {
                // optional EDT API
            }
        }
        return null;
    }

    private static volatile Properties MD_FEATURE_NAMES_RU;
    private static volatile Properties FORM_FEATURE_NAMES_RU;

    private static Properties mdFeatureNamesRu()
    {
        if (MD_FEATURE_NAMES_RU == null)
            MD_FEATURE_NAMES_RU = loadFeatureNamesBundle(
                    "com._1c.g5.v8.dt.md.ui", "localization/FeatureNames_ru.properties"); //$NON-NLS-1$ //$NON-NLS-2$
        return MD_FEATURE_NAMES_RU;
    }

    private static Properties formFeatureNamesRu()
    {
        if (FORM_FEATURE_NAMES_RU == null)
            FORM_FEATURE_NAMES_RU = loadFeatureNamesBundle(
                    "com._1c.g5.v8.dt.form.ui", "localization/FeatureNames_ru.properties"); //$NON-NLS-1$ //$NON-NLS-2$
        return FORM_FEATURE_NAMES_RU;
    }

    private static Properties loadFeatureNamesBundle(String bundleId, String entryPath)
    {
        try
        {
            Bundle bundle = Platform.getBundle(bundleId);
            if (bundle == null)
                return null;
            URL url = bundle.getEntry(entryPath);
            if (url == null)
                return null;
            Properties props = new Properties();
            try (InputStreamReader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))
            {
                props.load(reader);
            }
            return props;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Русский идентификатор из штатных FeatureNames_ru EDT
     * ({@code Catalog|codeLength} → {@code ДлинаКода}, {@code StandardAttribute|name} → {@code Имя}).
     */
    private static String russianFromFeatureNames(EObject owner, String english, String symbolicPath,
            EStructuralFeature[] featurePath, String displayName)
    {
        if (english == null || english.isEmpty())
            return null;
        Properties md = mdFeatureNamesRu();
        Properties form = formFeatureNamesRu();
        if (md == null && form == null)
            return null;

        Set<String> typePrefixes = new LinkedHashSet<>();
        typePrefixes.add("StandardAttribute"); //$NON-NLS-1$
        for (EObject cur = owner; cur != null; cur = cur.eContainer())
        {
            if (cur.eClass() != null)
                typePrefixes.add(cur.eClass().getName());
        }
        if (featurePath != null)
        {
            for (EStructuralFeature part : featurePath)
            {
                if (part == null)
                    continue;
                if (part.getEContainingClass() != null)
                    typePrefixes.add(part.getEContainingClass().getName());
            }
        }

        String leaf = english;
        if (symbolicPath != null && symbolicPath.contains(".")) //$NON-NLS-1$
        {
            int dot = symbolicPath.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < symbolicPath.length())
                leaf = symbolicPath.substring(dot + 1);
        }

        for (String type : typePrefixes)
        {
            String ru = lookupFeatureNameRu(md, type, english, displayName);
            if (ru != null)
                return ru;
            if (!leaf.equals(english))
            {
                ru = lookupFeatureNameRu(md, type, leaf, displayName);
                if (ru != null)
                    return ru;
            }
            ru = lookupFeatureNameRu(form, type, english, displayName);
            if (ru != null)
                return ru;
            if (!leaf.equals(english))
            {
                ru = lookupFeatureNameRu(form, type, leaf, displayName);
                if (ru != null)
                    return ru;
            }
        }
        return null;
    }

    private static String lookupFeatureNameRu(Properties props, String typePrefix, String featureName,
            String displayName)
    {
        if (props == null || typePrefix == null || featureName == null || featureName.isEmpty())
            return null;
        String label = props.getProperty(typePrefix + '|' + featureName);
        if (label == null || label.isEmpty())
            return null;
        return labelToRussianIdentifier(label, displayName);
    }

    /** Подпись из FeatureNames_ru → идентификатор без пробелов ({@code Длина кода} → {@code ДлинаКода}). */
    private static String labelToRussianIdentifier(String label, String displayName)
    {
        if (label == null || label.isEmpty() || !containsCyrillic(label))
            return null;
        String compact = label.replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return compact.isEmpty() ? null : compact;
    }

    private static String russianFromMdTranslationMaps(String english)
    {
        if (english == null || english.isEmpty())
            return null;
        try
        {
            for (String key : mdTranslationKeys(english))
            {
                String fromMap = MdTypesTranslationIntoRussian.standardAttributesTranslation.get(key);
                if (fromMap != null && !fromMap.isEmpty() && !fromMap.equals(english))
                    return fromMap;
            }
            String translated = MdTypesTranslationIntoRussian.translateIntoRussian(english);
            if (translated != null && !translated.isEmpty() && !translated.equals(english))
                return translated;
            String pascal = toPascalCase(english);
            if (pascal != null && !pascal.equals(english))
            {
                translated = MdTypesTranslationIntoRussian.translateIntoRussian(pascal);
                if (translated != null && !translated.isEmpty() && !translated.equals(english))
                    return translated;
            }
        }
        catch (Exception ignored)
        {
            // optional EDT API
        }
        return null;
    }

    private static String[] mdTranslationKeys(String english)
    {
        String pascal = toPascalCase(english);
        if (pascal != null && !pascal.equals(english))
            return new String[] { english, pascal };
        return new String[] { english };
    }

    private static String toPascalCase(String english)
    {
        if (english == null || english.isEmpty())
            return null;
        if (english.indexOf('.') >= 0)
            return null;
        return Character.toUpperCase(english.charAt(0)) + english.substring(1);
    }

    private static boolean isRussianCopyCandidate(String candidate, String english, String displayName)
    {
        if (candidate == null || candidate.isEmpty())
            return false;
        if (!english.isEmpty() && candidate.equals(english))
            return false;
        if (displayName != null && candidate.equals(displayName))
        {
            // Русский идентификатор может совпадать с короткой подписью («Имя» для name).
            if (!(containsCyrillic(candidate) && candidate.indexOf(' ') < 0
                    && !english.isEmpty() && !candidate.equals(english)))
                return false;
        }
        if (displayName != null && candidate.indexOf(' ') >= 0)
        {
            String compactCandidate = candidate.replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
            String compactDisplay = displayName.replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (compactCandidate.equalsIgnoreCase(compactDisplay))
                return false;
        }
        if (isHybridLocalizedSymbolicLink(candidate, english))
            return false;
        if (candidate.indexOf('.') >= 0)
            return isFullyLocalizedSymbolicLink(candidate);
        return containsCyrillic(candidate);
    }

    /** Отклоняет «СтандартныйРеквизит.codeLength» — префикс русский, хвост английский. */
    private static boolean isHybridLocalizedSymbolicLink(String candidate, String english)
    {
        int dot = candidate.lastIndexOf('.');
        if (dot < 0)
            return false;
        String suffix = candidate.substring(dot + 1);
        if (!english.isEmpty() && suffix.equals(english))
            return true;
        return isAsciiIdentifier(suffix);
    }

    private static boolean isFullyLocalizedSymbolicLink(String candidate)
    {
        for (String part : candidate.split("\\.")) //$NON-NLS-1$
        {
            if (isAsciiIdentifier(part))
                return false;
        }
        return containsCyrillic(candidate);
    }

    private static boolean isAsciiIdentifier(String value)
    {
        if (value == null || value.isEmpty())
            return false;
        if (!Character.isJavaIdentifierStart(value.charAt(0)))
            return false;
        for (int i = 1; i < value.length(); i++)
        {
            if (!Character.isJavaIdentifierPart(value.charAt(i)))
                return false;
        }
        return true;
    }

    private static boolean containsCyrillic(String value)
    {
        if (value == null || value.isEmpty())
            return false;
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            if (ch >= '\u0400' && ch <= '\u04FF') //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private static String nameRuFromDuallyNamed(EObject owner, String english)
    {
        if (owner instanceof DuallyNamedElement)
        {
            DuallyNamedElement named = (DuallyNamedElement) owner;
            if (english.equals(named.getName()))
            {
                String ru = named.getNameRu();
                if (ru != null && !ru.isEmpty())
                    return ru;
            }
        }
        return null;
    }

    private static String nameRuFromDuallyNamedField(EObject owner, EStructuralFeature feature)
    {
        if (owner == null || feature == null)
            return null;
        try
        {
            Object value = owner.eGet(feature);
            if (value instanceof DuallyNamedElement)
            {
                String ru = ((DuallyNamedElement) value).getNameRu();
                if (ru != null && !ru.isEmpty())
                    return ru;
            }
            if (value instanceof com._1c.g5.v8.dt.mcore.Field)
            {
                String ru = ((com._1c.g5.v8.dt.mcore.Field) value).getNameRu();
                if (ru != null && !ru.isEmpty())
                    return ru;
            }
        }
        catch (Exception ignored)
        {
            // read-only / wrong type
        }
        return null;
    }

    private static String resolveEventNameRu(EObject root, String displayName, String englishName)
    {
        if (root == null)
            return null;
        for (java.util.Iterator<EObject> it = root.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (obj == null || !obj.getClass().getName().contains("EventHandler")) //$NON-NLS-1$
                continue;
            Object eventObj = Global.invoke(obj, "getEvent"); //$NON-NLS-1$
            if (!(eventObj instanceof Event))
                continue;
            Event event = (Event) eventObj;
            if (!matchesEventProperty(displayName, englishName, event, obj))
                continue;
            String ru = event.getNameRu();
            if (ru != null && !ru.isEmpty())
                return ru;
        }
        return null;
    }

    private static boolean matchesEventProperty(String displayName, String englishName, Event event, EObject handler)
    {
        if (englishName != null)
        {
            if (englishName.equals(event.getName()) || englishName.equals(event.getNameRu()))
                return true;
        }
        if (displayName != null)
        {
            if (displayName.equals(event.getNameRu()) || displayName.equals(event.getName()))
                return true;
            Object handlerName = Global.invoke(handler, "getName"); //$NON-NLS-1$
            if (handlerName instanceof String && displayName.equals(handlerName))
                return true;
        }
        return false;
    }

    private static String[] symbolicLinksToTry(String english, String symbolicPath, EObject owner,
            EStructuralFeature feature, EStructuralFeature[] featurePath)
    {
        Set<String> links = new LinkedHashSet<>();
        if (symbolicPath != null && !symbolicPath.isEmpty())
            links.add(symbolicPath);
        if (english != null && !english.isEmpty())
            links.add(english);
        if (english != null && !english.isEmpty())
            links.add("StandardAttribute." + english); //$NON-NLS-1$
        if (owner != null && english != null && !english.isEmpty())
        {
            String typeName = owner.eClass() != null ? owner.eClass().getName() : null;
            if (typeName != null && !typeName.isEmpty())
            {
                links.add(typeName + '.' + english);
                if (symbolicPath != null && !symbolicPath.isEmpty())
                    links.add(typeName + '.' + symbolicPath);
            }
        }
        if (feature != null && feature.getEContainingClass() != null && english != null && !english.isEmpty())
        {
            String container = feature.getEContainingClass().getName();
            if (container != null && !container.isEmpty())
                links.add(container + '.' + english);
        }
        if (featurePath != null && featurePath.length > 0)
        {
            StringBuilder byName = new StringBuilder();
            StringBuilder byClass = new StringBuilder();
            for (EStructuralFeature part : featurePath)
            {
                if (part == null)
                    continue;
                if (byName.length() > 0)
                    byName.append('.');
                byName.append(part.getName());
                links.add(byName.toString());
                if (part.getEContainingClass() != null)
                {
                    String container = part.getEContainingClass().getName();
                    if (container != null && !container.isEmpty())
                    {
                        links.add(container + '.' + part.getName());
                        if (byClass.length() > 0)
                            byClass.append('.');
                        byClass.append(container).append('.').append(part.getName());
                        links.add(byClass.toString());
                    }
                }
            }
            if (owner != null && owner.eClass() != null && byName.length() > 0)
            {
                String ownerType = owner.eClass().getName();
                if (ownerType != null)
                    links.add(ownerType + '.' + byName);
            }
            if (byName.length() > 0)
                links.add("Form." + byName); //$NON-NLS-1$
        }
        return links.toArray(new String[0]);
    }

    private static EStructuralFeature[] featuresFromPaletteDefinition(Object page, String displayLabel)
    {
        if (page == null || displayLabel == null || displayLabel.isEmpty())
            return null;
        Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
        if (paletteModel == null)
            return null;
        Object definition = Global.invoke(paletteModel, "getDefinition"); //$NON-NLS-1$
        return featuresFromDefinitionTree(definition, displayLabel);
    }

    private static String tryLocalizeSymbolicLink(String link, EObject owner, EStructuralFeature feature,
            boolean formFirst)
    {
        if (link == null || link.isEmpty())
            return null;
        try
        {
            if (formFirst)
            {
                String ru = localizeViaFormSymbolicLink(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
                ru = FormLocalizerUtil.extendedTranslateSymbolicLinkIntoRussian(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
                ru = MdLocalizerUtil.translateSymbolicLinkIntoRussian(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
                ru = localizeViaMdSymbolicLink(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
            }
            else
            {
                String ru = MdLocalizerUtil.translateSymbolicLinkIntoRussian(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
                ru = localizeViaMdSymbolicLink(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
                ru = FormLocalizerUtil.extendedTranslateSymbolicLinkIntoRussian(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
                ru = localizeViaFormSymbolicLink(link, owner, feature);
                if (ru != null && !ru.isEmpty() && !ru.equals(link))
                    return ru;
            }
        }
        catch (Exception ignored)
        {
            // EDT API optional per context
        }
        return null;
    }

    private static String localizeViaMdSymbolicLink(String link, EObject owner, EStructuralFeature feature)
    {
        MdSymbolicLinkLocalizer localizer = new MdSymbolicLinkLocalizer();
        if (!localizer.canLocalizeSymbolicLink(link, owner, feature, Locale.RUSSIAN))
            return null;
        return localizer.localizeSymbolicLink(link, owner, feature, Locale.RUSSIAN);
    }

    private static String localizeViaFormSymbolicLink(String link, EObject owner, EStructuralFeature feature)
    {
        FormSymbolicLinkLocalizer localizer = new FormSymbolicLinkLocalizer();
        if (!localizer.canLocalizeSymbolicLink(link, owner, feature, Locale.RUSSIAN))
            return null;
        return localizer.localizeSymbolicLink(link, owner, feature, Locale.RUSSIAN);
    }

    private static String featureSymbolicPathFromPaletteDefinition(Object page, String displayLabel)
    {
        if (page == null || displayLabel == null || displayLabel.isEmpty())
            return null;
        Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
        if (paletteModel == null)
            return null;
        Object definition = Global.invoke(paletteModel, "getDefinition"); //$NON-NLS-1$
        return featureSymbolicPathFromDefinitionTree(definition, displayLabel);
    }

    private static String featureSymbolicPathFromDefinitionTree(Object definition, String displayLabel)
    {
        if (definition == null || displayLabel == null)
            return null;
        String fromField = featureSymbolicPathFromFieldDefinitionNode(definition, displayLabel);
        if (fromField != null)
            return fromField;
        Object children = Global.invoke(definition, "getChildren"); //$NON-NLS-1$
        if (!(children instanceof Iterable))
            return null;
        for (Object child : (Iterable<?>) children)
        {
            String nested = featureSymbolicPathFromDefinitionTree(child, displayLabel);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static String featureSymbolicPathFromFieldDefinitionNode(Object definition, String displayLabel)
    {
        if (definition == null)
            return null;
        Object paths = Global.invoke(definition, "getFeaturePaths"); //$NON-NLS-1$
        if (paths == null)
            return null;
        String label = labeledDefinitionText(definition);
        if (!displayLabel.equals(label))
            return null;
        return symbolicPathFromFeaturePaths(paths);
    }

    private static String symbolicPathFromFeaturePaths(Object paths)
    {
        Object firstPath = null;
        if (paths instanceof Object[])
        {
            Object[] arr = (Object[]) paths;
            if (arr.length > 0)
                firstPath = arr[0];
        }
        else if (paths instanceof Iterable)
        {
            for (Object path : (Iterable<?>) paths)
            {
                firstPath = path;
                break;
            }
        }
        return symbolicPathFromFeaturePath(firstPath);
    }

    private static String symbolicPathFromFeaturePath(Object featurePath)
    {
        if (featurePath == null)
            return null;
        Object features = Global.invoke(featurePath, "getFeaturePath"); //$NON-NLS-1$
        if (!(features instanceof EStructuralFeature[]))
            return null;
        EStructuralFeature[] arr = (EStructuralFeature[]) features;
        if (arr.length == 0)
            return null;
        StringBuilder sb = new StringBuilder();
        for (EStructuralFeature feature : arr)
        {
            if (feature == null)
                continue;
            if (sb.length() > 0)
                sb.append('.');
            sb.append(feature.getName());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static EObject selectionEObject(Object page)
    {
        Object selection = page != null ? Global.invoke(page, "getCurrentSelection") : null; //$NON-NLS-1$
        if (selection instanceof StructuredSelection structured && !structured.isEmpty())
        {
            Object first = structured.getFirstElement();
            if (first instanceof EObject)
                return (EObject) first;
        }
        return paletteRootEObject(page);
    }

    private static EObject paletteRootEObject(Object page)
    {
        Object paletteModel = page != null ? Global.invoke(page, "getPaletteModel") : null; //$NON-NLS-1$
        if (paletteModel == null)
            return null;
        Object objects = Global.invoke(paletteModel, "getObjects"); //$NON-NLS-1$
        if (!(objects instanceof Iterable))
            return null;
        for (Object item : (Iterable<?>) objects)
        {
            if (item instanceof EObject)
                return (EObject) item;
        }
        return null;
    }

    private static EmfBinding mergeEmfBinding(EmfBinding primary, EmfBinding secondary)
    {
        if (primary == null)
            return secondary;
        if (secondary == null)
            return primary;
        EObject owner = primary.owner != null ? primary.owner : secondary.owner;
        EStructuralFeature feature = primary.feature != null ? primary.feature : secondary.feature;
        String featureName = primary.featureName != null && !primary.featureName.isEmpty()
                ? primary.featureName
                : secondary.featureName;
        return new EmfBinding(owner, feature, featureName);
    }

    private static EmfBinding emfBindingFromPaletteDefinition(Object page, String displayLabel)
    {
        if (page == null || displayLabel == null || displayLabel.isEmpty())
            return null;
        Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
        if (paletteModel == null)
            return null;
        Object definition = Global.invoke(paletteModel, "getDefinition"); //$NON-NLS-1$
        EStructuralFeature[] features = featuresFromDefinitionTree(definition, displayLabel);
        if (features == null || features.length == 0)
            return null;
        EStructuralFeature feature = features[features.length - 1];
        String english = feature != null ? feature.getName() : null;
        if (english == null || english.isEmpty())
            return null;
        EObject owner = selectionEObject(page);
        return new EmfBinding(owner, feature, english);
    }

    private static EStructuralFeature[] featuresFromDefinitionTree(Object definition, String displayLabel)
    {
        if (definition == null || displayLabel == null)
            return null;
        EStructuralFeature[] fromField = featuresFromFieldDefinitionNode(definition, displayLabel);
        if (fromField != null)
            return fromField;
        Object children = Global.invoke(definition, "getChildren"); //$NON-NLS-1$
        if (!(children instanceof Iterable))
            return null;
        for (Object child : (Iterable<?>) children)
        {
            EStructuralFeature[] nested = featuresFromDefinitionTree(child, displayLabel);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static EStructuralFeature[] featuresFromFieldDefinitionNode(Object definition, String displayLabel)
    {
        if (definition == null)
            return null;
        Object paths = Global.invoke(definition, "getFeaturePaths"); //$NON-NLS-1$
        if (paths == null)
            return null;
        String label = labeledDefinitionText(definition);
        if (!displayLabel.equals(label))
            return null;
        return featuresFromFeaturePaths(paths);
    }

    private static EStructuralFeature[] featuresFromFeaturePaths(Object paths)
    {
        Object firstPath = null;
        if (paths instanceof Object[])
        {
            Object[] arr = (Object[]) paths;
            if (arr.length > 0)
                firstPath = arr[0];
        }
        else if (paths instanceof Iterable)
        {
            for (Object path : (Iterable<?>) paths)
            {
                firstPath = path;
                break;
            }
        }
        return featuresFromFeaturePath(firstPath);
    }

    private static EStructuralFeature[] featuresFromFeaturePath(Object featurePath)
    {
        if (featurePath == null)
            return null;
        Object features = Global.invoke(featurePath, "getFeaturePath"); //$NON-NLS-1$
        if (features instanceof EStructuralFeature[])
            return (EStructuralFeature[]) features;
        return null;
    }

    private static EStructuralFeature resolveFeatureBySymbolicPath(EObject owner, String symbolicPath)
    {
        if (owner == null || symbolicPath == null || symbolicPath.isEmpty())
            return null;
        String[] parts = symbolicPath.split("\\."); //$NON-NLS-1$
        EObject current = owner;
        EStructuralFeature last = null;
        for (String part : parts)
        {
            if (part == null || part.isEmpty() || current == null)
                return null;
            EStructuralFeature feature = current.eClass().getEStructuralFeature(part);
            if (feature == null)
                return null;
            last = feature;
            if (feature.isMany())
                return last;
            Object value = current.eGet(feature);
            if (value instanceof EObject)
                current = (EObject) value;
            else
                return last;
        }
        return last;
    }

    private static String escJson(String value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static Object findFieldComponentForView(Object scene, Object renderer, Object lwtView)
    {
        if (scene == null || lwtView == null)
            return null;
        Object matchedVm = viewModelForLwtView(renderer, lwtView);
        Object root = Global.invoke(scene, "getComponent"); //$NON-NLS-1$
        Object found = findFieldComponentInTree(root, renderer, lwtView, matchedVm);
        if (found != null)
            return found;
        if (root != null)
        {
            Object defComp = Global.invoke(root, "getDefinitionComponent"); //$NON-NLS-1$
            if (defComp != null)
                found = findFieldComponentInTree(defComp, renderer, lwtView, matchedVm);
        }
        return found;
    }

    private static String featureNameFromPaletteDefinition(Object page, String displayLabel)
    {
        if (page == null || displayLabel == null || displayLabel.isEmpty())
            return null;
        Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
        if (paletteModel == null)
            return null;
        Object definition = Global.invoke(paletteModel, "getDefinition"); //$NON-NLS-1$
        return featureNameFromDefinitionTree(definition, displayLabel);
    }

    private static String featureNameFromDefinitionTree(Object definition, String displayLabel)
    {
        if (definition == null || displayLabel == null)
            return null;
        String fromField = featureNameFromFieldDefinitionNode(definition, displayLabel);
        if (fromField != null)
            return fromField;
        Object children = Global.invoke(definition, "getChildren"); //$NON-NLS-1$
        if (!(children instanceof Iterable))
            return null;
        for (Object child : (Iterable<?>) children)
        {
            String nested = featureNameFromDefinitionTree(child, displayLabel);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static String featureNameFromFieldDefinitionNode(Object definition, String displayLabel)
    {
        if (definition == null)
            return null;
        Object paths = Global.invoke(definition, "getFeaturePaths"); //$NON-NLS-1$
        if (paths == null)
            return null;
        String label = labeledDefinitionText(definition);
        if (!displayLabel.equals(label))
            return null;
        return featureNameFromFeaturePaths(paths);
    }

    private static String labeledDefinitionText(Object definition)
    {
        Object label = Global.invoke(definition, "getLabel"); //$NON-NLS-1$
        if (label instanceof String)
            return (String) label;
        return ""; //$NON-NLS-1$
    }

    private static String featureNameFromFeaturePaths(Object paths)
    {
        if (paths instanceof Object[])
        {
            Object[] arr = (Object[]) paths;
            if (arr.length == 0)
                return null;
            return featureNameFromFeaturePath(arr[0]);
        }
        if (paths instanceof Iterable)
        {
            for (Object path : (Iterable<?>) paths)
            {
                String name = featureNameFromFeaturePath(path);
                if (name != null)
                    return name;
            }
        }
        return null;
    }

    private static String featureNameFromFeaturePath(Object featurePath)
    {
        if (featurePath == null)
            return null;
        Object features = Global.invoke(featurePath, "getFeaturePath"); //$NON-NLS-1$
        if (features instanceof org.eclipse.emf.ecore.EStructuralFeature[])
        {
            org.eclipse.emf.ecore.EStructuralFeature[] arr =
                    (org.eclipse.emf.ecore.EStructuralFeature[]) features;
            if (arr.length > 0)
                return arr[arr.length - 1].getName();
        }
        return null;
    }

    private static Object findFieldComponentByDisplayName(Object scene, String displayName)
    {
        if (scene == null || displayName == null || displayName.isEmpty())
            return null;
        Object root = Global.invoke(scene, "getComponent"); //$NON-NLS-1$
        Object found = findFieldComponentByDisplayNameInTree(root, displayName);
        if (found != null)
            return found;
        if (root != null)
        {
            Object defComp = Global.invoke(root, "getDefinitionComponent"); //$NON-NLS-1$
            if (defComp != null)
                found = findFieldComponentByDisplayNameInTree(defComp, displayName);
        }
        return found;
    }

    private static Object viewModelForLwtView(Object renderer, Object lwtView)
    {
        if (renderer == null || lwtView == null)
            return null;
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map))
            return null;
        for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) mapObj).entrySet())
        {
            if (entry.getValue() == lwtView)
                return entry.getKey();
        }
        return null;
    }

    private static Object findFieldComponentInTree(Object component, Object renderer, Object lwtView,
            Object matchedVm)
    {
        if (component == null)
            return null;
        if (component.getClass().getName().contains("FieldComponent")) //$NON-NLS-1$
        {
            if (fieldComponentOwnsView(component, renderer, lwtView, matchedVm))
                return component;
        }
        java.util.Iterator<?> it = componentChildren(component);
        if (it == null)
            return null;
        while (it.hasNext())
        {
            Object child = it.next();
            if (child != null)
            {
                Object found = findFieldComponentInTree(child, renderer, lwtView, matchedVm);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Object findFieldComponentByDisplayNameInTree(Object component, String displayName)
    {
        if (component == null)
            return null;
        if (component.getClass().getName().contains("FieldComponent")) //$NON-NLS-1$
        {
            Object label = Global.getField(component, "label"); //$NON-NLS-1$
            if (label != null)
            {
                Object labelVm = Global.invoke(label, "getLabel"); //$NON-NLS-1$
                if (labelVm == null)
                    labelVm = Global.getField(label, "viewModel"); //$NON-NLS-1$
                String text = labelTextOfViewModel(labelVm);
                if (displayName.equals(text))
                    return component;
            }
        }
        java.util.Iterator<?> it = componentChildren(component);
        if (it == null)
            return null;
        while (it.hasNext())
        {
            Object child = it.next();
            if (child != null)
            {
                Object found = findFieldComponentByDisplayNameInTree(child, displayName);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean fieldComponentOwnsView(Object field, Object renderer, Object lwtView,
            Object matchedVm)
    {
        Object label = Global.getField(field, "label"); //$NON-NLS-1$
        if (label != null)
        {
            Object labelVm = Global.invoke(label, "getLabel"); //$NON-NLS-1$
            if (labelVm == null)
                labelVm = Global.getField(label, "viewModel"); //$NON-NLS-1$
            if (matchedVm != null && matchedVm == labelVm)
                return true;
            if (viewForViewModel(renderer, labelVm) == lwtView)
                return true;
        }
        Object viewModels = Global.invoke(field, "getViewModels"); //$NON-NLS-1$
        if (viewModels instanceof Iterable)
        {
            for (Object vm : (Iterable<?>) viewModels)
            {
                if (matchedVm != null && matchedVm == vm)
                    return true;
                if (viewForViewModel(renderer, vm) == lwtView)
                    return true;
            }
        }
        return false;
    }

    static Object viewForViewModel(Object renderer, Object viewModel)
    {
        if (renderer == null || viewModel == null)
            return null;
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map))
            return null;
        return ((java.util.Map<?, ?>) mapObj).get(viewModel);
    }

    private static java.util.Iterator<?> componentChildren(Object component)
    {
        if (component == null)
            return null;
        Object children = Global.invoke(component, "getComponents"); //$NON-NLS-1$
        if (children instanceof Iterable)
            return ((Iterable<?>) children).iterator();
        return null;
    }

    private static String labelTextOfViewModel(Object viewModel)
    {
        if (viewModel == null)
            return ""; //$NON-NLS-1$
        Object text = Global.invoke(viewModel, "getText"); //$NON-NLS-1$
        if (text instanceof String)
            return (String) text;
        return SmartTreeElementLabels.resolve(viewModel, null);
    }

    private static String featureNameFromFieldComponent(Object fieldComponent)
    {
        if (fieldComponent == null)
            return null;
        Object model = Global.invoke(fieldComponent, "getModel"); //$NON-NLS-1$
        return featureNameFromModel(model);
    }

    private static String featureNameFromModel(Object model)
    {
        if (model == null)
            return null;
        String cn = model.getClass().getName();
        if (cn.contains("EmfValue")) //$NON-NLS-1$
        {
            Object feature = Global.invoke(model, "getProperty"); //$NON-NLS-1$
            if (feature instanceof org.eclipse.emf.ecore.EStructuralFeature)
                return ((org.eclipse.emf.ecore.EStructuralFeature) feature).getName();
        }
        if (cn.contains("EObjectFeature")) //$NON-NLS-1$
        {
            Object feature = Global.invoke(model, "getTargetFeature"); //$NON-NLS-1$
            if (feature instanceof org.eclipse.emf.ecore.EStructuralFeature)
                return ((org.eclipse.emf.ecore.EStructuralFeature) feature).getName();
        }
        return null;
    }

    /** Leaf DtLayoutComposite (~33px) для LWT view; null если не найден. */
    static Composite leafFieldRowHostForView(Object view)
    {
        return fieldRowFromLightHost(view);
    }

    private static Composite fieldRowFromLightHost(Object view)
    {
        Object light = lightControlFromView(view);
        if (light == null)
            return null;
        Class<?> hostClass = swtLightCompositeClass();
        if (hostClass != null)
        {
            Object hostSwtLc = Global.invoke(hostClass, "getHostSwtLightComposite", light); //$NON-NLS-1$
            if (hostSwtLc != null)
            {
                Object hostComposite = Global.invoke(hostSwtLc, "getSwtComposite"); //$NON-NLS-1$
                if (hostComposite instanceof Composite && !((Composite) hostComposite).isDisposed())
                    return nearestLayoutCompositeHost((Composite) hostComposite);
            }
        }
        Object swt = Global.invoke(light, "getSwtComposite"); //$NON-NLS-1$
        if (swt instanceof Composite && !((Composite) swt).isDisposed())
            return nearestLayoutCompositeHost((Composite) swt);
        return null;
    }

    private static Composite nearestLayoutCompositeHost(Composite start)
    {
        Composite bestLeaf = null;
        for (Composite c = start; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (isLwtFieldRowComposite(c))
                return c;
            String cn = c.getClass().getName();
            if (cn.contains("DtLayoutComposite") || cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Point s = c.getSize();
                if (s.x >= 100 && s.y >= 20 && s.y <= 80)
                    bestLeaf = c;
            }
        }
        return bestLeaf != null ? bestLeaf : start;
    }

    private static Composite fieldRowFromSectionBody(Object view, Composite paletteRoot)
    {
        if (paletteRoot == null || paletteRoot.isDisposed())
            return null;
        int labelY = lwtLabelDisplayY(view, paletteRoot);
        List<Composite> bodies = collectLwtSectionBodies(paletteRoot);
        if (bodies.isEmpty())
            return null;
        if (labelY == Integer.MAX_VALUE)
            return null;

        Composite bestInside = null;
        int bestInsideArea = Integer.MAX_VALUE;
        Composite bestNear = null;
        int bestNearDist = Integer.MAX_VALUE;
        for (Composite body : bodies)
        {
            int top = body.toDisplay(0, 0).y;
            int bottom = top + body.getSize().y;
            if (labelY >= top && labelY < bottom)
            {
                int area = body.getSize().x * body.getSize().y;
                if (area < bestInsideArea)
                {
                    bestInsideArea = area;
                    bestInside = body;
                }
            }
            int dist = labelY < top ? top - labelY : Math.max(0, labelY - bottom + 1);
            if (dist < bestNearDist)
            {
                bestNearDist = dist;
                bestNear = body;
            }
        }
        if (bestInside != null)
            return bestInside;
        if (bestNear != null && bestNearDist <= 48)
            return bestNear;
        return null;
    }

    private static List<Composite> collectLwtSectionBodies(Composite root)
    {
        List<Composite> out = new ArrayList<>();
        collectLwtSectionBodies(root, out);
        out.sort(java.util.Comparator.comparingInt(PropertySheetControlInterop::fieldRowDisplayY));
        return out;
    }

    private static void collectLwtSectionBodies(Composite composite, List<Composite> out)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (PropertySheetUiContext.isFilterAreaControl(composite))
            return;

        Composite parent = composite.getParent();
        if (parent != null)
        {
            String pn = parent.getClass().getName();
            if (pn.contains("Section") || pn.contains("ExpandableComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String cn = composite.getClass().getName();
                if ((cn.contains("DtLayoutComposite") || cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
                        && composite.getSize().x >= 100 && composite.getSize().y >= 20)
                {
                    out.add(composite);
                    return;
                }
            }
        }

        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectLwtSectionBodies((Composite) child, out);
        }
    }

    static int fieldRowDisplayY(Composite fieldRow)
    {
        if (fieldRow == null || fieldRow.isDisposed())
            return Integer.MAX_VALUE;
        return fieldRow.toDisplay(0, 0).y;
    }


    static int lwtLabelDisplayY(Object view, Composite paletteRoot)
    {
        Rectangle lightBounds = liveLightDisplayBounds(view);
        if (lightBounds != null)
            return lightBounds.y + Math.max(1, lightBounds.height / 2);

        Object light = lightControlFromView(view);
        if (light == null)
            return Integer.MAX_VALUE;

        Point display = lightDisplayOrigin(light);
        if (display != null)
            return display.y;

        Composite host = fieldRowFromLightHost(view);
        Object bounds = Global.invoke(light, "getBounds"); //$NON-NLS-1$
        if (bounds instanceof org.eclipse.swt.graphics.Rectangle && host != null && !host.isDisposed())
        {
            org.eclipse.swt.graphics.Rectangle r = (org.eclipse.swt.graphics.Rectangle) bounds;
            return host.toDisplay(0, 0).y + r.y;
        }

        if (bounds instanceof org.eclipse.swt.graphics.Rectangle && paletteRoot != null && !paletteRoot.isDisposed())
        {
            int localY = ((org.eclipse.swt.graphics.Rectangle) bounds).y;
            for (Composite body : collectLwtSectionBodies(paletteRoot))
            {
                if (localY >= 0 && localY < body.getSize().y)
                    return body.toDisplay(0, localY).y;
            }
        }

        if (host != null && !host.isDisposed())
            return host.toDisplay(0, 0).y;

        return Integer.MAX_VALUE;
    }

    /** LWT-хост для paint overlay (leaf row, section body или помеченный scan-ом). */
    static boolean isLwtPaintHost(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        if (control.getData(LWT_VIEW_KEY) != null || control.getData(LWT_LIGHT_KEY) != null)
            return true;
        if (control.getData("tormozit.ps.lwtMatchRows") != null) //$NON-NLS-1$
            return true;
        if (control instanceof Composite)
        {
            Composite composite = (Composite) control;
            return isLwtFieldRowComposite(composite) || isLwtSectionBodyComposite(composite);
        }
        return false;
    }


    static final String LWT_ORIGIN_KEY = "tormozit.ps.lwtOrigin"; //$NON-NLS-1$
    static final String LWT_VIEW_KEY = "tormozit.ps.lwtView"; //$NON-NLS-1$
    static final String LWT_LIGHT_KEY = "tormozit.ps.lwtLight"; //$NON-NLS-1$
    static final String LWT_BAND_KEY = "tormozit.ps.lwtBand"; //$NON-NLS-1$

    /** Сохранить origin текста LightLabel для paint overlay (координаты field row). */
    static void storeLwtHighlightOrigin(Control nameControl, Object view, Composite fieldRow, String propertyName)
    {
        if (nameControl == null)
            return;
        Point origin = lwtLabelPaintOrigin(view, fieldRow);
        if (propertyName != null && !propertyName.isEmpty())
            nameControl.setData(LWT_ORIGIN_KEY + '.' + propertyName, origin); //$NON-NLS-1$
        nameControl.setData(LWT_ORIGIN_KEY, origin);
        if (view != null)
        {
            if (propertyName != null && !propertyName.isEmpty())
                nameControl.setData(LWT_VIEW_KEY + '.' + propertyName, view); //$NON-NLS-1$
            nameControl.setData(LWT_VIEW_KEY, view);
        }
        Object light = lightControlFromView(view);
        if (light != null)
        {
            if (propertyName != null && !propertyName.isEmpty())
                nameControl.setData(LWT_LIGHT_KEY + '.' + propertyName, light); //$NON-NLS-1$
            nameControl.setData(LWT_LIGHT_KEY, light);
        }
        PropertySheetDebug.resolveVerbose("lwtOrigin " //$NON-NLS-1$
                + PropertySheetDebug.quote(lightControlText(lightControlFromView(view)))
                + " → (" + origin.x + "," + origin.y + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        PropertySheetDebug.scanVerbose("lwtOrigin " + PropertySheetDebug.quote(lightControlText(lightControlFromView(view))) //$NON-NLS-1$
                + " → (" + origin.x + "," + origin.y + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void storeLwtRowGeometry(Control host, Object view, String propertyName, Point origin,
            Rectangle band)
    {
        if (host == null || host.isDisposed())
            return;
        if (propertyName != null && !propertyName.isEmpty())
        {
            if (origin != null)
                host.setData(LWT_ORIGIN_KEY + '.' + propertyName, origin); //$NON-NLS-1$
            if (band != null)
                host.setData(LWT_BAND_KEY + '.' + propertyName, band); //$NON-NLS-1$
            if (view != null)
                host.setData(LWT_VIEW_KEY + '.' + propertyName, view); //$NON-NLS-1$
        }
        Object light = lightControlFromView(view);
        if (light != null)
        {
            if (propertyName != null && !propertyName.isEmpty())
                host.setData(LWT_LIGHT_KEY + '.' + propertyName, light); //$NON-NLS-1$
        }
        PropertySheetDebug.scanVerbose("lwtGeometry " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                + " origin=(" + (origin != null ? origin.x : -1) + "," //$NON-NLS-1$ //$NON-NLS-2$
                + (origin != null ? origin.y : -1) + ") band=" + band //$NON-NLS-1$
                + " host=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$
    }

    static Point lwtHighlightOrigin(Control nameControl, String propertyName)
    {
        if (nameControl == null)
            return new Point(0, 0);
        if (propertyName != null && !propertyName.isEmpty())
        {
            Object perView = nameControl.getData(LWT_VIEW_KEY + '.' + propertyName); //$NON-NLS-1$
            if (perView != null && nameControl instanceof Composite)
                return lwtLabelPaintOrigin(perView, (Composite) nameControl);
            Object perLight = nameControl.getData(LWT_LIGHT_KEY + '.' + propertyName); //$NON-NLS-1$
            if (perLight != null && nameControl instanceof Composite)
            {
                Point exact = lwtLabelDrawOrigin(perLight, (Composite) nameControl);
                if (exact != null)
                    return exact;
            }
            Object perRow = nameControl.getData(LWT_ORIGIN_KEY + '.' + propertyName); //$NON-NLS-1$
            if (perRow instanceof Point)
                return (Point) perRow;
            // Для именованной строки не используем общий origin host-а (он от другой строки).
            if (nameControl instanceof Composite)
                return fallbackLabelOrigin((Composite) nameControl);
            return new Point(0, 0);
        }
        Object view = rowView(nameControl, null);
        if (view != null && nameControl instanceof Composite)
            return lwtLabelPaintOrigin(view, (Composite) nameControl);
        Object data = nameControl.getData(LWT_ORIGIN_KEY);
        if (data instanceof Point)
            return (Point) data;
        if (nameControl instanceof Composite)
            return lwtLabelPaintOrigin(view, (Composite) nameControl);
        return new Point(0, 0);
    }

    static Object rowView(Control host, String propertyName)
    {
        if (host == null || propertyName == null)
            return host != null ? host.getData(LWT_VIEW_KEY) : null;
        Object perRow = host.getData(LWT_VIEW_KEY + '.' + propertyName); //$NON-NLS-1$
        if (perRow != null)
            return perRow;
        return host.getData(LWT_VIEW_KEY);
    }

    static Object rowLight(Control host, String propertyName)
    {
        if (host == null || propertyName == null)
            return null;
        Object perRow = host.getData(LWT_LIGHT_KEY + '.' + propertyName); //$NON-NLS-1$
        if (perRow != null)
            return perRow;
        return host.getData(LWT_LIGHT_KEY);
    }


    private static Point lwtLabelPaintOrigin(Object view, Composite fieldRow)
    {
        Object light = lightControlFromView(view);
        if (light != null && fieldRow != null && !fieldRow.isDisposed())
        {
            Point exact = lwtLabelDrawOrigin(light, fieldRow);
            if (exact != null)
                return exact;
        }
        return fallbackLabelOrigin(fieldRow);
    }

    /**
     * Точка начала текста как в {@code LightLabel.paint}: {@code x = bounds.x + marginLeft},
     * {@code y = bounds.y + (bounds.height - textHeight) / 2}, с переводом LWT→SWT.
     */
    static Point lwtLabelDrawOrigin(Object light, Composite fieldRow)
    {
        Object bounds = Global.invoke(light, "getBounds"); //$NON-NLS-1$
        if (!(bounds instanceof org.eclipse.swt.graphics.Rectangle))
            return null;

        Class<?> hostClass = swtLightCompositeClass();
        Object hostSwtLc = hostClass != null
                ? Global.invoke(hostClass, "getHostSwtLightComposite", light) : null; //$NON-NLS-1$

        org.eclipse.swt.graphics.Rectangle lwtBounds = (org.eclipse.swt.graphics.Rectangle) bounds;
        int marginLeft = lightMarginLeft(light);
        int textHeight = lwtTextHeight(light, fieldRow);

        if (hostSwtLc == null)
        {
            Point display = lightDisplayOrigin(light);
            if (display != null && fieldRow != null && !fieldRow.isDisposed())
            {
                Point local = fieldRow.toControl(display);
                int x = local.x + marginLeft;
                int y = local.y + Math.max(0, (lwtBounds.height - textHeight) / 2);
                return new Point(Math.max(0, x), Math.max(0, y));
            }
            int x = Math.max(0, lwtBounds.x + marginLeft);
            int y = Math.max(0, lwtBounds.y + Math.max(0, (lwtBounds.height - textHeight) / 2));
            return new Point(x, y);
        }

        Object swtRectObj = Global.invoke(hostSwtLc, "translateRectangleFromControl", light, bounds); //$NON-NLS-1$
        org.eclipse.swt.graphics.Rectangle swtRect = swtRectObj instanceof org.eclipse.swt.graphics.Rectangle
                ? (org.eclipse.swt.graphics.Rectangle) swtRectObj : lwtBounds;

        int xInHost = swtRect.x + marginLeft;
        int yInHost = swtRect.y + Math.max(0, (swtRect.height - textHeight) / 2);

        Object hostComposite = hostSwtLc != null
                ? Global.invoke(hostSwtLc, "getSwtComposite") : null; //$NON-NLS-1$
        if (hostComposite instanceof Control && !((Control) hostComposite).isDisposed())
        {
            Control host = (Control) hostComposite;
            Point display = host.toDisplay(xInHost, yInHost);
            Point local = fieldRow.toControl(display);
            return new Point(Math.max(0, local.x), Math.max(0, local.y));
        }
        return new Point(Math.max(0, xInHost), Math.max(0, yInHost));
    }

    private static Rectangle lightBoundsInHost(Object light, Composite host)
    {
        Object bounds = Global.invoke(light, "getBounds"); //$NON-NLS-1$
        if (!(bounds instanceof Rectangle))
            return null;
        Rectangle lwtBounds = (Rectangle) bounds;

        Class<?> hostClass = swtLightCompositeClass();
        Object hostSwtLc = hostClass != null
                ? Global.invoke(hostClass, "getHostSwtLightComposite", light) : null; //$NON-NLS-1$
        Rectangle swtRect = lwtBounds;
        if (hostSwtLc != null)
        {
            Object swtRectObj = Global.invoke(hostSwtLc, "translateRectangleFromControl", light, bounds); //$NON-NLS-1$
            if (swtRectObj instanceof Rectangle)
                swtRect = (Rectangle) swtRectObj;
            Object hostComposite = Global.invoke(hostSwtLc, "getSwtComposite"); //$NON-NLS-1$
            if (hostComposite instanceof Control && !((Control) hostComposite).isDisposed())
            {
                Point display = ((Control) hostComposite).toDisplay(swtRect.x, swtRect.y);
                Point local = host.toControl(display);
                return new Rectangle(Math.max(0, local.x), Math.max(0, local.y),
                        Math.max(1, swtRect.width), Math.max(1, swtRect.height));
            }
        }

        Point display = lightDisplayOrigin(light);
        if (display != null)
        {
            Point local = host.toControl(display);
            return new Rectangle(Math.max(0, local.x), Math.max(0, local.y),
                    Math.max(1, lwtBounds.width), Math.max(1, lwtBounds.height));
        }
        return new Rectangle(Math.max(0, swtRect.x), Math.max(0, swtRect.y),
                Math.max(1, swtRect.width), Math.max(1, swtRect.height));
    }

    /** Актуальная полоса LightLabel в координатах host (translateRectangleFromControl). */
    static Rectangle liveRowBandInHost(Object view, Control host)
    {
        if (view == null || !(host instanceof Composite) || host.isDisposed())
            return null;
        Object light = lightControlFromView(view);
        if (light == null)
            return null;
        return lightBoundsInHost(light, (Composite) host);
    }

    /** Полоса выделения по актуальным bounds LightLabel в координатах host. */
    static Rectangle selectionBandFromLightDisplay(Object view, Control host)
    {
        if (view == null || host == null || host.isDisposed())
            return null;
        Rectangle live = liveRowBandInHost(view, host);
        if (live == null || live.height <= 0)
            return null;
        int hostH = host.getSize().y;
        int rowY = Math.max(0, live.y - 1);
        int rowH = Math.min(hostH - rowY, live.height + 2);
        if (rowH <= 0)
            return null;
        if (hostH > 80 && rowH >= hostH - 2 && rowY <= 1)
            return null;
        int bandW = Math.max(80, host.getSize().x / 2);
        return new Rectangle(0, rowY, bandW, rowH);
    }

    /** Display-полоса hit-test по live bounds (совпадает с selectionBand, без раздувания min 22px). */
    static int[] rowHitBandDisplayY(Object view, Control host)
    {
        Rectangle band = selectionBandFromLightDisplay(view, host);
        if (band == null || host == null || host.isDisposed())
            return null;
        Point topLeft = host.toDisplay(band.x, band.y);
        return new int[] { topLeft.y, topLeft.y + band.height };
    }

    /** Display Y строки: leaf LWT host (точная высота строки), иначе canvas-scan fallback. */
    static int[] displayBandForFieldView(Object lwtView, int fallbackTop, int fallbackBottom)
    {
        Composite leaf = leafFieldRowHostForView(lwtView);
        if (leaf != null && !leaf.isDisposed())
        {
            int top = leaf.toDisplay(0, 0).y;
            int bottom = top + leaf.getSize().y;
            if (bottom > top)
                return new int[] { top, bottom };
        }
        if (fallbackTop >= 0 && fallbackBottom > fallbackTop)
            return new int[] { fallbackTop, fallbackBottom };
        return null;
    }

    static Object lightControlForView(Object view)
    {
        return lightControlFromView(view);
    }

    /** Актуальные bounds LightLabel в координатах host (без кэша). */
    static Rectangle liveLwtRowBand(Control host, Object view, String propertyName)
    {
        if (!(host instanceof Composite) || host.isDisposed())
            return null;
        Object light = view != null ? lightControlFromView(view) : rowLight(host, propertyName);
        if (light == null)
            return lwtRowBand(host, propertyName);
        Rectangle live = lightBoundsInHost(light, (Composite) host);
        if (live != null)
            return live;
        return lwtRowBand(host, propertyName);
    }

    static void refreshLwtRowGeometry(Control host, Object view, String propertyName)
    {
        if (host == null || host.isDisposed() || propertyName == null || propertyName.isEmpty())
            return;
        Point origin = lwtHighlightOrigin(host, propertyName);
        Rectangle band = liveLwtRowBand(host, view, propertyName);
        storeLwtRowGeometry(host, view, propertyName, origin, band);
    }

    private static Composite sectionBodyContaining(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Composite start = control instanceof Composite ? (Composite) control : control.getParent();
        for (Composite c = start; c != null && !c.isDisposed(); c = c.getParent())
        {
            Composite parent = c.getParent();
            if (parent == null)
                continue;
            String pn = parent.getClass().getName();
            if (!pn.contains("Section") && !pn.contains("ExpandableComposite")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            String cn = c.getClass().getName();
            if ((cn.contains("DtLayoutComposite") || cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
                    && c.getSize().x >= 100 && c.getSize().y >= 20)
                return c;
        }
        return null;
    }

    private static Point fallbackLabelOrigin(Composite fieldRow)
    {
        return new Point(fieldRowLabelOriginX(fieldRow), fieldRowLabelOriginY(fieldRow));
    }


    static org.eclipse.swt.graphics.Font lwtFont(Object light)
    {
        if (light == null)
            return null;
        Object font = Global.getField(light, "font"); //$NON-NLS-1$
        if (font instanceof org.eclipse.swt.graphics.Font)
        {
            org.eclipse.swt.graphics.Font f = (org.eclipse.swt.graphics.Font) font;
            if (!f.isDisposed())
                return f;
        }
        return null;
    }

    static int lwtTextHeight(Object light, Composite fieldRow)
    {
        Object cached = Global.getField(light, "cachedExtent"); //$NON-NLS-1$
        if (cached instanceof Point && ((Point) cached).y > 0)
            return ((Point) cached).y;

        if (fieldRow == null || fieldRow.isDisposed())
            return 13;
        GC gc = new GC(fieldRow);
        try
        {
            org.eclipse.swt.graphics.Font font = lwtFont(light);
            if (font != null)
                gc.setFont(font);
            String sample = lightControlText(light);
            if (sample == null || sample.isEmpty())
                sample = "Ay"; //$NON-NLS-1$
            return gc.textExtent(sample).y;
        }
        finally
        {
            gc.dispose();
        }
    }

    private static int lightMarginLeft(Object light)
    {
        Object margins = Global.getField(light, "margins"); //$NON-NLS-1$
        if (margins == null)
            margins = Global.invoke(light, "getMargins"); //$NON-NLS-1$
        if (margins != null)
        {
            Object left = Global.getField(margins, "left"); //$NON-NLS-1$
            if (left instanceof Number)
                return ((Number) left).intValue();
            Object leftM = Global.invoke(margins, "getLeft"); //$NON-NLS-1$
            if (leftM instanceof Number)
                return ((Number) leftM).intValue();
        }
        return 0;
    }

    private static Class<?> swtLightCompositeClass()
    {
        try
        {
            return Class.forName(SWT_LIGHT_COMPOSITE);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    private static int fieldRowLabelOriginY(Composite fieldRow)
    {
        if (fieldRow == null || fieldRow.isDisposed())
            return 2;
        GC gc = new GC(fieldRow);
        try
        {
            org.eclipse.swt.graphics.Font font = fieldRow.getFont();
            if (font != null)
                gc.setFont(font);
            int textHeight = gc.textExtent("Ay").y; //$NON-NLS-1$
            return Math.max(2, (fieldRow.getSize().y - textHeight) / 2);
        }
        finally
        {
            gc.dispose();
        }
    }

    private static int fieldRowLabelOriginX(Composite fieldRow)
    {
        Control anchor = labelColumnAnchor(fieldRow);
        if (anchor != null && !anchor.isDisposed() && fieldRow != null && !fieldRow.isDisposed())
        {
            Point local = fieldRow.toControl(anchor.toDisplay(0, 0));
            return Math.max(0, local.x + 2);
        }
        return 16;
    }


    private static Point lightDisplayOrigin(Object light)
    {
        Object pt = Global.invoke(light, "toDisplay", Integer.valueOf(0), Integer.valueOf(0)); //$NON-NLS-1$
        if (pt instanceof Point)
            return (Point) pt;
        Object abs = Global.invoke(light, "getAbsoluteBounds"); //$NON-NLS-1$
        if (abs instanceof org.eclipse.swt.graphics.Rectangle)
        {
            org.eclipse.swt.graphics.Rectangle r = (org.eclipse.swt.graphics.Rectangle) abs;
            return new Point(r.x, r.y);
        }
        Object loc = Global.invoke(light, "getLocationInWindow"); //$NON-NLS-1$
        if (loc instanceof Point)
            return (Point) loc;
        return null;
    }

    private static Control labelColumnAnchor(Composite fieldRow)
    {
        if (fieldRow == null || fieldRow.isDisposed())
            return null;
        Control best = null;
        int bestX = Integer.MAX_VALUE;
        int rowW = fieldRow.getSize().x;
        for (Control child : fieldRow.getChildren())
        {
            if (child == null || child.isDisposed() || isTwistieOrDecor(child))
                continue;
            Point size = child.getSize();
            if (size.x < 20 || size.y < 10 || size.y > 48)
                continue;
            Point local = child.getLocation();
            if (local.x >= rowW / 2)
                continue;
            if (local.x < bestX)
            {
                bestX = local.x;
                best = child;
            }
        }
        return best;
    }

    static boolean isTwistieOrDecor(Control control)
    {
        if (control == null)
            return false;
        String cn = control.getClass().getSimpleName();
        if (cn.contains("Twistie") || cn.contains("Expand") || cn.contains("Chevron")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        Point size = control.getSize();
        return size.x > 0 && size.x <= 14 && size.y > 0 && size.y <= 14;
    }


    private static Control leftmostNameCandidate(Composite composite)
    {
        Control best = null;
        int bestX = Integer.MAX_VALUE;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed() || isTwistieOrDecor(child))
                continue;
            Point size = child.getSize();
            if (size.y <= 0 || size.y > 48 || size.x < 20)
                continue;
            int x = child.toDisplay(0, 0).x;
            if (x < bestX)
            {
                bestX = x;
                best = child;
            }
        }
        return best;
    }

    private static Control findChildByText(Composite composite, String text)
    {
        if (composite == null || composite.isDisposed())
            return null;
        Control[] best = new Control[] { null };
        int[] bestArea = new int[] { Integer.MAX_VALUE };
        findChildByTextDeep(composite, text, best, bestArea);
        return best[0];
    }

    private static void findChildByTextDeep(Composite composite, String text,
            Control[] best, int[] bestArea)
    {
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            String childText = controlText(child);
            if (text.equals(childText))
            {
                Point size = child.getSize();
                int area = Math.max(1, size.x) * Math.max(1, size.y);
                if (area < bestArea[0] && size.y <= 48)
                {
                    bestArea[0] = area;
                    best[0] = child;
                }
            }
            if (child instanceof Composite)
                findChildByTextDeep((Composite) child, text, best, bestArea);
        }
    }

    static String controlText(Control control)
    {
        if (control == null || control.isDisposed())
            return ""; //$NON-NLS-1$
        if (control instanceof Label)
            return nullToEmpty(((Label) control).getText());
        if (control instanceof org.eclipse.swt.widgets.Text)
            return nullToEmpty(((org.eclipse.swt.widgets.Text) control).getText());
        Object text = Global.invoke(control, "getText"); //$NON-NLS-1$
        if (text instanceof String && !((String) text).isEmpty())
            return (String) text;
        if (control instanceof Composite)
        {
            for (Control child : ((Composite) control).getChildren())
            {
                String childText = controlText(child);
                if (!childText.isEmpty())
                    return childText;
            }
        }
        return ""; //$NON-NLS-1$
    }

    static String lightControlText(Object lightControl)
    {
        if (lightControl == null)
            return ""; //$NON-NLS-1$
        String direct = rawLightText(lightControl);
        if (!direct.isEmpty())
            return direct;

        // Обёртки вроде LightEditorBar/LightCombo (см. LightTextEditorBar) не имеют своего
        // getText() — реальный текст хранит их getContent() (LightText), как и в
        // PropertyNameIdentifierHook.readLightTextValue для поля «Имя».
        Object content = Global.invoke(lightControl, "getContent"); //$NON-NLS-1$
        if (content != null && content != lightControl)
        {
            String contentText = rawLightText(content);
            if (!contentText.isEmpty())
                return contentText;
        }
        return ""; //$NON-NLS-1$
    }

    private static String rawLightText(Object obj)
    {
        Object text = Global.invoke(obj, "getText"); //$NON-NLS-1$
        return text instanceof String ? (String) text : ""; //$NON-NLS-1$
    }

    /** Текст значения поля по AEF-view: {@link #lightControlFromView} + {@link #lightControlText}. */
    static String lwtViewText(Object view)
    {
        return lightControlText(lightControlFromView(view));
    }


    private static String nullToEmpty(String s)
    {
        return s != null ? s : ""; //$NON-NLS-1$
    }

}
