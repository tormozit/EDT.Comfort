package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Фон выделенной строки Table/Tree в тёмной теме: смещения от фона списка
 * (focused — голубоватый, unfocused — нейтрально светлее). Светлая тема — {@code null}.
 */
public final class ListSelectionThemeColors
{
  public static final String OPT_OUT_KEY = "tormozit.listSelectionThemeOptOut"; //$NON-NLS-1$
  private static final String PRE_PAINT_FILTER_KEY = "tormozit.listSelectionPrePaintFilter"; //$NON-NLS-1$

  /** Фон ячейки выделенной строки; {@code null} — не красить. */
  @FunctionalInterface
  public interface SelectionCellBackground
  {
    Color cellBackground(Table table, TableItem item, int column);
  }

  private static final double FOCUSED_T = 0.34;
  private static final double UNFOCUSED_T = 0.40;
  private static final double INACTIVE_MULTI_T = 0.16;
  private static final int FOCUSED_BLUE_EXTRA = 14;
  private static final int UNFOCUSED_BLUE_EXTRA = 14;
  /** Тёмная тема: фиксированные цвета match — контрастны и к фону списка, и к selection. */
  private static final RGB DARK_MATCH_BG = new RGB(170, 205, 255);
  private static final RGB DARK_MATCH_FG = new RGB(18, 32, 68);
  private static final double ACTIVE_CELL_EXTRA_T = 0.06;

  private ListSelectionThemeColors() {}

  public static void markOptOut(Control list)
  {
    if (list != null && !list.isDisposed())
      list.setData(OPT_OUT_KEY, Boolean.TRUE);
  }

  public static boolean isOptOut(Control list)
  {
    return list != null && Boolean.TRUE.equals(list.getData(OPT_OUT_KEY));
  }

  /** Тёмный список — по фактическому фону контрола, не по id темы Eclipse. */
  public static boolean isDarkList(Control list)
  {
    if (list == null || list.isDisposed())
      return false;
    return isDarkRgb(listBackgroundRgb(list));
  }

  static boolean isDarkColor(Color color)
  {
    if (color == null || color.isDisposed())
      return false;
    return isDarkRgb(color.getRGB());
  }

  private static boolean isDarkRgb(RGB rgb)
  {
    if (rgb == null)
      return false;
    int lum = (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
    return lum < 128;
  }

  /**
   * @return цвет фона selection или {@code null} — штатный SWT (светлая тема).
   */
  public static Color listSelectionBackground(Control list, boolean listFocused)
  {
    if (!isDarkList(list))
      return null;
    RGB base = listBackgroundRgb(list);
    double t = listFocused ? FOCUSED_T : UNFOCUSED_T;
    if (listFocused)
      return color(list.getDisplay(), blueTintRgb(base, t, FOCUSED_BLUE_EXTRA));
    return color(list.getDisplay(), blueTintRgb(base, t, UNFOCUSED_BLUE_EXTRA));
  }

  /**
   * Текст выделенной строки в тёмной теме — светлый, контрастный к {@link #listSelectionBackground}.
   * @return {@code null} — штатный SWT (светлая тема).
   */
  public static Color listSelectionForeground(Control list)
  {
    if (!isDarkList(list))
      return null;
    Display display = list.getDisplay();
    Color fg = display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT);
    if (fg != null && !fg.isDisposed() && !isDarkRgb(fg.getRGB()))
      return fg;
    fg = list.getForeground();
    if (fg != null && !fg.isDisposed() && !isDarkRgb(fg.getRGB()))
      return fg;
    return color(display, new RGB(240, 240, 240));
  }

