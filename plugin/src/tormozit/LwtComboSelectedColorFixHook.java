package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

/**
 * Фикс для https://github.com/1C-Company/1c-edt-issues/issues/2186 — пропадает подсветка
 * наведения в выпадающих списках LWT-комбобоксов ({@code com._1c.g5.lwt.controls.LightCombo}
 * и {@code LightImageCombo}) в светлой теме. Механизм используется практически везде, где AEF2
 * рисует поле выбора значения (палитра свойств, редакторы объектов метаданных) — см.
 * {@code com._1c.g5.aef2.standard.lwt.views.LwtComboView.createControl()}, который создаёт
 * {@code LightCombo} и красит границу/фон/текст поля, но ни разу не вызывает
 * {@code combo.setSelectedColor(...)}.
 *
 * <p>Корень проблемы: при заполнении попап-списка {@code LightCombo} делает
 * {@code item.setSelectedColor(this.getSelectedColor())} — затирает корректный цвет, который
 * {@code AbstractLightItem.initColors()} уже взял из {@code ColorRegistry}
 * ({@code listItemSelectedColor}), своим собственным полем {@code selectedColor}. Раз AEF2 его не
 * задаёт, оно остаётся {@code null} — в {@code AbstractLightItem.paint()} {@code isSelected==true}
 * даёт {@code color=null}, и {@code GC.fillRectangle} просто не вызывается: элемент визуально не
 * подсвечивается, хотя модель ({@code isSelected}) реально меняется при наведении.
 *
 * <p>Фикс: при первой встрече каждого {@code LightCombo}/{@code LightImageCombo} (через живую
 * модель попап-списка — {@code SwtLightComposite.getSwtLightComposite} + обход
 * {@code getChildren()}, синтетическое поле {@code this$0} у {@code LightComboList} ведёт к
 * владеющему combo) — если у combo {@code getSelectedColor()==null}, проставляем светлый цвет
 * через публичный {@code setSelectedColor(Color)}. Дополнительно чиним уже созданные (заполненные
 * с {@code null}) элементы текущего списка напрямую и просим перерисовку — чтобы уже открытый
 * в этот момент попап тоже сразу поправился, а не только следующие открытия.
 */
public final class LwtComboSelectedColorFixHook implements IStartup
{
    private static final String LWT_BUNDLE_ID = "com._1c.g5.lwt"; //$NON-NLS-1$

    /** Светлая подсветка вместо тёмно-синего {@code listItemSelectedColor} из реестра LWT —
     * тот цвет рассчитан на выделение значения в самом поле, а не на построчную подсветку списка. */
    private static final RGB FIX_COLOR = new RGB(214, 231, 251);

    /** Combo-инстансы, которым уже проставили цвет — не трогать повторно (identity, не equals). */
    private static final java.util.Set<Object> fixedCombos =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    @Override
    public void earlyStartup()
    {
        try
        {
            Display.getDefault().asyncExec(
                () -> Display.getDefault().addFilter(SWT.Paint, LwtComboSelectedColorFixHook::onGlobalPaint));
        }
        catch (Exception e)
        {
            // молча — при сбое просто не появится подсветка, штатное поведение не ломается
        }
    }

    private static void onGlobalPaint(Event event)
    {
        try
        {
            Widget widget = event.widget;
            if (widget == null || widget.isDisposed() || !(widget instanceof Control control))
                return;

            Object list = resolveLightListModel(control);
            if (list == null)
                return;
            if (!list.getClass().getName().startsWith("com._1c.g5.lwt.controls.")) //$NON-NLS-1$
                return;

            // this$0 — синтетическая ссылка на внешний LightCombo/LightImageCombo у вложенного
            // класса LightComboList (не static). У самостоятельного LightList поля нет — тогда
            // combo == null и просто ничего не делаем (не наш случай).
            Object combo = getFieldValue(list, "this$0", Object.class); //$NON-NLS-1$
            if (combo == null)
                return;
            if (!fixedCombos.add(combo))
                return; // уже чинили этот combo — не дублируем работу на каждый Paint

            fixCombo(combo, list, control);
        }
        catch (Exception e)
        {
            // молча — диагностику см. в истории чата/снимках при необходимости
        }
    }

