package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.google.inject.Injector;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
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
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.osgi.framework.Bundle;

/**
 * Текущее (последнее активное) свойство в панели «Свойства»: имя свойства окрашивается
 * акцентным цветом, а при перезаполнении панели (очистка фильтра, повторный показ того же
 * объекта) строка этого свойства доводится до видимой области.
 *
 * <p>Геометрию строк и оверлей по LWT-канве не использует: подпись свойства —
 * {@code LightLabel} со штатными {@code setTextColor(Color)} и {@code invalidate()}.
 * Координаты подписи для прокрутки — через
 * {@link PropertySheetControlInterop#lightControlFromView} и
 * {@link PropertySheetControlInterop#liveLightDisplayBounds}.
 *
 * <p>Строки палитры берутся из {@code renderer.viewModelToView} — это
 * {@link java.util.LinkedHashMap} с порядком вставки: подпись ({@code LabelViewModel}) и
 * следом её редакторы, до следующей подписи или границы секции ({@code SectionViewModel});
 * тот же приём, что и в {@link PropertyNameIdentifierHook#findValueViewAfterLabel}.
 *
 * <p>Текущее свойство определяется по фокусу ввода: после каждого пользовательского события
 * внутри панели ищется редактор, у которого {@code isFocused()} / {@code isFocusControl()},
 * и запоминается ТЕКСТ его подписи (а не контрол — при перезаполнении контролы
 * пересоздаются). Подсветка не гаснет при уходе фокуса из панели.
 */
public class PropertySheetActivePropertyHook implements IStartup
{
    private static final String TEMP_TOPIC = "свойства-активное"; //$NON-NLS-1$
    private static final String LABEL_VIEW_MODEL = "LabelViewModel"; //$NON-NLS-1$
    private static final String SECTION_VIEW_MODEL = "SectionViewModel"; //$NON-NLS-1$
    /** Пауза дебаунса пересинхронизации после перерисовки палитры. */
    private static final int SYNC_DELAY_MS = 100;
    /** Отступ от края видимой области при доводке строки до видимости. */
    private static final int SCROLL_MARGIN = 8;
    /** Глубина спуска по LWT-детям при поиске сфокусированного поля. */
    private static final int LIGHT_DEPTH = 4;

    /** Панели «Свойства», за которыми уже следим. */
    private static final Set<IViewPart> HOOKED_VIEWS =
        Collections.newSetFromMap(new WeakHashMap<>());
    /** Контролы палитры, на которых уже висит слушатель перерисовки. */
    private static final Set<Control> WATCHED_CONTROLS =
        Collections.newSetFromMap(new WeakHashMap<>());