  /**
   * Заливка фона выделения до owner-draw (фильтр {@link SWT#PaintItem}).
   * Owner-draw таблицы игнорируют {@link TableItem#setBackground} и затирают {@link SWT#EraseItem}.
   */
  public static void installSelectionPrePaintFilter(Table table, SelectionCellBackground provider, String logKind)
  {
    if (table == null || table.isDisposed() || provider == null)
      return;
    if (table.getData(PRE_PAINT_FILTER_KEY) != null)
      return;
    Display display = table.getDisplay();
    Listener filter = event ->
    {
      if (event.widget != table || event.type != SWT.PaintItem || event.gc == null)
        return;
      if (!(event.item instanceof TableItem item) || item.isDisposed())
        return;
      if (!isDarkList(table))
        return;
      Color bg = provider.cellBackground(table, item, event.index);
      if (bg == null)
        return;
      fillSelectionBackground(event, table, item, bg);
      if (!bg.isDisposed())
        bg.dispose();
    };
    display.addFilter(SWT.PaintItem, filter);
    table.setData(PRE_PAINT_FILTER_KEY, filter);
    table.addDisposeListener(e ->
    {
      if (!display.isDisposed())
        display.removeFilter(SWT.PaintItem, filter);
      table.setData(PRE_PAINT_FILTER_KEY, null);
    });
  }

  /** Заливка фона выделения на всю ширину строки/колонки (не только ширину текста). */
  static void fillSelectionBackground(Event event, Table table, TableItem item, Color bg)
  {
    if (event == null || event.gc == null || bg == null || table == null || item == null)
      return;
    int col = event.index;
    Rectangle colBounds = item.getBounds(col);
    Rectangle rowBounds = item.getBounds();
    int x = colBounds != null && !colBounds.isEmpty() ? colBounds.x : event.x;
    int y = colBounds != null && !colBounds.isEmpty() ? colBounds.y : event.y;
    int h = colBounds != null && colBounds.height > 0 ? colBounds.height
        : rowBounds != null && rowBounds.height > 0 ? rowBounds.height : event.height;
    int w = colBounds != null && colBounds.width > 0 ? colBounds.width : event.width;
    int cols = Math.max(1, table.getColumnCount());
    if (cols == 1 || col == cols - 1)
    {
      int right = table.getClientArea().width;
      w = Math.max(w, right - x);
    }
    event.gc.setBackground(bg);
    event.gc.fillRectangle(x, y, w, h);
  }

  /** Слабее текущей строки при мультивыделении. */
  public static Color inactiveRowSelectionBackground(Control list, boolean listFocused)
  {
    if (!isDarkList(list))
      return null;
    RGB base = listBackgroundRgb(list);
    double t = listFocused ? INACTIVE_MULTI_T + 0.04 : INACTIVE_MULTI_T;
    return color(list.getDisplay(), neutralLightenRgb(base, t));
  }

  public static Color activeCellBackground(Control list, Color rowBg)
  {
    if (!isDarkList(list) || rowBg == null || rowBg.isDisposed())
      return rowBg;
    RGB row = rowBg.getRGB();
    if (list.isFocusControl())
      return color(list.getDisplay(), blueTintRgb(row, ACTIVE_CELL_EXTRA_T, 6));
    return color(list.getDisplay(), neutralLightenRgb(row, ACTIVE_CELL_EXTRA_T));
  }

  public static Color matchBackground(Color base)
  {
    if (base == null || base.isDisposed())
      return null;
    if (!isDarkColor(base))
      return null;
    return color(base.getDevice(), DARK_MATCH_BG);
  }

  public static Color matchForeground(Color base)
  {
    if (base == null || base.isDisposed())
      return null;
    if (!isDarkColor(base))
      return null;
    return color(base.getDevice(), DARK_MATCH_FG);
  }

  static RGB listBackgroundRgb(Control list)
  {
    Color base = list.getBackground();
    if (base == null || base.isDisposed())
      base = list.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
    return base.getRGB();
  }

  static RGB neutralLightenRgb(RGB base, double t)
  {
    return new RGB(
        lerp(base.red, 255, t),
        lerp(base.green, 255, t),
        lerp(base.blue, 255, t));
  }

  static RGB blueTintRgb(RGB base, double t, int blueExtra)
  {
    int r = lerp(base.red, 200, t * 0.6);
    int g = lerp(base.green, 220, t * 0.8);
    int b = clampChannel(lerp(base.blue, 255, t) + blueExtra);
    RGB result = new RGB(r, g, b);
    return result;
  }

  private static int lerp(int from, int to, double t)
  {
    return clampChannel((int) (from + (to - from) * t));
  }

  private static int clampChannel(int value)
  {
    return Math.max(0, Math.min(255, value));
  }

  private static Color color(Device device, RGB rgb)
  {
    Display display = device instanceof Display d ? d : Display.getDefault();
    return new Color(display, rgb);
  }
}
