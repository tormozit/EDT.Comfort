package tormozit;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.StructuredSelection;
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

/** SWT-обёртка для AEF/LWT-контролов ({@code LightLabel}, {@code SwtLightControl}). */
final class PropertySheetControlInterop
{

    private static final String SWT_LIGHT_COMPOSITE = "com._1c.g5.lwt.interop.SwtLightComposite"; //$NON-NLS-1$

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

    /**
     * Английское EMF-имя признака ({@code codeLength}), не подпись палитры.
     */
    static String resolveCopyPropertyName(Object page, Object scene, Object lwtView, String displayName)
    {
        CopyNameContext ctx = resolveCopyNameContext(page, scene, lwtView, displayName);
        return ctx.english != null ? ctx.english : ""; //$NON-NLS-1$
    }

    /**
     * Имя для копирования во встроенный язык: только штатные источники
     * ({@code getNameRu}, локализаторы символьных ссылок), без подписей палитры.
     */
    static String resolveRussianCopyPropertyName(Object page, Object scene, Object lwtView,
            String displayName)
    {
        CopyNameContext ctx = resolveCopyNameContext(page, scene, lwtView, displayName);
        String ru = resolveRussianPropertyName(ctx.english, ctx.symbolicPath, ctx.featurePath,
                ctx.binding, displayName, page);
        if (ru != null && !ru.isEmpty())
            return ru;
        if (ctx.english != null && !ctx.english.isEmpty())
            return ctx.english;
        return displayName != null ? displayName : ""; //$NON-NLS-1$
    }

    static final class CopyNameContext
    {
        final EmfBinding binding;
        final String english;
        final String symbolicPath;
        final EStructuralFeature[] featurePath;

        CopyNameContext(EmfBinding binding, String english, String symbolicPath,
                EStructuralFeature[] featurePath)
        {
            this.binding = binding;
            this.english = english;
            this.symbolicPath = symbolicPath;
            this.featurePath = featurePath;
        }

        EObject owner()
        {
            return binding != null ? binding.owner : null;
        }

        EStructuralFeature feature()
        {
            return binding != null ? binding.feature : null;
        }
    }

    static CopyNameContext resolveCopyNameContext(Object page, Object scene, Object lwtView,
            String displayName)
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

        EStructuralFeature[] featurePath = featurePathFromField(field);
        String symbolicPath = symbolicPathFromField(field);
        if (!viewResolved)
        {
            if (featurePath == null)
                featurePath = featuresFromPaletteDefinition(page, displayName);
            if (symbolicPath == null)
                symbolicPath = featureSymbolicPathFromPaletteDefinition(page, displayName);
        }
        return new CopyNameContext(binding, english, symbolicPath, featurePath);
    }

    private static EStructuralFeature[] featurePathFromField(Object fieldComponent)
    {
        Object def = fieldDefinitionFromField(fieldComponent);
        if (def == null)
            return null;
        Object paths = Global.invoke(def, "getFeaturePaths"); //$NON-NLS-1$
        return featuresFromFeaturePaths(paths);
    }

    private static String symbolicPathFromField(Object fieldComponent)
    {
        Object def = fieldDefinitionFromField(fieldComponent);
        if (def == null)
            return null;
        Object paths = Global.invoke(def, "getFeaturePaths"); //$NON-NLS-1$
        return symbolicPathFromFeaturePaths(paths);
    }

    private static Object fieldDefinitionFromField(Object fieldComponent)
    {
        if (fieldComponent == null)
            return null;
        Object def = Global.invoke(fieldComponent, "getFieldDefinition"); //$NON-NLS-1$
        if (def == null)
            def = Global.invoke(fieldComponent, "getDefinition"); //$NON-NLS-1$
        return def;
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
        Object def = fieldDefinitionFromField(fieldComponent);
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

        String ru = resolveEventNameRu(selection != null ? selection : owner, displayName, english);
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

    static EObject selectionEObjectForCopy(Object page)
    {
        return selectionEObject(page);
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
        if (cn.contains("EventHandlerModel")) //$NON-NLS-1$
        {
            Event event = eventFromModel(model);
            if (event != null)
                return event.getName();
        }
        return null;
    }

    /** Платформенное событие из модели поля палитры ({@code EventHandlerModel}). */
    static Event eventFromFieldModel(Object page, Object scene, Object lwtView)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object field = null;
        if (lwtView != null)
            field = findFieldComponentForView(scene, renderer, lwtView);
        if (field == null)
            return null;
        Object model = Global.invoke(field, "getModel"); //$NON-NLS-1$
        return eventFromModel(model);
    }

    private static Event eventFromModel(Object model)
    {
        if (model == null || !model.getClass().getName().contains("EventHandlerModel")) //$NON-NLS-1$
            return null;
        Object eventObj = Global.invoke(model, "getEvent"); //$NON-NLS-1$
        return eventObj instanceof Event event ? event : null;
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

    private static String nullToEmpty(String s)
    {
        return s != null ? s : ""; //$NON-NLS-1$
    }
}