    private static void fixCombo(Object combo, Object list, Control paintedControl) throws ReflectiveOperationException
    {
        Color current = invoke(combo, "getSelectedColor"); //$NON-NLS-1$
        if (current != null)
            return; // уже задан (например, кем-то другим) — не наш случай, не трогаем

        Color fixColor = new Color(paintedControl.getDisplay(), FIX_COLOR);
        Method setSelectedColor = combo.getClass().getMethod("setSelectedColor", Color.class); //$NON-NLS-1$
        setSelectedColor.invoke(combo, fixColor);

        // Элементы уже открытого списка успели заполниться с null до этого момента — чиним их
        // напрямую, иначе подсветка появится только при следующем открытии/фильтрации.
        Iterable<?> items = invoke(list, "getItems"); //$NON-NLS-1$
        if (items != null)
        {
            Method setItemSelectedColor = null;
            for (Object item : items)
            {
                if (setItemSelectedColor == null)
                    setItemSelectedColor = item.getClass().getMethod("setSelectedColor", Color.class); //$NON-NLS-1$
                if (getFieldValue(item, "selectedColor", Color.class) == null) //$NON-NLS-1$
                    setItemSelectedColor.invoke(item, fixColor);
            }
        }
        if (!paintedControl.isDisposed())
            paintedControl.redraw();
    }

    // --- разрешение модели попап-списка через SwtLightControl/SwtLightComposite ---

    private static volatile Method getSwtLightControlMethod;

    private static volatile Method getOverlaySourceMethod;

    private static volatile Method getSwtLightCompositeMethod;

    private static volatile Method getChildrenMethod;

    private static Object resolveLightListModel(Widget widget) throws ReflectiveOperationException
    {
        Object viaControl = resolveViaSwtLightControl(widget);
        if (viaControl != null && invoke(viaControl, "getItems") != null) //$NON-NLS-1$
            return viaControl;
        return resolveViaSwtLightComposite(widget);
    }

    private static Object resolveViaSwtLightControl(Widget widget) throws ReflectiveOperationException
    {
        Method getSwtLightControl = getSwtLightControlMethod;
        Method getOverlaySource = getOverlaySourceMethod;
        if (getSwtLightControl == null || getOverlaySource == null)
        {
            Bundle lwtBundle = Platform.getBundle(LWT_BUNDLE_ID);
            if (lwtBundle == null)
                return null;
            Class<?> swtLightControlClass = lwtBundle.loadClass("com._1c.g5.lwt.interop.SwtLightControl"); //$NON-NLS-1$
            getSwtLightControl = swtLightControlClass.getMethod("getHostSwtLightControl", Widget.class); //$NON-NLS-1$
            getOverlaySource = swtLightControlClass.getMethod("getOverlaySource"); //$NON-NLS-1$
            getSwtLightControlMethod = getSwtLightControl;
            getOverlaySourceMethod = getOverlaySource;
        }
        Object swtLightControl = getSwtLightControl.invoke(null, widget);
        if (swtLightControl == null)
            return null;
        return getOverlaySource.invoke(swtLightControl);
    }

    private static Object resolveViaSwtLightComposite(Widget widget) throws ReflectiveOperationException
    {
        Method getSwtLightComposite = getSwtLightCompositeMethod;
        Method getChildren = getChildrenMethod;
        if (getSwtLightComposite == null || getChildren == null)
        {
            Bundle lwtBundle = Platform.getBundle(LWT_BUNDLE_ID);
            if (lwtBundle == null)
                return null;
            Class<?> swtLightCompositeClass = lwtBundle.loadClass("com._1c.g5.lwt.interop.SwtLightComposite"); //$NON-NLS-1$
            getSwtLightComposite = swtLightCompositeClass.getMethod("getSwtLightComposite", Widget.class); //$NON-NLS-1$
            getChildren = swtLightCompositeClass.getMethod("getChildren"); //$NON-NLS-1$
            getSwtLightCompositeMethod = getSwtLightComposite;
            getChildrenMethod = getChildren;
        }
        for (Control control = (widget instanceof Control c) ? c : null; control != null; control = control.getParent())
        {
            Object swtLightComposite = getSwtLightComposite.invoke(null, control);
            if (swtLightComposite == null)
                continue;
            Object found = findChildWithItems(swtLightComposite, getChildren, 0);
            if (found != null)
                return found;
        }
        return null;
    }

    private static Object findChildWithItems(Object lightComposite, Method getChildren, int depth) throws ReflectiveOperationException
    {
        if (depth > 6)
            return null;
        Iterable<?> children = (Iterable<?>)getChildren.invoke(lightComposite);
        for (Object child : children)
        {
            if (invoke(child, "getItems") != null) //$NON-NLS-1$
                return child;
            if (getChildren.getDeclaringClass().isInstance(child))
            {
                Object found = findChildWithItems(child, getChildren, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            return (T)m.invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getFieldValue(Object target, String fieldName, Class<T> type)
    {
        Class<?> c = target.getClass();
        while (c != null)
        {
            try
            {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return (T)f.get(target);
            }
            catch (NoSuchFieldException e)
            {
                c = c.getSuperclass();
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }
}
