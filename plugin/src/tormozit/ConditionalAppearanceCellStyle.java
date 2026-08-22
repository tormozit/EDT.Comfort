package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.ui.util.DcsUiUtil;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.Value;

/**
 * Ячейки колонки «Оформление» в списке условного оформления рисуются самим этим оформлением:
 * цвет фона, цвет текста и шрифт берутся из настроек строки. Результат виден сразу, без открытия
 * диалога «Оформление».
 *
 * <p>Работает везде, где EDT показывает штатный список условного оформления (виджет
 * {@code com._1c.g5.v8.dt.dcs.ui.settings.conditional.ConditionalAppearance}): страница
 * «Условное оформление» и диалог по гиперссылке «Открыть» в редакторе формы, страница
 * «Настройки → УсловноеОформление» конструктора схемы компоновки данных. Момент создания виджета
 * у каждого свой, общего события нет — поэтому {@link #install} слушает {@link SWT#Show} на уровне
 * {@link Display}: контрол получает это событие, когда становится видимым.
 *
 * <p><b>Почему красится существующая колонка, а не добавляется своя.</b> Список построен на
 * {@code TableEx} (внутри — Nebula Grid). Соответствие «колонка → ячейка грида»
 * ({@code TableEx.columnsItems}) заполняется при создании колонок, до появления строк, и колонка,
 * добавленная позже, в него не попадает: любой {@code refresh} падает с NPE в
 * {@code TableEx.setItemText}, а заодно ломается редактирование ячеек. Поэтому берётся готовая
 * колонка, и у неё подменяется провайдер ячеек.
 *
 * <p><b>Читаемость важнее точности.</b> Если цвет текста сливается с цветом фона (в том числе
 * когда задан только один из них, а второй — цвет списка), текст рисуется чёрным или белым — по
 * яркости фона. Порог — {@link #MIN_CONTRAST}, отношение относительных яркостей по WCAG.
 * Пустое оформление (ни цвета, ни шрифта) ячейку не перекрашивает: иначе брался бы
 * {@code control.getBackground()} у {@code TableEx}, а он совпадает с фоном тулбара, не списка.
 */
final class ConditionalAppearanceCellStyle
{
    private static final String WIDGET_CLASS = "ConditionalAppearance"; //$NON-NLS-1$

    private static final String KEY_ATTACHED = "tormozit.dcsAppearanceStyle.attached"; //$NON-NLS-1$

    /** Ключ JFace: виджет колонки хранит свой {@link ViewerColumn} ({@code ViewerColumn.COLUMN_VIEWER_KEY}). */
    private static final String COLUMN_VIEWER_KEY = "org.eclipse.jface.columnViewer"; //$NON-NLS-1$

    private static final String MESSAGES_CLASS = "com._1c.g5.v8.dt.dcs.ui.Messages"; //$NON-NLS-1$

    private static final String APPEARANCE_TITLE_FIELD = "Dcs_Appearance"; //$NON-NLS-1$

    /** Запасные заголовки колонки, если NLS-класс недоступен. */
    private static final String[] APPEARANCE_TITLES = { "Оформление", "Appearance" }; //$NON-NLS-1$ //$NON-NLS-2$

    private static final int MAX_DEPTH = 12;

    /** Подсказка заголовка колонки «Оформление». */
    private static final String TOOLTIP = "Ячейка нарисована оформлением своей строки:"
        + " цвет фона, цвет текста и шрифт." + " Если текст сливается с фоном, он рисуется чёрным"
        + " или белым — по яркости фона." + " Пустое оформление не перекрашивает ячейку."
        + Global.pluginSignForTooltip();

    /** Ниже этого отношения яркостей текст считается нечитаемым на своём фоне. */
    private static final double MIN_CONTRAST = 2.0;

    private static final RGB BLACK = new RGB(0, 0, 0);

    private static final RGB WHITE = new RGB(255, 255, 255);

    /** Имена параметров оформления в обоих вариантах написания. */
    private static final String[] TEXT_COLOR_NAMES = { "TextColor", "ЦветТекста" }; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String[] BACK_COLOR_NAMES = { "BackColor", "ЦветФона" }; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String[] FONT_NAMES = { "Font", "Шрифт" }; //$NON-NLS-1$ //$NON-NLS-2$

