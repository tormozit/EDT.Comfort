package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.core.runtime.Platform;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.model.ExtInfo;
import com._1c.g5.v8.dt.form.localization.EventNameLocalizationProvider;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.common.Pair;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainer;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.inject.Injector;

/**
 * Русское имя свойства палитры: тип-владелец платформы + {@link Property#getNameRu()}.
 * Подписи палитры и склейка слов не используются.
 */
final class PropertySheetPlatformPropertyResolver
{
    private static final String TEMP_TOPIC = "свойства-имя-платформы"; //$NON-NLS-1$
    private static final String FORM_BUNDLE = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$
    private static final String FORM_PLUGIN =
            "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$
    private static final String MD_PROPERTY_ANNOTATION =
            "http://www.1c.ru/v8/dt/metadata/MdProperty"; //$NON-NLS-1$
    private static final String METADATA_OBJECT_TYPE_PREFIX = "MetadataObject"; //$NON-NLS-1$

    /** Guice-синглтон бандла {@code com._1c.g5.v8.dt.form}; {@code new()} без инжектора бесполезен. */
    private static volatile FormItemInformationService FORM_ITEM_INFO;

    static final class Resolved
    {
        final Type ownerType;
        final Property property;

        Resolved(Type ownerType, Property property)
        {
            this.ownerType = ownerType;
            this.property = property;
        }

        String russianName()
        {
            if (property == null)
                return null;
            String ru = property.getNameRu();
            return ru != null && !ru.isEmpty() ? ru : null;
        }

        String englishName()
        {
            return property != null ? property.getName() : null;
        }

        /** Точная пара для поиска в синтакс-помощнике: {@code ГруппаФормы.Свойство}. */
        String syntaxHelpSearchQuery()
        {
            String propRu = russianName();
            if (propRu == null)
                return null;
            if (ownerType == null)
                return propRu;
            String typeRu = McoreUtil.getTypeNameRu(ownerType);
            if (typeRu == null || typeRu.isEmpty())
                return propRu;
            return typeRu + '.' + propRu;
        }
    }

    static final class ResolvedEvent
    {
        final Type ownerType;
        final Event event;

        ResolvedEvent(Type ownerType, Event event)
        {
            this.ownerType = ownerType;
            this.event = event;
        }

        String russianName()
        {
            if (event == null)
                return null;
            String ru = event.getNameRu();
            return ru != null && !ru.isEmpty() ? ru : null;
        }

        String englishName()
        {
            return event != null ? event.getName() : null;
        }

        /** Точная пара для поиска в синтакс-помощнике: {@code Форма.Событие}. */
        String syntaxHelpSearchQuery()
        {
            String eventRu = russianName();
            if (eventRu == null)
                return null;
            if (ownerType == null)
                return eventRu;
            String typeRu = McoreUtil.getTypeNameRu(ownerType);
            if (typeRu == null || typeRu.isEmpty())
                return eventRu;
            return typeRu + '.' + eventRu;
        }
    }

    private PropertySheetPlatformPropertyResolver() {}

    /**
     * Свойства с типом перечисления из {@code metadata.common} (например
     * {@code ChoiceDataGetModeOnInputByString}) есть в объектной модели метаданных, но
     * страницы синтакс-помощника для них нет — запрос к {@code BslDocumentationProvider}
     * в EDT падает с {@code ClassCastException} (enum → {@code Help}).
     */
    static boolean supportsBslSyntaxHelp(Resolved resolved, EStructuralFeature feature)
    {
        if (resolved == null || resolved.property == null)
            return false;
        return !isMetadataCommonEnumFeature(feature);
    }

    static boolean isMetadataCommonEnumFeature(EStructuralFeature feature)
    {
        if (feature == null)
            return false;
        EClassifier type = feature.getEType();
        if (!(type instanceof EEnum))
            return false;
        String className = type.getInstanceClassName();
        if (className != null && className.contains("metadata.common")) //$NON-NLS-1$
            return true;
        org.eclipse.emf.ecore.EPackage pkg = type.getEPackage();
        if (pkg == null)
            return false;
        String nsUri = pkg.getNsURI();
        return nsUri != null && nsUri.contains("metadata/common"); //$NON-NLS-1$
    }

    static Resolved resolve(Object page, Object scene, Object lwtView, String displayName)
    {
        return resolve(page, scene, lwtView, displayName, null);
    }

