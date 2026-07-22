package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import java.net.URL;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ScrollBar;
import org.osgi.framework.Bundle;

/**
 * Панель «Текущая строка» под панелями сравнения текста — по одной подписанной
 * строке {@link StyledText} на каждую сторону сравнения, с общей горизонтальной
 * прокруткой (полоса видна только у последней/нижней строки, остальные скрыты,
 * но синхронно прокручиваются) и посимвольной раскраской различий через
 * {@link CompareCurrentLineDiff}.
 *
 * <p>Используется и в модальном «Вставить со сравнением»
 * ({@link PasteWithCompareActions}, две строки), и в окнах трёхстороннего
 * слияния 1C:EDT ({@link ThreeSideMergeCurrentLinesHook}, три строки).
 */
public final class CompareCurrentLinesPanel
{
    /** Отступ слева от первого различия при автопрокрутке — виден небольшой контекст. */
    private static final int SCROLL_MARGIN_PIXELS = 20;
    /** Композит панели — всегда 2 колонки (подпись, поле), независимо от числа строк. */
    private static final int LAYOUT_COLUMNS = 2;

    private static final String TOGGLE_LABEL = "Текущие строки"; //$NON-NLS-1$
    private static final String TOGGLE_TOOLTIP =
        "Показывать/скрывать область сравнения текущих строк"; //$NON-NLS-1$
    /** Две приглушённые строки-соседи и текущая строка на всю ширину с кареткой слева. */
    private static final String TOGGLE_ICON_PATH = "icons/etool16/current_line_toggle.png"; //$NON-NLS-1$

    private final Composite composite;
    private final StyledText[] rows;
    private final Label[] labelWidgets;

    /** Поставщик полных текстов для кнопки «Сравнить ИР» — {@code null}, пока не задан вызывающей стороной. */
    private Supplier<FullTextPair> compareInIrSupplier;

    /**
     * Цвета различий — как у двухстороннего {@code TextMergeViewer} в режимах EGit
     * (Local/Index, коммит/предыдущий): слева зелёное, справа красное.
     * {@code SPACE} — отдельный синий (заполнитель напротив символа другой стороны).
     */
    private Color leftDiffBackground;
    private Color leftDiffForeground;
    private Color rightDiffBackground;
    private Color rightDiffForeground;
    private Color spaceBackground;
    private Color spaceForeground;

    /**
     * {@code true} (по умолчанию) — как 2-way EGit TextMergeViewer: уникальность слева
     * (DELETE) зелёная, справа (INSERT) красная.
     * {@code false} — классика добавление/удаление: INSERT зелёный, DELETE красный
     * (трёхстороннее слияние: добавления в результат зелёные, см.
     * {@link ThreeSideMergeCurrentLinesHook}).
     */
    private boolean sideAlignedDiffColors = true;

    private CompareCurrentLinesPanel(Composite composite, StyledText[] rows, Label[] labelWidgets)
    {
        this.composite = composite;
        this.rows = rows;
        this.labelWidgets = labelWidgets;
    }

    /**
     * Строит панель — {@code labels.length} подписанных строк — как дочерний
     * {@link Composite} для {@code parent}. Полоса горизонтальной прокрутки
     * видна только у последней строки; её перемещение синхронно прокручивает
     * остальные.
     */
    public static CompareCurrentLinesPanel create(Composite parent, String... labels)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(LAYOUT_COLUMNS, false);
        layout.marginWidth = 4;
        layout.marginHeight = 2;
        layout.horizontalSpacing = 6;
        layout.verticalSpacing = 2;
        composite.setLayout(layout);

        CompareCurrentLinesPanel panel = new CompareCurrentLinesPanel(
            composite, new StyledText[labels.length], new Label[labels.length]);
        panel.createDiffColors(composite.getDisplay());
        composite.addDisposeListener(e -> panel.disposeDiffColors());

        for (int i = 0; i < labels.length; i++)
        {
            Label label = new Label(composite, SWT.NONE);
            label.setText(labels[i]);
            panel.labelWidgets[i] = label;
            boolean isLastRow = i == labels.length - 1;
            panel.rows[i] = createRowWidget(composite, isLastRow);
        }

