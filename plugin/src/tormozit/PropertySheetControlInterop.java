package tormozit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

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
        Composite resolved = resolveLwtFieldRowHost(view, paletteRoot);
        if (paletteRoot == null || paletteRoot.isDisposed())
            return resolved != null && !resolved.isDisposed() ? resolved : null;

        List<Composite> hosts = collectLwtPaintHosts(paletteRoot);
        if (resolved != null && !resolved.isDisposed() && !hosts.contains(resolved))
            hosts.add(resolved);
        if (hosts.isEmpty())
            return null;

        int labelY = lwtLabelDisplayY(view, paletteRoot);
        Composite best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Composite host : hosts)
        {
            Point origin = lwtLabelPaintOrigin(view, host);
            if (!acceptsOrigin(host, origin))
                continue;
            int top = host.toDisplay(0, 0).y;
            int bottom = top + host.getSize().y;
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
        return best;
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
        return size.x >= 100 && size.y > 80 && size.y <= 250;
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
        for (Composite c = start; c != null && !c.isDisposed(); c = c.getParent())
        {
            String cn = c.getClass().getName();
            if (cn.contains("DtLayoutComposite") || cn.contains("LayoutComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Point s = c.getSize();
                if (s.x >= 100 && s.y >= 20)
                    return c;
            }
        }
        return start;
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