    static Resolved resolve(Object page, Object scene, Object lwtView, String displayName,
            String englishHint)
    {
        try
        {
            PropertySheetControlInterop.CopyNameContext ctx = PropertySheetControlInterop
                    .resolveCopyNameContext(page, scene, lwtView, displayName);
            String english = preferredEnglish(ctx.english, englishHint);
            EObject owner = ctx.owner();
            boolean fromBinding = owner != null;
            if (owner == null)
                owner = PropertySheetControlInterop.selectionEObjectForCopy(page);
            Global.tempLog(TEMP_TOPIC, "контекст «" + displayName + "»: признак=" + english //$NON-NLS-1$ //$NON-NLS-2$
                    + ", владелец=" + (owner == null ? "<null>" : owner.eClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                    + (fromBinding ? " (из привязки)" : " (из выделения панели)")); //$NON-NLS-1$ //$NON-NLS-2$

            if (english == null || english.isEmpty())
            {
                ResolvedEvent eventResolved = resolveEvent(page, scene, lwtView, displayName, englishHint);
                if (eventResolved != null)
                    return null;
                Global.tempLog(TEMP_TOPIC, "пустой признак для «" + displayName + "»"); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }

            EStructuralFeature feature = featureFromContext(ctx);

            // Невизуальные объекты формы (команда формы, реквизит формы) не проходят через
            // FormItemInformationService — он знает только FormVisualEntity. Подъём по
            // контейнерам приводил к форме, и свойство искалось у ClientApplicationForm: для
            // «Сочетания клавиш» это промах, а для «Отображения» — ложное срабатывание на
            // ОтображениеОбсуждений. У таких объектов имя класса модели совпадает с именем
            // типа платформы ({@code FormCommand} → «КомандаФормы»), и тип берётся напрямую.
            if (isNonVisualFormEntity(owner))
            {
                Resolved resolved = resolveByEClassType(owner, feature, english);
                if (resolved != null)
                {
                    Global.tempLog(TEMP_TOPIC, "форма (по классу объекта): " //$NON-NLS-1$
                            + McoreUtil.getTypeName(resolved.ownerType) + '.' + resolved.englishName()
                            + " → " + resolved.russianName()); //$NON-NLS-1$
                    return resolved;
                }
                // К типу формы намеренно НЕ откатываемся: чужой тип даёт ложные совпадения.
                // Но объект расширения поля (ExtInfo и т.п.) физически вложен в элемент формы —
                // общие свойства элемента (ПолеФормы.Ширина и т.п.) показаны в той же палитре,
                // а их признак объявлен не на классе расширения, а выше. Ищем по содержащему
                // визуальному элементу, только если он найден по цепочке контейнеров (не по
                // выделению панели — иначе для команды/реквизита получили бы ту же ложную
                // подмену, которой избегает комментарий выше).
                FormVisualEntity containingItem = containingFormVisualEntity(owner);
                if (containingItem != null)
                {
                    Resolved viaItem = resolveFormProperty(containingItem, page, feature, english);
                    if (viaItem != null)
                    {
                        Global.tempLog(TEMP_TOPIC, "форма (расширение поля, по элементу): " //$NON-NLS-1$
                                + McoreUtil.getTypeName(viaItem.ownerType) + '.' + viaItem.englishName()
                                + " → " + viaItem.russianName()); //$NON-NLS-1$
                        return viaItem;
                    }
                }
                Global.tempLog(TEMP_TOPIC, "форма: у типа " + owner.eClass().getName() //$NON-NLS-1$
                        + " нет свойства " + english + " (подпись «" + displayName + "»)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return null;
            }

            if (formVisualEntityForLookup(owner, page) != null)
            {
                Resolved resolved = resolveFormProperty(owner, page, feature, english);
                if (resolved != null)
                {
                    Global.tempLog(TEMP_TOPIC, "форма: " + McoreUtil.getTypeName(resolved.ownerType) //$NON-NLS-1$
                            + '.' + resolved.englishName() + " → " + resolved.russianName()); //$NON-NLS-1$
                    return resolved;
                }
                Global.tempLog(TEMP_TOPIC, "форма: не найдено для признака " + english //$NON-NLS-1$
                        + " (подпись «" + displayName + "»)"); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }

            MdObject mdOwner = mdObjectForPropertyLookup(owner);
            if (mdOwner != null)
            {
                // Сначала тип непосредственного владельца из привязки поля (реквизит, вложенный
                // объект), а не корень редактора — иначе свойство ищется не на том типе платформы.
                if (owner instanceof MdObject mdDirect && mdDirect != mdOwner)
                {
                    Resolved direct = resolveMdProperty(mdDirect, feature, english);
                    if (direct != null)
                    {
                        Global.tempLog(TEMP_TOPIC, "мд (прямой): " //$NON-NLS-1$
                            + McoreUtil.getTypeName(direct.ownerType) + '.' + direct.englishName()
                            + " → " + direct.russianName()); //$NON-NLS-1$
                        return direct;
                    }
                }
                if (owner != null && feature != null && featureBelongsTo(owner, feature))
                {
                    Resolved byClass = resolveByEClassType(owner, feature, english);
                    if (byClass != null)
                    {
                        Global.tempLog(TEMP_TOPIC, "мд (класс " + owner.eClass().getName() + "): " //$NON-NLS-1$ //$NON-NLS-2$
                            + McoreUtil.getTypeName(byClass.ownerType) + '.' + byClass.englishName()
                            + " → " + byClass.russianName()); //$NON-NLS-1$
                        return byClass;
                    }
                }
                Resolved resolved = resolveMdProperty(mdOwner, feature, english);
                if (resolved != null)
                {
                    Global.tempLog(TEMP_TOPIC, "мд: " + McoreUtil.getTypeName(resolved.ownerType) //$NON-NLS-1$
                            + '.' + resolved.englishName() + " → " + resolved.russianName()); //$NON-NLS-1$
                    return resolved;
                }
                Global.tempLog(TEMP_TOPIC, "мд: не найдено для признака " + english //$NON-NLS-1$
                        + " (подпись «" + displayName + "», " + mdOwner.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                        + ")"); //$NON-NLS-1$
                return null;
            }
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "resolve «" + displayName + "»", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    static ResolvedEvent resolveEvent(Object page, Object scene, Object lwtView, String displayName)
    {
        return resolveEvent(page, scene, lwtView, displayName, null);
    }

    static ResolvedEvent resolveEvent(Object page, Object scene, Object lwtView, String displayName,
            String englishHint)
    {
        try
        {
            PropertySheetControlInterop.CopyNameContext ctx = PropertySheetControlInterop
                    .resolveCopyNameContext(page, scene, lwtView, displayName);
            EObject owner = ctx.owner();
            if (owner == null)
                owner = PropertySheetControlInterop.selectionEObjectForCopy(page);
            String english = preferredEnglish(ctx.english, englishHint);
            Event event = findFormEvent(owner, page, displayName, scene, lwtView, english);
            if (event == null)
                return null;

            Type ownerType = null;
            FormVisualEntity formItem = formVisualEntityForLookup(owner, page);
            if (formItem != null)
            {
                FormItemInformationService service = formItemInformationService();
                if (service != null)
                    ownerType = service.getTypeOfFormItem(formItem);
            }
            Global.tempLog(TEMP_TOPIC, "событие: " + (ownerType != null //$NON-NLS-1$
                    ? McoreUtil.getTypeName(ownerType) + '.' : "") //$NON-NLS-1$
                    + event.getName() + " → " + event.getNameRu()); //$NON-NLS-1$
            return new ResolvedEvent(ownerType, event);
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "resolveEvent «" + displayName + "»", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    static String russianNameForCopy(Object page, Object scene, Object lwtView, String displayName)
    {
        return russianNameForCopy(page, scene, lwtView, displayName, null);
    }

    static String russianNameForCopy(Object page, Object scene, Object lwtView, String displayName,
            String englishHint)
    {
        try
        {
            Resolved resolved = resolve(page, scene, lwtView, displayName, englishHint);
            if (resolved != null && resolved.russianName() != null)
                return resolved.russianName();
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "russianNameForCopy resolve", e); //$NON-NLS-1$
        }

        PropertySheetControlInterop.CopyNameContext ctx = PropertySheetControlInterop
                .resolveCopyNameContext(page, scene, lwtView, displayName);
        EObject owner = ctx.owner();
        if (owner == null)
            owner = PropertySheetControlInterop.selectionEObjectForCopy(page);
        ResolvedEvent eventResolved = resolveEvent(page, scene, lwtView, displayName, englishHint);
        if (eventResolved != null && eventResolved.russianName() != null)
            return eventResolved.russianName();

        String english = preferredEnglish(ctx.english, englishHint);

        try
        {
            String fallback = PropertySheetControlInterop.resolveRussianCopyPropertyName(page, scene,
                    lwtView, displayName);
            if (fallback != null && !fallback.isEmpty() && !fallback.equals(displayName))
                return fallback;
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "russianNameForCopy fallback", e); //$NON-NLS-1$
        }

        if (!english.isEmpty())
            return english;
        return displayName != null ? displayName : ""; //$NON-NLS-1$
    }

    private static EStructuralFeature featureFromContext(
            PropertySheetControlInterop.CopyNameContext ctx)
    {
        EStructuralFeature fromBinding = ctx.feature();
        if (fromBinding != null)
            return fromBinding;
        if (ctx.featurePath != null && ctx.featurePath.length > 0)
            return ctx.featurePath[ctx.featurePath.length - 1];
        return null;
    }

    private static Resolved resolveFormProperty(EObject owner, Object page,
            EStructuralFeature feature, String english)
    {
        FormVisualEntity formItem = formVisualEntityForLookup(owner, page);
        if (formItem == null)
            return null;
        FormItemInformationService service = formItemInformationService();
        if (service == null)
            return null;

        List<Type> types = service.getTypesOfFormItem(formItem);
        if (types == null || types.isEmpty())
            return null;

        // Сужение по признаку — только если признак ДЕЙСТВИТЕЛЬНО принадлежит классу элемента:
        // внутри getAllowedContextDefItem EDT делает item.eGet(feature) и на чужом признаке
        // бросает IllegalArgumentException («The feature 'shape' is not a valid feature»),
        // обрывая весь разбор — в логе это выглядело как «свойство не найдено».
        if (formItem instanceof FormItem item && featureBelongsTo(item, feature))
        {
            List<ContextDef> allowed;
            try
            {
                allowed = service.getAllowedContextDefItem(item, feature);
            }
            catch (RuntimeException e)
            {
                // Сужение — необязательный шаг: даже если сервис EDT на нём споткнулся,
                // поиск по всем типам элемента ниже отработать обязан.
                Global.tempLog(TEMP_TOPIC, "форма: сужение по признаку " + english //$NON-NLS-1$
                        + " не удалось (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                allowed = null;
            }
            if (allowed != null && !allowed.isEmpty())
            {
                for (int i = types.size() - 1; i >= 0; i--)
                {
                    Type type = types.get(i);
                    ContextDef contextDef = type != null ? type.getContextDef() : null;
                    if (contextDef == null || !contextDefIn(allowed, contextDef))
                        continue;
                    Property property = findProperty(type, english);
                    if (property != null)
                        return new Resolved(type, property);
                }
            }
        }

        for (int i = types.size() - 1; i >= 0; i--)
        {
            Type type = types.get(i);
            Property property = findProperty(type, english);
            if (property != null)
                return new Resolved(type, property);
        }

        // Точного совпадения имён нет. У объектов метаданных для этого случая уже работают
        // запасные стратегии (аннотация признака, тип значения, похожее имя) — у формы они
        // не применялись, хотя расхождения имён те же: у Button признак `shape`, а свойство
        // платформы называется иначе. Отдельным проходом, ПОСЛЕ точного поиска по всем типам:
        // иначе нестрогое совпадение на типе-расширении перебило бы точное на базовом типе.
        for (int i = types.size() - 1; i >= 0; i--)
        {
            Type type = types.get(i);
            Property property = findFormPlatformPropertyFallback(type, feature, english);
            if (property != null)
            {
                Global.tempLog(TEMP_TOPIC, "форма: признак " + english + " → свойство " //$NON-NLS-1$ //$NON-NLS-2$
                        + property.getName() + " (запасная стратегия)"); //$NON-NLS-1$
                return new Resolved(type, property);
            }
        }
        logSimilarProperties(formItem, types, english);
        return null;
    }

    /**
     * Что вообще есть у типа-владельца рядом по имени — чтобы промах было видно по логу, а не
     * гадать: свойство названо иначе или его во встроенном языке нет вовсе.
     */
    private static void logSimilarProperties(FormVisualEntity formItem, List<Type> types, String english)
    {
        String needle = english.toLowerCase(Locale.ROOT);
        StringBuilder similar = new StringBuilder();
        StringBuilder typeNames = new StringBuilder();
        int total = 0;
        for (Type type : types)
        {
            if (typeNames.length() > 0)
                typeNames.append('+');
            typeNames.append(McoreUtil.getTypeName(type));
            ContextDef contextDef = type != null ? type.getContextDef() : null;
            if (contextDef == null)
                continue;
            for (Property property : contextDef.allProperties())
            {
                String name = property != null ? property.getName() : null;
                if (name == null || name.isEmpty())
                    continue;
                total++;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.contains(needle) && !needle.contains(lower))
                    continue;
                if (similar.length() > 0)
                    similar.append(", "); //$NON-NLS-1$
                similar.append(McoreUtil.getTypeName(type)).append('.').append(name);
            }
        }
        Global.tempLog(TEMP_TOPIC, "форма: объект " //$NON-NLS-1$
                + (formItem != null ? formItem.eClass().getName() : "<null>") //$NON-NLS-1$
                + ", тип " + typeNames + "; похожих на " + english + " среди " + total //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " свойств: " + (similar.length() > 0 ? similar.toString() : "нет")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean contextDefIn(List<ContextDef> allowed, ContextDef candidate)
    {
        if (allowed == null || candidate == null)
            return false;
        for (ContextDef item : allowed)
        {
            if (item == candidate)
                return true;
        }
        return false;
    }

    /** Объект формы, который не является её визуальным элементом: команда, реквизит, параметр. */
    private static boolean isNonVisualFormEntity(EObject owner)
    {
        if (owner == null || owner instanceof FormVisualEntity)
            return false;
        for (EObject cur = owner; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof Form)
                return cur != owner;
        }
        return false;
    }

    /**
     * Тип платформы по имени класса модели: у объектов формы они совпадают
     * ({@code FormCommand}, {@code FormAttribute}).
     */
    private static Resolved resolveByEClassType(EObject owner, EStructuralFeature feature, String english)
    {
        String typeName = owner.eClass().getName();
        Type type = loadResolvedPlatformType(owner, typeName, McorePackage.Literals.TYPE);
        if (type == null)
            type = loadResolvedPlatformType(owner, typeName, McorePackage.Literals.TYPE_ITEM);
        if (type == null)
        {
            Global.tempLog(TEMP_TOPIC, "тип платформы не загружен: " + typeName); //$NON-NLS-1$
            return null;
        }
        Property property = findProperty(type, english);
        if (property == null)
            property = findFormPlatformPropertyFallback(type, feature, english);
        return property != null ? new Resolved(type, property) : null;
    }

    /**
     * Ближайший визуальный элемент формы по цепочке контейнеров, БЕЗ обращения к выделению
     * панели. В отличие от {@link #formVisualEntityForLookup}, для объектов, которые в форме
     * лежат не внутри элемента (команда, реквизит), возвращает {@code null}, а не подменяет
     * элемент случайным выделением — здесь это используется как признак «объект физически
     * вложен в элемент формы» (расширение поля и т.п.), а не только для поиска.
     */
    private static FormVisualEntity containingFormVisualEntity(EObject owner)
    {
        for (EObject cur = owner != null ? owner.eContainer() : null; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof FormVisualEntity entity)
                return entity;
            if (cur instanceof Form)
                break;
        }
        return null;
    }

    private static FormVisualEntity formVisualEntityForLookup(EObject owner, Object page)
    {
        for (EObject cur = owner; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof FormVisualEntity entity)
                return entity;
        }
        EObject selection = PropertySheetControlInterop.selectionEObjectForCopy(page);
        return selection instanceof FormVisualEntity entity ? entity : null;
    }

    /** Признак объявлен в классе объекта (или его предке) — иначе {@code eGet} по нему упадёт. */
    private static boolean featureBelongsTo(EObject object, EStructuralFeature feature)
    {
        if (object == null || feature == null)
            return false;
        EClass owner = feature.getEContainingClass();
        return owner != null && owner.isSuperTypeOf(object.eClass());
    }

    private static String preferredEnglish(String fromContext, String englishHint)
    {
        if (englishHint != null && !englishHint.isEmpty())
            return englishHint;
        return fromContext != null ? fromContext : ""; //$NON-NLS-1$
    }

    private static Event findFormEvent(EObject owner, Object page, String displayName, Object scene,
            Object lwtView, String english)
    {
        Event fromField = PropertySheetControlInterop.eventFromFieldModel(page, scene, lwtView);
        if (fromField != null)
            return fromField;

        FormVisualEntity formItem = formVisualEntityForLookup(owner, page);
        if (formItem == null)
            return null;
        FormItemInformationService service = formItemInformationService();
        if (service == null)
            return null;

        ExtInfo extInfo = service.getExtensionInfo(formItem);
        List<Event> events = service.getAllowedEvents(formItem);
        if (events == null || events.isEmpty())
            return null;

        if (english != null && !english.isEmpty())
        {
            for (Event event : events)
            {
                if (event != null && english.equalsIgnoreCase(event.getName()))
                    return event;
            }
        }

        for (Event event : events)
        {
            if (event == null)
                continue;
            if (matchesEventDisplayName(extInfo, event, displayName))
                return event;
        }
        return null;
    }

    private static boolean matchesEventDisplayName(ExtInfo extInfo, Event event, String displayName)
    {
        if (displayName == null || displayName.isEmpty())
            return false;
        String ru = event.getNameRu();
        if (displayName.equals(ru))
            return true;
        String en = event.getName();
        if (en != null && displayName.equalsIgnoreCase(en))
            return true;
        try
        {
            String localized = EventNameLocalizationProvider.INSTANCE
                    .getString(Pair.newPair(extInfo, event));
            if (displayName.equals(localized))
                return true;
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "локализация события " + event.getName(), e); //$NON-NLS-1$
        }
        return false;
    }

    private static FormItemInformationService formItemInformationService()
    {
        FormItemInformationService cached = FORM_ITEM_INFO;
        if (cached != null)
            return cached;
        try
        {
            Bundle bundle = Platform.getBundle(FORM_BUNDLE);
            if (bundle == null)
            {
                Global.tempLog(TEMP_TOPIC, "FormItemInformationService: бандл " + FORM_BUNDLE //$NON-NLS-1$
                        + " недоступен"); //$NON-NLS-1$
                return null;
            }
            Class<?> pluginClass = bundle.loadClass(FORM_PLUGIN);
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin != null)
            {
                Object injector = plugin.getClass().getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
                if (injector instanceof Injector guice)
                {
                    cached = guice.getInstance(FormItemInformationService.class);
                    FORM_ITEM_INFO = cached;
                    Global.tempLog(TEMP_TOPIC, "FormItemInformationService: FormPlugin injector"); //$NON-NLS-1$
                    return cached;
                }
            }
            Global.tempLog(TEMP_TOPIC, "FormItemInformationService: injector недоступен"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "FormItemInformationService", e); //$NON-NLS-1$
        }
        return null;
    }

    private static Resolved resolveMdProperty(MdObject mdOwner, EStructuralFeature feature, String english)
    {
        Type ownerType = metadataObjectPlatformType(mdOwner);
        if (ownerType == null)
            return null;

        Property property = findMdPlatformProperty(ownerType, feature, english);
        if (property == null)
            return null;
        return new Resolved(ownerType, property);
    }

    private static MdObject mdObjectForPropertyLookup(EObject owner)
    {
        if (owner instanceof MdObject mdObject)
            return mdObject;
        return GoToDefinition.findContainingMdObject(owner);
    }

    private static Type metadataObjectPlatformType(MdObject mdOwner)
    {
        if (mdOwner == null || mdOwner.eClass() == null)
            return null;
        String typeName = METADATA_OBJECT_TYPE_PREFIX + mdOwner.eClass().getName();
        try
        {
            Type type = loadResolvedPlatformType(mdOwner, typeName, McorePackage.Literals.TYPE);
            if (type == null)
                type = loadResolvedPlatformType(mdOwner, typeName, McorePackage.Literals.TYPE_ITEM);
            if (type == null)
            {
                Global.tempLog(TEMP_TOPIC, "мд-тип: не загружен " + typeName); //$NON-NLS-1$
                return null;
            }
            ContextDef contextDef = type.getContextDef();
            int propCount = contextDef != null ? contextDef.allProperties().size() : 0;
            Global.tempLog(TEMP_TOPIC, "мд-тип: " + typeName + ", свойств=" + propCount //$NON-NLS-1$ //$NON-NLS-2$
                    + ", proxy=" + type.eIsProxy()); //$NON-NLS-1$
            return propCount > 0 ? type : null;
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "metadataObjectPlatformType " + typeName, e); //$NON-NLS-1$
        }
        return null;
    }

