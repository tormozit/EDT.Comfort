package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Tree;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;

/** Общее представление колонки типа значения в деревьях реквизитов метаданных и формы. */
final class ValueTypeColumnLabelProvider extends ColumnLabelProvider
{
    private final Tree tree;
    private final Function<Object, TypeDescription> typeOf;
    private final Function<Object, EObject> contextOf;
    private final ColumnLabelProvider base;

    ValueTypeColumnLabelProvider(Tree tree, Function<Object, TypeDescription> typeOf,
        Function<Object, EObject> contextOf, ColumnLabelProvider base)
    {
        this.tree = tree;
        this.typeOf = typeOf;
        this.contextOf = contextOf;
        this.base = base;
    }

    @Override
    public String getText(Object element)
    {
        TypeDescription type = typeOf.apply(element);
        if (type != null && type.getTypes().size() == 1)
        {
            String name = typeName(resolve(type.getTypes().get(0), element));
            int dot = name != null ? name.lastIndexOf('.') : -1;
            if (dot > 0 && dot < name.length() - 1 && isReferenceCategory(name.substring(0, dot)))
                return name.substring(dot + 1);
        }
        return fullText(element, type);
    }

    @Override
    public Image getImage(Object element)
    {
        TypeDescription type = typeOf.apply(element);
        if (type == null || type.getTypes().isEmpty())
            return base != null ? base.getImage(element) : null;
        if (type.getTypes().size() > 1)
            return MdUiSharedImages.getImage(MdUiSharedImages.OBJS_TYPE_DESCRIPTION);
        TypeItem item = resolve(type.getTypes().get(0), element);
        String category = item != null ? McoreUtil.getTypeCategory(item) : null;
        Image image = category != null && !category.isBlank() ? MdUiSharedImages.getTypeImage(category) : null;
        return image != null ? image : base != null ? base.getImage(element) : null;
    }

    @Override
    public String getToolTipText(Object element)
    {
        if (base != null)
        {
            String baseToolTip = base.getToolTipText(element);
            if (baseToolTip != null && !baseToolTip.isEmpty())
                return TooltipText.wrap(tree, baseToolTip);
        }
        TypeDescription type = typeOf.apply(element);
        String text = fullText(element, type);
        return text == null || text.isEmpty() ? null : TooltipText.wrap(tree, text);
    }

    @Override
    public Font getFont(Object element)
    {
        return base != null ? base.getFont(element) : super.getFont(element);
    }

    @Override
    public Color getForeground(Object element)
    {
        return base != null ? base.getForeground(element) : super.getForeground(element);
    }

    @Override
    public Color getBackground(Object element)
    {
        return base != null ? base.getBackground(element) : super.getBackground(element);
    }

    private String fullText(Object element, TypeDescription type)
    {
        if (base != null)
        {
            String text = base.getText(element);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        if (type == null)
            return ""; //$NON-NLS-1$
        List<String> names = new ArrayList<>();
        for (TypeItem item : type.getTypes())
        {
            String name = typeName(resolve(item, element));
            if (name != null && !name.isBlank())
                names.add(name);
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    private TypeItem resolve(TypeItem item, Object element)
    {
        if (item == null)
            return null;
        EObject context = contextOf.apply(element);
        EObject resolved = item.eIsProxy() && context != null ? EcoreUtil.resolve(item, context) : item;
        return resolved instanceof TypeItem typeItem ? typeItem : null;
    }

    private static String typeName(TypeItem item)
    {
        if (item == null)
            return null;
        String name = McoreUtil.getTypeNameRu(item);
        return name == null || name.isBlank() ? McoreUtil.getTypeName(item) : name;
    }

    private static boolean isReferenceCategory(String category)
    {
        return category.endsWith("Ссылка") || category.endsWith("Ref"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