    private ConditionalAppearanceCellStyle()
    {
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, ConditionalAppearanceCellStyle::onShow);
    }

    private static void onShow(Event event)
    {
        if (!(event.widget instanceof Composite composite) || composite.isDisposed())
            return;
        composite.getDisplay().asyncExec(() -> {
            if (!composite.isDisposed())
                attach(composite, 0);
        });
    }

    /** Красит колонку «Оформление» во всех списках условного оформления поддерева. */
    static void attach(Composite root)
    {
        attach(root, 0);
    }

    private static void attach(Composite root, int depth)
    {
        if (root == null || root.isDisposed() || depth > MAX_DEPTH)
            return;
        if (WIDGET_CLASS.equals(root.getClass().getSimpleName()))
        {
            attachToWidget(root);
            return;
        }
        for (Control child : root.getChildren())
        {
            if (child instanceof Composite composite)
                attach(composite, depth + 1);
        }
    }

    /** @param widget виджет {@code ConditionalAppearance} */
    static void attachToWidget(Composite widget)
    {
        try
        {
            if (widget == null || widget.isDisposed() || widget.getData(KEY_ATTACHED) != null)
                return;
            if (!(Global.invoke(widget, "getViewer") instanceof ColumnViewer viewer)) //$NON-NLS-1$
                return;
            Control control = viewer.getControl();
            Object column = appearanceColumn(viewer);
            if (control == null || control.isDisposed() || column == null)
                return;
            Object viewerColumn = Global.invoke(column, "getData", COLUMN_VIEWER_KEY); //$NON-NLS-1$
            if (!(viewerColumn instanceof ViewerColumn))
                return;
            widget.setData(KEY_ATTACHED, Boolean.TRUE);

            Resources resources = new Resources();
            control.addListener(SWT.Dispose, event -> resources.dispose());

            Object base = Global.invoke(viewerColumn, "getLabelProvider"); //$NON-NLS-1$
            CellLabelProvider styled = new StyledCellLabelProvider(
                base instanceof CellLabelProvider provider ? provider : null, resources, control);
            // Двухаргументный setLabelProvider (пакетный) — чтобы штатный провайдер НЕ был
            // освобождён: обёртка продолжает им пользоваться. Публичный однааргументный вариант
            // вызывает у прежнего провайдера dispose().
            Global.invoke(viewerColumn, "setLabelProvider", styled, Boolean.FALSE); //$NON-NLS-1$
            setColumnTooltip(viewer, column);
            viewer.refresh();
        }
        catch (Exception e)
        {
            Global.logError("ConditionalAppearanceCellStyle", "attach", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Подсказка заголовка колонки. Своего API подсказок у {@code TableExColumn} нет: шапка — это
     * отдельный Nebula {@code Grid}, а колонка хранит адрес своей ячейки шапки
     * ({@code getPoint()}: {@code y} — строка, {@code x} — колонка грида), тем же адресом
     * {@code TableEx.setColumnText} пишет её заголовок.
     */
    private static void setColumnTooltip(ColumnViewer viewer, Object column)
    {
        Object table = Global.invoke(viewer, "getTable"); //$NON-NLS-1$
        Object header = table != null ? Global.invoke(table, "getHeaderControl") : null; //$NON-NLS-1$
        Object point = Global.invoke(column, "getPoint"); //$NON-NLS-1$
        if (header == null || !(point instanceof org.eclipse.swt.graphics.Point cell)
            || cell.x < 0 || cell.y < 0)
            return;
        Object item = Global.invoke(header, "getItem", Integer.valueOf(cell.y)); //$NON-NLS-1$
        if (item == null)
            return;
        Control control = viewer.getControl();
        String tooltip = control != null && !control.isDisposed() ? TooltipText.wrap(control, TOOLTIP)
            : TOOLTIP;
        Global.invoke(item, "setToolTipText", Integer.valueOf(cell.x), tooltip); //$NON-NLS-1$
    }

    /** Колонка «Оформление» списка ({@code TableExColumn}) — по её заголовку. */
    private static Object appearanceColumn(ColumnViewer viewer)
    {
        Object table = Global.invoke(viewer, "getTable"); //$NON-NLS-1$
        Object count = table != null ? Global.invoke(table, "getColumnCount") : null; //$NON-NLS-1$
        if (!(count instanceof Integer columnCount))
            return null;
        String title = appearanceTitle();
        for (int i = 0; i < columnCount.intValue(); i++)
        {
            Object column = Global.invoke(table, "getColumn", Integer.valueOf(i)); //$NON-NLS-1$
            Object text = column != null ? Global.invoke(column, "getText") : null; //$NON-NLS-1$
            if (!(text instanceof String columnTitle))
                continue;
            if (columnTitle.equals(title) || matches(columnTitle, APPEARANCE_TITLES))
                return column;
        }
        return null;
    }

    /** Заголовок колонки берётся из NLS самого EDT — он локализован. */
    private static String appearanceTitle()
    {
        try
        {
            Class<?> messages = DcsUiUtil.class.getClassLoader().loadClass(MESSAGES_CLASS);
            java.lang.reflect.Field field = messages.getDeclaredField(APPEARANCE_TITLE_FIELD);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String title ? title : null;
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            return null;
        }
    }

    // =========================================================================
    // Значения оформления
    // =========================================================================

    private static DataCompositionAppearance appearanceOf(Object element)
    {
        return element instanceof DataCompositionConditionalAppearanceItem item ? item.getAppearance()
            : null;
    }

    /** Значение параметра оформления по имени; {@code null} — параметр не задан или выключен. */
    private static Value parameterValue(DataCompositionAppearance appearance, String[] names)
    {
        if (appearance == null)
            return null;
        for (DataCompositionParameterValue value : appearance.getItems())
        {
            if (value == null || !value.isUse())
                continue;
            DataCompositionParameter parameter = value.getParameter();
            String name = parameter != null ? parameter.getValue() : null;
            if (name == null || !matches(name, names) || value.getValues().isEmpty())
                continue;
            return value.getValues().get(0);
        }
        return null;
    }

    private static boolean matches(String name, String[] names)
    {
        for (String candidate : names)
        {
            if (candidate.equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    private static RGB rgb(Value value)
    {
        if (!(value instanceof ColorValue colorValue))
            return null;
        com._1c.g5.v8.dt.mcore.Color color = colorValue.getValue();
        return color != null ? new RGB(color.red(), color.green(), color.blue()) : null;
    }

    private static FontData fontData(Value value, FontData template)
    {
        if (!(value instanceof FontValue fontValue))
            return null;
        com._1c.g5.v8.dt.mcore.Font font = fontValue.getValue();
        if (font == null)
            return null;
        String name = font.faceName() != null && !font.faceName().isBlank() ? font.faceName()
            : template.getName();
        int height = font.height() > 0 ? font.height() : template.getHeight();
        int style = (font.bold() ? SWT.BOLD : SWT.NORMAL) | (font.italic() ? SWT.ITALIC : SWT.NORMAL);
        return new FontData(name, height, style);
    }

    // =========================================================================
    // Читаемость
    // =========================================================================

    /**
     * Цвет текста, который точно будет виден на этом фоне: заданный — если он достаточно
     * контрастен, иначе чёрный или белый по яркости фона.
     */
    private static RGB readableForeground(RGB text, RGB background)
    {
        if (text != null && contrast(text, background) >= MIN_CONTRAST)
            return text;
        return luminance(background) > 0.5 ? BLACK : WHITE;
    }

    private static double contrast(RGB first, RGB second)
    {
        double one = luminance(first) + 0.05;
        double two = luminance(second) + 0.05;
        return one > two ? one / two : two / one;
    }

    /** Относительная яркость по WCAG. */
    private static double luminance(RGB rgb)
    {
        return 0.2126 * channel(rgb.red) + 0.7152 * channel(rgb.green) + 0.0722 * channel(rgb.blue);
    }

    private static double channel(int value)
    {
        double normalized = value / 255.0;
        return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    // =========================================================================
    // Провайдер ячеек
    // =========================================================================

    /** Штатное содержимое ячейки плюс цвета и шрифт её оформления. */
    private static final class StyledCellLabelProvider
        extends CellLabelProvider
    {
        private final CellLabelProvider base;

        private final Resources resources;

        private final Control control;

        StyledCellLabelProvider(CellLabelProvider base, Resources resources, Control control)
        {
            this.base = base;
            this.resources = resources;
            this.control = control;
        }

        @Override
        public void update(ViewerCell cell)
        {
            if (base != null)
                base.update(cell);
            if (control.isDisposed())
                return;
            DataCompositionAppearance appearance = appearanceOf(cell.getElement());
            RGB back = rgb(parameterValue(appearance, BACK_COLOR_NAMES));
            RGB text = rgb(parameterValue(appearance, TEXT_COLOR_NAMES));
            FontData font = fontData(parameterValue(appearance, FONT_NAMES),
                control.getFont().getFontData()[0]);
            // Без своего цвета фона ячейку не красим: control.getBackground() у TableEx —
            // фон родителя (тулбар), а не строки списка.
            if (back != null)
            {
                cell.setBackground(resources.color(back));
                cell.setForeground(resources.color(readableForeground(text, back)));
            }
            else if (text != null)
            {
                cell.setBackground(null);
                RGB listBackground = control.getDisplay()
                    .getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
                cell.setForeground(resources.color(readableForeground(text, listBackground)));
            }
            else
            {
                cell.setBackground(null);
                cell.setForeground(null);
            }
            cell.setFont(font != null ? resources.font(font) : null);
        }

        @Override
        public String getToolTipText(Object element)
        {
            return base != null ? base.getToolTipText(element) : null;
        }
    }

    /** Цвета и шрифты колонки; живут столько же, сколько список. */
    private static final class Resources
    {
        private final Map<RGB, Color> colors = new HashMap<>();

        private final Map<FontData, Font> fonts = new HashMap<>();

        Color color(RGB rgb)
        {
            if (rgb == null)
                return null;
            return colors.computeIfAbsent(rgb, key -> new Color(Display.getCurrent(), key));
        }

        Font font(FontData data)
        {
            Display display = Display.getCurrent();
            if (data == null || display == null)
                return null;
            return fonts.computeIfAbsent(data, key -> new Font(display, key));
        }

        void dispose()
        {
            colors.values().forEach(Color::dispose);
            colors.clear();
            fonts.values().forEach(Font::dispose);
            fonts.clear();
        }
    }
}
