/******************************************************************************
 * Copyright (c) 2006-2023 The IndentGuide Authors.
 * Copyright (c) 2026 EDT Comfort contributors.
 *
 * Adapted from net.certiv.tools.indentguide (MIT License):
 * https://opensource.org/licenses/MIT
 *****************************************************************************/
package tormozit;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.regex.Pattern;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IPaintPositionManager;
import org.eclipse.jface.text.IPainter;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

/**
 * Painter направляющих отступов — адаптация {@code GuidePainter} из IndentGuide.
 */
final class IndentGuidePainter implements IPainter, PaintListener
{
    private ITextViewer viewer;
    private StyledText widget;

    private boolean advanced;
    private IPreferenceStore store;

    private boolean active;
    private int lineAlpha;
    private int lineStyle;
    private int lineWidth;
    private int lineShift;
    private Color lineColor;
    private boolean drawLeadEdge;
    private boolean drawBlankLn;
    private boolean drawComment;

    private Line prevNb;
    private Line currLn;
    private Line nextNb;

    IndentGuidePainter(ITextViewer viewer)
    {
        this.viewer = viewer;
        widget = viewer.getTextWidget();
        advanced = setAdvanced(widget);
        ComfortSettings settings = ComfortSettings.getInstance();
        store = settings != null ? settings.getPreferenceStore() : null;
        loadPrefs();
    }

    @Override
    public void paint(int reason)
    {
        IDocument doc = viewer.getDocument();
        if (doc == null)
        {
            deactivate(false);
            return;
        }

        if (!active)
        {
            active = true;
            widget.addPaintListener(this);
            redrawAll();
        }
        else if (reason == CONFIGURATION || reason == INTERNAL)
        {
            redrawAll();
        }
        else if (reason == TEXT_CHANGE)
        {
            try
            {
                int caret = widget.getCaretOffset();
                int docOffset = docOffset(viewer, caret);
                if (docOffset < 0)
                    return;
                IRegion region = doc.getLineInformationOfOffset(docOffset);
                int offset = widgetOffset(viewer, region.getOffset());
                int cnt = widget.getCharCount();
                int len = Math.min(region.getLength(), cnt - offset);
                if (offset >= 0 && len > 0)
                    widget.redrawRange(offset, len, true);
            }
            catch (BadLocationException ignored)
            {
            }
        }
    }

    void redrawAll()
    {
        if (widget != null && !widget.isDisposed())
            widget.redraw();
    }

    @Override
    public void paintControl(PaintEvent evt)
    {
        if (widget != null)
            handleDrawRequest(evt.gc, evt.x, evt.y, evt.width, evt.height);
    }

    private void handleDrawRequest(GC gc, int x, int y, int w, int h)
    {
        int begLine = widget.getLineIndex(y);
        int endLine = widget.getLineIndex(y + h - 1);

        if (begLine <= endLine && begLine < widget.getLineCount())
        {
            Color color = gc.getForeground();
            LineAttributes attributes = gc.getLineAttributes();

            if (lineColor != null)
                gc.setForeground(lineColor);
            gc.setLineStyle(lineStyle);
            gc.setLineWidth(lineWidth);
            if (advanced)
            {
                int alpha = gc.getAlpha();
                gc.setAlpha(lineAlpha);
                drawLineRange(gc, begLine, endLine, x, w);
                gc.setAlpha(alpha);
            }
            else
            {
                drawLineRange(gc, begLine, endLine, x, w);
            }

            gc.setForeground(color);
            gc.setLineAttributes(attributes);
        }
    }

