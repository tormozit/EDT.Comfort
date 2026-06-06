package tormozit;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;

/**
 * Текст элемента дерева для умного фильтра (Outline, SelectType, AEF).
 * EDT/AEF часто отдают {@link org.eclipse.jface.viewers.LabelProvider} с пустым {@code getText}.
 */
public final class SmartTreeElementLabels
{
    private static final String[] ELEMENT_METHODS = {
            "getNameRu", "getName", "getDisplayName", "getLabel", "getText", "getTitle", "getFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "getQualifiedName", "getPresentationName", "getTypeName" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    private SmartTreeElementLabels() {}

    public static String resolve(Object element, IBaseLabelProvider labelProvider)
    {
        if (element == null)
            return ""; //$NON-NLS-1$

        String fromLp = fromLabelProvider(element, labelProvider);
        if (isUsefulLabel(fromLp, element))
            return fromLp;

        for (String method : ELEMENT_METHODS)
        {
            Object value = Global.invoke(element, method);
            if (value instanceof String)
            {
                String s = (String) value;
                if (isUsefulLabel(s, element))
                    return s;
            }
        }

        Object nested = Global.invoke(element, "getElement"); //$NON-NLS-1$
        if (nested != null && nested != element)
        {
            String inner = resolve(nested, labelProvider);
            if (isUsefulLabel(inner, nested))
                return inner;
        }

        String toString = element.toString();
        if (isUsefulLabel(toString, element))
            return toString;

        return fromLp != null ? fromLp : ""; //$NON-NLS-1$
    }

    private static String fromLabelProvider(Object element, IBaseLabelProvider labelProvider)
    {
        if (labelProvider == null)
            return ""; //$NON-NLS-1$
        try
        {
            if (labelProvider instanceof ILabelProvider)
                return nullToEmpty(((ILabelProvider) labelProvider).getText(element));
            if (labelProvider instanceof DelegatingStyledCellLabelProvider)
            {
                IStyledLabelProvider styled =
                        ((DelegatingStyledCellLabelProvider) labelProvider).getStyledStringProvider();
                if (styled != null)
                {
                    org.eclipse.jface.viewers.StyledString ss = styled.getStyledText(element);
                    if (ss != null)
                        return nullToEmpty(ss.getString());
                }
            }
            Object text = Global.invoke(labelProvider, "getText", element); //$NON-NLS-1$
            if (text instanceof String)
                return (String) text;
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    private static boolean isUsefulLabel(String label, Object element)
    {
        if (label == null)
            return false;
        String t = label.trim();
        if (t.isEmpty())
            return false;
        if (element != null)
        {
            String cn = element.getClass().getName();
            if (t.equals(cn) || t.equals(element.toString()))
                return false;
            if (t.startsWith(cn) && t.contains("@")) //$NON-NLS-1$
                return false;
        }
        return true;
    }

    private static String nullToEmpty(String s)
    {
        return s != null ? s : ""; //$NON-NLS-1$
    }
}