    /** Текст подписи текущего свойства; {@code null} — активного свойства нет. */
    private static String activePropertyName;
    /** Окрашенная сейчас подпись ({@code LightLabel}). */
    private static Object highlightedLabel;
    /** Исходный цвет окрашенной подписи ({@code null} — цвет по умолчанию). */
    private static Color originalTextColor;
    /** Исходный шрифт окрашенной подписи ({@code null} — шрифт SWT-хоста). */
    private static Font originalFont;
    /** Исходные границы подписи — если под жирный текст пришлось расширять. */
    private static Rectangle originalBounds;
    /** Исходный {@code cachedExtent} подписи (замер текста, который держит сам LightLabel). */
    private static Object originalExtent;
    /** Жирный шрифт реально применён — только тогда его и надо снимать. */
    private static boolean boldApplied;
    /** Полужирный шрифт подписи (создаётся один раз на палитру). */
    private static Font boldFont;
    /** Дебаунс: пересинхронизация уже запланирована. */
    private static boolean syncScheduled;
    /** Кэш контролов палитры — {@link #onUserInput} не должен обходить сцену на каждую клавишу. */
    private static IViewPart paletteView;
    private static Object palettePage;
    private static Composite paletteRoot;
    /** Контрол всей страницы панели (палитра + область фильтра) — для проверки «событие в панели». */
    private static Composite palettePageControl;
    /** Попытки найти палитру: строки панели наполняются асинхронно, окно ожидания ~20 с. */
    private static int resolveAttempts;
    private static Class<?> swtLightCompositeClass;
    /** Контрол, которому мы поставили свою подсказку (чужие тултипы EDT не трогаем). */
    private static Control tooltipControl;
    private static String tooltipProperty;
    private static String tooltipText;
    /** Подсказка для свойств палитры, которых нет в объектной модели платформы. */
    private static final String NO_PLATFORM_PROPERTY_TOOLTIP =
        "Свойство недоступно во встроенном языке"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(PropertySheetActivePropertyHook::install);
    }

    private static void install()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        Display display = Display.getDefault();
        Listener inputListener = event -> onUserInput(event);
        display.addFilter(SWT.MouseDown, inputListener);
        display.addFilter(SWT.KeyDown, inputListener);
        display.addFilter(SWT.FocusIn, inputListener);
        display.addFilter(SWT.MenuDetect, PropertySheetActivePropertyHook::onMenuDetect);
        display.addFilter(SWT.MouseHover, PropertySheetActivePropertyHook::onMouseHover);
        display.addFilter(SWT.MouseMove, PropertySheetActivePropertyHook::onMouseMove);

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
                    hookView((IViewPart)part);
            }
        });
    }

    private static void hookView(IViewPart view)
    {
        if (view == null)
            return;
        HOOKED_VIEWS.add(view);
        resolveAttempts = 0;
        scheduleSync();
    }

    // =========================================================================
    // Отслеживание текущего свойства
    // =========================================================================

    /**
     * Любой ввод внутри панели «Свойства» может сменить поле с фокусом. Внутри LWT фокус
     * переходит между light-контролами ОДНОГО SWT-канваса, поэтому SWT-события фокуса при
     * этом не приходят — определяем поле не по адресату события, а обходом сцены уже после
     * обработки события ({@code asyncExec}).
     */
    private static void onUserInput(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        if (!isInsidePanel(control))
            return;
        // Ввод в области фильтра тоже относится к панели: очистка фильтра перезаполняет
        // палитру, а значит подпись текущего свойства придётся красить заново.
        scheduleSync();
        IViewPart view = paletteView;
        // Клик мог попасть в саму подпись — там фокусируемого контрола нет, и по фокусу
        // свойство не определить; точку клика запоминаем в display-координатах (сам контрол
        // к моменту asyncExec может уже быть пересоздан).
        Point clickDisplay = event.type == SWT.MouseDown ? control.toDisplay(event.x, event.y) : null;
        Display.getDefault().asyncExec(() -> updateActiveProperty(view, control, clickDisplay));
    }

    /** Только сравнение ссылок по цепочке родителей — вызывается на каждую клавишу в EDT. */
    private static boolean isInsidePanel(Control control)
    {
        Composite root = palettePageControl != null && !palettePageControl.isDisposed()
            ? palettePageControl : paletteRoot;
        if (root == null || root.isDisposed() || paletteView == null)
            return false;
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == root)
                return true;
        }
        return false;
    }

    /**
     * Делает текущим свойство, по которому кликнули (если клик пришёлся в подпись), иначе —
     * свойство, чей редактор сейчас в фокусе.
     */
    private static void updateActiveProperty(IViewPart view, Control clicked, Point clickDisplay)
    {
        Map<?, ?> map = viewModelToView(view);
        if (map == null)
            return;
        if (clickDisplay != null && !clicked.isDisposed()
            && activateByLabelClick(view, map, clicked, clickDisplay))
            return;
        String labelText = null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            String keyClass = key == null ? "" : key.getClass().getName(); //$NON-NLS-1$
            if (keyClass.contains(LABEL_VIEW_MODEL))
            {
                labelText = labelText(key);
                continue;
            }
            if (keyClass.contains(SECTION_VIEW_MODEL))
            {
                labelText = null;
                continue;
            }
            if (labelText == null || labelText.isEmpty())
                continue;
            Object nativeControl = Global.invoke(entry.getValue(), "getNativeControl"); //$NON-NLS-1$
            if (!isFocusedDeep(nativeControl, 0))
                continue;
            setActiveProperty(view, labelText);
            return;
        }
    }

    /**
     * Клик по имени свойства: подпись сама фокус не принимает, поэтому свойство делается
     * текущим по попаданию точки клика в bounds её {@code LightLabel}, а ввод передаётся
     * первому фокусируемому редактору этого свойства (кнопка «...» — {@code ActionBarViewModel} —
     * берётся только если другого редактора у свойства нет).
     *
     * @return {@code true}, если клик пришёлся в подпись (определение по фокусу уже не нужно)
     */
    private static boolean activateByLabelClick(IViewPart view, Map<?, ?> map, Control clicked, Point click)
    {
        Object hit = lightControlAt(clicked, click);
        if (hit == null)
        {
            Global.tempLog(TEMP_TOPIC, "клик " + click.x + "," + click.y //$NON-NLS-1$ //$NON-NLS-2$
                + ": LWT-контрол не найден (виджет " + clicked.getClass().getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        String hitLabel = null;
        boolean rowStarted = false;
        List<Object> editors = new ArrayList<>();
        List<Object> actionBars = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            String keyClass = key == null ? "" : key.getClass().getName(); //$NON-NLS-1$
            boolean boundary = keyClass.contains(LABEL_VIEW_MODEL) || keyClass.contains(SECTION_VIEW_MODEL);
            if (boundary && rowStarted)
                break; // строка кликнутого свойства закончилась
            if (keyClass.contains(LABEL_VIEW_MODEL))
            {
                if (PropertySheetControlInterop.lightControlFromView(entry.getValue()) == hit)
                {
                    hitLabel = labelText(key);
                    rowStarted = true;
                }
                continue;
            }
            if (boundary || !rowStarted)
                continue;
            Object nativeControl = Global.invoke(entry.getValue(), "getNativeControl"); //$NON-NLS-1$
            if (nativeControl == null)
                continue;
            if (keyClass.contains("ActionBarViewModel")) //$NON-NLS-1$
                actionBars.add(nativeControl);
            else
                editors.add(nativeControl);
        }
        if (hitLabel == null || hitLabel.isEmpty())
        {
            Global.tempLog(TEMP_TOPIC, "клик: под точкой " + click.x + "," + click.y //$NON-NLS-1$ //$NON-NLS-2$
                + " не подпись, а " + hit.getClass().getName()); //$NON-NLS-1$
            return false;
        }

        setActiveProperty(view, hitLabel);
        editors.addAll(actionBars);
        for (Object editor : editors)
        {
            if (AefFieldFocus.focusNativeControl(editor))
            {
                Global.tempLog(TEMP_TOPIC, "клик по имени «" + hitLabel + "»: фокус в поле"); //$NON-NLS-1$ //$NON-NLS-2$
                return true;
            }
        }
        Global.tempLog(TEMP_TOPIC, "клик по имени «" + hitLabel //$NON-NLS-1$
            + "»: фокусируемого поля нет (редакторов " + editors.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    /**
     * LWT-контрол под точкой клика. Своей геометрии не считаем (в июньской попытке именно на
     * ней всё и сломалось): у {@code SwtLightComposite} есть штатный хиттест
     * {@code controlFromPoint(x, y)} в координатах его SWT-композита. Клик мог прийти в
     * произвольный SWT-контрол внутри панели, поэтому поднимаемся по родителям до первого,
     * у которого есть привязанный {@code SwtLightComposite}.
     */
    private static Object lightControlAt(Control clicked, Point display)
    {
        Class<?> hostClass = swtLightCompositeClass();
        if (hostClass == null)
            return null;
        for (Control c = clicked; c != null && !c.isDisposed(); c = c.getParent())
        {
            Object host = Global.invoke(hostClass, "getSwtLightComposite", c); //$NON-NLS-1$
            if (host == null)
                continue;
            Object swtComposite = Global.invoke(host, "getSwtComposite"); //$NON-NLS-1$
            if (!(swtComposite instanceof Control hostControl) || hostControl.isDisposed())
                continue;
            Point local = hostControl.toControl(display);
            Object hit = Global.invoke(host, "controlFromPoint", //$NON-NLS-1$
                Integer.valueOf(local.x), Integer.valueOf(local.y));
            if (hit != null)
                return hit;
        }
        return null;
    }

    /** {@code com._1c.g5.lwt.interop.SwtLightComposite} из classloader'а бандла LWT. */
    private static Class<?> swtLightCompositeClass()
    {
        Class<?> cached = swtLightCompositeClass;
        if (cached != null)
            return cached;
        Object sample = PropertySheetControlInterop.lightControlFromView(anyLabelView());
        if (sample == null)
            return null;
        try
        {
            cached = Class.forName("com._1c.g5.lwt.interop.SwtLightComposite", //$NON-NLS-1$
                false, sample.getClass().getClassLoader());
            swtLightCompositeClass = cached;
            return cached;
        }
        catch (ClassNotFoundException e)
        {
            Global.tempLog(TEMP_TOPIC, "SwtLightComposite не найден в classloader'е LWT"); //$NON-NLS-1$
            return null;
        }
    }

    /** Любая подпись палитры — нужна только как источник classloader'а бандла LWT. */
    private static Object anyLabelView()
    {
        Map<?, ?> map = paletteView != null ? viewModelToView(paletteView) : null;
        if (map == null)
            return null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key != null && key.getClass().getName().contains(LABEL_VIEW_MODEL))
                return entry.getValue();
        }
        return null;
    }

    private static void setActiveProperty(IViewPart view, String labelText)
    {
        if (labelText.equals(activePropertyName))
            return;
        Global.tempLog(TEMP_TOPIC, "текущее свойство: " + labelText); //$NON-NLS-1$
        activePropertyName = labelText;
        clearHighlight();
        applyHighlight(view, false);
    }

    /** Карта {@code viewModel → view} рендерера сцены панели (порядок вставки — порядок строк). */
    private static Map<?, ?> viewModelToView(IViewPart view)
    {
        Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object map = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        return map instanceof Map<?, ?> m ? m : null;
    }

    /** Текст подписи {@code LabelViewModel} (двоеточие в конце панель рисует сама). */
    private static String labelText(Object labelViewModel)
    {
        Object text = Global.invoke(labelViewModel, "getText"); //$NON-NLS-1$
        if (text == null)
            text = Global.getField(labelViewModel, "text"); //$NON-NLS-1$
        return text instanceof String s ? s : ""; //$NON-NLS-1$
    }

    /**
     * Поле в фокусе. Нативный контрол поля LWT — часто контейнер ({@code LightEditorBar}),
     * а фокус держит вложенный {@code LightText}, поэтому спускаемся по LWT-детям
     * (тот же обход, что в {@link AefFieldFocus}).
     */
    private static boolean isFocusedDeep(Object nativeControl, int depth)
    {
        if (nativeControl == null || depth > LIGHT_DEPTH)
            return false;
        if (nativeControl instanceof Control control)
            return !control.isDisposed() && control.isFocusControl();
        if (Boolean.TRUE.equals(Global.invoke(nativeControl, "isFocused"))) //$NON-NLS-1$
            return true;
        Object children = Global.invoke(nativeControl, "getChildren"); //$NON-NLS-1$
        if (children instanceof Iterable<?> iterable)
        {
            for (Object child : iterable)
            {
                if (isFocusedDeep(child, depth + 1))
                    return true;
            }
        }
        for (String getter : new String[] { "getContent", "getLeftSide", "getRightSide" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            if (isFocusedDeep(Global.invoke(nativeControl, getter), depth + 1))
                return true;
        }
        return false;
    }

    // =========================================================================
    // Окраска имени и доводка до видимой области
    // =========================================================================

    /**
     * Возвращает подсветку на подпись текущего свойства.
     *
     * @param afterRebuild панель перезаполнена (подпись пересоздана) — строку нужно ещё и
     *        довести до видимой области
     */
    private static void applyHighlight(IViewPart view, boolean afterRebuild)
    {
        if (activePropertyName == null)
            return;
        Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        Map<?, ?> map = viewModelToView(view);
        if (page == null || map == null)
            return;

        Object labelView = null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key == null || !key.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            if (activePropertyName.equals(labelText(key)))
            {
                labelView = entry.getValue();
                break;
            }
        }
        if (labelView == null)
            return;

        Object label = PropertySheetControlInterop.lightControlFromView(labelView);
        if (label == null || !label.getClass().getName().contains("LightLabel")) //$NON-NLS-1$
        {
            Global.tempLog(TEMP_TOPIC, "подпись не LightLabel: " //$NON-NLS-1$
                + (label == null ? "<null>" : label.getClass().getName())); //$NON-NLS-1$
            return;
        }
        if (label == highlightedLabel)
        {
            if (afterRebuild)
                revealLabel(page, labelView);
            return;
        }

        originalTextColor = colorField(label);
        originalFont = fontField(label);
        originalBounds = null;
        originalExtent = null;
        boldApplied = false;
        highlightedLabel = label;
        // setTextColor/setFont сами вызывают invalidate() — отдельная перерисовка не нужна.
        applyBold(label);
        Global.invokeVoid(label, "setTextColor", accentColor()); //$NON-NLS-1$
        Global.tempLog(TEMP_TOPIC, "окрашено «" + activePropertyName + "»" //$NON-NLS-1$ //$NON-NLS-2$
            + (afterRebuild ? " (после перезаполнения)" : "")); //$NON-NLS-1$ //$NON-NLS-2$
        if (afterRebuild)
            revealLabel(page, labelView);
    }

    /** Снимает подсветку с прежней подписи (возвращает исходные цвет, шрифт и границы). */
    private static void clearHighlight()
    {
        Object label = highlightedLabel;
        highlightedLabel = null;
        Color original = originalTextColor;
        Font font = originalFont;
        Rectangle bounds = originalBounds;
        Object extent = originalExtent;
        boolean bold = boldApplied;
        originalTextColor = null;
        originalFont = null;
        originalBounds = null;
        originalExtent = null;
        boldApplied = false;
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return;
        if (bold)
        {
            // Порядок важен: setFont сбрасывает cachedExtent, поэтому сохранённый замер
            // возвращается ПОСЛЕ смены шрифта. Без него подпись остаётся обрезанной:
            // при пустом cachedExtent LightLabel меряет текст заново, шрифтом GC на момент
            // отрисовки, и результат может не совпасть с шириной, посчитанной при разметке.
            Global.invokeVoid(label, "setFont", font); //$NON-NLS-1$
            if (bounds != null)
                Global.invokeVoid(label, "setBounds", bounds); //$NON-NLS-1$
            Global.setField(label, "cachedExtent", extent); //$NON-NLS-1$
            Global.invokeVoid(label, "invalidate"); //$NON-NLS-1$
        }
        // null допустим: LightLabel.paint при пустом textColor берёт цвет из SWT-хоста.
        Global.invokeVoid(label, "setTextColor", original); //$NON-NLS-1$
    }

    /**
     * Доводит строку свойства до видимой области прокручиваемой палитры. Если строка и так
     * видна целиком — прокрутка не трогается (иначе панель дёргалась бы при каждом
     * перезаполнении).
     */
    private static void revealLabel(Object page, Object labelView)
    {
        ScrolledComposite scrolled = PropertySheetUiContext.findPaletteScrolledComposite(page);
        if (scrolled == null || scrolled.isDisposed())
            return;
        Rectangle labelBounds = PropertySheetControlInterop.liveLightDisplayBounds(labelView);
        if (labelBounds == null)
            return;

        Point scrolledOrigin = scrolled.toDisplay(0, 0);
        Rectangle client = scrolled.getClientArea();
        int top = labelBounds.y - scrolledOrigin.y;
        int bottom = top + labelBounds.height;
        int delta = 0;
        if (top < 0)
            delta = top - SCROLL_MARGIN;
        else if (bottom > client.height)
            delta = bottom - client.height + SCROLL_MARGIN;
        if (delta == 0)
            return;

        Point origin = scrolled.getOrigin();
        int y = Math.max(0, origin.y + delta);
        scrolled.setOrigin(origin.x, y);
        Global.tempLog(TEMP_TOPIC, "прокрутка к «" + activePropertyName //$NON-NLS-1$
            + "»: origin.y " + origin.y + " → " + y); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Цвет имени текущего свойства. Синий ({@code COLOR_LIST_SELECTION}) не годится — на экране
     * не отличить от гиперссылки, которых в панели хватает; красный читался бы как ошибка.
     * Пурпурный в EDT ничем не занят. В тёмной теме {@link ThemeAwareColors} инвертирует
     * светлоту, оставляя тон.
     */
    private static Color accentColor()
    {
        return ThemeAwareColors.effectiveSystemColor(Display.getDefault(), SWT.COLOR_DARK_MAGENTA);
    }

    private static Color colorField(Object label)
    {
        Object color = Global.getField(label, "textColor"); //$NON-NLS-1$
        return color instanceof Color c && !c.isDisposed() ? c : null;
    }

    /**
     * Делает имя свойства полужирным. Границы подписи палитра выставляет ровно по ширине её
     * текста обычным шрифтом (замер лога: жирный не влезал ни в одну строку), поэтому одной
     * смены шрифта мало — {@code LightLabel.paint} обрежет текст многоточием. Подпись
     * расширяется на нехватку, но только в пределах свободного места СВОЕЙ строки: правая
     * граница — ближайший сосед справа, иначе край родительского контейнера. Колонка значений
     * при этом не двигается (разметку не пересчитываем). Места не хватило — остаётся только цвет.
     *
     * <p>Замер повторяет {@code LightLabel.computeSize}: {@code GC.textExtent} с теми же флагами.
     */
    private static void applyBold(Object label)
    {
        Font bold = boldFont(originalFont);
        String text = Global.invoke(label, "getText") instanceof String s ? s : ""; //$NON-NLS-1$
        Rectangle bounds = Global.invoke(label, "getBounds") instanceof Rectangle r ? r : null; //$NON-NLS-1$
        Control host = hostControl(label);
        if (text.isEmpty() || bounds == null || bounds.width <= 0 || host == null)
            return;

        int needed;
        GC gc = new GC(host);
        try
        {
            gc.setFont(bold);
            needed = gc.textExtent(text, SWT.DRAW_DELIMITER | SWT.DRAW_TAB
                | SWT.DRAW_MNEMONIC | SWT.DRAW_TRANSPARENT).x + marginsWidth(label);
        }
        finally
        {
            gc.dispose();
        }

        int room = Math.max(bounds.width, freeWidth(label, bounds));
        if (needed > room)
        {
            Global.tempLog(TEMP_TOPIC, "жирный пропущен: нужно " + needed + ", есть " + room //$NON-NLS-1$ //$NON-NLS-2$
                + " (" + activePropertyName + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        originalExtent = Global.getField(label, "cachedExtent"); //$NON-NLS-1$
        if (needed > bounds.width)
        {
            originalBounds = bounds;
            Global.invokeVoid(label, "setBounds", //$NON-NLS-1$
                new Rectangle(bounds.x, bounds.y, needed, bounds.height));
        }
        Global.invokeVoid(label, "setFont", bold); //$NON-NLS-1$
        boldApplied = true;
    }

    /** Ширина от левого края подписи до ближайшего соседа справа (или до края контейнера). */
    private static int freeWidth(Object label, Rectangle bounds)
    {
        Object parent = Global.invoke(label, "getParent"); //$NON-NLS-1$
        if (parent == null)
            return -1;
        int limit = Global.invoke(parent, "getBounds") instanceof Rectangle pb ? pb.width : -1; //$NON-NLS-1$
        Object children = Global.invoke(parent, "getChildren"); //$NON-NLS-1$
        if (children instanceof Iterable<?> iterable)
        {
            for (Object child : iterable)
            {
                if (child == label)
                    continue;
                if (!(Global.invoke(child, "getBounds") instanceof Rectangle cb)) //$NON-NLS-1$
                    continue;
                if (cb.x >= bounds.x + bounds.width && (limit < 0 || cb.x < limit))
                    limit = cb.x;
            }
        }
        return limit < 0 ? -1 : limit - bounds.x;
    }

    private static int marginsWidth(Object label)
    {
        Object margins = Global.getField(label, "margins"); //$NON-NLS-1$
        Object width = margins != null ? Global.invoke(margins, "getWidth") : null; //$NON-NLS-1$
        return width instanceof Integer i ? i.intValue() : 0;
    }

    /** SWT-контрол, на котором рисуется light-контрол. */
    private static Control hostControl(Object lightControl)
    {
        Class<?> hostClass = swtLightCompositeClass();
        Object host = hostClass != null
            ? Global.invoke(hostClass, "getHostSwtLightComposite", lightControl) : null; //$NON-NLS-1$
        Object swtComposite = host != null ? Global.invoke(host, "getSwtComposite") : null; //$NON-NLS-1$
        return swtComposite instanceof Control c && !c.isDisposed() ? c : null;
    }

    private static Font fontField(Object label)
    {
        Object font = Global.getField(label, "font"); //$NON-NLS-1$
        return font instanceof Font f && !f.isDisposed() ? f : null;
    }

    /**
     * Полужирный вариант шрифта подписи. У {@code LightLabel} свой шрифт обычно не задан —
     * тогда основой служит шрифт SWT-хоста палитры. Экземпляр один на всё время работы:
     * шрифт — системный ресурс, создавать его на каждую смену свойства нельзя.
     */
    private static Font boldFont(Font labelFont)
    {
        Font cached = boldFont;
        if (cached != null && !cached.isDisposed())
            return cached;
        Font base = labelFont;
        if (base == null || base.isDisposed())
            base = paletteRoot != null && !paletteRoot.isDisposed() ? paletteRoot.getFont() : null;
        Display display = Display.getDefault();
        if (base == null || base.isDisposed())
            base = display.getSystemFont();
        FontData[] data = base.getFontData();
        for (FontData item : data)
            item.setStyle(item.getStyle() | SWT.BOLD);
        cached = new Font(display, data);
        boldFont = cached;
        Font owned = cached;
        display.disposeExec(() ->
        {
            if (!owned.isDisposed())
                owned.dispose();
        });
        return cached;
    }

    // =========================================================================
    // Перезаполнение палитры
    // =========================================================================

    /**
     * Перезаполнение панели (очистка фильтра, смена объекта) пересоздаёт подписи, и окраска
     * пропадает вместе со старым {@code LightLabel}. Отдельного события об этом нет, поэтому
     * ориентир — перерисовка/изменение размера палитры: слушатель ставится на прокручиваемый
     * контейнер (он живёт дольше содержимого) и на само содержимое.
     */
    private static void ensureRebuildWatch(Object page)
    {
        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        if (root == null || root.isDisposed())
            return;
        paletteRoot = root;
        Object pageControl = Global.invoke(page, "getControl"); //$NON-NLS-1$
        palettePageControl = pageControl instanceof Composite c && !c.isDisposed() ? c : root;
        watch(root);
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

    private static void scheduleSync()
    {
        if (syncScheduled)
            return;
        syncScheduled = true;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(SYNC_DELAY_MS, () ->
        {
            syncScheduled = false;
            sync();
        });
    }

    /**
     * Дешёвая проверка: пока окрашенная подпись жива, полный обход карты не нужен —
     * перерисовки палитры идут часто (мигание каретки в поле ввода — тоже перерисовка).
     */
    private static void sync()
    {
        resolvePalette();
        IViewPart view = paletteView;
        if (paletteRoot == null || paletteRoot.isDisposed())
        {
            // Палитра ещё не построена: своих событий, чтобы дождаться, у нас пока нет
            // (слушатели ставятся как раз на её контролы) — ограниченная серия попыток.
            if (resolveAttempts++ < 100)
                scheduleSync();
            return;
        }
        resolveAttempts = 0;
        if (view == null || activePropertyName == null)
            return;
        if (isHighlightAlive())
            return;
        // Подпись пересоздана (или переиспользована под другое свойство) — красим заново
        // и доводим строку до видимой области.
        highlightedLabel = null;
        originalTextColor = null;
        originalFont = null;
        originalBounds = null;
        originalExtent = null;
        boldApplied = false;
        applyHighlight(view, true);
    }

    /** Окрашенная подпись жива И всё ещё принадлежит текущему свойству. */
    private static boolean isHighlightAlive()
    {
        Object label = highlightedLabel;
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return false;
        // LWT-контролы палитры могут переиспользоваться: тот же LightLabel после
        // перезаполнения показывает уже другое свойство — тогда цвет надо вернуть на место.
        Object text = Global.invoke(label, "getText"); //$NON-NLS-1$
        if (activePropertyName.equals(text))
            return true;
        clearHighlight();
        return false;
    }

    // =========================================================================
    // Контекстное меню и подсказка по имени свойства
    // =========================================================================

    /** Свойство под точкой: отображаемое имя + view его подписи. */
    private static final class HitProperty
    {
        final String name;
        final Object labelView;

        HitProperty(String name, Object labelView)
        {
            this.name = name;
            this.labelView = labelView;
        }
    }

    /**
     * Имя свойства, чьё ЗНАЧЕНИЕ (поле ввода, гиперссылка, флажок) находится под точкой экрана;
     * {@code null} — точка не в палитре или не над полем значения.
     *
     * <p>В отличие от {@link #propertyAt} ищется не подпись, а поле: карта
     * {@code viewModelToView} идёт в порядке построения палитры, поэтому имя поля — это текст
     * ближайшей ПРЕДЫДУЩЕЙ подписи (тот же приём, что у
     * {@code PropertyNameIdentifierHook.findValueViewAfterLabel}).
     *
     * <p>Нужно, чтобы отличать щелчок по конкретной гиперссылке палитры от любого другого щелчка
     * в панели «Свойства» (см. {@code FormEditorHook.AppearancePage}).
     */
    static String valuePropertyNameAt(Control control, Point display)
    {
        if (control == null || control.isDisposed() || !isInsidePanel(control))
            return null;
        Object hit = lightControlAt(control, display);
        Map<?, ?> map = hit != null && paletteView != null ? viewModelToView(paletteView) : null;
        if (map == null)
            return null;
        String label = null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key == null)
                continue;
            if (key.getClass().getName().contains(LABEL_VIEW_MODEL))
            {
                String text = labelText(key);
                if (!text.isEmpty())
                    label = text;
                continue;
            }
            if (PropertySheetControlInterop.lightControlFromView(entry.getValue()) == hit)
                return label;
        }
        return null;
    }

    /** Свойство, чья подпись находится под точкой (штатный LWT-хиттест, своей геометрии нет). */
    private static HitProperty propertyAt(Control control, Point display)
    {
        Object hit = lightControlAt(control, display);
        Map<?, ?> map = hit != null && paletteView != null ? viewModelToView(paletteView) : null;
        if (map == null)
            return null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key == null || !key.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            if (PropertySheetControlInterop.lightControlFromView(entry.getValue()) != hit)
                continue;
            String name = labelText(key);
            return name.isEmpty() ? null : new HitProperty(name, entry.getValue());
        }
        return null;
    }

    /**
     * Правый клик по имени свойства — своё меню вместо штатного (у подписи палитры его нет,
     * но родительский контрол может отдать чужое).
     */
    private static void onMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed()
            || !isInsidePanel(control))
            return;
        Point display = new Point(event.x, event.y);
        HitProperty hit = propertyAt(control, display);
        if (hit == null)
            return;
        event.doit = false;
        showPropertyMenu(control, display, hit);
    }

    private static void showPropertyMenu(Control control, Point display, HitProperty hit)
    {
        Menu menu = new Menu(control.getShell(), SWT.POP_UP);

        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать имя"); //$NON-NLS-1$
        copyItem.setToolTipText("Копировать имя свойства в буфер обмена" //$NON-NLS-1$
            + Global.pluginSignForTooltip());
        copyItem.addListener(SWT.Selection, e -> copyPropertyName(control, hit));

        // «Синтакс-помощник» показывается, только если справка по свойству реально есть:
        // у свойств, которых нет в объектной модели платформы, открывать нечего, и пункт,
        // ведущий в пустоту, из меню убираем (разбор кэшируется, повторные вызовы дешёвые).
        SyntaxHelp.PropertyDoc doc =
            SyntaxHelp.resolve(palettePage, sceneForCopy(), hit.labelView, hit.name);
        if (doc != null && doc.viewPage != null)
        {
            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem syntaxItem = new MenuItem(menu, SWT.PUSH);
            syntaxItem.setText("Синтакс-помощник"); //$NON-NLS-1$
            syntaxItem.setToolTipText("Открыть справку по свойству в синтакс-помощнике" //$NON-NLS-1$
                + Global.pluginSignForTooltip());
            syntaxItem.addListener(SWT.Selection,
                e -> SyntaxHelp.open(palettePage, sceneForCopy(), hit.labelView, hit.name));
        }

        menu.addListener(SWT.Hide, e -> control.getDisplay().asyncExec(() ->
        {
            if (!menu.isDisposed())
                menu.dispose();
        }));
        menu.setLocation(display);
        menu.setVisible(true);
    }

    private static Object sceneForCopy()
    {
        return palettePage != null ? Global.invoke(palettePage, "getScene") : null; //$NON-NLS-1$
    }

    /** Английское имя признака модели ({@code conditionalAppearance}) для строки палитры. */
    private static String englishFeature(HitProperty hit)
    {
        String resolved = PropertySheetControlInterop.resolveCopyPropertyName(palettePage,
            sceneForCopy(), hit.labelView, hit.name);
        return resolved != null ? resolved : ""; //$NON-NLS-1$
    }

    /**
     * В буфер уходит имя свойства так, как оно называется во встроенном языке
     * ({@code УсловноеОформление}). Подпись палитры («Условное оформление») именем НЕ является —
     * это отдельный текст с пробелами, поэтому русское имя берётся из документации платформы по
     * английскому имени признака с учётом класса-владельца (см. {@link SyntaxHelp#resolve}).
     * Если документация свойство не дала — копируется английское имя, и только если нет и его —
     * подпись.
     */
    private static void copyPropertyName(Control control, HitProperty hit)
    {
        String english = PropertySheetControlInterop.resolveModelPropertyName(palettePage,
            sceneForCopy(), hit.labelView, hit.name);
        if (english == null || english.isEmpty())
            english = englishFeature(hit);
        String text;
        try
        {
            text = PropertySheetPlatformPropertyResolver.russianNameForCopy(palettePage,
                sceneForCopy(), hit.labelView, hit.name, english);
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "копирование «" + hit.name + "»", e); //$NON-NLS-1$ //$NON-NLS-2$
            text = english;
            if (text == null || text.isEmpty())
                text = hit.name;
        }
        PropertySheetUiContext.copyToClipboard(control, text);
        ToastNotification.show("Скопировано", text, 2_500); //$NON-NLS-1$
        Global.tempLog(TEMP_TOPIC, "копирование «" + hit.name + "»: признак=" + english //$NON-NLS-1$ //$NON-NLS-2$
            + ", в буфер=" + text); //$NON-NLS-1$
    }

    /**
     * Пауза указателя над именем свойства — подсказка с описанием из синтакс-помощника.
     * Текст ставится штатным SWT-тултипом того же контрола, поверх которого рисует LWT.
     */
    private static void onMouseHover(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed()
            || !isInsidePanel(control))
            return;
        HitProperty hit = propertyAt(control, control.toDisplay(event.x, event.y));
        if (hit == null)
            return;
        if (control == tooltipControl && hit.name.equals(tooltipProperty))
            return;
        String text = SyntaxHelp.describe(palettePage, sceneForCopy(), hit.labelView, hit.name);
        if (text == null || text.isEmpty())
        {
            // Часть свойств палитры существует только в метаданных: описания у них нет и быть
            // не может. Пустая подсказка выглядела бы как «не успело загрузиться», поэтому
            // говорим прямо.
            text = NO_PLATFORM_PROPERTY_TOOLTIP;
        }
        text = TooltipText.wrap(control, text);
        tooltipControl = control;
        tooltipProperty = hit.name;
        tooltipText = text;
        control.setToolTipText(text);
    }

    /**
     * Указатель ушёл — снимаем свою подсказку. Снимаем ТОЛЬКО свою: у контролов панели могут
     * быть штатные тултипы EDT, затирать их нельзя.
     */
    private static void onMouseMove(Event event)
    {
        if (tooltipControl == null || event.widget != tooltipControl)
            return;
        if (tooltipControl.isDisposed())
        {
            tooltipControl = null;
            return;
        }
        if (!tooltipText.equals(tooltipControl.getToolTipText()))
            return;
        tooltipControl.setToolTipText(null);
        tooltipControl = null;
        tooltipProperty = null;
    }

    /**
     * Текущие страница и корень палитры. Страница панели пересоздаётся при смене типа
     * выбранного объекта, поэтому она перечитывается всегда (пара reflection-вызовов), а
     * заметно более дорогой поиск корня — только когда страница сменилась или корень умер.
     */
    private static void resolvePalette()
    {
        for (IViewPart view : HOOKED_VIEWS)
        {
            Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
            if (page == null)
                continue;
            paletteView = view;
            if (page != palettePage || paletteRoot == null || paletteRoot.isDisposed())
            {
                palettePage = page;
                ensureRebuildWatch(page);
            }
            return;
        }
    }

    /**
     * Документация платформы по свойству палитры: русское имя свойства, страница для панели
     * «Синтакс-помощник» и текст описания для подсказки при наведении.
     *
     * <p><b>Почему не через {@code Class.forName}.</b> Все нужные классы лежат во внутренних
     * пакетах бандлов {@code com._1c.g5.v8.dt.bsl.ui} и {@code com._1c.g5.v8.dt.platform.doc};
     * эти пакеты наружу не экспортируются, и {@code Class.forName} из НАШЕГО бандла падает с
     * {@code ClassNotFoundException} (подтверждено логом). Классы грузятся загрузчиком самого
     * бандла — {@code Platform.getBundle(...).loadClass(...)}, он ограничения экспорта не
     * проверяет. По той же причине не работал июньский вариант, перенесённый сюда ранее.
     *
     * <p><b>Как ищется свойство.</b> Подпись палитры («Условное оформление») именем свойства не
     * является, поэтому идём от модели платформы, а не от текстов документации: сначала
     * ТИП-ВЛАДЕЛЕЦ объекта панели ({@code FormItemInformationService.getTypeOfFormItem}), затем
     * его свойство по английскому имени признака ({@code conditionalAppearance}) среди
     * {@code Type.getContextDef().allProperties()}. У найденного {@code Property} есть оба
     * имени — {@code getName()} и {@code getNameRu()}; русское идёт в буфер обмена. Страница
     * синтакс-помощника запрашивается уже по самому свойству
     * ({@code getViewDocumentationPages(EObject)}), а не поиском по названиям.
     */
    private static final class SyntaxHelp
    {
        private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
        private static final String BSL_ACTIVATOR =
            "com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"; //$NON-NLS-1$
        private static final String DOC_PROVIDER =
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"; //$NON-NLS-1$
        private static final String VIEW_PAGE =
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.IBslDocumentationViewPage"; //$NON-NLS-1$
        private static final String PAGE_DESCRIPTOR =
            "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.description.DocumentationPageDescriptor"; //$NON-NLS-1$
        private static final String SYNTAX_VIEW_UTIL =
            "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.SyntaxAssistViewUtil"; //$NON-NLS-1$
        private static final String SYNTAX_VIEW_ID = "com._1c.g5.v8.dt.bsl.ui.view.BslInfoView"; //$NON-NLS-1$
        /** Подсказка — не статья: длинное описание обрезается. */
        private static final int MAX_TOOLTIP_CHARS = 1200;
        /** Сколько кандидатов выписывать в лог при неоднозначности/промахе. */
        private static final int LOG_CANDIDATES = 10;

        /** Найденное свойство или событие в документации платформы. */
        static final class PropertyDoc
        {
            /** Имя во встроенном языке ({@code УсловноеОформление}); {@code null} — не найдено. */
            final String russianName;
            /** Страница синтакс-помощника; {@code null} — узел найден, а страница не собралась. */
            final Object viewPage;
            /** Ключ кэша описания. */
            final String cacheKey;

            PropertyDoc(String russianName, Object viewPage, String cacheKey)
            {
                this.russianName = russianName;
                this.viewPage = viewPage;
                this.cacheKey = cacheKey;
            }
        }

        /** Результаты по ключу «класс объекта/английское имя». */
        private static final Map<String, PropertyDoc> RESOLVED = new HashMap<>();
        private static final Map<String, String> DESCRIPTIONS = new HashMap<>();
        private static final PropertyDoc NOTHING = new PropertyDoc(null, null, ""); //$NON-NLS-1$
        /** {@code BslDocumentationProvider} — Guice-синглтон, ищется один раз. */
        private static Object docProviderCache;

        private SyntaxHelp() {}

        static PropertyDoc resolve(Object page, Object scene, Object lwtView, String displayName)
        {
            String englishHint = PropertySheetControlInterop.resolveModelPropertyName(page, scene,
                lwtView, displayName);

            PropertySheetPlatformPropertyResolver.Resolved propertyResolved =
                PropertySheetPlatformPropertyResolver.resolve(page, scene, lwtView, displayName,
                    englishHint);
            if (propertyResolved != null && propertyResolved.property != null)
                return resolvePropertyDoc(propertyResolved);

            PropertySheetPlatformPropertyResolver.ResolvedEvent eventResolved =
                PropertySheetPlatformPropertyResolver.resolveEvent(page, scene, lwtView,
                    displayName, englishHint);
            if (eventResolved != null && eventResolved.event != null)
                return resolveEventDoc(eventResolved);

            return null;
        }

        private static PropertyDoc resolvePropertyDoc(
                PropertySheetPlatformPropertyResolver.Resolved resolved)
        {
            String english = resolved.englishName();
            if (english == null || english.isEmpty())
                return null;
            String typeKey = resolved.ownerType != null
                ? McoreUtil.getTypeName(resolved.ownerType) : "?"; //$NON-NLS-1$
            String key = typeKey + "/prop/" + english; //$NON-NLS-1$
            PropertyDoc cached = RESOLVED.get(key);
            if (cached != null)
                return cached == NOTHING ? null : cached;

            PropertyDoc found = null;
            long started = System.currentTimeMillis();
            try
            {
                Object viewPage = propertyPage(resolved.property);
                found = new PropertyDoc(resolved.russianName(), viewPage, key);
            }
            catch (Exception e)
            {
                Global.tempLogException(TEMP_TOPIC, "документация: " + key, e); //$NON-NLS-1$
            }
            RESOLVED.put(key, found != null ? found : NOTHING);
            Global.tempLog(TEMP_TOPIC, "документация «" + key + "»: " //$NON-NLS-1$ //$NON-NLS-2$
                + (found != null ? "имя=" + found.russianName + ", страница=" + (found.viewPage != null) //$NON-NLS-1$ //$NON-NLS-2$
                    : "не найдено") //$NON-NLS-1$
                + ", " + (System.currentTimeMillis() - started) + " мс"); //$NON-NLS-1$ //$NON-NLS-2$
            return found;
        }

        private static PropertyDoc resolveEventDoc(
                PropertySheetPlatformPropertyResolver.ResolvedEvent resolved)
        {
            String english = resolved.englishName();
            if (english == null || english.isEmpty())
                return null;
            String typeKey = resolved.ownerType != null
                ? McoreUtil.getTypeName(resolved.ownerType) : "?"; //$NON-NLS-1$
            String key = typeKey + "/event/" + english; //$NON-NLS-1$
            PropertyDoc cached = RESOLVED.get(key);
            if (cached != null)
                return cached == NOTHING ? null : cached;

            PropertyDoc found = null;
            long started = System.currentTimeMillis();
            try
            {
                Object viewPage = eventPage(resolved.event);
                found = new PropertyDoc(resolved.russianName(), viewPage, key);
            }
            catch (Exception e)
            {
                Global.tempLogException(TEMP_TOPIC, "документация события: " + key, e); //$NON-NLS-1$
            }
            RESOLVED.put(key, found != null ? found : NOTHING);
            Global.tempLog(TEMP_TOPIC, "документация события «" + key + "»: " //$NON-NLS-1$ //$NON-NLS-2$
                + (found != null ? "имя=" + found.russianName + ", страница=" + (found.viewPage != null) //$NON-NLS-1$ //$NON-NLS-2$
                    : "не найдено") //$NON-NLS-1$
                + ", " + (System.currentTimeMillis() - started) + " мс"); //$NON-NLS-1$ //$NON-NLS-2$
            return found;
        }

        /**
         * Страница синтакс-помощника для найденного свойства: у {@code BslDocumentationProvider}
         * есть разбор по конкретному объекту модели платформы, поэтому свойство передаётся ему
         * напрямую — искать страницу по названиям не нужно.
         */
        private static Object propertyPage(Property property) throws Exception
        {
            Object docProvider = documentationProvider();
            if (docProvider == null)
                return null;
            Object group = docProvider.getClass()
                .getMethod("getViewDocumentationPages", EObject.class, String.class) //$NON-NLS-1$
                .invoke(docProvider, property, Locale.getDefault().getLanguage());
            Object pagesObj = group != null ? Global.invoke(group, "getPages") : null; //$NON-NLS-1$
            if (pagesObj instanceof List<?> pages && !pages.isEmpty())
                return pages.get(0);
            return null;
        }

        /** Страница синтакс-помощника для платформенного события. */
        private static Object eventPage(com._1c.g5.v8.dt.mcore.Event event) throws Exception
        {
            Object docProvider = documentationProvider();
            if (docProvider == null || event == null)
                return null;
            return docProvider.getClass()
                .getMethod("getEventDocumentationPage", com._1c.g5.v8.dt.mcore.Event.class, //$NON-NLS-1$
                    String.class)
                .invoke(docProvider, event, Locale.getDefault().getLanguage());
        }

        /** Открывает описание свойства в панели «Синтакс-помощник». */
        static void open(Object page, Object scene, Object lwtView, String displayName)
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage wbPage = window != null ? window.getActivePage() : null;
                if (wbPage != null)
                    wbPage.showView(SYNTAX_VIEW_ID);
                Class<?> utilClass = bslUiClass(SYNTAX_VIEW_UTIL);
                if (utilClass == null)
                    return;
                utilClass.getMethod("showOrGetShowedView").invoke(null); //$NON-NLS-1$

                PropertyDoc doc = resolve(page, scene, lwtView, displayName);
                if (doc != null && doc.viewPage != null && openInSyntaxAssist(doc.viewPage))
                {
                    Global.tempLog(TEMP_TOPIC, "синтакс-помощник: открыто описание «" //$NON-NLS-1$
                        + (doc.russianName != null ? doc.russianName : displayName) + "»"); //$NON-NLS-1$
                    return;
                }
                String englishHint = PropertySheetControlInterop.resolveModelPropertyName(page,
                    scene, lwtView, displayName);
                PropertySheetPlatformPropertyResolver.ResolvedEvent eventResolved =
                    PropertySheetPlatformPropertyResolver.resolveEvent(page, scene, lwtView,
                        displayName, englishHint);
                PropertySheetPlatformPropertyResolver.Resolved propertyResolved =
                    PropertySheetPlatformPropertyResolver.resolve(page, scene, lwtView, displayName,
                        englishHint);
                String search = eventResolved != null && eventResolved.syntaxHelpSearchQuery() != null
                    ? eventResolved.syntaxHelpSearchQuery()
                    : propertyResolved != null && propertyResolved.syntaxHelpSearchQuery() != null
                        ? propertyResolved.syntaxHelpSearchQuery()
                        : doc != null && doc.russianName != null ? doc.russianName : displayName;
                utilClass.getMethod("showSearch", String.class).invoke(null, search); //$NON-NLS-1$
                Global.tempLog(TEMP_TOPIC, "синтакс-помощник: страницы нет, поиск по «" //$NON-NLS-1$
                    + search + "»"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.tempLogException(TEMP_TOPIC, "синтакс-помощник: " + displayName, e); //$NON-NLS-1$
            }
        }

        /** Описание свойства простым текстом (для подсказки при наведении). */
        static String describe(Object page, Object scene, Object lwtView, String displayName)
        {
            PropertyDoc doc = resolve(page, scene, lwtView, displayName);
            if (doc == null || doc.viewPage == null)
                return null;
            String key = doc.cacheKey != null ? doc.cacheKey : ""; //$NON-NLS-1$
            String cached = DESCRIPTIONS.get(key);
            if (cached != null)
                return cached.isEmpty() ? null : cached;
            String text = extractDescriptionSection(
                    htmlToText(string(doc.viewPage, "getViewHtml"))); //$NON-NLS-1$
            if (text.length() > MAX_TOOLTIP_CHARS)
                text = text.substring(0, MAX_TOOLTIP_CHARS) + "…"; //$NON-NLS-1$
            DESCRIPTIONS.put(key, text);
            Global.tempLog(TEMP_TOPIC, "описание «" + key + "»: " //$NON-NLS-1$ //$NON-NLS-2$
                + (text.isEmpty() ? "нет" : text.length() + " симв.")); //$NON-NLS-1$ //$NON-NLS-2$
            return text.isEmpty() ? null : text;
        }

        private static boolean openInSyntaxAssist(Object viewPage) throws Exception
        {
            Object docProvider = documentationProvider();
            Class<?> utilClass = bslUiClass(SYNTAX_VIEW_UTIL);
            if (docProvider == null || utilClass == null)
                return false;
            Object view = utilClass.getMethod("showOrGetShowedView").invoke(null); //$NON-NLS-1$
            Object panel = Global.invoke(view, "getDescriptionPanel"); //$NON-NLS-1$
            Object browser = Global.invoke(panel, "getBrowser"); //$NON-NLS-1$
            if (browser == null)
                return false;
            Object descriptor = bslUiClass(PAGE_DESCRIPTOR)
                .getConstructor(bslUiClass(VIEW_PAGE), bslUiClass(DOC_PROVIDER))
                .newInstance(viewPage, docProvider);
            return Global.invokeVoid(browser, "openPage", descriptor); //$NON-NLS-1$
        }

        /**
         * {@code BslDocumentationProvider} из Guice-инжектора языка BSL — того же, из которого
         * его берёт сама панель «Синтакс-помощник». У {@code BslActivator.getInjector} есть
         * параметр языка (безаргументного {@code getInjector()} в этой версии EDT нет).
         *
         * <p>Сам инжектор приводится к {@link Injector} (пакет {@code com.google.inject} нашему
         * бандлу доступен), а не вызывается рефлексией: {@code InjectorImpl} лежит во внутреннем
         * пакете Guice, и рефлексивный вызов по нему может молча не пройти. Если инжектор языка
         * почему-либо недоступен, тот же экземпляр берётся из Xtext-реестра сервисов по любому
         * BSL-URI — путь, уже используемый в {@code ParamHintHtmlModifier}.
         *
         * <p>Каждый шаг логируется: провайдер возвращался {@code null} без единого исключения,
         * и без пошагового следа не видно, какое именно звено обрывается.
         */
        private static Object documentationProvider() throws Exception
        {
            Object cached = docProviderCache;
            if (cached != null)
                return cached;
            Class<?> providerClass = bslUiClass(DOC_PROVIDER);
            Class<?> activatorClass = bslUiClass(BSL_ACTIVATOR);
            if (providerClass == null || activatorClass == null)
            {
                Global.tempLog(TEMP_TOPIC, "провайдер: классы bsl.ui недоступны (провайдер=" //$NON-NLS-1$
                    + providerClass + ", активатор=" + activatorClass + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            Object activator = activatorClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
            Object language = activatorClass.getField("COM__1C_G5_V8_DT_BSL_BSL").get(null); //$NON-NLS-1$
            Object injector = activator != null
                ? activatorClass.getMethod("getInjector", String.class).invoke(activator, language) : null; //$NON-NLS-1$
            Object provider = injector instanceof Injector guice ? guice.getInstance(providerClass) : null;
            Global.tempLog(TEMP_TOPIC, "провайдер: активатор=" + (activator != null) //$NON-NLS-1$
                + ", язык=" + language //$NON-NLS-1$
                + ", инжектор=" + (injector == null ? "null" : injector.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + ", провайдер=" + (provider == null ? "null" : provider.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
            if (provider == null)
                provider = providerFromXtextRegistry(providerClass);
            docProviderCache = provider;
            return provider;
        }

        /** Запасной путь: тот же Guice-экземпляр через реестр сервисов Xtext по BSL-URI. */
        private static Object providerFromXtextRegistry(Class<?> providerClass)
        {
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(URI.createURI("comfort.bsl")); //$NON-NLS-1$
            Object provider = rsp != null ? rsp.get(providerClass) : null;
            Global.tempLog(TEMP_TOPIC, "провайдер через реестр Xtext: rsp=" //$NON-NLS-1$
                + (rsp == null ? "null" : rsp.getClass().getName()) //$NON-NLS-1$
                + ", провайдер=" + (provider == null ? "null" : provider.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
            return provider;
        }

        private static Class<?> bslUiClass(String name)
        {
            return bundleClass(BSL_UI_BUNDLE, name);
        }

        /** Класс из внутреннего пакета чужого бандла — только его же загрузчиком. */
        private static Class<?> bundleClass(String bundleId, String name)
        {
            try
            {
                Bundle bundle = Platform.getBundle(bundleId);
                return bundle != null ? bundle.loadClass(name) : null;
            }
            catch (ClassNotFoundException e)
            {
                Global.tempLog(TEMP_TOPIC, "класс не найден в " + bundleId + ": " + name); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }

        /** Объект, свойства которого показывает панель. */
        private static EObject selectionObject(Object page)
        {
            Object selection = page != null ? Global.invoke(page, "getCurrentSelection") : null; //$NON-NLS-1$
            if (selection instanceof StructuredSelection structured && !structured.isEmpty()
                && structured.getFirstElement() instanceof EObject fromSelection)
                return fromSelection;
            Object paletteModel = page != null ? Global.invoke(page, "getPaletteModel") : null; //$NON-NLS-1$
            Object objects = paletteModel != null ? Global.invoke(paletteModel, "getObjects") : null; //$NON-NLS-1$
            if (objects instanceof Iterable<?> iterable)
            {
                for (Object item : iterable)
                {
                    if (item instanceof EObject eObject)
                        return eObject;
                }
            }
            return null;
        }

        private static String string(Object target, String method, Object... args)
        {
            Object value = Global.invoke(target, method, args);
            return value instanceof String s ? s : ""; //$NON-NLS-1$
        }

        /** HTML справки → простой текст: разметка снимается, переносы сохраняются. */
        private static String htmlToText(String html)
        {
            if (html == null || html.isEmpty())
                return ""; //$NON-NLS-1$
            return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("(?i)<br\\s*/?>", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("<[^>]+>", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("&nbsp;", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("&lt;", "<").replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace("&quot;", "\"").replace("&amp;", "&") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replaceAll("[ \\t]+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll(" ?\\n ?", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\n{3,}", "\n\n") //$NON-NLS-1$ //$NON-NLS-2$
                .trim();
        }

        /**
         * Только блок «Описание: …» до «Доступность:» (или англ. {@code Description}/{@code Availability}).
         * Если заголовков нет — весь текст без изменений.
         */
        private static String extractDescriptionSection(String text)
        {
            if (text == null || text.isEmpty())
                return ""; //$NON-NLS-1$
            int start = indexAfterSectionHeader(text, "Описание:", "Description:"); //$NON-NLS-1$ //$NON-NLS-2$
            if (start < 0)
                return text;
            int end = indexOfSectionHeader(text, start, "Доступность:", "Availability:"); //$NON-NLS-1$ //$NON-NLS-2$
            String section = end >= 0 ? text.substring(start, end) : text.substring(start);
            return section.trim();
        }

        private static int indexAfterSectionHeader(String text, String ruHeader, String enHeader)
        {
            int idx = indexOfSectionHeader(text, 0, ruHeader, enHeader);
            if (idx < 0)
                return -1;
            int colon = text.indexOf(':', idx);
            if (colon < 0)
                return -1;
            int pos = colon + 1;
            while (pos < text.length() && text.charAt(pos) != '\n'
                    && Character.isWhitespace(text.charAt(pos)))
                pos++;
            if (pos < text.length() && text.charAt(pos) == '\n')
                pos++;
            return pos;
        }

        private static int indexOfSectionHeader(String text, int from, String ruHeader, String enHeader)
        {
            int ru = text.indexOf(ruHeader, from);
            int en = text.indexOf(enHeader, from);
            if (ru < 0)
                return en;
            if (en < 0)
                return ru;
            return Math.min(ru, en);
        }
    }
}