    private void drawLineRange(GC gc, int begLine, int endLine, int x, int w)
    {
        int tabWidth = widget.getTabs();
        StyledTextContent content = widget.getContent();

        prevNb = null;
        currLn = null;
        nextNb = null;

        for (int line = begLine; line <= endLine; line++)
        {
            int offset = widget.getOffsetAtLine(line);
            int height = widget.getLineHeight(offset);
            int spacing = widget.getLineSpacing();

            int docLine = content.getLineAtOffset(offset);

            if (!isFolded(viewer, docLine))
            {
                prevNb = prevNonblankLine(line, tabWidth);
                currLn = new Line(widget, line, tabWidth);

                if (drawBlankLn)
                {
                    if (currLn.blank)
                    {
                        nextNb = nextNonblankLine(line, tabWidth);
                        currLn.delta = nextNb.tabs() - prevNb.tabs();
                        currLn.stops.clear();
                        currLn.stops.addAll(prevNb.stops);
                        if (currLn.delta < 0 && currLn.tabs() > 1)
                            currLn.stops.removeLast();
                    }
                }

                boolean only = currLn.tabs(1);
                boolean multi = currLn.tabs() > 1;
                boolean zero = currLn.delta == 0;

                for (Pos stop : currLn.stops)
                {
                    boolean first = stop == currLn.stops.peekFirst();
                    boolean last = stop == currLn.stops.peekLast();

                    if (currLn.comment)
                    {
                        if (stop.col == currLn.beg)
                            continue;
                        if (only && !(drawComment || drawLeadEdge))
                            continue;
                        if (first && !only && !drawLeadEdge)
                            continue;
                        if (last && !only && !drawComment)
                            continue;
                    }
                    else if (currLn.blank)
                    {
                        if (first && only && zero)
                            continue;
                        if (last && !only && zero)
                            continue;
                        if (first && !zero && !(drawBlankLn && drawLeadEdge))
                            continue;
                        if (first && zero && multi && !(drawBlankLn && drawLeadEdge))
                            continue;
                    }
                    else
                    {
                        if (stop.col == currLn.beg)
                            continue;
                        if (first && !drawLeadEdge)
                            continue;
                    }

                    boolean asc = stop.col >= prevNb.endStop();
                    Point pos = widget.getLocationAtOffset(offset);
                    draw(gc, pos, stop.loc, spacing, height, asc);
                }
            }
        }
    }

    private void draw(GC gc, Point pos, int loc, int sp, int ht, boolean asc)
    {
        // loc — абсолютный X из getLocationAtOffset стопа (не дельта к началу строки)
        int x = loc + lineShift;
        if (asc)
            gc.drawLine(x, pos.y - sp, x, pos.y + ht + sp);
        else
            gc.drawLine(x, pos.y, x, pos.y + ht + sp);
    }

    private Line prevNonblankLine(int line, int tabWidth)
    {
        if (currLn != null && !currLn.blank)
            return currLn;
        if (prevNb != null && prevNb.line < line)
            return prevNb;

        for (int prev = line - 1; prev >= 0; prev--)
        {
            String text = widget.getLine(prev);
            if (!text.isBlank())
                return new Line(widget, prev, tabWidth);
        }
        return new Line(null, -1, tabWidth);
    }

    private Line nextNonblankLine(int line, int tabWidth)
    {
        int end = widget.getLineCount();
        if (nextNb != null && nextNb.line < end && nextNb.line > line)
            return nextNb;

        for (int next = line + 1; next < end; next++)
        {
            String text = widget.getLine(next);
            if (!text.isBlank())
                return new Line(widget, next, tabWidth);
        }
        return new Line(null, end, tabWidth);
    }

    void loadPrefs()
    {
        if (store == null)
            return;
        lineAlpha = store.getInt(ComfortSettings.PREF_INDENT_GUIDE_LINE_ALPHA);
        lineStyle = store.getInt(ComfortSettings.PREF_INDENT_GUIDE_LINE_STYLE);
        lineWidth = store.getInt(ComfortSettings.PREF_INDENT_GUIDE_LINE_WIDTH);
        lineShift = store.getInt(ComfortSettings.PREF_INDENT_GUIDE_LINE_SHIFT);

        disposeLineColor();
        lineColor = createLineColor(store);

        drawLeadEdge = store.getBoolean(ComfortSettings.PREF_INDENT_GUIDE_DRAW_LEAD_EDGE);
        drawBlankLn = store.getBoolean(ComfortSettings.PREF_INDENT_GUIDE_DRAW_BLANK_LINE);
        drawComment = store.getBoolean(ComfortSettings.PREF_INDENT_GUIDE_DRAW_COMMENT_BLOCK);
    }

    static Color createLineColor(IPreferenceStore store)
    {
        String key = ComfortSettings.indentGuideLineColorKey();
        String raw = store.getString(key);
        RGB rgb = StringConverter.asRGB(raw, new RGB(0, 0, 0));
        Display display = Display.getDefault();
        return new Color(display, rgb);
    }

    boolean isActive()
    {
        return active;
    }

    void activate(boolean redraw)
    {
        if (!active)
        {
            active = true;
            widget.addPaintListener(this);
            if (redraw)
                redrawAll();
        }
    }

    @Override
    public void deactivate(boolean redraw)
    {
        if (active)
        {
            active = false;
            if (widget != null && !widget.isDisposed())
                widget.removePaintListener(this);
            if (redraw)
                redrawAll();
        }
    }

    @Override
    public void dispose()
    {
        store = null;
        viewer = null;
        widget = null;
        disposeLineColor();
    }

