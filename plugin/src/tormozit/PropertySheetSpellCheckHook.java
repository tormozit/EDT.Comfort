package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Орфография Hunspell в полях панели «Свойства»: Заголовок, Подсказка, Синоним, Пояснение.
 * Волны — {@link LineStyleListener} на StyledText-оверлее LWT.
 * <p>
 * Основной путь: FocusIn/Show оверлея с {@code lwtOverlay} → {@code LightText} при открытой
 * панели «Свойства» (карта {@code viewModelToView} для этих полей часто не даёт LightText —
 * см. TypeComboOverlayHook). Работает только при
 * {@link SpellCheckHook#isComfortPlatformSpellingActive()} (орфография + наш ru_RU).
 * Подключение: editable {@code LightText}-оверлей в «Свойствах». Волны — LineStyle + Paint.
 * Токены — Pascal/camelCase. Ctrl+1/ПКМ, Ctrl+C/X.
 */
public final class PropertySheetSpellCheckHook implements IStartup
{
    private static final String[] FIELD_LABELS = {
        "Заголовок", //$NON-NLS-1$
        "Подсказка", //$NON-NLS-1$
        "Синоним", //$NON-NLS-1$
        "Пояснение" //$NON-NLS-1$
    };

    private static final String LWT_OVERLAY_DATA_KEY = "com._1c.g5.lwt.lwtOverlay"; //$NON-NLS-1$

    private static final Set<Object> ATTACHED_LIGHT =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<StyledText> WIRED_OVERLAYS =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<IViewPart> SCHEDULING =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Object> DUMPED_SCENES =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean overlayFilterInstalled;
    private static boolean assistKeyFilterInstalled;

    @Override
    public void earlyStartup()
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            installOverlayFilter(display);
            installAssistKeyFilter(display);
            installWorkbenchHooks();
        });
    }

    private static void installWorkbenchHooks()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (PropertyNameIdentifierHook.isPropertySheetView(view))
                    scheduleAttach(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)       { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)      { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)    { tryFromRef(ref); }
            @Override public void partInputChanged(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref)       {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)  {}
            @Override public void partHidden(IWorkbenchPartReference ref)       {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (PropertyNameIdentifierHook.isPropertySheetView(part))
                    scheduleAttach((IViewPart) part, 0);
            }
        });
    }

    private static void scheduleAttach(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || view == null)
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
        {
            SCHEDULING.remove(view);
            return;
        }
        if (attempt == 0)
        {
            if (SCHEDULING.contains(view))
                return;
            SCHEDULING.add(view);
        }
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
            {
                SCHEDULING.remove(view);
                return;
            }
            if (view.getSite() == null || view.getSite().getShell() == null
                || view.getSite().getShell().isDisposed())
            {
                SCHEDULING.remove(view);
                return;
            }
            int attached = tryAttachAll(view, attempt);
            if (attached > 0 && (attempt == 0 || attempt % 10 == 0))
            if (attempt < 100)
                scheduleAttach(view, attempt + 1);
            else
                SCHEDULING.remove(view);
        });
    }

    private static int tryAttachAll(IViewPart view, int attempt)
    {
        Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        if (page == null)
            return 0;
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (scene == null)
            return 0;

        int count = 0;
        for (String label : FIELD_LABELS)
        {
            if (tryAttachField(scene, label, attempt))
                count++;
        }
        if (count == 0 && (attempt == 0 || attempt == 50))
            dumpSceneLabels(scene);
        return count;
    }

    private static boolean tryAttachField(Object scene, String label, int attempt)
    {
        Object lightText = findLightTextAfterLabel(scene, label);
        if (lightText == null)
        {
            if (attempt == 0 || attempt == 50)
            return false;
        }
        if (ATTACHED_LIGHT.contains(lightText))
            return true;

        boolean ok = Global.installLightControlListener(lightText, event ->
        {
            if (event == null)
                return;
            if (event.type == SWT.FocusIn || event.type == SWT.KeyDown || event.type == SWT.Modify)
                Display.getDefault().asyncExec(() -> wireOverlayForLightText(lightText));
        });
        if (!ok)
        {
            return false;
        }
        ATTACHED_LIGHT.add(lightText);
        return true;
    }

    /**
     * После LabelViewModel с текстом {@code displayName} ищет первый {@code LightText},
     * пропуская Separator / ActionBar / прочие декорации до следующей подписи/секции.
     */
    private static Object findLightTextAfterLabel(Object scene, String displayName)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map))
            return null;

        boolean foundLabel = false;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            String keyClass = key == null ? "" : key.getClass().getName(); //$NON-NLS-1$

            if (foundLabel)
            {
                if (keyClass.contains("LabelViewModel") || keyClass.contains("SectionViewModel")) //$NON-NLS-1$ //$NON-NLS-2$
                    return null;
                if (keyClass.contains("ActionBarViewModel")) //$NON-NLS-1$
                    continue;

                Object view = entry.getValue();
                Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
                Object lightText = resolveLightText(nativeControl);
                if (lightText != null)
                    return lightText;
                continue;
            }

            if (key != null && keyClass.contains("LabelViewModel")) //$NON-NLS-1$
            {
                Object text = Global.invoke(key, "getText"); //$NON-NLS-1$
                if (text == null)
                    text = Global.getField(key, "text"); //$NON-NLS-1$
                if (displayName.equals(text))
                    foundLabel = true;
            }
        }
        return null;
    }

    private static Object resolveLightText(Object nativeControl)
    {
        if (nativeControl == null)
            return null;
        String nativeCn = nativeControl.getClass().getName();
        if (nativeCn.contains("LightSeparator") || nativeCn.contains("LightLabel") //$NON-NLS-1$ //$NON-NLS-2$
            || nativeCn.contains("LightCombo") || nativeCn.contains("LightButton")) //$NON-NLS-1$ //$NON-NLS-2$
            return null;

        Object content = Global.invoke(nativeControl, "getContent"); //$NON-NLS-1$
        Object candidate = content != null ? content : nativeControl;
        if (candidate.getClass().getName().contains("LightText")) //$NON-NLS-1$
            return candidate;
        if (nativeCn.contains("LightText")) //$NON-NLS-1$
            return nativeControl;
        return null;
    }

    private static void dumpSceneLabels(Object scene)
    {
        if (scene == null || DUMPED_SCENES.contains(scene))
            return;
        DUMPED_SCENES.add(scene);
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map))
        {
            return;
        }
        StringBuilder labels = new StringBuilder();
        int n = 0;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key == null || !key.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                continue;
            Object text = Global.invoke(key, "getText"); //$NON-NLS-1$
            if (text == null)
                text = Global.getField(key, "text"); //$NON-NLS-1$
            if (n > 0)
                labels.append('|');
            labels.append(text);
            n++;
            if (n >= 40)
                break;
        }

        for (String want : FIELD_LABELS)
        {
            boolean found = false;
            StringBuilder after = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                Object key = entry.getKey();
                String keyClass = key == null ? "?" : key.getClass().getSimpleName(); //$NON-NLS-1$
                if (!found)
                {
                    if (key != null && key.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                    {
                        Object text = Global.invoke(key, "getText"); //$NON-NLS-1$
                        if (text == null)
                            text = Global.getField(key, "text"); //$NON-NLS-1$
                        if (want.equals(text))
                            found = true;
                    }
                    continue;
                }
                Object view = entry.getValue();
                Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
                String nativeCn = nativeControl == null ? "null" : nativeControl.getClass().getSimpleName(); //$NON-NLS-1$
                Object light = resolveLightText(nativeControl);
                after.append(keyClass).append('/').append(nativeCn)
                    .append(light != null ? "+LT" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(';');
                if (after.length() > 200)
                    break;
            }
        }
    }

    private static void installOverlayFilter(Display display)
    {
        if (overlayFilterInstalled || display.isDisposed())
            return;
        overlayFilterInstalled = true;
        Listener listener = event ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            if (!(event.widget instanceof StyledText styled) || styled.isDisposed())
                return;
            Object owner = styled.getData(LWT_OVERLAY_DATA_KEY);
            if (owner == null)
                return;
            if (!owner.getClass().getName().contains("LightText")) //$NON-NLS-1$
                return;
            if (!isSpellEligibleLightText(owner))
                return;
            if (!ATTACHED_LIGHT.contains(owner))
            {
                ATTACHED_LIGHT.add(owner);
            }
            wireOverlay(styled);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.FocusIn, listener);
    }

    /**
     * Горячие клавиши на wired-оверлее через Display filter (Workbench/LWT иначе перехватывают).
     * Ctrl+1 — подсказки; Ctrl+C/X — копирование/вырезание выделенного текста.
     */
    private static void installAssistKeyFilter(Display display)
    {
        if (assistKeyFilterInstalled || display.isDisposed())
            return;
        assistKeyFilterInstalled = true;
        display.addFilter(SWT.KeyDown, event ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            if (!(event.widget instanceof StyledText styled) || styled.isDisposed())
                return;
            if (!WIRED_OVERLAYS.contains(styled))
                return;
            if ((event.stateMask & SWT.MOD1) == 0)
                return;
            if (event.keyCode == 'c' || event.keyCode == 'C')
            {
                if (copySelection(styled))
                {
                    event.doit = false;
                    event.type = SWT.None;
                }
                return;
            }
            if (event.keyCode == 'x' || event.keyCode == 'X')
            {
                if (cutSelection(styled))
                {
                    event.doit = false;
                    event.type = SWT.None;
                }
                return;
            }
            boolean ctrl1 = event.keyCode == '1' || event.keyCode == SWT.KEYPAD_1;
            if (!ctrl1)
                return;
            WordSpan span = wordSpanAt(styled, styled.getCaretOffset());
            if (span == null || !span.misspelled)
                return;
            event.doit = false;
            event.type = SWT.None;
            Point loc;
            try
            {
                loc = styled.toDisplay(styled.getLocationAtOffset(span.start));
            }
            catch (IllegalArgumentException ex)
            {
                loc = styled.toDisplay(0, styled.getLineHeight());
            }
            showSuggestionMenu(styled, loc.x, loc.y + styled.getLineHeight(), span);
        });
    }

    /**
     * Любой редактируемый LightText в открытой панели «Свойства».
     * Имя/Синоним — обычно SINGLE ({@code isMultiline=false}); карта подписей часто пуста.
     */
    private static boolean isSpellEligibleLightText(Object lightText)
    {
        if (lightText == null || !lightText.getClass().getName().contains("LightText")) //$NON-NLS-1$
            return false;
        if (!isAnyPropertySheetVisible())
            return false;
        Object editable = Global.invoke(lightText, "isEditable"); //$NON-NLS-1$
        if (Boolean.FALSE.equals(editable))
            return false;
        Object readOnly = Global.invoke(lightText, "isReadOnly"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(readOnly))
            return false;
        Object password = Global.invoke(lightText, "isPasswordMode"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(password))
            return false;
        return true;
    }

    private static boolean isMultilineLightText(Object lightText)
    {
        Object ml = Global.invoke(lightText, "isMultiline"); //$NON-NLS-1$
        return Boolean.TRUE.equals(ml);
    }

    /** Подпись из {@link #FIELD_LABELS}, если этот LightText — редактор этого поля. */
    private static String resolveSpellFieldLabel(Object lightText)
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null || lightText == null)
            return null;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                for (IViewReference ref : page.getViewReferences())
                {
                    IViewPart view = ref.getView(false);
                    if (!PropertyNameIdentifierHook.isPropertySheetView(view))
                        continue;
                    Object sheetPage = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
                    Object scene = sheetPage != null ? Global.invoke(sheetPage, "getScene") : null; //$NON-NLS-1$
                    if (scene == null)
                        continue;
                    for (String label : FIELD_LABELS)
                    {
                        Object found = findLightTextAfterLabel(scene, label);
                        if (found == lightText)
                            return label;
                    }
                    String byMap = findLabelBeforeLightText(scene, lightText);
                    if (byMap != null && isSpellFieldLabel(byMap))
                        return byMap;
                }
            }
        }
        return null;
    }

    private static String findLabelBeforeLightTextInOpenSheets(Object lightText)
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null || lightText == null)
            return null;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                for (IViewReference ref : page.getViewReferences())
                {
                    IViewPart view = ref.getView(false);
                    if (!PropertyNameIdentifierHook.isPropertySheetView(view))
                        continue;
                    Object sheetPage = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
                    Object scene = sheetPage != null ? Global.invoke(sheetPage, "getScene") : null; //$NON-NLS-1$
                    if (scene == null)
                        continue;
                    String label = findLabelBeforeLightText(scene, lightText);
                    if (label != null)
                        return label;
                }
            }
        }
        return null;
    }

    private static boolean isSpellFieldLabel(String label)
    {
        if (label == null || label.isEmpty())
            return false;
        for (String allowed : FIELD_LABELS)
        {
            if (allowed.equals(label))
                return true;
        }
        return false;
    }

    /** Подпись, после которой в {@code viewModelToView} идёт данный {@code LightText}. */
    private static String findLabelBeforeLightText(Object scene, Object lightText)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map))
            return null;
        String lastLabel = null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            String keyClass = key == null ? "" : key.getClass().getName(); //$NON-NLS-1$
            if (keyClass.contains("SectionViewModel")) //$NON-NLS-1$
            {
                lastLabel = null;
                continue;
            }
            if (keyClass.contains("LabelViewModel")) //$NON-NLS-1$
            {
                Object text = Global.invoke(key, "getText"); //$NON-NLS-1$
                if (text == null)
                    text = Global.getField(key, "text"); //$NON-NLS-1$
                lastLabel = text instanceof String s ? s : null;
                continue;
            }
            if (keyClass.contains("ActionBarViewModel")) //$NON-NLS-1$
                continue;
            Object view = entry.getValue();
            Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
            Object found = resolveLightText(nativeControl);
            if (found == lightText)
                return lastLabel;
        }
        return null;
    }

    private static int lightTextLength(Object lightText)
    {
        Object text = Global.invoke(lightText, "getText"); //$NON-NLS-1$
        return text instanceof String s ? s.length() : -1;
    }

    private static boolean isAnyPropertySheetVisible()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return false;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                for (IViewReference ref : page.getViewReferences())
                {
                    IViewPart view = ref.getView(false);
                    if (PropertyNameIdentifierHook.isPropertySheetView(view) && page.isPartVisible(view))
                        return true;
                }
            }
        }
        return false;
    }

    private static void wireOverlayForLightText(Object lightText)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        for (org.eclipse.swt.widgets.Shell shell : display.getShells())
        {
            if (shell.isDisposed())
                continue;
            findAndWireOverlays(shell, lightText);
        }
    }

    private static void findAndWireOverlays(org.eclipse.swt.widgets.Control root, Object lightText)
    {
        if (root == null || root.isDisposed())
            return;
        if (root instanceof StyledText styled)
        {
            Object owner = styled.getData(LWT_OVERLAY_DATA_KEY);
            if (owner == lightText)
                wireOverlay(styled);
        }
        if (root instanceof org.eclipse.swt.widgets.Composite composite)
        {
            for (org.eclipse.swt.widgets.Control child : composite.getChildren())
                findAndWireOverlays(child, lightText);
        }
    }

    private static void wireOverlay(StyledText styled)
    {
        if (styled == null || styled.isDisposed())
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
            return;
        Object owner = styled.getData(LWT_OVERLAY_DATA_KEY);
        if (owner == null || !ATTACHED_LIGHT.contains(owner))
            return;
        if (WIRED_OVERLAYS.contains(styled))
        {
            styled.redraw();
            return;
        }

        LineStyleListener styleListener = PropertySheetSpellCheckHook::provideLineStyles;
        styled.addLineStyleListener(styleListener);
        // LWT SINGLE часто не рисует UNDERLINE_ERROR из LineStyleListener — дублируем в Paint
        styled.addListener(SWT.Paint, PropertySheetSpellCheckHook::paintErrorUnderlines);
        styled.addModifyListener(e ->
        {
            if (!styled.isDisposed())
                styled.redraw();
        });
        installAssistHandlers(styled);
        styled.addDisposeListener(e -> WIRED_OVERLAYS.remove(styled));
        WIRED_OVERLAYS.add(styled);
        styled.redraw();
        String sample = styled.getText();
        int miss = sample == null || sample.isEmpty()
            ? 0
            : ComfortSpellingEngine.findMisspelledRanges(sample).size();
    }

    /** Красная волна под ошибочными сегментами (поверх текста оверлея). */
    private static void paintErrorUnderlines(Event event)
    {
        if (!(event.widget instanceof StyledText styled) || styled.isDisposed())
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
            return;
        String text = styled.getText();
        if (text == null || text.isEmpty())
            return;
        List<int[]> bad = ComfortSpellingEngine.findMisspelledRanges(text);
        if (bad.isEmpty())
            return;
        GC gc = event.gc;
        if (gc == null)
            return;
        Color red = spellingUnderlineColor(styled.getDisplay());
        Color oldFg = gc.getForeground();
        gc.setForeground(red);
        int lineHeight = styled.getLineHeight();
        for (int[] r : bad)
        {
            int from = r[0];
            int to = r[0] + r[1];
            try
            {
                Point p0 = styled.getLocationAtOffset(from);
                Point p1 = styled.getLocationAtOffset(to);
                int y = p0.y + lineHeight - 2;
                drawWavyLine(gc, p0.x, p1.x, y);
            }
            catch (IllegalArgumentException ignored)
            {
                // offset вне видимой области
            }
        }
        gc.setForeground(oldFg);
    }

    private static void drawWavyLine(GC gc, int x1, int x2, int y)
    {
        if (x2 <= x1)
            return;
        int amp = 1;
        int step = 2;
        int prevX = x1;
        int prevY = y;
        boolean up = true;
        for (int x = x1 + step; x <= x2; x += step)
        {
            int nextY = up ? y - amp : y + amp;
            gc.drawLine(prevX, prevY, x, nextY);
            prevX = x;
            prevY = nextY;
            up = !up;
        }
        if (prevX < x2)
            gc.drawLine(prevX, prevY, x2, y);
    }

    private static void installAssistHandlers(StyledText styled)
    {
        styled.addListener(SWT.MenuDetect, event ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            if (!(event.widget instanceof StyledText st) || st.isDisposed())
                return;
            int offset = offsetFromDisplay(st, event.x, event.y);
            WordSpan span = wordSpanAt(st, offset);
            String selection = st.getSelectionText();
            boolean hasSelection = selection != null && !selection.isEmpty();
            if ((span == null || !span.misspelled) && !hasSelection)
                return;
            event.doit = false;
            showAssistMenu(st, event.x, event.y, span, hasSelection);
        });
    }

    private static boolean copySelection(StyledText styled)
    {
        if (styled == null || styled.isDisposed())
            return false;
        String sel = styled.getSelectionText();
        if (sel == null || sel.isEmpty())
            return false;
        Clipboard cb = new Clipboard(styled.getDisplay());
        try
        {
            cb.setContents(new Object[] { sel }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
        return true;
    }

    private static boolean cutSelection(StyledText styled)
    {
        if (styled == null || styled.isDisposed() || !styled.getEditable())
            return false;
        Point range = styled.getSelection();
        if (range == null || range.x == range.y)
            return false;
        if (!copySelection(styled))
            return false;
        styled.replaceTextRange(range.x, range.y - range.x, ""); //$NON-NLS-1$
        return true;
    }

    private static int offsetFromDisplay(StyledText styled, int displayX, int displayY)
    {
        try
        {
            Point p = styled.toControl(displayX, displayY);
            return styled.getOffsetAtPoint(p);
        }
        catch (IllegalArgumentException ex)
        {
            return styled.getCaretOffset();
        }
    }

    private static WordSpan wordSpanAt(StyledText styled, int offset)
    {
        if (styled == null || styled.isDisposed())
            return null;
        String text = styled.getText();
        if (text == null || text.isEmpty())
            return null;
        int caret = Math.max(0, Math.min(offset, text.length()));
        if (caret > 0 && caret == text.length() && !isWordChar(text.charAt(caret - 1)))
            caret--;
        if (caret < text.length() && !isWordChar(text.charAt(caret)) && caret > 0
            && isWordChar(text.charAt(caret - 1)))
            caret--;
        if (caret >= text.length() || !isWordChar(text.charAt(caret)))
            return null;
        int runStart = caret;
        while (runStart > 0 && isWordChar(text.charAt(runStart - 1)))
            runStart--;
        int runEnd = caret;
        while (runEnd < text.length() && isWordChar(text.charAt(runEnd)))
            runEnd++;
        if (runStart >= runEnd)
            return null;
        List<int[]> segments = ComfortSpellingEngine.splitIdentifierSegments(text, runStart, runEnd);
        int[] chosen = segments.isEmpty() ? new int[] { runStart, runEnd } : segments.get(0);
        for (int[] seg : segments)
        {
            if (caret >= seg[0] && caret < seg[1])
            {
                chosen = seg;
                break;
            }
        }
        if (caret == runEnd && !segments.isEmpty())
            chosen = segments.get(segments.size() - 1);
        int segStart = chosen[0];
        int segEnd = chosen[1];
        String word = text.substring(segStart, segEnd);
        if (!hasLetter(word))
            return null;
        boolean misspelled = word.length() >= 2
            && ComfortSpellingEngine.isMisspelledAt(text, segStart, segEnd - segStart);
        return new WordSpan(segStart, segEnd - segStart, word, misspelled);
    }

    private static void showSuggestionMenu(StyledText styled, int displayX, int displayY,
        WordSpan span)
    {
        showAssistMenu(styled, displayX, displayY, span, false);
    }

    private static void showAssistMenu(StyledText styled, int displayX, int displayY,
        WordSpan span, boolean offerCopy)
    {
        if (styled == null || styled.isDisposed())
            return;
        Menu menu = new Menu(styled);
        // «Добавить в словарь» всегда первый пункт меню.
        if (span != null && span.misspelled)
        {
            MenuItem addDict = new MenuItem(menu, SWT.PUSH);
            addDict.setText("Добавить в словарь: " + span.word); //$NON-NLS-1$
            addDict.addListener(SWT.Selection, e -> styled.getDisplay().asyncExec(() ->
                ComfortSpellingEngine.addUserWordFromUi(span.word)));
            fillSuggestionsAsync(menu, styled, span);
            if (offerCopy)
                new MenuItem(menu, SWT.SEPARATOR);
        }
        if (offerCopy)
        {
            MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
            copyItem.setText("Копировать\tCtrl+C"); //$NON-NLS-1$
            copyItem.addListener(SWT.Selection,
                e -> styled.getDisplay().asyncExec(() -> copySelection(styled)));
            if (styled.getEditable())
            {
                MenuItem cutItem = new MenuItem(menu, SWT.PUSH);
                cutItem.setText("Вырезать\tCtrl+X"); //$NON-NLS-1$
                cutItem.addListener(SWT.Selection,
                    e -> styled.getDisplay().asyncExec(() -> cutSelection(styled)));
            }
        }
        if (menu.getItemCount() == 0)
        {
            menu.dispose();
            return;
        }
        menu.addListener(SWT.Hide, e -> styled.getDisplay().asyncExec(() ->
        {
            if (!menu.isDisposed())
                menu.dispose();
        }));
        menu.setLocation(displayX, displayY);
        menu.setVisible(true);
    }

    private static final int SUGGEST_MAX = 12;
    private static final int SUGGEST_UI_THROTTLE_MS = 300;

    /**
     * Варианты в меню: из кэша сразу; иначе «…» и фоновый
     * {@link ComfortSpellingEngine#suggestStreaming} с дописыванием перед «…»
     * (не чаще раза в {@link #SUGGEST_UI_THROTTLE_MS} мс). «Добавить в словарь» остаётся первым.
     */
    private static void fillSuggestionsAsync(Menu menu, StyledText styled, WordSpan span)
    {
        List<String> cached = ComfortSpellingEngine.peekSuggestCache(span.word, SUGGEST_MAX);
        if (cached != null)
        {
            if (cached.isEmpty())
            {
                MenuItem empty = new MenuItem(menu, SWT.PUSH);
                empty.setText("Нет вариантов исправления"); //$NON-NLS-1$
                empty.setEnabled(false);
            }
            else
            {
                int at = 1; // сразу после «Добавить в словарь»
                for (String suggestion : cached)
                {
                    addSuggestionMenuItem(menu, styled, span, suggestion, at);
                    at++;
                }
            }
            return;
        }

        MenuItem loading = new MenuItem(menu, SWT.PUSH);
        loading.setText("..."); //$NON-NLS-1$
        loading.setEnabled(false);

        final String word = span.word;
        final java.util.ArrayList<String> pending = new java.util.ArrayList<>();
        final Object pendingLock = new Object();
        final boolean[] flushScheduled = { false };
        final long[] lastFlushMs = { 0L };

        Runnable flushPending = () ->
        {
            if (menu.isDisposed() || loading.isDisposed())
                return;
            java.util.ArrayList<String> batch;
            synchronized (pendingLock)
            {
                flushScheduled[0] = false;
                lastFlushMs[0] = System.currentTimeMillis();
                if (pending.isEmpty())
                    return;
                batch = new java.util.ArrayList<>(pending);
                pending.clear();
            }
            int idx = menu.indexOf(loading);
            for (String suggestion : batch)
            {
                if (menu.isDisposed() || loading.isDisposed())
                    return;
                idx = menu.indexOf(loading);
                addSuggestionMenuItem(menu, styled, span, suggestion, idx >= 0 ? idx : 1);
            }
        };

        Job job = new Job("Комфорт: варианты орфографии (свойства)") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                ComfortSpellingEngine.suggestStreaming(word, SUGGEST_MAX, suggestion ->
                {
                    if (monitor.isCanceled())
                        return;
                    Display display = styled.getDisplay();
                    if (display == null || display.isDisposed())
                        return;
                    synchronized (pendingLock)
                    {
                        pending.add(suggestion);
                        long now = System.currentTimeMillis();
                        long wait = lastFlushMs[0] + SUGGEST_UI_THROTTLE_MS - now;
                        if (wait <= 0)
                        {
                            flushScheduled[0] = false;
                            display.asyncExec(flushPending);
                        }
                        else if (!flushScheduled[0])
                        {
                            flushScheduled[0] = true;
                            int delay = (int) wait;
                            // timerExec только из UI-потока
                            display.asyncExec(() ->
                            {
                                if (!display.isDisposed())
                                    display.timerExec(delay, flushPending);
                            });
                        }
                    }
                }, monitor);
                Display display = styled.getDisplay();
                if (display == null || display.isDisposed())
                    return Status.OK_STATUS;
                display.asyncExec(() ->
                {
                    flushPending.run();
                    if (menu.isDisposed())
                        return;
                    if (!loading.isDisposed())
                        loading.dispose();
                    if (!menuHasSpellSuggestion(menu))
                    {
                        // сразу после «Добавить в словарь» (индекс 1)
                        MenuItem empty = new MenuItem(menu, SWT.PUSH, Math.min(1, menu.getItemCount()));
                        empty.setText("Нет вариантов исправления"); //$NON-NLS-1$
                        empty.setEnabled(false);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        job.schedule();
        menu.addListener(SWT.Dispose, e -> job.cancel());
    }

    private static boolean menuHasSpellSuggestion(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed())
                continue;
            String t = item.getText();
            if (t == null || t.isEmpty())
                continue;
            if (t.startsWith("Добавить в словарь") //$NON-NLS-1$
                || t.startsWith("Копировать") || t.startsWith("Вырезать") //$NON-NLS-1$ //$NON-NLS-2$
                || "...".equals(t) //$NON-NLS-1$
                || "Нет вариантов исправления".equals(t)) //$NON-NLS-1$
                continue;
            return true;
        }
        return false;
    }

    private static void addSuggestionMenuItem(Menu menu, StyledText styled, WordSpan span,
        String suggestion, int index)
    {
        // Не вставлять перед «Добавить в словарь» (индекс 0).
        int safeIndex = index <= 0 ? 1 : index;
        safeIndex = Math.min(safeIndex, menu.getItemCount());
        MenuItem item = new MenuItem(menu, SWT.PUSH, safeIndex);
        item.setText(suggestion);
        item.addListener(SWT.Selection, e ->
        {
            String replacement = suggestion;
            styled.getDisplay().asyncExec(() -> applySuggestion(styled, span, replacement));
        });
    }

    private static void applySuggestion(StyledText styled, WordSpan span, String replacement)
    {
        if (styled == null || styled.isDisposed() || span == null || replacement == null)
            return;
        String text = styled.getText();
        if (text == null || span.start < 0 || span.start + span.length > text.length())
            return;
        styled.replaceTextRange(span.start, span.length, replacement);
        styled.setCaretOffset(span.start + replacement.length());
        styled.redraw();
    }

    private static final class WordSpan
    {
        final int start;
        final int length;
        final String word;
        final boolean misspelled;

        WordSpan(int start, int length, String word, boolean misspelled)
        {
            this.start = start;
            this.length = length;
            this.word = word;
            this.misspelled = misspelled;
        }
    }

    private static void provideLineStyles(LineStyleEvent event)
    {
        if (event == null || event.lineText == null)
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
        {
            event.styles = new StyleRange[0];
            return;
        }
        List<int[]> bad = ComfortSpellingEngine.findMisspelledRanges(event.lineText);
        if (bad.isEmpty())
        {
            event.styles = new StyleRange[0];
            return;
        }
        Color underline = event.widget instanceof StyledText st
            ? spellingUnderlineColor(st.getDisplay())
            : Display.getDefault().getSystemColor(SWT.COLOR_RED);
        StyleRange[] ranges = new StyleRange[bad.size()];
        for (int i = 0; i < bad.size(); i++)
        {
            int[] r = bad.get(i);
            StyleRange sr = new StyleRange();
            sr.start = event.lineOffset + r[0];
            sr.length = r[1];
            sr.underline = true;
            sr.underlineStyle = SWT.UNDERLINE_ERROR;
            sr.underlineColor = underline;
            ranges[i] = sr;
        }
        event.styles = ranges;
    }

    /** После изменения пользовательского словаря — перерисовать подчёркивания. */
    static void onUserDictionaryChanged(String word, boolean added)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable redraw = () ->
        {
            for (StyledText styled : new ArrayList<>(WIRED_OVERLAYS))
            {
                if (styled != null && !styled.isDisposed())
                    styled.redraw();
            }
        };
        if (display.getThread() == Thread.currentThread())
            redraw.run();
        else
            display.asyncExec(redraw);
    }

    private static Color spellingUnderlineColor(Display display)
    {
        return display.getSystemColor(SWT.COLOR_RED);
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '-' || c == '\'';
    }

    private static boolean hasLetter(String word)
    {
        for (int i = 0; i < word.length(); i++)
        {
            if (Character.isLetter(word.charAt(i)))
                return true;
        }
        return false;
    }
}
