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
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.md.ui.editor.aef.AbstractDtGranularEditorAefPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Текущее (последнее активное) свойство в AEF-палитре свойств: панель «Свойства» и
 * страницы свойств редактора объекта метаданных (вкладки «Основные» и др.). Имя
 * свойства окрашивается акцентным цветом, а при перезаполнении палитры строка
 * доводится до видимой области.
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
    private static final int LIGHT_DEPTH = 6;
    /** Длительность одного состояния мигания жирностью. */
    private static final int BLINK_STEP_MS = 500;
    /** Сколько раз включается жирный при мигании (2 цикла «обычный → жирный»). */
    private static final int BLINK_CYCLES = 2;
    /**
     * Шаг мигания одним и тем же объектом: {@code Display#timerExec(-1, …)} отменяет задание
     * по совпадению ссылки, а ссылка на метод каждый раз давала бы новый объект — старый тик
     * продолжал бы срабатывать и после остановки.
     */
    private static final Runnable BLINK_TICK = PropertySheetActivePropertyHook::blinkTick;
    /** Сколько ждать завершения перехода, если о нём не сообщили (активация без удержания). */
    private static final int ACTIVATION_FREEZE_MS = 1500;
    private static final Runnable ACTIVATION_SETTLED =
        PropertySheetActivePropertyHook::onActivationSettled;

    /** Панели «Свойства», за которыми уже следим. */
    private static final Set<IViewPart> HOOKED_VIEWS =
        Collections.newSetFromMap(new WeakHashMap<>());
    /** Редакторы объектов метаданных с AEF-страницами свойств. */
    private static final Set<DtGranularEditor<?>> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());
    /** Контролы палитры, на которых уже висит слушатель перерисовки. */
    private static final Set<Control> WATCHED_CONTROLS =
        Collections.newSetFromMap(new WeakHashMap<>());

    /** Текст подписи текущего свойства; {@code null} — активного свойства нет. */
    private static String activePropertyName;
    /** Окрашенная сейчас подпись ({@code LightLabel}). */
    private static Object highlightedLabel;
    /**
     * Страница, которой принадлежит окрашенная подпись. Жирность зависит от того, стоит ли
     * ввод в поле ЭТОГО свойства, а проверять это надо по своей странице: кэш палитры
     * ({@link #palettePage}) может указывать уже на другую (панель «Свойства» вместо
     * редактора объекта метаданных), и тогда решение о жирности принималось бы по чужой сцене.
     */
    private static Object highlightedPage;
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
    /** Идёт мигание жирностью (свойство стало текущим без участия пользователя). */
    private static boolean blinkActive;
    /** Номер шага мигания: 0 — включить жирный, 1 — снять, 2 — включить, 3 — конец. */
    private static int blinkStep;
    /** Контрол программно активированного поля — мигнёт свойство именно его строки. */
    private static Object blinkControl;
    /** Идёт программный переход к свойству: жирность заморожена до его завершения. */
    private static boolean activationInProgress;
    /** Свойство, к которому идёт программный переход — только оно подсвечивается во время него. */
    private static String targetPropertyName;
    /** Идёт обработка ввода пользователя в панели: активация поля отсюда — не повод мигать. */
    private static boolean handlingPaletteInput;
    /** Дебаунс: пересинхронизация уже запланирована. */
    private static boolean syncScheduled;
    /** Кэш контролов палитры — {@link #onUserInput} не должен обходить сцену на каждую клавишу. */
    private static IViewPart paletteView;
    private static DtGranularEditor<?> paletteEditor;
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
        // Жирность зависит от того, где ввод. Проверка отложена в asyncExec: на FocusOut
        // новый владелец фокуса ещё не назначен, и getFocusControl() дал бы прежний контрол.
        Listener focusListener = event -> display.asyncExec(
            PropertySheetActivePropertyHook::syncBoldWithFocus);
        display.addFilter(SWT.FocusIn, focusListener);
        display.addFilter(SWT.FocusOut, focusListener);
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
                    hookView((IViewPart)part);
                else if (part instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        });
    }

    private static void hookEditor(DtGranularEditor<?> editor)
    {
        if (editor == null || !HOOKED_EDITORS.add(editor))
            return;
        editor.addPageChangedListener(event -> {
            resolveAttempts = 0;
            scheduleSync();
        });
        resolveAttempts = 0;
        scheduleSync();
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
        Object page = resolvePageFromControl(control);
        if (page == null)
            return;
        // Клик или клавиша в самой панели: пользователь уже здесь, привлекать внимание не нужно.
        boolean paletteInput = event.type == SWT.MouseDown || event.type == SWT.KeyDown;
        if (paletteInput)
        {
            blinkControl = null;
            onActivationSettled(); // пользователь вмешался — переход закончен, жирность разморожена
            stopBlink();
        }
        bindPalette(page);
        // Ввод в области фильтра тоже относится к панели: очистка фильтра перезаполняет
        // палитру, а значит подпись текущего свойства придётся красить заново.
        scheduleSync();
        // Клик мог попасть в саму подпись — там фокусируемого контрола нет, и по фокусу
        // свойство не определить; точку клика запоминаем в display-координатах (сам контрол
        // к моменту asyncExec может уже быть пересоздан).
        Point clickDisplay = event.type == SWT.MouseDown ? control.toDisplay(event.x, event.y) : null;
        Display.getDefault().asyncExec(() -> {
            handlingPaletteInput = paletteInput;
            try
            {
                updateActiveProperty(page, control, clickDisplay);
            }
            finally
            {
                handlingPaletteInput = false;
            }
        });
    }

    /** Привязывает кэш палитры к странице, в которой произошло событие. */
    private static void bindPalette(Object page)
    {
        if (page == null)
            return;
        palettePage = page;
        paletteView = null;
        paletteEditor = null;
        for (IViewPart view : HOOKED_VIEWS)
        {
            Object sheetPage = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
            if (sheetPage == page)
            {
                paletteView = view;
                break;
            }
        }
        if (paletteView == null)
        {
            for (DtGranularEditor<?> editor : HOOKED_EDITORS)
            {
                if (editor.getActivePageInstance() == page)
                {
                    paletteEditor = editor;
                    break;
                }
            }
        }
    }

    /**
     * Страница AEF-палитры, содержащая {@code control}; {@code null} — вне палитры.
     * Package-private: переиспользуется {@link PropertySheetEventHandlerClearHook}.
     */
    static Object resolvePageFromControl(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Composite cachedRoot = palettePageControl != null && !palettePageControl.isDisposed()
            ? palettePageControl : paletteRoot;
        if (cachedRoot != null && isUnder(control, cachedRoot) && palettePage != null)
            return palettePage;
        for (IViewPart view : HOOKED_VIEWS)
        {
            Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
            Composite host = pageControlFor(page);
            if (host != null && isUnder(control, host))
                return page;
        }
        for (DtGranularEditor<?> editor : HOOKED_EDITORS)
        {
            IFormPage page = editor.getActivePageInstance();
            if (!isAefPropertyPage(page))
                continue;
            Composite host = pageControlFor(page);
            if (host != null && isUnder(control, host))
                return page;
        }
        return null;
    }

    private static boolean isAefPropertyPage(IFormPage page)
    {
        return page instanceof AbstractDtGranularEditorAefPage<?>;
    }

    private static Composite pageControlFor(Object page)
    {
        if (page == null)
            return null;
        Object pageControl = Global.invoke(page, "getControl"); //$NON-NLS-1$
        if (pageControl instanceof Composite composite && !composite.isDisposed())
            return composite;
        pageControl = Global.invoke(page, "getPartControl"); //$NON-NLS-1$
        return pageControl instanceof Composite composite && !composite.isDisposed() ? composite : null;
    }

    private static boolean isUnder(Control control, Composite root)
    {
        if (root == null || root.isDisposed())
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
    private static void updateActiveProperty(Object page, Control clicked, Point clickDisplay)
    {
        Map<?, ?> map = viewModelToView(page);
        if (map == null)
            return;
        if (clickDisplay != null && !clicked.isDisposed()
            && activateByLabelClick(page, map, clicked, clickDisplay))
            return;
        String focused = focusedPropertyName(page);
        if (focused != null)
        {
            setActiveProperty(page, focused);
            return;
        }
        if (clickDisplay == null || clicked.isDisposed())
            return;
        // Ввод ушёл не в контрол поля, а в контейнер разметки (по логу — DtLayoutComposite):
        // такое поле по карте сцены не опознать ни по самому контролу, ни по его потомкам.
        // Тогда свойство определяется по СТРОКЕ кликнутого LWT-контрола: подпись и редактор
        // одной строки лежат на одном хосте и совпадают по вертикали.
        String byRow = propertyNameForClickedRow(page, clicked, clickDisplay);
        if (byRow != null)
        {
            setActiveProperty(page, byRow);
            return;
        }
        if (clickDisplay != null && !clicked.isDisposed())
        {
            Object hit = lightControlAt(clicked, clickDisplay);
            String fromField = PropertySheetControlInterop.displayNameForLwtHit(page, hit);
            if (fromField != null && !fromField.isEmpty())
            {
                setActiveProperty(page, fromField);
                return;
            }
        }
        Control focusControl = Display.getDefault().getFocusControl();
        Global.tempLog(TEMP_TOPIC, "клик в палитре: поле свойства не определено, ввод в " //$NON-NLS-1$
            + (focusControl == null ? "<null>" : focusControl.getClass().getName())); //$NON-NLS-1$
    }

    private static String propertyNameForClickedRow(Object page, Control clicked, Point click)
    {
        Object hit = lightControlAt(clicked, click);
        Map<?, ?> map = hit != null ? viewModelToView(page) : null;
        Object hitHost = hit != null ? lightHost(hit) : null;
        Rectangle hitBounds = hitHost != null ? boundsInHost(hitHost, hit) : null;
        if (map == null || hitBounds == null)
            return null;

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int hitCenter = hitBounds.y + hitBounds.height / 2;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key == null || !key.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            Object label = PropertySheetControlInterop.lightControlFromView(entry.getValue());
            if (label == null || lightHost(label) != hitHost)
                continue;
            Rectangle bounds = boundsInHost(hitHost, label);
            if (bounds == null || bounds.y + bounds.height <= hitBounds.y
                || bounds.y >= hitBounds.y + hitBounds.height)
                continue; // другая строка
            int distance = Math.abs(bounds.y + bounds.height / 2 - hitCenter);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = labelText(key);
            }
        }
        if (best != null && !best.isEmpty())
        {
            Global.tempLog(TEMP_TOPIC, "свойство определено по строке клика: " + best); //$NON-NLS-1$
            return best;
        }
        return null;
    }

    /** {@code SwtLightComposite}, на котором рисуется light-контрол. */
    private static Object lightHost(Object lightControl)
    {
        Class<?> hostClass = swtLightCompositeClass();
        return hostClass != null
            ? Global.invoke(hostClass, "getHostSwtLightComposite", lightControl) : null; //$NON-NLS-1$
    }

    /** Границы light-контрола в координатах его хоста. */
    private static Rectangle boundsInHost(Object host, Object lightControl)
    {
        Object bounds = Global.invoke(lightControl, "getBounds"); //$NON-NLS-1$
        if (!(bounds instanceof Rectangle))
            return null;
        Object translated = Global.invoke(host, "translateRectangleFromControl", //$NON-NLS-1$
            lightControl, bounds);
        return translated instanceof Rectangle r ? r : (Rectangle)bounds;
    }

    /** Подпись свойства, чей редактор сейчас держит ввод; {@code null} — такого поля нет. */
    private static String focusedPropertyName(Object page)
    {
        String fromField = PropertySheetControlInterop.displayNameForFocusedField(page);
        if (fromField != null && !fromField.isEmpty())
            return fromField;

        // Двухколоночная палитра: порядок viewModelToView не совпадает с парами «подпись→редактор».
        // Если ввод уже в редакторе, не угадывать свойство по карте — это давало чужую подпись
        // из левой колонки и мигание подсветки.
        if (PropertySheetControlInterop.hasFocusedEditorView(page))
            return null;

        // Запасной путь для одноколоночной палитры «Свойства» (LabelViewModel сразу перед редактором).
        Map<?, ?> map = viewModelToView(page);
        if (map == null)
            return null;
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
            if (isFocusedDeep(nativeControl, 0))
                return labelText;
        }
        return null;
    }

    /**
     * Клик по имени свойства: подпись сама фокус не принимает, поэтому свойство делается
     * текущим по попаданию точки клика в bounds её {@code LightLabel}, а ввод передаётся
     * первому фокусируемому редактору этого свойства (кнопка «...» — {@code ActionBarViewModel} —
     * берётся только если другого редактора у свойства нет).
     *
     * @return {@code true}, если клик пришёлся в подпись (определение по фокусу уже не нужно)
     */
    private static boolean activateByLabelClick(Object page, Map<?, ?> map, Control clicked, Point click)
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

        setActiveProperty(page, hitLabel);
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
        Map<?, ?> map = palettePage != null ? viewModelToView(palettePage) : null;
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

    private static void setActiveProperty(Object page, String labelText)
    {
        // Во время перехода принимается только целевое свойство: панель по дороге ставит ввод
        // в первое поле («Имя»), и подсветка прыгала бы по чужим строкам.
        if (activationInProgress && targetPropertyName != null
            && !propertyNamesMatch(labelText, targetPropertyName))
        {
            return;
        }
        if (propertyNamesMatch(labelText, activePropertyName))
        {
            consumeBlinkRequest(page, labelText);
            return;
        }
        Global.tempLog(TEMP_TOPIC, "текущее свойство: " + labelText); //$NON-NLS-1$
        activePropertyName = normalizePropertyDisplayName(labelText);
        clearHighlight();
        applyHighlight(page, false);
        consumeBlinkRequest(page, labelText);
    }

    /**
     * Поле панели «Свойства» активировано программно — командой плагина, а не руками
     * пользователя: переход к найденному свойству из результатов поиска, двойной клик по
     * колонке дерева элементов формы и т.п. Взгляд в такой момент не на панели, поэтому имя
     * свойства мигнёт жирностью, когда подсветка на него встанет (если она уже там — сразу).
     * <p>
     * Вызывать из единственного места, где фокус реально ставится ({@link AefFieldFocus} и
     * наш оверлей поля «Тип»), а не из каждого сценария перехода: активацию свойства делают
     * все через них, и знать про мигание им не нужно.
     */
    static void onFieldActivatedProgrammatically(Object nativeControl)
    {
        if (handlingPaletteInput || nativeControl == null)
            return; // фокус в поле поставил клик по имени свойства в самой панели
        blinkControl = nativeControl;
        activationInProgress = true;
        targetPropertyName = resolveTargetProperty(nativeControl);
        // Пока идёт борьба за фокус, подсвечено либо целевое свойство, либо никакое: прежнее
        // свойство уже не то, к которому переходит пользователь, а промежуточные (панель по
        // дороге ставит ввод в «Имя») только мельтешили бы.
        clearHighlight();
        activePropertyName = null;
        // Страховка на случай, если о завершении перехода никто не сообщит (активация без
        // удержания): жирность не должна остаться замороженной навсегда.
        Display.getDefault().timerExec(ACTIVATION_FREEZE_MS, ACTIVATION_SETTLED);
    }

    /**
     * Переход к свойству завершён (удержание отработало или прервано). Пока он идёт, жирность
     * подписи не меняется: EDT в это время несколько раз отбирает и отдаёт ввод, и подпись ЕЩЁ
     * ТЕКУЩЕГО (старого) свойства дёргалась жирностью на каждый перехват — со стороны это
     * выглядело как мигание не того свойства.
     */
    static void onActivationSettled()
    {
        if (!activationInProgress)
            return;
        activationInProgress = false;
        targetPropertyName = null;
        Display.getDefault().timerExec(-1, ACTIVATION_SETTLED);
        // Борьба закончилась, а целевое свойство так и не стало текущим (панель оставила ввод
        // где-то ещё) — подсвечиваем то свойство, в поле которого ввод оказался.
        if (activePropertyName == null)
        {
            String focused = focusedPropertyName(palettePage);
            if (focused != null)
                setActiveProperty(palettePage, focused);
        }
        syncBoldWithFocus();
    }

    /** Подпись свойства активируемого поля — определяется, пока его контрол ещё на сцене. */
    private static String resolveTargetProperty(Object nativeControl)
    {
        String name = propertyNameForControl(palettePage, nativeControl);
        if (name != null)
            return name;
        for (IViewPart view : HOOKED_VIEWS)
        {
            name = propertyNameForControl(
                PropertyNameIdentifierHook.resolvePropertySheetPage(view), nativeControl);
            if (name != null)
                return name;
        }
        for (DtGranularEditor<?> editor : HOOKED_EDITORS)
        {
            IFormPage page = editor.getActivePageInstance();
            name = isAefPropertyPage(page) ? propertyNameForControl(page, nativeControl) : null;
            if (name != null)
                return name;
        }
        return null;
    }

    /**
     * Запускает отложенное мигание, если подсветка встала на свойство ИМЕННО активированного
     * поля. Сверка идёт по контролу, а не по прежнему имени: в момент активации панель ещё
     * считает сфокусированным старое свойство, и по имени мигало бы оно.
     */
    private static void consumeBlinkRequest(Object page, String labelText)
    {
        if (blinkControl == null
            || !propertyNamesMatch(labelText, propertyNameForControl(page, blinkControl)))
        {
            return;
        }
        blinkControl = null;
        startBlink();
    }

    /** Подпись свойства, чьей строке принадлежит контрол поля ({@code null} — не найдено). */
    private static String propertyNameForControl(Object page, Object nativeControl)
    {
        Map<?, ?> map = viewModelToView(page);
        if (map == null)
            return null;
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
            if (labelText != null && !labelText.isEmpty()
                && Global.invoke(entry.getValue(), "getNativeControl") == nativeControl) //$NON-NLS-1$
            {
                return labelText;
            }
        }
        return null;
    }

    /** Карта {@code viewModel → view} рендерера сцены палитры (порядок вставки — порядок строк). */
    private static Map<?, ?> viewModelToView(Object page)
    {
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
    static boolean isFocusedDeep(Object nativeControl, int depth)
    {
        if (nativeControl == null || depth > LIGHT_DEPTH)
            return false;
        if (nativeControl instanceof Control control)
        {
            // Не только сам контрол: у части полей панели (например «Сочетание клавиш») рендерер
            // отдаёт контейнер, а ввод держит SWT-контрол внутри него — по isFocusControl()
            // самого контейнера такое поле не определялось, и свойство не становилось текущим.
            return containsFocus(control);
        }
        // Флагу isFocused() light-контрола верить нельзя без проверки канваса — см. AefFieldFocus.
        if (AefFieldFocus.hasFocusNow(nativeControl))
            return true;
        // LightText в режиме ввода держит выделение и фокус в SWT-оверлее (StyledText).
        if (containsFocus(Global.getField(nativeControl, "overlay") instanceof Control overlay //$NON-NLS-1$
            ? overlay : null))
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

    /** Контрол сам держит ввод или ввод в одном из его потомков. */
    private static boolean containsFocus(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        Display display = control.getDisplay();
        Control focus = display != null && !display.isDisposed() ? display.getFocusControl() : null;
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == control)
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
    private static void applyHighlight(Object page, boolean afterRebuild)
    {
        if (activePropertyName == null)
            return;
        Map<?, ?> map = viewModelToView(page);
        if (page == null || map == null)
            return;

        Object labelView = null;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            if (key == null || !key.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            if (propertyNamesMatch(activePropertyName, labelText(key)))
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
        highlightedPage = page;
        // setTextColor/setFont сами вызывают invalidate() — отдельная перерисовка не нужна.
        boolean focusedNow = activeFieldFocused(page);
        // Во время мигания жирность держит blinkTick — иначе перезаполнение палитры сбивало бы фазу.
        // Во время программного перехода жирность заморожена (см. onActivationSettled).
        if (!focusedNow && !blinkActive && !activationInProgress)
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
        highlightedPage = null;
        // Подсветки больше нет — мигать нечему (новую подпись мигание запросит само).
        blinkActive = false;
        Display.getDefault().timerExec(-1, BLINK_TICK);
        Color original = originalTextColor;
        originalTextColor = null;
        removeBold(label);
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return;
        // null допустим: LightLabel.paint при пустом textColor берёт цвет из SWT-хоста.
        Global.invokeVoid(label, "setTextColor", original); //$NON-NLS-1$
    }

    /** Возвращает подписи исходный шрифт (и ширину, если под жирный её расширяли). */
    private static void removeBold(Object label)
    {
        Font font = originalFont;
        Rectangle bounds = originalBounds;
        Object extent = originalExtent;
        boolean bold = boldApplied;
        originalFont = null;
        originalBounds = null;
        originalExtent = null;
        boldApplied = false;
        if (!bold || label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return;
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

    /**
     * Жирность имени снимается, пока ввод находится в поле ЭТОГО свойства, и возвращается,
     * когда ввод ушёл. Пока свойство редактируют, его и так видно по каретке, а жирный шрифт
     * шире обычного и на длинных именах приводит к обрезке; после ухода из поля именно
     * заметность и нужна — свойство остаётся текущим, но ничем другим уже не выделено.
     */
    private static void syncBoldWithFocus()
    {
        Object label = highlightedLabel;
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return;
        // Подписи палитры переиспользуются: после перезаполнения тот же LightLabel показывает
        // уже другое свойство. Менять на нём жирность нельзя — этим займётся applyHighlight,
        // когда подсветка переедет на подпись текущего свойства.
        if (!labelDisplayTextMatches(activePropertyName, label))
            return;
        boolean bold = !activeFieldFocused(highlightedPage);
        // Пока идёт мигание, жирностью распоряжается только оно: фокус в этот момент панель
        // часто ставит сама (после перезаполнения), и по нему мигание гасить нельзя — гасит
        // его именно ввод пользователя (см. onUserInput). Во время программного перехода
        // жирность тоже не трогаем — EDT там несколько раз перехватывает ввод туда-обратно.
        if (blinkActive || activationInProgress)
            return;
        if (bold == boldApplied)
            return;
        setBold(label, bold);
    }

    /**
     * Свойство стало текущим без участия пользователя — он на панель не смотрит, поэтому имя
     * мигает жирностью: {@link #BLINK_CYCLES} циклов «обычный {@link #BLINK_STEP_MS} мс →
     * жирный {@link #BLINK_STEP_MS} мс». После мигания жирность возвращается к обычному
     * правилу (см. {@link #syncBoldWithFocus}). Любой клик или ввод в панели мигание гасит.
     */
    private static void startBlink()
    {
        Object label = highlightedLabel;
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return;
        Display.getDefault().timerExec(-1, BLINK_TICK); // прежнее мигание
        blinkActive = true;
        blinkStep = 0;
        setBold(label, false); // мигание начинается с обычного шрифта
        Display.getDefault().timerExec(BLINK_STEP_MS, BLINK_TICK);
    }

    /** Один шаг мигания: чётные шаги включают жирный, нечётные снимают. */
    private static void blinkTick()
    {
        if (!blinkActive)
            return;
        Object label = highlightedLabel;
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed")) //$NON-NLS-1$
            || !labelDisplayTextMatches(activePropertyName, label))
        {
            blinkActive = false;
            return;
        }
        if (blinkStep >= BLINK_CYCLES * 2 - 1)
        {
            stopBlink();
            return;
        }
        setBold(label, blinkStep % 2 == 0);
        blinkStep++;
        Display.getDefault().timerExec(BLINK_STEP_MS, BLINK_TICK);
    }

    /** Гасит мигание и возвращает жирность к обычному правилу (по фокусу). */
    private static void stopBlink()
    {
        if (!blinkActive)
            return;
        blinkActive = false;
        blinkStep = 0;
        Display.getDefault().timerExec(-1, BLINK_TICK);
        syncBoldWithFocus();
    }

    /** Включает или снимает жирность подписи, если она ещё не в нужном состоянии. */
    private static void setBold(Object label, boolean bold)
    {
        if (bold == boldApplied)
            return;
        if (bold)
        {
            originalFont = fontField(label);
            applyBold(label);
        }
        else
        {
            removeBold(label);
        }
    }

    /**
     * Ввод сейчас в поле текущего свойства (а не в другом поле палитры и не вне её).
     * <p>
     * Флажок ({@code LightCheckbox}) фокус по клику удерживает недолго: EDT перезаполняет
     * палитру, LWT-контролы пересоздаются, и {@link #focusedPropertyName} перестаёт видеть
     * сфокусированное поле, хотя ввод из панели никуда не уходил. Раньше это читалось как
     * «ввод ушёл» и подпись становилась жирной прямо под курсором. Поэтому: если ни одно поле
     * палитры не опознано как сфокусированное, но SWT-фокус остался внутри самой панели —
     * считаем, что ввод по-прежнему в поле текущего свойства. Жирность вернётся, когда фокус
     * реально уйдёт из панели или встанет в поле другого свойства (там имя определяется).
     */
    private static boolean activeFieldFocused(Object page)
    {
        if (page == null || activePropertyName == null)
            return false;
        String focused = focusedPropertyName(page);
        if (focused != null)
            return propertyNamesMatch(activePropertyName, focused);
        return containsFocus(pageControlFor(page));
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
        Composite host = pageControlFor(page);
        palettePageControl = host != null ? host : root;
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
        Object page = palettePage;
        if (paletteRoot == null || paletteRoot.isDisposed())
        {
            // Палитра ещё не построена: своих событий, чтобы дождаться, у нас пока нет
            // (слушатели ставятся как раз на её контролы) — ограниченная серия попыток.
            if (resolveAttempts++ < 100)
                scheduleSync();
            return;
        }
        resolveAttempts = 0;
        if (page == null || activePropertyName == null)
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
        applyHighlight(page, true);
    }

    /** Окрашенная подпись жива И всё ещё принадлежит текущему свойству. */
    private static boolean isHighlightAlive()
    {
        Object label = highlightedLabel;
        if (label == null || Boolean.TRUE.equals(Global.invoke(label, "isDisposed"))) //$NON-NLS-1$
            return false;
        // LWT-контролы палитры могут переиспользоваться: тот же LightLabel после
        // перезаполнения показывает уже другое свойство — тогда цвет надо вернуть на место.
        if (labelDisplayTextMatches(activePropertyName, label))
            return true;
        clearHighlight();
        return false;
    }

    /** Сверка подписи палитры с {@code LabelViewModel}: у {@code LightLabel} может быть «:» в конце. */
    private static boolean labelDisplayTextMatches(String propertyName, Object label)
    {
        if (propertyName == null || label == null)
            return false;
        Object text = Global.invoke(label, "getText"); //$NON-NLS-1$
        if (!(text instanceof String shown) || shown.isEmpty())
            return false;
        return propertyNamesMatch(propertyName, shown);
    }

    /** Имя свойства без суффикса «:» / «: », как в {@code LabelViewModel.getText()}. */
    static String normalizePropertyDisplayName(String name)
    {
        if (name == null || name.isEmpty())
            return name;
        if (name.endsWith(": ")) //$NON-NLS-1$
            return name.substring(0, name.length() - 2);
        if (name.endsWith(":")) //$NON-NLS-1$
            return name.substring(0, name.length() - 1);
        return name;
    }

    /** Сравнение имён свойства: {@code LabelViewModel}, {@code FieldComponent}, {@code LightLabel}. */
    static boolean propertyNamesMatch(String a, String b)
    {
        if (a == null || b == null)
            return false;
        String na = normalizePropertyDisplayName(a);
        String nb = normalizePropertyDisplayName(b);
        return !na.isEmpty() && na.equals(nb);
    }

    // =========================================================================
    // Контекстное меню и подсказка по имени свойства
    // =========================================================================

    /** Свойство под точкой: отображаемое имя + view его подписи. */
    /**
     * Свойство под указателем ВМЕСТЕ со страницей, в которой оно найдено. Страница нужна
     * именно снимком: пункт меню срабатывает много позже показа меню, и к этому моменту
     * кэш палитры может указывать уже на другую страницу (панель «Свойства» вместо
     * редактора объекта метаданных) — тогда разбор шёл бы по чужой сцене.
     */
    private static final class HitProperty
    {
        final String name;
        final Object labelView;
        final Object page;

        HitProperty(String name, Object labelView, Object page)
        {
            this.name = name;
            this.labelView = labelView;
            this.page = page;
        }

        Object scene()
        {
            return page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
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
        if (control == null || control.isDisposed())
            return null;
        Object page = resolvePageFromControl(control);
        if (page == null)
            return null;
        bindPalette(page);
        Object hit = lightControlAt(control, display);
        String fromField = PropertySheetControlInterop.displayNameForLwtHit(page, hit);
        if (fromField != null && !fromField.isEmpty())
            return fromField;
        Map<?, ?> map = hit != null && palettePage != null ? viewModelToView(palettePage) : null;
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
    private static HitProperty propertyAt(Object page, Control control, Point display)
    {
        Object hit = lightControlAt(control, display);
        Map<?, ?> map = hit != null && page != null ? viewModelToView(page) : null;
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
            return name.isEmpty() ? null : new HitProperty(name, entry.getValue(), page);
        }
        return null;
    }

    /**
     * Правый клик по имени свойства — своё меню вместо штатного (у подписи палитры его нет,
     * но родительский контрол может отдать чужое).
     */
    private static void onMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Object page = resolvePageFromControl(control);
        if (page == null)
            return;
        bindPalette(page);
        Point display = new Point(event.x, event.y);
        HitProperty hit = propertyAt(page, control, display);
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
            SyntaxHelp.resolve(hit.page, hit.scene(), hit.labelView, hit.name);
        if (doc != null && doc.viewPage != null)
        {
            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem syntaxItem = new MenuItem(menu, SWT.PUSH);
            syntaxItem.setText("Синтакс-помощник"); //$NON-NLS-1$
            syntaxItem.setToolTipText("Открыть справку по свойству в синтакс-помощнике" //$NON-NLS-1$
                + Global.pluginSignForTooltip());
            syntaxItem.addListener(SWT.Selection,
                e -> SyntaxHelp.open(hit.page, hit.scene(), hit.labelView, hit.name));
        }

        menu.addListener(SWT.Hide, e -> control.getDisplay().asyncExec(() ->
        {
            if (!menu.isDisposed())
                menu.dispose();
        }));
        menu.setLocation(display);
        menu.setVisible(true);
    }

    /** Английское имя признака модели ({@code conditionalAppearance}) для строки палитры. */
    private static String englishFeature(HitProperty hit)
    {
        String resolved = PropertySheetControlInterop.resolveCopyPropertyName(hit.page,
            hit.scene(), hit.labelView, hit.name);
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
        String english = PropertySheetControlInterop.resolveModelPropertyName(hit.page,
            hit.scene(), hit.labelView, hit.name);
        if (english == null || english.isEmpty())
            english = englishFeature(hit);
        String text;
        try
        {
            text = PropertySheetPlatformPropertyResolver.russianNameForCopy(hit.page,
                hit.scene(), hit.labelView, hit.name, english);
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
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Object page = resolvePageFromControl(control);
        if (page == null)
            return;
        bindPalette(page);
        HitProperty hit = propertyAt(page, control, control.toDisplay(event.x, event.y));
        if (hit == null)
            return;
        if (control == tooltipControl && hit.name.equals(tooltipProperty))
            return;
        String text;
        try
        {
            text = SyntaxHelp.describe(hit.page, hit.scene(), hit.labelView, hit.name);
        }
        catch (RuntimeException e)
        {
            Global.tempLogException(TEMP_TOPIC, "подсказка «" + hit.name + "»", e); //$NON-NLS-1$ //$NON-NLS-2$
            text = null;
        }
        if (text == null || text.isEmpty())
        {
            // Часть свойств палитры существует только в метаданных: описания у них нет и быть
            // не может. Пустая подсказка выглядела бы как «не успело загрузиться», поэтому
            // говорим прямо.
            text = NO_PLATFORM_PROPERTY_TOOLTIP;
        }
        String caption = truncatedCaption(control, hit.labelView, hit.name);
        if (caption != null)
            text = caption + ".\n" + text; //$NON-NLS-1$
        text = TooltipText.wrap(control, text);
        tooltipControl = control;
        tooltipProperty = hit.name;
        tooltipText = text;
        control.setToolTipText(text);
    }

    /**
     * Полный заголовок свойства, если он сейчас обрезан по ширине палитры (иначе {@code null}).
     * {@code LightLabel} сам штатного тултипа для этого не ставит — при отрисовке лишь сравнивает
     * {@code computeSize(gc, -1, -1, true).x} (полный размер без обрезки, из {@code cachedExtent})
     * с {@code getBounds().width} и, если не влезает, укорачивает текст многоточием только для
     * рисования. Тот же расчёт, отдельным {@code GC} на контроле палитры.
     */
    private static String truncatedCaption(Control control, Object labelView, String fullCaption)
    {
        if (labelView == null || fullCaption == null || fullCaption.isEmpty())
            return null;
        // hit.labelView — обёртка AEF-«вида», не сам LightLabel: bounds/computeSize есть только
        // у света под ней (см. PropertySheetControlInterop.liveLightDisplayBounds).
        Object light = PropertySheetControlInterop.lightControlFromView(labelView);
        if (light == null)
            return null;
        Object boundsObj = Global.invoke(light, "getBounds"); //$NON-NLS-1$
        if (!(boundsObj instanceof Rectangle bounds) || bounds.width <= 0)
            return null;
        GC gc = new GC(control);
        try
        {
            Object sizeObj = Global.invoke(light, "computeSize", gc, -1, -1, true); //$NON-NLS-1$
            return sizeObj instanceof Point size && size.x > bounds.width ? fullCaption : null;
        }
        finally
        {
            gc.dispose();
        }
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
        if (palettePage != null)
        {
            Composite root = PropertySheetUiContext.findPaletteRoot(palettePage);
            if (root != null && !root.isDisposed())
            {
                if (paletteRoot == null || paletteRoot.isDisposed() || paletteRoot != root)
                    ensureRebuildWatch(palettePage);
                return;
            }
        }
        for (IViewPart view : HOOKED_VIEWS)
        {
            Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
            if (page == null)
                continue;
            paletteView = view;
            paletteEditor = null;
            if (page != palettePage || paletteRoot == null || paletteRoot.isDisposed())
            {
                palettePage = page;
                ensureRebuildWatch(page);
            }
            return;
        }
        for (DtGranularEditor<?> editor : HOOKED_EDITORS)
        {
            IFormPage page = editor.getActivePageInstance();
            if (!isAefPropertyPage(page))
                continue;
            paletteView = null;
            paletteEditor = editor;
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
     * <p><b>Доступ к внутренним классам EDT.</b> Резолв {@code BslDocumentationProvider}, показ
     * панели, поиск и открытие готовой страницы вынесены в {@link BslSyntaxAssist} — тем же
     * пользуется команда «Открыть синтакс-помощник» редактора модуля. Там же объяснено, почему
     * классы грузятся загрузчиком бандла, а не через {@code Class.forName}.
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

        private SyntaxHelp() {}

        static PropertyDoc resolve(Object page, Object scene, Object lwtView, String displayName)
        {
            String englishHint = PropertySheetControlInterop.resolveModelPropertyName(page, scene,
                lwtView, displayName);
            PropertySheetControlInterop.CopyNameContext ctx = PropertySheetControlInterop
                .resolveCopyNameContext(page, scene, lwtView, displayName);
            org.eclipse.emf.ecore.EStructuralFeature feature = ctx.feature();
            if (feature == null && ctx.featurePath != null && ctx.featurePath.length > 0)
                feature = ctx.featurePath[ctx.featurePath.length - 1];

            PropertySheetPlatformPropertyResolver.Resolved propertyResolved =
                PropertySheetPlatformPropertyResolver.resolve(page, scene, lwtView, displayName,
                    englishHint);
            if (propertyResolved != null && propertyResolved.property != null)
                return resolvePropertyDoc(propertyResolved, feature);

            PropertySheetPlatformPropertyResolver.ResolvedEvent eventResolved =
                PropertySheetPlatformPropertyResolver.resolveEvent(page, scene, lwtView,
                    displayName, englishHint);
            if (eventResolved != null && eventResolved.event != null)
                return resolveEventDoc(eventResolved);

            return null;
        }

        private static PropertyDoc resolvePropertyDoc(
                PropertySheetPlatformPropertyResolver.Resolved resolved,
                org.eclipse.emf.ecore.EStructuralFeature feature)
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
                if (PropertySheetPlatformPropertyResolver.supportsBslSyntaxHelp(resolved, feature))
                {
                    Object viewPage = propertyPage(resolved.property);
                    found = new PropertyDoc(resolved.russianName(), viewPage, key);
                }
                else
                {
                    found = new PropertyDoc(resolved.russianName(), null, key);
                    Global.tempLog(TEMP_TOPIC, "документация «" + key //$NON-NLS-1$
                        + "»: свойство метаданных-перечисления, синтакс-помощник пропущен"); //$NON-NLS-1$
                }
            }
            catch (RuntimeException e)
            {
                Global.tempLogException(TEMP_TOPIC, "документация: " + key, e); //$NON-NLS-1$
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
                if (BslSyntaxAssist.showView() == null)
                    return;

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
                BslSyntaxAssist.showSearch(search);
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
            try
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
            catch (RuntimeException e)
            {
                Global.tempLogException(TEMP_TOPIC, "описание «" + displayName + "»", e); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }

        private static boolean openInSyntaxAssist(Object viewPage)
        {
            return BslSyntaxAssist.openViewPage(viewPage);
        }

        /** {@code BslDocumentationProvider} — общий с панелью экземпляр, см. {@link BslSyntaxAssist}. */
        private static Object documentationProvider()
        {
            return BslSyntaxAssist.documentationProvider();
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
