package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Координатор UI-доработок палитры свойств.
 * Новые возможности — отдельные {@link PropertySheetUiFeature}, регистрация в {@link #features(Object)}.
 */
final class PropertySheetUiCoordinator
{
    private static final int MAX_SYNC_ATTEMPTS = 8;
    private static final int SYNC_DELAY_MS = 80;

    private static final Map<Object, PageSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PropertySheetUiCoordinator() {}

    static void scheduleSync(Object page, SmartMatcher matcher)
    {
        if (page == null || !isPageAlive(page))
            return;
        PageSession session = SESSIONS.computeIfAbsent(page, key -> new PageSession());
        session.pendingMatcher = matcher;
        session.syncAttempt = 0;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        cancelPendingTimer(session);
        session.pendingRunnable = () -> syncNow(page);
        PropertySheetDebug.uiVerbose("scheduleSync pattern=" + PropertySheetDebug.quote(matcher.fullPattern) //$NON-NLS-1$
                + " delay=" + SYNC_DELAY_MS + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        display.timerExec(SYNC_DELAY_MS, session.pendingRunnable);
    }

    /** Остановить отложенный sync для страницы (закрытие view / dispose). */
    static void cancelSync(Object page)
    {
        if (page == null)
            return;
        PageSession session = SESSIONS.remove(page);
        if (session == null)
            return;
        cancelPendingTimer(session);
        PropertySheetDebug.uiVerbose("sync CANCEL page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
    }

    /** Закрытие workbench window — снять все отложенные sync. */
    static void cancelAll()
    {
        synchronized (SESSIONS)
        {
            for (PageSession session : SESSIONS.values())
                cancelPendingTimer(session);
            SESSIONS.clear();
        }
        PropertySheetDebug.uiVerbose("sync CANCEL ALL"); //$NON-NLS-1$
    }

    /** Закрытие PropertySheet view — снять все «висящие» таймеры. */
    static void cancelForView(Object viewPart)
    {
        if (viewPart == null)
            return;
        Object page = Global.invoke(viewPart, "getCurrentPage"); //$NON-NLS-1$
        cancelSync(page);
        Object pageBook = Global.invoke(viewPart, "getPageBook"); //$NON-NLS-1$
        Object bookPage = pageBook != null ? Global.invoke(pageBook, "getCurrentPage") : null; //$NON-NLS-1$
        if (bookPage != null && bookPage != page)
            cancelSync(bookPage);
        purgeDeadSessions();
    }

    private static void purgeDeadSessions()
    {
        synchronized (SESSIONS)
        {
            SESSIONS.entrySet().removeIf(entry -> {
                if (isPageAlive(entry.getKey()))
                    return false;
                cancelPendingTimer(entry.getValue());
                PropertySheetDebug.uiVerbose("sync PURGE dead page=" + PropertySheetDebug.safe(entry.getKey())); //$NON-NLS-1$
                return true;
            });
        }
    }

    private static boolean isPageAlive(Object page)
    {
        if (page == null)
            return false;
        Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
        if (control instanceof org.eclipse.swt.widgets.Control)
            return !((org.eclipse.swt.widgets.Control) control).isDisposed();
        return true;
    }

    private static void cancelPendingTimer(PageSession session)
    {
        if (session == null || session.pendingRunnable == null)
            return;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(-1, session.pendingRunnable);
        session.pendingRunnable = null;
    }

    private static void syncNow(Object page)
    {
        PageSession session = SESSIONS.get(page);
        if (session == null)
            return;
        if (!isPageAlive(page))
        {
            PropertySheetDebug.uiVerbose("sync STOP page disposed"); //$NON-NLS-1$
            cancelSync(page);
            return;
        }
        SmartMatcher matcher = session.pendingMatcher != null
                ? session.pendingMatcher : new SmartMatcher(""); //$NON-NLS-1$
        PropertySheetDebug.uiVerbose("syncNow attempt=" + session.syncAttempt //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(matcher.fullPattern)); //$NON-NLS-1$
        PropertySheetUiContext ctx = PropertySheetUiContext.build(page, matcher);
        if (ctx == null)
        {
            if (session.syncAttempt >= MAX_SYNC_ATTEMPTS)
            {
        PropertySheetDebug.uiProblem("sync GIVE UP context=null attempt=" + session.syncAttempt); //$NON-NLS-1$
                cancelSync(page);
                return;
            }
            PropertySheetDebug.uiVerbose("sync WAIT context=null attempt=" + session.syncAttempt //$NON-NLS-1$
                    + "/" + MAX_SYNC_ATTEMPTS); //$NON-NLS-1$
            retryLater(page, session);
            return;
        }
        if (ctx.rows.isEmpty() && session.syncAttempt < MAX_SYNC_ATTEMPTS)
        {
            PropertySheetDebug.uiVerbose("sync WAIT rows=0 attempt=" + session.syncAttempt //$NON-NLS-1$
                    + "/" + MAX_SYNC_ATTEMPTS); //$NON-NLS-1$
            retryLater(page, session);
            return;
        }
        if (ctx.rows.isEmpty())
        {
            PropertySheetDebug.uiProblem("sync GIVE UP rows=0 attempt=" + session.syncAttempt); //$NON-NLS-1$
            cancelSync(page);
            return;
        }
        int expected = PropertySheetPaletteScanner.expectedLabelRows(ctx.scene);
        if (expected > ctx.rows.size())
            PropertySheetDebug.uiProblem("sync partial rows=" + ctx.rows.size() + " expected=" + expected); //$NON-NLS-1$ //$NON-NLS-2$
        applySync(ctx, session);
        session.syncAttempt = 0;
        session.lastContext = ctx;
    }

    private static void applySync(PropertySheetUiContext ctx, PageSession session)
    {
        if (session.selectedName != null || session.selectedView != null)
            restoreSelection(ctx, session);
        int expected = PropertySheetPaletteScanner.expectedLabelRows(ctx.scene);
        PropertySheetDebug.uiVerbose("sync APPLY rows=" + ctx.rows.size() //$NON-NLS-1$
                + " expected=" + expected //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(ctx.matcher.fullPattern) //$NON-NLS-1$
                + " features=" + session.features.size()); //$NON-NLS-1$
        for (PropertySheetUiFeature feature : session.features)
            feature.refresh(ctx);
        if (ctx.selectedRow() != null)
        {
            session.selectedName = ctx.selectedRow().propertyName;
            session.selectedView = ctx.selectedRow().lwtView;
        }
    }

    private static void retryLater(Object page, PageSession session)
    {
        session.syncAttempt++;
        if (!isPageAlive(page) || session.syncAttempt > MAX_SYNC_ATTEMPTS)
        {
            cancelSync(page);
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            cancelSync(page);
            return;
        }
        cancelPendingTimer(session);
        session.pendingRunnable = () -> syncNow(page);
        display.timerExec(SYNC_DELAY_MS, session.pendingRunnable);
    }

    private static void restoreSelection(PropertySheetUiContext ctx, PageSession session)
    {
        if (session.selectedName == null && session.selectedView == null)
            return;
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (session.selectedView != null && session.selectedView == row.lwtView)
            {
                ctx.setSelectedRow(row);
                PropertySheetDebug.uiVerbose("restoreSelection byView " //$NON-NLS-1$
                        + PropertySheetDebug.safe(session.selectedView));
                return;
            }
            if (session.selectedName != null && session.selectedName.equals(row.propertyName))
            {
                ctx.setSelectedRow(row);
                PropertySheetDebug.uiVerbose("restoreSelection " + PropertySheetDebug.quote(session.selectedName)); //$NON-NLS-1$
                return;
            }
        }
        PropertySheetDebug.uiVerbose("restoreSelection MISS " + PropertySheetDebug.quote(session.selectedName)); //$NON-NLS-1$
    }

    /** Точка расширения: добавить feature для конкретной страницы или глобально. */
    static List<PropertySheetUiFeature> features(Object page)
    {
        PageSession session = SESSIONS.get(page);
        return session != null ? session.features : defaultFeatures();
    }

    static List<PropertySheetUiFeature> defaultFeatures()
    {
        List<PropertySheetUiFeature> list = new ArrayList<>();
        list.add(new PropertySheetMatchHighlightFeature());
        list.add(new PropertySheetNameCopyFeature());
        list.add(new PropertySheetRowSelectionFeature());
        return list;
    }

    static void rememberSelection(Object page, PropertySheetPaletteRow row)
    {
        if (page == null || row == null)
            return;
        PageSession session = SESSIONS.get(page);
        if (session != null)
        {
            session.selectedName = row.propertyName;
            session.selectedView = row.lwtView;
        }
    }

    static PropertySheetUiContext lastContext(Object page)
    {
        if (page == null)
            return null;
        PageSession session = SESSIONS.get(page);
        return session != null ? session.lastContext : null;
    }

    static Object pageForControl(org.eclipse.swt.widgets.Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        synchronized (SESSIONS)
        {
            for (Map.Entry<Object, PageSession> entry : SESSIONS.entrySet())
            {
                PropertySheetUiContext ctx = entry.getValue().lastContext;
                if (ctx == null)
                    continue;
                for (PropertySheetPaletteRow row : ctx.rows)
                {
                    org.eclipse.swt.widgets.Control target =
                            PropertySheetRowSelectionFeature.interactionTarget(row);
                    if (target != null && !target.isDisposed()
                            && (control == target
                                    || (target instanceof org.eclipse.swt.widgets.Composite
                                            && isDescendant(control,
                                                    (org.eclipse.swt.widgets.Composite) target))
                                    || (control instanceof org.eclipse.swt.widgets.Composite
                                            && isDescendant(target,
                                                    (org.eclipse.swt.widgets.Composite) control))))
                        return entry.getKey();
                }
                org.eclipse.swt.widgets.Composite root = PropertySheetUiContext.findPaletteRoot(entry.getKey());
                if (root != null && !root.isDisposed() && isDescendant(control, root))
                    return entry.getKey();
                if (!ctx.rows.isEmpty())
                {
                    org.eclipse.swt.widgets.Control target =
                            PropertySheetRowSelectionFeature.interactionTarget(ctx.rows.get(0));
                    if (target != null && !target.isDisposed() && target.getShell() == control.getShell())
                        return entry.getKey();
                }
            }
        }
        return null;
    }

    static void handleRowClick(Object page, PropertySheetPaletteRow row)
    {
        if (page == null || row == null)
            return;
        PageSession session = SESSIONS.get(page);
        if (session == null || session.rowSelection == null)
            return;
        PropertySheetUiContext ctx = session.lastContext;
        if (ctx == null)
            return;
        session.rowSelection.selectRow(ctx, row);
    }

    static void showRowContextMenu(Object page, PropertySheetPaletteRow row,
            org.eclipse.swt.widgets.Control widget, org.eclipse.swt.graphics.Point displayPoint)
    {
        if (page == null || row == null || widget == null || widget.isDisposed())
            return;
        PageSession session = SESSIONS.get(page);
        if (session == null || session.nameCopy == null)
            return;
        session.nameCopy.showContextMenu(row, widget, displayPoint);
    }

    private static boolean isDescendant(org.eclipse.swt.widgets.Control control,
            org.eclipse.swt.widgets.Composite ancestor)
    {
        if (control == null || ancestor == null || control.isDisposed() || ancestor.isDisposed())
            return false;
        if (control == ancestor)
            return true;
        for (org.eclipse.swt.widgets.Composite p = control.getParent();
                p != null && !p.isDisposed(); p = p.getParent())
        {
            if (p == ancestor)
                return true;
        }
        return false;
    }

    private static final class PageSession
    {
        final PropertySheetMatchHighlightFeature matchHighlight = new PropertySheetMatchHighlightFeature();
        final PropertySheetNameCopyFeature nameCopy = new PropertySheetNameCopyFeature();
        final PropertySheetRowSelectionFeature rowSelection = new PropertySheetRowSelectionFeature();
        final List<PropertySheetUiFeature> features = featuresOf(
                matchHighlight, nameCopy, rowSelection);
        SmartMatcher pendingMatcher = new SmartMatcher(""); //$NON-NLS-1$
        Runnable pendingRunnable;
        String selectedName;
        Object selectedView;
        int syncAttempt;
        PropertySheetUiContext lastContext;
    }

    private static List<PropertySheetUiFeature> featuresOf(PropertySheetUiFeature... items)
    {
        List<PropertySheetUiFeature> list = new ArrayList<>();
        Collections.addAll(list, items);
        return list;
    }

    /**
     * Подсветка вхождений фильтра на имени свойства.
     * SWT {@link Label} — overlay; LWT field row — синий overlay с origin из LightLabel.
     */
    private static final class PropertySheetMatchHighlightFeature implements PropertySheetUiFeature
    {
        private static final String HOOK_KEY = "tormozit.ps.matchHighlight"; //$NON-NLS-1$
        private static final String LWT_HOOK_KEY = "tormozit.ps.lwtMatchHighlight"; //$NON-NLS-1$
        private static final String LWT_ROWS_KEY = "tormozit.ps.lwtMatchRows"; //$NON-NLS-1$

        private static final class LwtRowHighlight
        {
            SmartMatcher matcher;
            String text;
            String propertyName;
            Object light;
        }

        @Override
        public void refresh(PropertySheetUiContext ctx)
        {
            if (ctx == null)
                return;
            SmartMatcher matcher = ctx.matcher;
            purgeStaleHighlights(ctx);
            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                Control host = highlightHost(row);
                if (matcher.isEmpty)
                    clearHighlight(host, row.propertyName);
                else
                    decorateName(host, matcher, row.propertyName);
            }
            PropertySheetDebug.feature("highlight rows=" + ctx.rows.size() //$NON-NLS-1$
                    + " pattern=" + PropertySheetDebug.quote(matcher.fullPattern)); //$NON-NLS-1$
        }

        private static Control highlightHost(PropertySheetPaletteRow row)
        {
            Control name = row.nameControl;
            if (row.lwtView != null && name != null && !name.isDisposed()
                    && PropertySheetControlInterop.isLwtPaintHost(name))
                return name;
            if (name != null && !name.isDisposed() && hasDirectSwtNameText(name, row.propertyName))
                return name;
            if (row.rowComposite != null && !row.rowComposite.isDisposed())
                return row.rowComposite;
            return name;
        }

        private static boolean hasDirectSwtNameText(Control control, String propertyName)
        {
            if (control == null || control.isDisposed() || propertyName == null || propertyName.isEmpty())
                return false;
            if (control instanceof Label)
                return true;
            String visible = PropertySheetControlInterop.controlText(control);
            return propertyName.equals(visible);
        }

        private static void decorateName(Control control, SmartMatcher matcher, String propertyName)
        {
            if (control == null || control.isDisposed())
                return;
            if (PropertySheetUiContext.isFilterAreaControl(control))
                return;

            String text = resolveDisplayText(control, propertyName);
            if (text.isEmpty() || !matcher.matches(text))
            {
                clearHighlight(control, propertyName);
                return;
            }

            if (PropertySheetControlInterop.isLwtPaintHost(control) && !hasDirectSwtNameText(control, propertyName))
                installLwtTextOverlay(control, matcher, text, propertyName);
            else
                installTextOverlay(control, matcher, text);
        }

        private static String resolveDisplayText(Control control, String propertyName)
        {
            if (PropertySheetControlInterop.isLwtPaintHost(control)
                    && propertyName != null && !propertyName.isEmpty())
            {
                // Для LWT host controlText() часто возвращает текст значения ("Открыть" и т.п.),
                // а не имя свойства текущей строки.
                return propertyName;
            }
            if (control instanceof Label)
            {
                String labelText = ((Label) control).getText();
                if (labelText != null && !labelText.isEmpty())
                    return labelText;
            }
            String visible = PropertySheetControlInterop.controlText(control);
            return visible.isEmpty() ? (propertyName != null ? propertyName : "") : visible; //$NON-NLS-1$
        }

        private static void installTextOverlay(Control control, SmartMatcher matcher, String text)
        {
            clearLwtHooks(control, null);
            if (skipIfSame(control, HOOK_KEY, matcher, text))
                return;

            if (control.getData(HOOK_KEY) == null)
            {
                control.addPaintListener(new PaintListener()
                {
                    @Override
                    public void paintControl(PaintEvent e)
                    {
                        SmartMatcher active = activeMatcher(control, HOOK_KEY);
                        String drawn = activeText(control, HOOK_KEY);
                        if (active == null || drawn.isEmpty() || !active.matches(drawn))
                            return;
                        SmartMatchHighlight.paintTextMatchOverlay(e.gc, control, drawn, active);
                    }
                });
                control.setData(HOOK_KEY, Boolean.TRUE);
            }
            storeMatcher(control, HOOK_KEY, matcher, text);
            control.redraw();
        }

        /** LWT: синий жирный overlay в позиции LightLabel (origin из scan). Несколько свойств на одном host. */
        private static void installLwtTextOverlay(Control host, SmartMatcher matcher, String text, String propertyName)
        {
            clearTextOverlayHooks(host);
            if (propertyName == null || propertyName.isEmpty())
                propertyName = text;

            @SuppressWarnings("unchecked")
            Map<String, LwtRowHighlight> rows = (Map<String, LwtRowHighlight>) host.getData(LWT_ROWS_KEY);
            if (rows == null)
            {
                rows = new HashMap<>();
                host.setData(LWT_ROWS_KEY, rows);
            }

            LwtRowHighlight existing = rows.get(propertyName);
            if (existing != null && matcher.fullPattern.equals(existing.matcher.fullPattern)
                    && text.equals(existing.text))
                return;

            LwtRowHighlight entry = new LwtRowHighlight();
            entry.matcher = matcher;
            entry.text = text;
            entry.propertyName = propertyName;
            entry.light = PropertySheetControlInterop.rowLight(host, propertyName);
            rows.put(propertyName, entry);

            if (host.getData(LWT_HOOK_KEY) == null)
            {
                host.addPaintListener(new PaintListener()
                {
                    @Override
                    public void paintControl(PaintEvent e)
                    {
                        @SuppressWarnings("unchecked")
                        Map<String, LwtRowHighlight> activeRows =
                                (Map<String, LwtRowHighlight>) host.getData(LWT_ROWS_KEY);
                        if (activeRows == null || activeRows.isEmpty())
                            return;
                        for (LwtRowHighlight rh : activeRows.values())
                        {
                            if (rh.matcher == null || rh.matcher.isEmpty || !rh.matcher.matches(rh.text))
                                continue;
                            Point origin = resolveLwtHighlightOrigin(host, rh);
                            Rectangle band = PropertySheetControlInterop.lwtRowBand(host, rh.propertyName);
                            if (band != null)
                            {
                                if (origin.x < band.x)
                                    origin.x = band.x;
                                if (rh.light == null)
                                {
                                    int textHeight = PropertySheetControlInterop.lwtTextHeight(rh.light,
                                            host instanceof Composite ? (Composite) host : null);
                                    if (origin.y < band.y - 2 || origin.y > band.y + band.height + 2)
                                        origin.y = band.y + Math.max(0, (band.height - textHeight) / 2);
                                }
                            }
                            Rectangle prevClip = e.gc.getClipping();
                            if (band != null)
                                e.gc.setClipping(band);
                            SmartMatchHighlight.paintLwtTextMatchOverlay(e.gc, host, rh.text, rh.matcher,
                                    origin.x, origin.y, rh.light);
                            if (band != null)
                                e.gc.setClipping(prevClip);
                        }
                    }
                });
                host.setData(LWT_HOOK_KEY, Boolean.TRUE);
            }
            host.redraw();
            Point origin = resolveLwtHighlightOrigin(host, entry);
            PropertySheetDebug.feature("highlight lwt " + PropertySheetDebug.quote(text) //$NON-NLS-1$
                    + " origin=(" + origin.x + "," + origin.y + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " ctrl=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$
        }

        private static Point resolveLwtHighlightOrigin(Control host, LwtRowHighlight rh)
        {
            if (rh != null && rh.light != null && host instanceof Composite)
            {
                Point exact = PropertySheetControlInterop.lwtLabelDrawOrigin(rh.light, (Composite) host);
                if (exact != null)
                    return exact;
            }
            return PropertySheetControlInterop.lwtHighlightOrigin(host,
                    rh != null ? rh.propertyName : null);
        }

        private static void purgeStaleHighlights(PropertySheetUiContext ctx)
        {
            Set<Control> activeSwt = new HashSet<>();
            Map<Control, Set<String>> activeLwt = new HashMap<>();
            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                Control host = highlightHost(row);
                if (host == null || host.isDisposed())
                    continue;
                if (PropertySheetControlInterop.isLwtPaintHost(host))
                    activeLwt.computeIfAbsent(host, k -> new HashSet<>()).add(row.propertyName);
                else
                    activeSwt.add(host);
            }

            Composite root = PropertySheetUiContext.findPaletteRoot(ctx.page);
            if (root != null && !root.isDisposed())
                purgeStaleInComposite(root, activeSwt, activeLwt, ctx.matcher.isEmpty);
        }

        private static void purgeStaleInComposite(Composite composite, Set<Control> activeSwt,
                Map<Control, Set<String>> activeLwt, boolean filterEmpty)
        {
            if (composite == null || composite.isDisposed())
                return;
            purgeStaleOnControl(composite, activeSwt, activeLwt, filterEmpty);
            for (Control child : composite.getChildren())
            {
                if (child instanceof Composite)
                    purgeStaleInComposite((Composite) child, activeSwt, activeLwt, filterEmpty);
                else
                    purgeStaleOnControl(child, activeSwt, activeLwt, filterEmpty);
            }
        }

        private static void purgeStaleOnControl(Control control, Set<Control> activeSwt,
                Map<Control, Set<String>> activeLwt, boolean filterEmpty)
        {
            if (control == null || control.isDisposed())
                return;

            Object rowsObj = control.getData(LWT_ROWS_KEY);
            if (rowsObj instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, LwtRowHighlight> rows = (Map<String, LwtRowHighlight>) rowsObj;
                Set<String> allowed = activeLwt.get(control);
                if (filterEmpty || allowed == null)
                    rows.clear();
                else
                {
                    for (Iterator<String> it = rows.keySet().iterator(); it.hasNext();)
                    {
                        if (!allowed.contains(it.next()))
                            it.remove();
                    }
                }
                clearLegacyLwtKeys(control);
                control.redraw();
            }
            else if (Boolean.TRUE.equals(control.getData(LWT_HOOK_KEY)))
            {
                clearLegacyLwtKeys(control);
                control.redraw();
            }

            if (Boolean.TRUE.equals(control.getData(HOOK_KEY)) && !activeSwt.contains(control))
                clearTextOverlayHooks(control);
        }

        /** Старый формат (один matcher на host) — иначе ghost-подсветка от прежних listener. */
        private static void clearLegacyLwtKeys(Control control)
        {
            control.setData(LWT_HOOK_KEY + ".pattern", ""); //$NON-NLS-1$ //$NON-NLS-2$
            control.setData(LWT_HOOK_KEY + ".text", ""); //$NON-NLS-1$ //$NON-NLS-2$
            control.setData(LWT_HOOK_KEY + ".matcher", new SmartMatcher("")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static boolean skipIfSame(Control control, String keyPrefix, SmartMatcher matcher, String text)
        {
            Object prevPattern = control.getData(keyPrefix + ".pattern"); //$NON-NLS-1$
            Object prevText = control.getData(keyPrefix + ".text"); //$NON-NLS-1$
            return matcher.fullPattern.equals(prevPattern) && text.equals(prevText);
        }

        private static SmartMatcher activeMatcher(Control control, String keyPrefix)
        {
            Object m = control.getData(keyPrefix + ".matcher"); //$NON-NLS-1$
            return m instanceof SmartMatcher ? (SmartMatcher) m : null;
        }

        private static String activeText(Control control, String keyPrefix)
        {
            Object stored = control.getData(keyPrefix + ".text"); //$NON-NLS-1$
            return stored instanceof String ? (String) stored : ""; //$NON-NLS-1$
        }

        private static void storeMatcher(Control control, String keyPrefix, SmartMatcher matcher, String text)
        {
            control.setData(keyPrefix + ".matcher", matcher); //$NON-NLS-1$
            control.setData(keyPrefix + ".pattern", matcher.fullPattern); //$NON-NLS-1$
            control.setData(keyPrefix + ".text", text); //$NON-NLS-1$
        }

        private static void clearHighlight(Control control, String propertyName)
        {
            if (control == null || control.isDisposed())
                return;
            clearTextOverlayHooks(control);
            clearLwtHooks(control, propertyName);
        }

        private static void clearTextOverlayHooks(Control control)
        {
            if (control == null || control.isDisposed())
                return;
            control.setData(HOOK_KEY + ".pattern", ""); //$NON-NLS-1$ //$NON-NLS-2$
            control.setData(HOOK_KEY + ".matcher", new SmartMatcher("")); //$NON-NLS-1$ //$NON-NLS-2$
            control.setData(HOOK_KEY + ".text", ""); //$NON-NLS-1$ //$NON-NLS-2$
            control.redraw();
        }

        private static void clearLwtHooks(Control control, String propertyName)
        {
            if (control == null || control.isDisposed())
                return;
            Object rowsObj = control.getData(LWT_ROWS_KEY);
            if (rowsObj instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, ?> rows = (Map<String, ?>) rowsObj;
                if (propertyName != null && !propertyName.isEmpty())
                    rows.remove(propertyName);
                else
                    rows.clear();
                clearLegacyLwtKeys(control);
                control.redraw();
                return;
            }
            clearLegacyLwtKeys(control);
            control.redraw();
        }
    }


    /**
     * Правый клик по строке свойства → контекстное меню с командой копирования имени.
     * Заменяет прежний левый клик-копирование, который конфликтовал с выделением строки.
     */
    private static final class PropertySheetNameCopyFeature implements PropertySheetUiFeature
    {
        private static final String HOOK_KEY = "tormozit.ps.nameCopyMenu"; //$NON-NLS-1$

        @Override
        public void refresh(PropertySheetUiContext ctx)
        {
            if (ctx == null)
                return;
            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                Control target = PropertySheetRowSelectionFeature.interactionTarget(row);
                if (target == null || target.isDisposed())
                    continue;
                if (row.lwtView != null && PropertySheetControlInterop.isLwtPaintHost(target))
                    continue;
                if (Boolean.TRUE.equals(target.getData(HOOK_KEY)))
                    continue;
                target.setData(HOOK_KEY, Boolean.TRUE);
                installContextMenu(target, row);
            }
            PropertySheetDebug.feature("nameCopyMenu hooked=" + ctx.rows.size()); //$NON-NLS-1$
        }

        void showContextMenu(PropertySheetPaletteRow row, Control widget, Point displayPoint)
        {
            if (row == null || widget == null || widget.isDisposed())
                return;
            String name = row.propertyName;
            if (name == null || name.isEmpty())
                name = PropertySheetControlInterop.controlText(widget);
            final String copyText = name != null ? name : ""; //$NON-NLS-1$

            Menu menu = new Menu(widget.getShell(), SWT.POP_UP);
            MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
            copyItem.setText("Копировать имя\tCtrl+C"); //$NON-NLS-1$
            if (copyText.isEmpty())
                copyItem.setEnabled(false);
            else
            {
                copyItem.addListener(SWT.Selection, e -> {
                    PropertySheetDebug.feature("contextMenu copy " + PropertySheetDebug.quote(copyText)); //$NON-NLS-1$
                    PropertySheetUiContext.copyToClipboard(widget, copyText);
                    ToastNotification.show("Скопировано", copyText, 2_500); //$NON-NLS-1$
                });
            }
            Point at = displayPoint != null ? displayPoint : widget.getDisplay().getCursorLocation();
            menu.setLocation(at);
            menu.setVisible(true);
            menu.addListener(SWT.Hide, e -> {
                if (!menu.isDisposed())
                    menu.dispose();
            });
        }

        private static void installContextMenu(Control target, PropertySheetPaletteRow row)
        {
            Menu menu = new Menu(target.getShell(), SWT.POP_UP);

            // Пересоздаём пункты при каждом показе — чтобы текст был актуальным.
            menu.addMenuListener(new MenuAdapter()
            {
                @Override
                public void menuShown(MenuEvent e)
                {
                    // Очищаем старые пункты
                    for (MenuItem item : menu.getItems())
                        item.dispose();

                    String name = row.propertyName;
                    if (name == null || name.isEmpty())
                        name = PropertySheetControlInterop.controlText(target);
                    final String copyText = (name != null) ? name : ""; //$NON-NLS-1$

                    MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
                    copyItem.setText("Копировать имя\tCtrl+C"); //$NON-NLS-1$

                    if (copyText.isEmpty())
                    {
                        copyItem.setEnabled(false);
                        return;
                    }

                    copyItem.addListener(SWT.Selection, event -> {
                        PropertySheetDebug.feature("contextMenu copy " + PropertySheetDebug.quote(copyText)); //$NON-NLS-1$
                        PropertySheetUiContext.copyToClipboard(target, copyText);
                        ToastNotification.show("Скопировано", copyText, 2_500); //$NON-NLS-1$
                    });
                }
            });

            target.setMenu(menu);
            target.addDisposeListener(e -> {
                if (!menu.isDisposed())
                    menu.dispose();
            });
        }
    }

}
