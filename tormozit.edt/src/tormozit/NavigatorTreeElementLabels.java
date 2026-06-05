package tormozit;

import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IBaseLabelProvider;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderFolder;

/**
 * Текст для умного фильтра навигатора EDT: как штатный поиск — только объекты метаданных,
 * не группы («Реквизиты», «Макеты»…); строка поиска = имя + синоним/комментарий + подсказка.
 */
public final class NavigatorTreeElementLabels
{
    private static final String VIRTUAL_ADAPTER =
            "com._1c.g5.v8.dt.navigator.adapters.VirtualNavigatorAdapterBase"; //$NON-NLS-1$
    private static final String EXTERNAL_FOLDER_ADAPTER =
            "com._1c.g5.v8.dt.navigator.adapters.ExternalObjectFolderNavigatorAdapterBase"; //$NON-NLS-1$

    private static final String[] MODEL_METHODS = {
            "getModel", "getMdObject", "getEObject", "getElement" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    private static final String[] TEXT_FEATURES = {
            "getToolTip", "getTooltip", "getExplanation", "getHelp" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    private NavigatorTreeElementLabels() {}

    /** Группа навигатора — не участвует в собственном совпадении по тексту. */
    public static boolean isGroupNode(Object element)
    {
        if (element == null)
            return false;
        if (element instanceof INavigatorContentProviderFolder)
            return true;

        String className = element.getClass().getName();
        if (className.endsWith("$Folder")) //$NON-NLS-1$
            return true;
        if (className.contains("FolderNavigatorAdapter")) //$NON-NLS-1$
            return true;
        return isInstanceOf(element, VIRTUAL_ADAPTER) || isInstanceOf(element, EXTERNAL_FOLDER_ADAPTER);
    }

    public static String resolveSearchText(Object element, IBaseLabelProvider labelProvider)
    {
        if (element == null)
            return ""; //$NON-NLS-1$
        if (isGroupNode(element))
            return ""; //$NON-NLS-1$

        Object model = resolveModel(element);
        if (model instanceof MdObject)
            return NavigatorFuzzySearch.joinSearchTexts((MdObject) model);
        if (model instanceof EObject)
            return buildEObjectSearchText((EObject) model);

        return SmartTreeElementLabels.resolve(element, labelProvider);
    }

    public static MdObject resolveMdObject(Object element)
    {
        Object model = resolveModel(element);
        return model instanceof MdObject ? (MdObject) model : null;
    }

    private static Object resolveModel(Object element)
    {
        if (element == null)
            return null;
        if (element instanceof MdObject || element instanceof EObject)
            return element;

        for (String method : MODEL_METHODS)
        {
            Object value = Global.invoke(element, method);
            if (value instanceof MdObject || value instanceof EObject)
                return value;
        }

        Object nested = Global.invoke(element, "getElement"); //$NON-NLS-1$
        if (nested != null && nested != element)
            return resolveModel(nested);

        return null;
    }

    private static String buildEObjectSearchText(EObject eObject)
    {
        if (eObject == null)
            return ""; //$NON-NLS-1$

        StringBuilder text = new StringBuilder();
        appendTextFeature(text, eObject, "getName"); //$NON-NLS-1$
        appendTextFeature(text, eObject, "getSynonym"); //$NON-NLS-1$
        appendTextFeature(text, eObject, "getComment"); //$NON-NLS-1$
        for (String feature : TEXT_FEATURES)
            appendTextFeature(text, eObject, feature);
        Object extension = Global.invoke(eObject, "getExtension"); //$NON-NLS-1$
        if (extension != null)
        {
            for (String feature : TEXT_FEATURES)
                appendTextFeature(text, extension, feature);
            appendTextFeature(text, extension, "getComment"); //$NON-NLS-1$
        }
        return text.toString().trim();
    }

    private static void appendTextFeature(StringBuilder text, Object target, String method)
    {
        if (target == null)
            return;
        Object value = Global.invoke(target, method);
        appendTextValue(text, value);
    }

    private static void appendTextValue(StringBuilder text, Object value)
    {
        if (value == null)
            return;
        if (value instanceof String)
        {
            appendToken(text, (String) value);
            return;
        }
        if (value instanceof Map<?, ?>)
        {
            for (Object entryValue : ((Map<?, ?>) value).values())
                appendTextValue(text, entryValue);
            return;
        }
        if (value instanceof Iterable<?> && !(value instanceof String))
        {
            for (Object item : (Iterable<?>) value)
                appendTextValue(text, item);
            return;
        }

        Object extracted = Global.invoke(value, "getValue"); //$NON-NLS-1$
        if (extracted != null && extracted != value)
            appendTextValue(text, extracted);
    }

    private static void appendToken(StringBuilder text, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (trimmed.isEmpty())
            return;
        if (text.length() > 0)
            text.append(' ');
        text.append(trimmed);
    }

    private static boolean isInstanceOf(Object element, String typeName)
    {
        if (element == null || typeName == null)
            return false;
        for (Class<?> cls = element.getClass(); cls != null; cls = cls.getSuperclass())
        {
            if (typeName.equals(cls.getName()))
                return true;
        }
        return false;
    }
}
