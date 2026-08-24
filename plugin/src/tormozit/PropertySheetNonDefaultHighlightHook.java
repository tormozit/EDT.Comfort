package tormozit;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.fieldassist.FieldDecoration;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
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
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.md.ui.editor.aef.AbstractDtGranularEditorAefPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * В палитре свойств помечает списки выбора, в которых есть пункт «Авто», а выбрано
 * другое значение: цвет текста поля и иконка в штатной декорации слева (только если
 * у поля нет ошибки).
 */
public final class PropertySheetNonDefaultHighlightHook implements IStartup
{
    private static final String TEMP_TOPIC = "свойства-неавто"; //$NON-NLS-1$
    private static final String DECORATION_KEY = "DecorationSupport.LwtControlDecoration"; //$NON-NLS-1$
    private static final String CHANGED_LISTENER = "com._1c.g5.lwt.controls.IChangedListener"; //$NON-NLS-1$
    private static final String DECORATION_TOOLTIP = "Значение не Авто"; //$NON-NLS-1$
    private static final int SYNC_DELAY_MS = 100;

    private static final Set<IViewPart> HOOKED_VIEWS =
        Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<DtGranularEditor<?>> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Control> WATCHED_CONTROLS =
        Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> WIRED_COMBOS =
        Collections.newSetFromMap(new IdentityHashMap<>());
    /** Исходный цвет текста комбо, которое мы окрашивали. */
    private static final Map<Object, Color> ORIGINAL_FOREGROUND = new IdentityHashMap<>();
    /** Комбо, у которых мы сами показали декорацию (не штатную ошибку). */
    private static final Set<Object> OUR_DECORATION =
        Collections.newSetFromMap(new IdentityHashMap<>());

    /** Коричневый акцент подсветки, в координатах светлой темы (см. {@link ThemeAwareColors}). */
    private static final org.eclipse.swt.graphics.RGB ACCENT_LIGHT_RGB =
        new org.eclipse.swt.graphics.RGB(104, 52, 14);