    private void disposeLineColor()
    {
        if (lineColor != null)
        {
            lineColor.dispose();
            lineColor = null;
        }
    }

    @Override
    public void setPositionManager(IPaintPositionManager manager)
    {
    }

    static boolean setAdvanced(StyledText widget)
    {
        GC gc = new GC(widget);
        gc.setAdvanced(true);
        boolean adv = gc.getAdvanced();
        gc.dispose();
        return adv;
    }

    static boolean isFolded(ITextViewer viewer, int line)
    {
        if (viewer instanceof ITextViewerExtension5 ext)
        {
            int modelLine = ext.widgetLine2ModelLine(line);
            int widgetLine = ext.modelLine2WidgetLine(modelLine + 1);
            return widgetLine == -1;
        }
        return false;
    }

    static int widgetOffset(ITextViewer viewer, int offset)
    {
        if (viewer instanceof ITextViewerExtension5 view)
            return view.modelOffset2WidgetOffset(offset);
        IRegion visible = viewer.getVisibleRegion();
        int widgetOffset = offset - visible.getOffset();
        if (widgetOffset > visible.getLength())
            return -1;
        return widgetOffset;
    }

    static int docOffset(ITextViewer viewer, int offset)
    {
        if (viewer instanceof ITextViewerExtension5 view)
            return view.widgetOffset2ModelOffset(offset);
        IRegion visible = viewer.getVisibleRegion();
        if (offset > visible.getLength())
            return -1;
        return offset + visible.getOffset();
    }

    /** Позиция стопа отступа в строке. */
    private static final class Pos
    {
        static final Pos P0 = Pos.at(0, 0, 0, 1);

        final int idx;
        final int pos;
        final int col;
        final int loc;

        static Pos at(int idx, int pos, int col, int loc)
        {
            return new Pos(idx, pos, col, loc);
        }

        private Pos(int idx, int pos, int col, int loc)
        {
            this.idx = idx;
            this.pos = pos;
            this.col = col;
            this.loc = loc;
        }
    }

    /** Анализ ведущего отступа одной строки виджета. */
    private static final class Line implements Iterable<Pos>
    {
        private static final Pattern COMMENT = Pattern.compile(
            "^(?:\\h*(?:" //$NON-NLS-1$
                + "/\\*.*|" //$NON-NLS-1$
                + " \\*|" //$NON-NLS-1$
                + " \\* .*|" //$NON-NLS-1$
                + " \\*/.*|" //$NON-NLS-1$
                + " (?:\\*.*)?\\*/" //$NON-NLS-1$
                + "))$"); //$NON-NLS-1$

        final StyledText widget;
        final int line;
        final String txt;
        final int tabwidth;
        final boolean blank;
        final int length;
        final boolean comment;
        final LinkedList<Pos> stops = new LinkedList<>();
        final int beg;
        int delta;

        Line(StyledText widget, int line, int tabwidth)
        {
            this.widget = widget;
            this.line = line;
            this.txt = widget != null ? widget.getLine(line) : ""; //$NON-NLS-1$
            this.tabwidth = tabwidth;
            blank = this.txt.isBlank();
            length = this.txt.length();
            stops.add(Pos.P0);
            beg = process();
            comment = inBlockComment();
        }

        private int process()
        {
            int begCol = 0;
            for (int pos = 0, col = 0; pos < length; pos++)
            {
                int ch = txt.codePointAt(pos);
                switch (ch)
                {
                    case ' ':
                        begCol = col += Character.charCount(ch);
                        if (col % tabwidth == 0)
                            stops.add(pos(stops.size(), pos + 1, col));
                        break;
                    case '\t':
                        begCol = col += tabwidth - (col % tabwidth);
                        stops.add(pos(stops.size(), pos + 1, col));
                        break;
                    default:
                        begCol = col;
                        return begCol;
                }
            }
            return begCol;
        }

        private Pos pos(int idx, int pos, int col)
        {
            int offset = widget.getOffsetAtLine(line);
            Point loc = widget.getLocationAtOffset(offset + pos);
            return Pos.at(idx, pos, col, loc.x);
        }

        private boolean inBlockComment()
        {
            if (blank)
                return false;
            int pos = stops.peekLast().pos;
            String rem = txt.substring(pos);
            return COMMENT.matcher(rem).matches();
        }

        int tabs()
        {
            return stops.size();
        }

        boolean tabs(int cnt)
        {
            return stops.size() == cnt;
        }

        int endStop()
        {
            return !stops.isEmpty() ? stops.peekLast().col : 0;
        }

        @Override
        public Iterator<Pos> iterator()
        {
            return stops.iterator();
        }
    }
}
