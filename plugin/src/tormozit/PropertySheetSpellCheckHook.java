package tormozit;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
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
 * <p>
 * Основной путь: FocusIn/Show оверлея с {@code lwtOverlay} → {@code LightText} при открытой
 * панели «Свойства» (карта {@code viewModelToView} для этих полей часто не даёт LightText —
 * см. TypeComboOverlayHook). Работает только при
 * {@link SpellCheckHook#isComfortPlatformSpellingActive()} (орфография + наш ru_RU).
 * <p>
 * Здесь только поиск нужного {@code StyledText}-оверлея LWT; сами волны, меню ПКМ, Ctrl+1
 * и Ctrl+C/X — общие для всех мест проверки по виджету, они в {@link StyledTextSpellCheck}.
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

    private static final Set<IViewPart> SCHEDULING =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Object> DUMPED_SCENES =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean overlayFilterInstalled;

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
        Object owner = styled.getData(LWT_OVERLAY_DATA_KEY);
        if (owner == null || !ATTACHED_LIGHT.contains(owner))
            return;
        StyledTextSpellCheck.install(styled);
    }

    /** После изменения пользовательского словаря — перерисовать подчёркивания. */
    static void onUserDictionaryChanged(String word, boolean added)
    {
        StyledTextSpellCheck.redrawAll();
    }
}