    private static boolean syncScheduled;
    private static Image markerImage;
    private static Color cachedAccentColor;
    private static org.eclipse.swt.graphics.RGB cachedAccentEffectiveRgb;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(PropertySheetNonDefaultHighlightHook::install);
    }

    private static void install()
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
        Global.tempLog(TEMP_TOPIC, "установлен"); //$NON-NLS-1$
        scheduleSync();
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
                    hookView(view);
            }
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
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
                    hookView((IViewPart) part);
                else if (part instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        });
    }

    private static void hookView(IViewPart view)
    {
        if (view == null)
            return;
        HOOKED_VIEWS.add(view);
        scheduleSync();
    }

    private static void hookEditor(DtGranularEditor<?> editor)
    {
        if (editor == null || !HOOKED_EDITORS.add(editor))
            return;
        editor.addPageChangedListener(event -> scheduleSync());
        scheduleSync();
    }

    private static void scheduleSync()
    {
        if (syncScheduled)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        syncScheduled = true;
        display.timerExec(SYNC_DELAY_MS, () ->
        {
            syncScheduled = false;
            sync();
        });
    }

    private static void sync()
    {
        pruneDead();
        Set<Object> live = Collections.newSetFromMap(new IdentityHashMap<>());
        Global.tempLog(TEMP_TOPIC, "sync views=" + HOOKED_VIEWS.size() //$NON-NLS-1$
            + " editors=" + HOOKED_EDITORS.size()); //$NON-NLS-1$
        for (IViewPart view : HOOKED_VIEWS)
        {
            Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
            applyPage(page, live);
        }
        for (DtGranularEditor<?> editor : HOOKED_EDITORS)
        {
            IFormPage page = editor.getActivePageInstance();
            if (page instanceof AbstractDtGranularEditorAefPage<?>)
                applyPage(page, live);
        }
        restoreMissing(live);
    }

    private static void applyPage(Object page, Set<Object> live)
    {
        if (page == null)
        {
            Global.tempLog(TEMP_TOPIC, "applyPage page=null"); //$NON-NLS-1$
            return;
        }
        watchPage(page);
        Map<?, ?> map = viewModelToView(page);
        if (map == null)
        {
            Global.tempLog(TEMP_TOPIC, "applyPage " + simpleName(page) + " map=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        int combos = 0;
        int marked = 0;
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object vm = entry.getKey();
            if (keys.length() < 400)
            {
                if (keys.length() > 0)
                    keys.append(',');
                keys.append(simpleName(vm));
            }
            if (!isComboViewModel(vm))
                continue;
            Object light = comboControlFromView(entry.getValue());
            combos++;
            if (!isComboControl(light) || isDisposed(light))
            {
                Global.tempLog(TEMP_TOPIC, "skip light vm=" + simpleName(vm) //$NON-NLS-1$
                    + " view=" + simpleName(entry.getValue()) //$NON-NLS-1$
                    + " light=" + className(light)); //$NON-NLS-1$
                continue;
            }
            live.add(light);
            wireCombo(light);
            if (applyCombo(vm, light))
                marked++;
        }
        Global.tempLog(TEMP_TOPIC, "applyPage " + simpleName(page) //$NON-NLS-1$
            + " map=" + map.size() + " combos=" + combos + " marked=" + marked //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " keys=" + keys); //$NON-NLS-1$
    }

    private static boolean applyCombo(Object viewModel, Object light)
    {
        boolean mark = shouldMark(viewModel);
        Global.tempLog(TEMP_TOPIC, (mark ? "MARK " : "skip ") + simpleName(viewModel) //$NON-NLS-1$ //$NON-NLS-2$
            + " light=" + simpleName(light) //$NON-NLS-1$
            + " selected=" + itemText(Global.invoke(viewModel, "getSelectedItem")) //$NON-NLS-1$ //$NON-NLS-2$
            + " items=" + itemsDump(viewModel) //$NON-NLS-1$
            + " statusOk=" + statusOk(viewModel) //$NON-NLS-1$
            + " deco=" + (Global.invoke(light, "getData", DECORATION_KEY) != null)); //$NON-NLS-1$ //$NON-NLS-2$
        if (mark)
        {
            if (!ORIGINAL_FOREGROUND.containsKey(light))
                ORIGINAL_FOREGROUND.put(light, readForeground(light));
            Color accent = accentColor();
            if (!sameRgb(readForeground(light), accent))
                writeForeground(light, accent);
            showDecorationIfNoError(viewModel, light);
            return true;
        }
        restoreCombo(light, viewModel);
        return false;
    }

    private static void restoreCombo(Object light, Object viewModel)
    {
        if (isDisposed(light))
        {
            ORIGINAL_FOREGROUND.remove(light);
            OUR_DECORATION.remove(light);
            return;
        }
        if (ORIGINAL_FOREGROUND.containsKey(light))
        {
            Color original = ORIGINAL_FOREGROUND.remove(light);
            writeForeground(light, original);
        }
        hideOurDecoration(light, viewModel);
    }

    private static void restoreMissing(Set<Object> live)
    {
        for (Object light : ORIGINAL_FOREGROUND.keySet().toArray())
        {
            if (!live.contains(light) && !isDisposed(light))
                restoreCombo(light, null);
        }
    }

    /**
     * Комбо в фокусе на странице палитры; {@code null} — фокус не в комбо палитры.
     * {@code [0]} — {@code ComboViewModel}, {@code [1]} — светлый контрол ({@code LightCombo}
     * / {@code LightImageCombo}). Используется {@link PropertySheetEventHandlerClearHook}
     * (Shift+F4 переключает «не Авто» значение на «Авто»).
     */
    static Object[] focusedCombo(Object page)
    {
        Map<?, ?> map = viewModelToView(page);
        if (map == null)
            return null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object vm = entry.getKey();
            if (!isComboViewModel(vm))
                continue;
            Object light = comboControlFromView(entry.getValue());
            if (!isComboControl(light) || isDisposed(light))
                continue;
            if (PropertySheetActivePropertyHook.isFocusedDeep(light, 0))
                return new Object[] { vm, light };
        }
        return null;
    }

    /**
     * Индекс пункта «Авто» в списке комбо, если сейчас выбрано другое значение и «Авто» в
     * списке есть; иначе {@code null} (менять не на что или менять не нужно).
     */
    static Integer autoItemIndexToSwitchTo(Object viewModel)
    {
        if (!shouldMark(viewModel))
            return null;
        Object items = Global.invoke(viewModel, "getItems"); //$NON-NLS-1$
        if (!(items instanceof Iterable<?> list))
            return null;
        int i = 0;
        for (Object item : list)
        {
            if (isAutoText(itemText(item)))
                return i;
            i++;
        }
        return null;
    }

    private static boolean shouldMark(Object viewModel)
    {
        if (!hasAutoChoice(viewModel))
            return false;
        Object selected = Global.invoke(viewModel, "getSelectedItem"); //$NON-NLS-1$
        String text = itemText(selected);
        return text != null && !text.isEmpty() && !isAutoText(text);
    }

    private static boolean hasAutoChoice(Object viewModel)
    {
        Object items = Global.invoke(viewModel, "getItems"); //$NON-NLS-1$
        if (!(items instanceof Iterable<?> list))
            return false;
        for (Object item : list)
        {
            if (isAutoText(itemText(item)))
                return true;
        }
        return false;
    }

    private static String itemText(Object item)
    {
        if (item == null)
            return null;
        Object text = Global.invoke(item, "getText"); //$NON-NLS-1$
        if (text instanceof String s)
            return s.trim();
        return null;
    }

    private static boolean isAutoText(String text)
    {
        return text != null
            && (text.equalsIgnoreCase("Авто") || text.equalsIgnoreCase("Auto")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void showDecorationIfNoError(Object viewModel, Object light)
    {
        if (!statusOk(viewModel))
        {
            OUR_DECORATION.remove(light);
            return;
        }
        Object deco = Global.invoke(light, "getData", DECORATION_KEY); //$NON-NLS-1$
        if (deco == null)
            return;
        Image image = markerImage();
        if (image == null)
            return;
        Global.invokeVoid(deco, "setImage", image); //$NON-NLS-1$
        Global.invokeVoid(deco, "setTooltip", //$NON-NLS-1$
            DECORATION_TOOLTIP + Global.pluginSignForTooltip());
        Global.invokeVoid(deco, "show"); //$NON-NLS-1$
        OUR_DECORATION.add(light);
    }

    private static void hideOurDecoration(Object light, Object viewModel)
    {
        if (!OUR_DECORATION.remove(light))
            return;
        if (viewModel != null && !statusOk(viewModel))
            return;
        Object deco = Global.invoke(light, "getData", DECORATION_KEY); //$NON-NLS-1$
        if (deco != null)
            Global.invokeVoid(deco, "hide"); //$NON-NLS-1$
    }

    private static boolean statusOk(Object viewModel)
    {
        if (viewModel == null)
            return true;
        Object status = Global.invoke(viewModel, "getStatus"); //$NON-NLS-1$
        return !(status instanceof IStatus s) || s.isOK();
    }

    private static Image markerImage()
    {
        Image cached = markerImage;
        if (cached != null && !cached.isDisposed())
            return cached;
        FieldDecoration decoration = FieldDecorationRegistry.getDefault()
            .getFieldDecoration(FieldDecorationRegistry.DEC_INFORMATION);
        cached = decoration != null ? decoration.getImage() : null;
        markerImage = cached;
        return cached;
    }

    private static Color accentColor()
    {
        Display display = Display.getDefault();
        org.eclipse.swt.graphics.RGB effective = ThemeAwareColors.toEffectiveRgb(ACCENT_LIGHT_RGB);
        if (cachedAccentColor == null || cachedAccentColor.isDisposed()
            || !effective.equals(cachedAccentEffectiveRgb))
        {
            if (cachedAccentColor != null && !cachedAccentColor.isDisposed())
                cachedAccentColor.dispose();
            cachedAccentColor = new Color(display, effective);
            cachedAccentEffectiveRgb = effective;
        }
        return cachedAccentColor;
    }

    private static boolean sameRgb(Color a, Color b)
    {
        if (a == b)
            return true;
        if (a == null || b == null || a.isDisposed() || b.isDisposed())
            return false;
        return a.getRGB().equals(b.getRGB());
    }

    private static void wireCombo(Object light)
    {
        if (!WIRED_COMBOS.add(light))
            return;
        Global.addGenericListener(light, "addSelectionIndexListener", CHANGED_LISTENER, //$NON-NLS-1$
            PropertySheetNonDefaultHighlightHook::scheduleSync);
    }

    private static void watchPage(Object page)
    {
        watch(PropertySheetUiContext.findPaletteRoot(page));
        watch(PropertySheetUiContext.findPaletteScrolledComposite(page));
        watch(PropertySheetUiContext.findPaletteContent(page));
    }

    private static void watch(Control control)
    {
        if (control == null || control.isDisposed() || !WATCHED_CONTROLS.add(control))
            return;
        Listener listener = event -> scheduleSync();
        control.addListener(SWT.Paint, listener);
        control.addListener(SWT.Resize, listener);
    }

    private static Map<?, ?> viewModelToView(Object page)
    {
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object map = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        return map instanceof Map<?, ?> m ? m : null;
    }

    private static boolean isComboViewModel(Object key)
    {
        if (key == null)
            return false;
        String cn = key.getClass().getName();
        if (cn.contains("ComboItem")) //$NON-NLS-1$
            return false;
        return cn.contains("Combo") && cn.contains("ViewModel"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Object comboControlFromView(Object view)
    {
        Object nativeCtrl = Global.invoke(view, "getNativeControl"); //$NON-NLS-1$
        if (isComboControl(nativeCtrl))
            return nativeCtrl;
        Object light = PropertySheetControlInterop.lightControlFromView(view);
        if (isComboControl(light))
            return light;
        return nativeCtrl != null ? nativeCtrl : light;
    }

    private static boolean isComboControl(Object light)
    {
        if (light == null)
            return false;
        String cn = light.getClass().getName();
        return cn.endsWith(".LightCombo") || cn.endsWith(".LightImageCombo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isTextCombo(Object light)
    {
        return light != null && light.getClass().getName().endsWith(".LightCombo"); //$NON-NLS-1$
    }

    private static Color readForeground(Object light)
    {
        Object color = isTextCombo(light)
            ? Global.invoke(light, "getForegroundColor") //$NON-NLS-1$
            : Global.getField(light, "textColor"); //$NON-NLS-1$
        return color instanceof Color c && !c.isDisposed() ? c : null;
    }

    private static void writeForeground(Object light, Color color)
    {
        if (isTextCombo(light))
        {
            Global.invokeVoid(light, "setForegroundColor", color); //$NON-NLS-1$
            Object content = Global.invoke(light, "getContent"); //$NON-NLS-1$
            if (content != null)
                Global.invokeVoid(content, "invalidate"); //$NON-NLS-1$
        }
        else
            Global.setField(light, "textColor", color); //$NON-NLS-1$
        Global.invokeVoid(light, "invalidate"); //$NON-NLS-1$
    }

    private static String itemsDump(Object viewModel)
    {
        Object items = Global.invoke(viewModel, "getItems"); //$NON-NLS-1$
        if (!(items instanceof Iterable<?> list))
            return items == null ? "<null>" : simpleName(items); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
        int n = 0;
        for (Object item : list)
        {
            if (n > 0)
                sb.append('|');
            sb.append(itemText(item));
            n++;
            if (n >= 8)
            {
                sb.append("|…"); //$NON-NLS-1$
                break;
            }
        }
        sb.append(']').append(n);
        return sb.toString();
    }

    private static String simpleName(Object o)
    {
        if (o == null)
            return "<null>"; //$NON-NLS-1$
        String cn = o.getClass().getName();
        int dot = cn.lastIndexOf('.');
        return dot < 0 ? cn : cn.substring(dot + 1);
    }

    private static String className(Object o)
    {
        return o == null ? "<null>" : o.getClass().getName(); //$NON-NLS-1$
    }

    private static boolean isDisposed(Object light)
    {
        return light == null || Boolean.TRUE.equals(Global.invoke(light, "isDisposed")); //$NON-NLS-1$
    }

    private static void pruneDead()
    {
        ORIGINAL_FOREGROUND.keySet().removeIf(PropertySheetNonDefaultHighlightHook::isDisposed);
        OUR_DECORATION.removeIf(PropertySheetNonDefaultHighlightHook::isDisposed);
        WIRED_COMBOS.removeIf(PropertySheetNonDefaultHighlightHook::isDisposed);
    }
}