    private static Type loadResolvedPlatformType(EObject mdOwner, String typeName, EClass providerClass)
    {
        Version version = platformVersion(mdOwner);
        if (version == null)
            version = Version.LATEST;
        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(providerClass, version);
        if (provider == null)
            return null;
        IEObjectDescription description = provider.getEObjectDescription(typeName);
        if (description == null)
            return null;
        EObject object = description.getEObjectOrProxy();
        if (object == null)
            return null;
        org.eclipse.emf.ecore.resource.Resource resource = mdOwner.eResource();
        if (resource != null)
            object = EcoreUtil.resolve(object, resource);
        return object instanceof Type type ? type : null;
    }

    private static Version platformVersion(EObject context)
    {
        if (context == null)
            return null;
        try
        {
            IRuntimeVersionSupport support = Global.getOsgiService(IRuntimeVersionSupport.class);
            if (support != null)
                return support.getRuntimeVersion(context);
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "platformVersion", e); //$NON-NLS-1$
        }
        return null;
    }

    private static Property findMdPlatformProperty(Type ownerType, EStructuralFeature feature, String english)
    {
        if (ownerType == null)
            return null;

        Property property = findProperty(ownerType, english);
        return property != null ? property : findPlatformPropertyFallback(ownerType, feature, english);
    }

    /**
     * Запасные стратегии для формы — только строгие. Нестрогое «похожее имя» здесь НЕ
     * применяется: у свойств формы имена коротки и вложены друг в друга, и правило «имя
     * платформы содержится в имени признака» даёт ложные срабатывания — признак
     * {@code userVisible} («Пользовательская видимость», объектной модели неизвестен)
     * притягивался к свойству {@code Visible}.
     *
     * <p>Совпадение по типу значения тоже сужено: годятся только именованные типы платформы
     * (перечисление или объект), но не примитивы. Иначе единственное булево свойство типа
     * притянуло бы к себе любой булев признак.
     */
    private static Property findFormPlatformPropertyFallback(Type ownerType,
            EStructuralFeature feature, String english)
    {
        if (ownerType == null)
            return null;
        if (feature != null)
        {
            String fromAnnotation = platformPropertyEnglishFromAnnotation(feature);
            if (fromAnnotation != null && !fromAnnotation.isEmpty())
            {
                Property property = findProperty(ownerType, fromAnnotation);
                if (property != null)
                    return property;
            }
            if (feature.getEType() instanceof EEnum || feature.getEType() instanceof EClass)
            {
                Property property = findPropertyByFeatureType(ownerType, feature);
                if (property != null)
                    return property;
            }
        }
        return findPropertyBySuffix(ownerType, english);
    }

    /**
     * Запасные стратегии для объектов метаданных: английское имя из аннотации признака,
     * единственное свойство подходящего типа значения, уточнённое владельцем имя, похожее имя.
     */
    private static Property findPlatformPropertyFallback(Type ownerType, EStructuralFeature feature,
            String english)
    {
        if (ownerType == null)
            return null;
        if (feature != null)
        {
            String fromAnnotation = platformPropertyEnglishFromAnnotation(feature);
            if (fromAnnotation != null && !fromAnnotation.isEmpty())
            {
                Property property = findProperty(ownerType, fromAnnotation);
                if (property != null)
                    return property;
            }
        }

        Property property = findPropertyByFeatureType(ownerType, feature);
        if (property != null)
            return property;

        property = findPropertyBySuffix(ownerType, english);
        if (property != null)
            return property;

        return findPropertyByEnglishHint(ownerType, english);
    }

    /**
     * Имя свойства платформы уточняет имя признака владельцем: признак {@code shape} у кнопки —
     * свойство {@code ButtonShape}. Годится только однозначное совпадение: если суффиксу
     * отвечают несколько свойств, выбирать наугад нельзя.
     */
    private static Property findPropertyBySuffix(Type ownerType, String english)
    {
        ContextDef contextDef = ownerType.getContextDef();
        if (contextDef == null || english == null || english.length() < 4)
            return null;
        String suffix = english.toLowerCase(Locale.ROOT);
        List<Property> matches = new ArrayList<>();
        for (Property candidate : contextDef.allProperties())
        {
            String name = candidate != null ? candidate.getName() : null;
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(suffix))
                matches.add(candidate);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static String platformPropertyEnglishFromAnnotation(EStructuralFeature feature)
    {
        if (feature == null)
            return null;
        for (EAnnotation annotation : feature.getEAnnotations())
        {
            if (!MD_PROPERTY_ANNOTATION.equals(annotation.getSource()))
                continue;
            String code = annotation.getDetails().get("code"); //$NON-NLS-1$
            if (code != null && !code.isEmpty())
                return code;
        }
        return null;
    }

    private static Property findPropertyByFeatureType(Type ownerType, EStructuralFeature feature)
    {
        if (ownerType == null || feature == null)
            return null;
        if (isMetadataCommonEnumFeature(feature))
            return null;
        String platformTypeName = platformTypeNameForFeature(feature.getEType());
        if (platformTypeName == null || platformTypeName.isEmpty())
            return null;

        ContextDef contextDef = ownerType.getContextDef();
        if (contextDef == null)
            return null;

        List<Property> matches = new ArrayList<>();
        for (Property candidate : contextDef.allProperties())
        {
            if (candidate != null && propertyReferencesType(candidate, platformTypeName))
                matches.add(candidate);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static String platformTypeNameForFeature(EClassifier classifier)
    {
        if (classifier == null)
            return null;
        if (classifier instanceof EEnum || classifier instanceof EClass)
            return classifier.getName();
        if (classifier instanceof EDataType dataType)
        {
            String name = dataType.getName();
            if ("EBoolean".equals(name)) //$NON-NLS-1$
                return "Boolean"; //$NON-NLS-1$
            if ("EString".equals(name)) //$NON-NLS-1$
                return "String"; //$NON-NLS-1$
            if ("EInt".equals(name) || "EInteger".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                return "Number"; //$NON-NLS-1$
            if ("EDouble".equals(name)) //$NON-NLS-1$
                return "Number"; //$NON-NLS-1$
            if ("EDate".equals(name)) //$NON-NLS-1$
                return "Date"; //$NON-NLS-1$
        }
        return null;
    }

    private static boolean propertyReferencesType(Property property, String typeName)
    {
        if (property == null || typeName == null || typeName.isEmpty())
            return false;
        TypeContainer container = property.getTypeContainer();
        if (container != null)
        {
            for (TypeItem item : container.allTypes())
            {
                if (typeNameMatches(item, typeName))
                    return true;
            }
        }
        for (TypeItem item : property.getTypes())
        {
            if (typeNameMatches(item, typeName))
                return true;
        }
        return false;
    }

    private static boolean typeNameMatches(TypeItem item, String typeName)
    {
        if (item == null)
            return false;
        String name = McoreUtil.getTypeName(item);
        return typeName.equalsIgnoreCase(name);
    }

    /**
     * EMF- и платформенные имена часто расходятся ({@code postInPrivilegedMode} →
     * {@code PrivilegedPostingMode}); точное совпадение и тип значения не помогли.
     */
    private static Property findPropertyByEnglishHint(Type ownerType, String english)
    {
        if (ownerType == null || english == null || english.isEmpty())
            return null;
        ContextDef contextDef = ownerType.getContextDef();
        if (contextDef == null)
            return null;

        String emf = english.toLowerCase(Locale.ROOT);
        Property best = null;
        int bestScore = 0;
        for (Property candidate : contextDef.allProperties())
        {
            if (candidate == null)
                continue;
            String platform = candidate.getName();
            if (platform == null || platform.isEmpty())
                continue;
            int score = englishHintScore(emf, platform);
            if (score > bestScore)
            {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 2 ? best : null;
    }

    private static int englishHintScore(String emfLower, String platformEnglish)
    {
        String platform = platformEnglish.toLowerCase(Locale.ROOT);
        int score = 0;
        if (emfLower.contains("unpost") && platform.contains("unpost")) //$NON-NLS-1$ //$NON-NLS-2$
            score += 3;
        else if (emfLower.contains("post") && platform.contains("post") && !platform.contains("unpost")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            score += 2;
        if (emfLower.contains("privileged") && platform.contains("privileged")) //$NON-NLS-1$ //$NON-NLS-2$
            score += 2;
        if (emfLower.contains("register") && platform.contains("register")) //$NON-NLS-1$ //$NON-NLS-2$
            score += 1;
        if (emfLower.contains("writing") && platform.contains("writing")) //$NON-NLS-1$ //$NON-NLS-2$
            score += 1;
        if (emfLower.contains("deletion") && platform.contains("deletion")) //$NON-NLS-1$ //$NON-NLS-2$
            score += 2;
        if (platform.length() >= 4 && emfLower.contains(platform)) //$NON-NLS-1$
            score += 3;
        return score;
    }

    private static Property findProperty(Type type, String english)
    {
        if (type == null || english == null || english.isEmpty())
            return null;
        ContextDef contextDef = type.getContextDef();
        if (contextDef == null)
            return null;
        for (Property property : contextDef.allProperties())
        {
            if (property != null && english.equalsIgnoreCase(property.getName()))
                return property;
        }
        return null;
    }
}