        panel.wireSharedScroll();
        return panel;
    }

    /**
     * Поставщик полных текстов и заголовка окна для кнопки «Сравнить ИР» — вызывается
     * при нажатии. Возвращает {@code null} (или {@code null} внутри {@link FullTextPair}),
     * если сравнивать сейчас нечего.
     */
    public void setCompareInIrSupplier(Supplier<FullTextPair> supplier)
    {
        this.compareInIrSupplier = supplier;
    }

    /**
     * Режим раскраски различий.
     *
     * @param sideAligned {@code true} — слева зелёное / справа красное (2-way EGit);
     *                    {@code false} — INSERT зелёный / DELETE красный (3-way слияние)
     */
    public void setSideAlignedDiffColors(boolean sideAligned)
    {
        this.sideAlignedDiffColors = sideAligned;
    }

    public String getLabelText(int rowIndex)
    {
        Label label = labelWidgets[rowIndex];
        return label != null && !label.isDisposed() ? label.getText() : ""; //$NON-NLS-1$
    }

    /**
     * Пара полных текстов сторон сравнения (не текущих строк), заголовок окна ИР
     * и номер текущей строки (под кареткой) в каждом поле — ИР сразу прокрутит
     * форму сравнения к этому месту.
     */
    public static final class FullTextPair
    {
        public final String left;
        public final String right;
        public final String title;
        /** Подписи сторон (Название1/2 в форме ИР) — {@code null}, если не заданы вызывающей стороной. */
        public final String leftLabel;
        public final String rightLabel;
        /** {@code ВариантСинтаксиса} для ИР — см. {@link IrCompareValuesHandler#syntaxVariantFor}. */
        public final String syntaxVariant;
        public final int leftLine;
        public final int rightLine;

        public FullTextPair(String left, String right, String title, String leftLabel, String rightLabel,
            String syntaxVariant, int leftLine, int rightLine)
        {
            this.left = left;
            this.right = right;
            this.title = title;
            this.leftLabel = leftLabel;
            this.rightLabel = rightLabel;
            this.syntaxVariant = syntaxVariant;
            this.leftLine = leftLine;
            this.rightLine = rightLine;
        }
    }

    /**
     * Вызывает «Сравнить ИР» с полными текстами сторон (не текущих строк) из
     * {@link #compareInIrSupplier}. Кнопку/пункт меню, вызывающие этот метод, размещает
     * вызывающая сторона — в верхней командной панели просмотрщика сравнения
     * ({@link PasteWithCompareActions}, {@link GitCompareCurrentLinesHook},
     * {@link ThreeSideMergeCurrentLinesHook}), а не в этой панели.
     */
    public void triggerCompareInIr()
    {
        if (compareInIrSupplier == null)
            return;
        FullTextPair pair = compareInIrSupplier.get();
        if (pair == null || pair.left == null || pair.right == null)
            return;
        IrCompareValuesHandler.compare(
            pair.left, pair.right, pair.title, pair.leftLabel, pair.rightLabel,
            pair.syntaxVariant, pair.leftLine, pair.rightLine);
    }

    public Composite getControl()
    {
        return composite;
    }

    /**
     * Показывает/скрывает панель — {@code exclude} в {@link GridData}, чтобы скрытая
     * панель не резервировала место в layout родителя (просто {@code setVisible(false)}
     * оставил бы пустое место). Требует, чтобы {@code composite.getLayoutData()} уже был
     * {@link GridData} (выставляется вызывающей стороной сразу после {@link #create}).
     */
    public void setVisible(boolean visible)
    {
        if (composite.isDisposed())
            return;
        composite.setVisible(visible);
        if (composite.getLayoutData() instanceof GridData gridData)
        {
            gridData.exclude = !visible;
            Composite parent = composite.getParent();
            if (parent != null && !parent.isDisposed())
                parent.layout(true, true);
        }
    }

    /**
     * Кнопка-переключатель показа этой панели в командной панели просмотрщика сравнения
     * (рядом с «Сравнить ИР») — общая настройка на все окна сравнения, запоминается между
     * запусками EDT ({@link ComfortSettings#isCompareCurrentLinesVisible}). Сразу применяет
     * сохранённое состояние к панели.
     */
    public Action createVisibilityToggleAction()
    {
        Action action = new Action("", IAction.AS_CHECK_BOX) //$NON-NLS-1$
        {
            @Override
            public void run()
            {
                boolean visible = isChecked();
                ComfortSettings.setCompareCurrentLinesVisible(visible);
                setVisible(visible);
            }
        };
        action.setImageDescriptor(toggleIconDescriptor());
        action.setToolTipText(TOGGLE_LABEL + " — " + TOGGLE_TOOLTIP + Global.pluginSignForTooltip()); //$NON-NLS-1$
        action.setChecked(ComfortSettings.isCompareCurrentLinesVisible());
        setVisible(action.isChecked());
        return action;
    }

    /**
     * {@code AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, ...)} не подходит:
     * {@code PLUGIN_ID = "tormozit"} — внутренний ключ preference store, а не
     * {@code Bundle-SymbolicName} (в MANIFEST.MF это {@code tormozit.comfort}) — OSGi не находит
     * бандл по неверному имени и молча возвращает пустой дескриптор (значок был невидимым).
     * Берём бандл напрямую через {@link Activator#getDefault()} — тот же приём, что и в
     * {@code BslInspectSupport.loadInspectCommandImage}.
     */
    private static ImageDescriptor toggleIconDescriptor()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return ImageDescriptor.getMissingImageDescriptor();
        Bundle bundle = activator.getBundle();
        if (bundle == null)
            return ImageDescriptor.getMissingImageDescriptor();
        URL url = bundle.getEntry(TOGGLE_ICON_PATH);
        return url != null ? ImageDescriptor.createFromURL(url) : ImageDescriptor.getMissingImageDescriptor();
    }

    public StyledText getRow(int index)
    {
        return rows[index];
    }

    public int getRowCount()
    {
        return rows.length;
    }

    /** Меняет подпись строки — например, при обновлении заголовков сторон в редакторе слияния. */
    public void setLabelText(int rowIndex, String text)
    {
        Label label = labelWidgets[rowIndex];
        if (label == null || label.isDisposed() || text == null)
            return;
        if (!text.equals(label.getText()))
            label.setText(text);
    }

    /** Раскрашивает пару строк через посимвольное выравнивание и возвращает результат выравнивания. */
    public CompareCurrentLineDiff.AlignedResult renderPair(int rowA, int rowB, String textA, String textB)
    {
        CompareCurrentLineDiff.AlignedResult aligned = CompareCurrentLineDiff.align(textA, textB);
        applyStyledLine(rows[rowA], aligned.left, aligned.leftTypes);
        applyStyledLine(rows[rowB], aligned.right, aligned.rightTypes);
        return aligned;
    }

    /** Выводит обычный (нераскрашенный) текст в строку — например, для несопоставленной стороны. */
    public void renderPlain(int rowIndex, String text)
    {
        StyledText widget = rows[rowIndex];
        if (widget == null || widget.isDisposed())
            return;
        widget.setText(text != null ? text : ""); //$NON-NLS-1$
    }

    /** Сбрасывает горизонтальную прокрутку всех строк в начало. */
    public void resetScroll()
    {
        setHorizontalPixelAll(0);
    }

    /**
     * Прокручивает все строки так, чтобы был виден первый различающийся символ
     * в {@code referenceWidget} — но только если он целиком не помещается в
     * видимую область после сброса прокрутки в начало.
     */
    public void scrollToFirstDifference(StyledText referenceWidget, CompareCurrentLineDiff.CharType[] types)
    {
        if (referenceWidget == null || referenceWidget.isDisposed())
            return;

        resetScroll();

        int firstDiffIndex = -1;
        for (int i = 0; i < types.length; i++)
        {
            if (types[i] != CompareCurrentLineDiff.CharType.COMMON)
            {
                firstDiffIndex = i;
                break;
            }
        }
        if (firstDiffIndex < 0)
            return;

        Rectangle bounds = referenceWidget.getTextBounds(firstDiffIndex, firstDiffIndex);
        int clientWidth = referenceWidget.getClientArea().width;
        if (bounds.x >= 0 && bounds.x + bounds.width <= clientWidth)
            return; // уже полностью видно при прокрутке в начало

        setHorizontalPixelAll(Math.max(0, bounds.x - SCROLL_MARGIN_PIXELS));
    }

    private void setHorizontalPixelAll(int pixel)
    {
        for (StyledText row : rows)
        {
            if (row != null && !row.isDisposed())
                row.setHorizontalPixel(pixel);
        }
    }

    private void wireSharedScroll()
    {
        StyledText master = rows[rows.length - 1];
        ScrollBar masterBar = master.getHorizontalBar();
        if (masterBar == null)
            return;

        /*
         * ScrollBar.setSelection (используется в setHorizontalPixelAll для программной
         * прокрутки, например при автопрокрутке к первому различию) не порождает
         * событие Selection — программная синхронизация не зациклится через этот слушатель.
         */
        masterBar.addListener(SWT.Selection, e ->
        {
            int pixel = master.getHorizontalPixel();
            for (StyledText row : rows)
            {
                if (row != master && !row.isDisposed())
                    row.setHorizontalPixel(pixel);
            }
        });
    }

    /**
     * У StyledText со стилем {@code SWT.H_SCROLL} {@code computeTrim()} резервирует
     * место под горизонтальную полосу, даже если сам {@code ScrollBar} скрыт через
     * {@code setVisible(false)} — стиль остаётся на контроле, и высота однострочного
     * поля становится больше, чем нужно (пустое место снизу). Поэтому полосу не
     * скрываем — её просто не создаём: {@code H_SCROLL} добавляется только строке
     * с видимой полосой. Программная прокрутка ({@code setHorizontalPixel}) не зависит
     * от стиля {@code H_SCROLL} и работает у всех строк одинаково.
     */
    private static StyledText createRowWidget(Composite parent, boolean showScrollbar)
    {
        int style = SWT.BORDER | (showScrollbar ? SWT.H_SCROLL : SWT.NONE);
        StyledText widget = new StyledText(parent, style);
        widget.setEditable(false);
        widget.setCaret(null);
        widget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return widget;
    }

    private void createDiffColors(Display display)
    {
        // Слева — зелёное (как ADDITION в TextMergeViewer), справа — красное (DELETION).
        leftDiffBackground = new Color(display, 216, 255, 216);
        leftDiffForeground = new Color(display, ThemeAwareColors.toEffectiveRgb(new RGB(46, 125, 50)));
        rightDiffBackground = new Color(display, 255, 224, 224);
        rightDiffForeground = new Color(display, ThemeAwareColors.toEffectiveRgb(new RGB(198, 40, 40)));
        spaceBackground = new Color(display, 224, 224, 255);
        spaceForeground = new Color(display, ThemeAwareColors.toEffectiveRgb(new RGB(21, 101, 192)));
    }

    private void disposeDiffColors()
    {
        for (Color color : new Color[] { leftDiffBackground, leftDiffForeground,
            rightDiffBackground, rightDiffForeground, spaceBackground, spaceForeground })
        {
            if (color != null && !color.isDisposed())
                color.dispose();
        }
    }

    private void applyStyledLine(StyledText widget, String text, CompareCurrentLineDiff.CharType[] types)
    {
        if (widget == null || widget.isDisposed())
            return;
        widget.setText(text);

        List<StyleRange> ranges = new ArrayList<>();
        int i = 0;
        while (i < types.length)
        {
            CompareCurrentLineDiff.CharType type = types[i];
            int start = i;
            while (i < types.length && types[i] == type)
                i++;
            if (type != CompareCurrentLineDiff.CharType.COMMON)
                ranges.add(styleRangeFor(type, start, i - start));
        }
        widget.setStyleRanges(ranges.toArray(new StyleRange[0]));
    }

    private StyleRange styleRangeFor(CompareCurrentLineDiff.CharType type, int start, int length)
    {
        StyleRange range = new StyleRange();
        range.start = start;
        range.length = length;
        switch (type)
        {
        case DELETE: // символ только в первой строке пары (align left)
            if (sideAlignedDiffColors)
            {
                range.background = leftDiffBackground;
                range.foreground = leftDiffForeground;
            }
            else
            {
                range.background = rightDiffBackground;
                range.foreground = rightDiffForeground;
            }
            break;
        case INSERT: // символ только во второй строке пары (align right)
            if (sideAlignedDiffColors)
            {
                range.background = rightDiffBackground;
                range.foreground = rightDiffForeground;
            }
            else
            {
                range.background = leftDiffBackground;
                range.foreground = leftDiffForeground;
            }
            break;
        case SPACE:
            range.background = spaceBackground;
            range.foreground = spaceForeground;
            break;
        default:
            break;
        }
        return range;
    }
}