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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
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

    private static Object lightControlFromView(Object view)
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
        Object field = null;
        boolean viewResolved = false;
        if (lwtView != null)
        {
            field = findFieldComponentForView(scene, renderer, lwtView);
            viewResolved = field != null;
        }
        if (field == null && displayName != null && !displayName.isEmpty())
            field = findFieldComponentByDisplayName(scene, displayName);
        String name = featureNameFromFieldComponent(field);
        if (name == null || name.isEmpty())
            name = featureNameFromFieldDefinition(field);
        if ((name == null || name.isEmpty()) && !viewResolved)
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
        Object text = Global.invoke(lightControl, "getText"); //$NON-NLS-1$
        return text instanceof String ? (String) text : ""; //$NON-NLS-1$
    }


    private static String nullToEmpty(String s)
    {
        return s != null ? s : ""; //$NON-NLS-1$
    }

}
