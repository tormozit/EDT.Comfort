package tormozit;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Поиск выделяемого текста заголовка формы Eclipse Forms ({@link org.eclipse.ui.forms.widgets.Form}).
 *
 * <p>Область заголовка ({@code org.eclipse.ui.internal.forms.widgets.TitleRegion}) всегда содержит
 * два контрола — {@code Label} и {@code StyledText}, видим ровно один; переключение —
 * {@code Form.setTitleTextSelectable(boolean)}. Оформить часть текста стилем (например, ссылкой)
 * можно только на {@code StyledText} — {@code Label} не поддерживает стили части текста.
 *
 * <p>Общий код для {@link MdEditorTitleNavigatorMenuHook} и {@link InfobaseEditorTitleMenuHook}.
 */
final class FormHeaderTitleText
{
    private static final String TITLE_REGION_CLASS = "TitleRegion"; //$NON-NLS-1$

    private FormHeaderTitleText()
    {
    }

    /** Область заголовка формы — {@code org.eclipse.ui.internal.forms.widgets.TitleRegion}. */
    static Control findTitleRegion(Composite head)
    {
        for (Control child : head.getChildren())
        {
            if (TITLE_REGION_CLASS.equals(child.getClass().getSimpleName()))
                return child;
        }
        return null;
    }

    /** Выделяемый вариант текста заголовка внутри области заголовка. */
    static StyledText findTitleText(Control titleRegion)
    {
        if (!(titleRegion instanceof Composite region))
            return null;
        for (Control child : region.getChildren())
        {
            if (child instanceof StyledText styledText && !styledText.isDisposed())
                return styledText;
        }
        return null;
    }
}
