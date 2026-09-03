package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Активация (передача фокуса ввода) поля AEF по его компоненту — единый механизм для всех
 * мест плагина: панель «Свойства» ({@code ConfigSearchResultsHook.PropertyFieldFocus},
 * {@code ProblemViewOpenTargetHook}), редакторы объектов метаданных
 * ({@link EventHandlersOpenHandlerHook} — поле «Источник» подписки) и т.п.
 *
 * <p>Как это работает: компонент AEF сам по себе не контрол — он держит {@code IViewModel},
 * а рендерер сцены хранит соответствие {@code viewModelToView}; у view берётся
 * {@code getNativeControl()} — это либо SWT {@link Control}, либо LWT-контрол, которому фокус
 * ставится через {@code setFocus(FocusSource.Keyboard)}. Подписи ({@code LabelViewModel} /
 * {@code LabelComponent}) пропускаются — фокус нужен в поле ввода, а не в подписи.
 *
 * <p>Чего делать НЕ нужно (проверено): слать {@code ClientFocusEvent} сцене с ключом-свойством
 * ({@code scene.queueEvent(feature, event)}) — {@code Scene.queueEvent} ищет компонент через
 * {@code findByQuery(ключ)} и при неудаче молча выходит, так что вызов «проходит», а фокус не
 * меняется. Отправка того же события напрямую компоненту тоже ничего не гарантирует: реальный
 * фокус ставится нативному контролу, до которого и добирается этот класс.
 *
 * <p>Возвращаемое значение — {@code true} только если контрол ДЕЙСТВИТЕЛЬНО забрал фокус
 * ({@code isFocusControl()} / {@code isFocused()}), а не просто принял вызов: на этом строятся
 * циклы ожидания у потребителей (панель наполняется асинхронно).
 */
final class AefFieldFocus
{
    /**
     * Задержки перепроверок после программной активации поля. EDT доводит наполнение панели
     * «Свойства» асинхронно и при этом ставит ввод в первое поле («Имя»), отбирая его у
     * активированного — поэтому фокус ещё некоторое время удерживается (см. {@link #holdFocus}).
     */
    private static final int[] FOCUS_HOLD_DELAYS = { 100, 250, 500, 1000 };

    private static volatile Object lwtKeyboardFocusSource;
    private static volatile Class<?> swtLightCompositeClass;
    /** Текущее удержание: новая активация обесценивает расписание прежней. */
    private static Object holdToken;
    /** Контрол, в который активация поставила ввод последним — по нему видно, отобрали ли его. */
    private static Object lastFocusedControl;
    /** Идёт повтор активации из удержания — своё же расписание обесценивать не надо. */
    private static boolean replayingHold;
    /** Время последнего ввода пользователя — после него фокус у него не отбирается. */
    private static long lastUserInputTime;
    private static boolean userInputListenerInstalled;

    private AefFieldFocus() {}

    /**
     * Ставит фокус в поле ввода компонента (или его потомка).
     *
     * @param scene сцена AEF, которой принадлежит компонент ({@code getScene()} страницы/компонента)
     * @param component компонент поля
     * @return {@code true}, если фокус реально получен
     */
    static boolean focusComponent(Object scene, Object component)
    {
        for (Object nativeControl : editorNativeControls(scene, component))
            if (focusNativeControl(nativeControl))
                return true;
        return false;
    }

    /**
     * Снимает выделение текста в НЕактивных полях ввода поддерева компонентов. Нужно после
     * программной активации другого поля: EDT при открытии редактора выделяет текст первого
     * поля («Имя»), а выделение в потерявшем фокус контроле остаётся видимым — на экране
     * подсвеченными выглядят сразу два поля.
     */
    static void clearSelectionInUnfocusedFields(Object scene, Object rootComponent)
    {
        for (Object nativeControl : editorNativeControls(scene, rootComponent))
        {
            if (nativeControl instanceof Control control)
            {
                if (control.isDisposed() || control.isFocusControl())
                    continue;
                if (control instanceof Text text)
                    text.setSelection(0, 0);
                else if (control instanceof StyledText styledText)
                    styledText.setSelection(0, 0);
            }
            else if (!Boolean.TRUE.equals(Global.invoke(nativeControl, "isFocused"))) //$NON-NLS-1$
                clearLightTreeSelection(nativeControl, 0);
        }
    }

    /**
     * Поле ввода LWT лежит НЕ на верхнем уровне: рендерер отдаёт контейнер
     * ({@code LightEditorBar} — панель поля, {@code SwtLightComposite} — мост к SWT), а сам
     * {@code LightText} — внутри него (подтверждено дампом: среди нативных контролов страницы
     * {@code LightText} не встречается вовсе). Поэтому спускаемся по LWT-детям
     * ({@code ILightComposite.getChildren()}, {@code ILightContentComposite.getContent()},
     * левая/правая части панели) и снимаем выделение у каждого поля, кроме сфокусированного.
     */
    private static String clearLightTreeSelection(Object lightControl, int depth)
    {
        if (lightControl == null || depth > 6)
            return "не поддержано"; //$NON-NLS-1$
        if (Boolean.TRUE.equals(Global.invoke(lightControl, "isFocused"))) //$NON-NLS-1$
            return "в фокусе — пропуск"; //$NON-NLS-1$

        String own = clearLightControlSelection(lightControl);
        if (!own.startsWith("не поддержано")) //$NON-NLS-1$
            return own;

        for (Object child : lightChildren(lightControl))
        {
            String result = clearLightTreeSelection(child, depth + 1);
            if (!result.startsWith("не поддержано") && !result.startsWith("в фокусе")) //$NON-NLS-1$ //$NON-NLS-2$
                return child.getClass().getSimpleName() + ": " + result; //$NON-NLS-1$
        }
        return own;
    }

    private static List<Object> lightChildren(Object lightControl)
    {
        List<Object> out = new ArrayList<>();
        Object children = Global.invoke(lightControl, "getChildren"); //$NON-NLS-1$
        if (children instanceof Iterable)
            for (Object child : (Iterable<?>)children)
                if (child != null)
                    out.add(child);
        for (String getter : new String[] { "getContent", "getLeftSide", "getRightSide" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object child = Global.invoke(lightControl, getter);
            if (child != null && !out.contains(child))
                out.add(child);
        }
        return out;
    }

    /**
     * LWT-поле: своих полей выделения у {@code LightText} нет — выделение живёт в его
     * {@code overlay} ({@link StyledText}, существует, пока поле в режиме ввода), туда же
     * ходит и штатный {@code clearSelection()}. Поэтому сначала overlay напрямую, затем
     * штатные методы по именам.
     */
    private static String clearLightControlSelection(Object lightControl)
    {
        Object overlay = Global.getField(lightControl, "overlay"); //$NON-NLS-1$
        if (overlay instanceof StyledText overlayText && !overlayText.isDisposed())
        {
            overlayText.setSelection(0, 0);
            return "lwt overlay StyledText"; //$NON-NLS-1$
        }
        if (Global.invokeVoid(lightControl, "clearSelection")) //$NON-NLS-1$
            return "lwt clearSelection()"; //$NON-NLS-1$
        if (Global.invokeVoid(lightControl, "setSelection", Integer.valueOf(0), Integer.valueOf(0))) //$NON-NLS-1$
            return "lwt setSelection(0,0)"; //$NON-NLS-1$
        return "не поддержано (overlay=" + (overlay == null ? "null" : overlay.getClass().getName()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Фокус уже в поле этого компонента. Нужно там, где чужой код (собственная инициализация
     * страницы редактора) может перевести ввод обратно на первое поле уже ПОСЛЕ нашего вызова:
     * проверка позволяет повторить активацию только когда она реально сбита.
     */
    static boolean isComponentFocused(Object scene, Object component)
    {
        for (Object nativeControl : editorNativeControls(scene, component))
        {
            if (nativeControl instanceof Control control)
            {
                if (!control.isDisposed() && control.isFocusControl())
                    return true;
            }
            else if (Boolean.TRUE.equals(Global.invoke(nativeControl, "isFocused"))) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    /**
     * Нативные контролы редакторов компонента и его потомков в порядке обхода. Подписи
     * ({@code LabelViewModel}/{@code LabelComponent}) исключаются — фокус нужен в поле ввода.
     */
    static List<Object> editorNativeControls(Object scene, Object component)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        List<Object> out = new ArrayList<>();
        if (mapObj instanceof Map)
            collectEditorNativeControls((Map<?, ?>)mapObj, component, out, 0);
        return out;
    }

    private static void collectEditorNativeControls(Map<?, ?> viewModelToView, Object component,
        List<Object> out, int depth)
    {
        if (component == null || depth > 8)
            return;
        Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
        if (viewModels instanceof Iterable)
        {
            for (Object viewModel : (Iterable<?>)viewModels)
            {
                if (viewModel == null || viewModel.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                    continue;
                Object view = viewModelToView.get(viewModel);
                Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
                if (nativeControl != null && !out.contains(nativeControl))
                    out.add(nativeControl);
            }
        }
        for (Object child : childComponents(component))
            if (!child.getClass().getName().contains("LabelComponent")) //$NON-NLS-1$
                collectEditorNativeControls(viewModelToView, child, out, depth + 1);
    }

    static List<Object> childComponents(Object component)
    {
        List<Object> out = new ArrayList<>();
        Object children = Global.invoke(component, "getComponents"); //$NON-NLS-1$
        if (children instanceof Iterable)
            for (Object child : (Iterable<?>)children)
                if (child != null)
                    out.add(child);
        Object definitionComponent = Global.invoke(component, "getDefinitionComponent"); //$NON-NLS-1$
        if (definitionComponent != null && !out.contains(definitionComponent))
            out.add(definitionComponent);
        return out;
    }

    /** @return {@code true}, если контрол реально ЗАБРАЛ фокус (а не просто принял вызов) */
    static boolean focusNativeControl(Object nativeControl)
    {
        boolean focused = doFocusNativeControl(nativeControl);
        if (focused)
        {
            // Активировали ДРУГОЕ поле — удержание прежнего немедленно обесценивается, иначе оно
            // возвращает ввод в прежнее свойство уже после нового перехода (и мигает старое имя).
            if (!replayingHold)
                holdToken = null;
            lastFocusedControl = nativeControl;
            // Активация поля идёт через этот класс отовсюду, поэтому и панель «Свойства» узнаёт
            // о ней здесь — сценариям перехода (поиск, дерево элементов формы) знать про
            // подсветку имени свойства незачем.
            PropertySheetActivePropertyHook.onFieldActivatedProgrammatically(nativeControl);
        }
        return focused;
    }

    /**
     * Ввод сейчас в этом контроле. У LWT-контрола флаг {@code isFocused()} остаётся поднятым и
     * после того, как ввод ушёл в соседний SWT-контрол (поле «Высота» — это {@code StyledText},
     * отдельный виджет, а не light-контрол на том же канвасе), поэтому флаг признаётся, только
     * если SWT-ввод и правда на канвасе этого light-контрола.
     */
    static boolean hasFocusNow(Object nativeControl)
    {
        if (nativeControl == null)
            return false;
        if (nativeControl instanceof Control control)
            return !control.isDisposed() && control.isFocusControl();
        return Boolean.TRUE.equals(Global.invoke(nativeControl, "isFocused")) //$NON-NLS-1$
            && swtCanvasHasFocus(nativeControl);
    }

    /** SWT-ввод находится на канвасе ({@code SwtLightComposite}) этого light-контрола. */
    private static boolean swtCanvasHasFocus(Object lightControl)
    {
        Class<?> hostClass = swtLightCompositeClass(lightControl);
        Object host = hostClass != null
            ? Global.invoke(hostClass, "getHostSwtLightComposite", lightControl) : null; //$NON-NLS-1$
        Object swtComposite = host != null ? Global.invoke(host, "getSwtComposite") : null; //$NON-NLS-1$
        if (!(swtComposite instanceof Control canvas) || canvas.isDisposed())
            return false;
        Display display = canvas.getDisplay();
        Control focus = display != null && !display.isDisposed() ? display.getFocusControl() : null;
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == canvas)
                return true;
        }
        return false;
    }

    private static Class<?> swtLightCompositeClass(Object lightControl)
    {
        Class<?> cached = swtLightCompositeClass;
        if (cached != null)
            return cached;
        try
        {
            cached = Class.forName("com._1c.g5.lwt.interop.SwtLightComposite", false, //$NON-NLS-1$
                lightControl.getClass().getClassLoader());
            swtLightCompositeClass = cached;
            return cached;
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    /**
     * Удержание активации свойства: EDT дозаполняет панель «Свойства» уже ПОСЛЕ того, как поле
     * найдено и активировано, при этом пересоздаёт её контролы и ставит ввод в первое поле
     * («Имя») — свойство, к которому переходил пользователь, оставалось неактивированным.
     * Удерживать сам контрол бесполезно (после перезаполнения он уже не на сцене), поэтому
     * повторяется вся активация: {@code activation} ищет поле в АКТУАЛЬНОЙ сцене заново.
     * <p>
     * Расписание обрывается, как только пользователь сам что-то нажал (отбирать у него ввод
     * нельзя), сменилось активное окно или началась активация другого поля.
     *
     * @param activation ищет поле свойства и ставит в него ввод; {@code true} — получилось
     */
    static void holdActivation(BooleanSupplier activation)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        installUserInputListener(display);
        Object token = new Object();
        holdToken = token;
        holdActivation(activation, 0, token, display.getActiveShell(), System.currentTimeMillis());
    }

    private static void holdActivation(BooleanSupplier activation, int attempt, Object token,
        Shell shell, long activatedAt)
    {
        if (attempt >= FOCUS_HOLD_DELAYS.length)
        {
            PropertySheetActivePropertyHook.onActivationSettled();
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(FOCUS_HOLD_DELAYS[attempt], () -> {
            if (token != holdToken)
            {
                return; // начался другой переход — о его завершении сообщит он сам
            }
            if (lastUserInputTime > activatedAt || display.isDisposed()
                || display.getActiveShell() != shell)
            {
                PropertySheetActivePropertyHook.onActivationSettled();
                return;
            }
            // Ввод там же, где мы его оставили — панель ещё не вмешалась, повторять нечего.
            boolean keptFocus = hasFocusNow(lastFocusedControl);
            if (!keptFocus)
            {
                replayingHold = true;
                boolean again;
                try
                {
                    again = activation.getAsBoolean();
                }
                finally
                {
                    replayingHold = false;
                }
                Global.tempLog("свойства-активное", //$NON-NLS-1$
                    "удержание: повтор активации #" + attempt + " → " + again); //$NON-NLS-1$ //$NON-NLS-2$
            }
            holdActivation(activation, attempt + 1, token, shell, activatedAt);
        });
    }

    /** Ввод пользователя отменяет удержание: перехватывать у него фокус недопустимо. */
    private static void installUserInputListener(Display display)
    {
        if (userInputListenerInstalled)
            return;
        userInputListenerInstalled = true;
        Listener listener = event -> lastUserInputTime = System.currentTimeMillis();
        display.addFilter(SWT.MouseDown, listener);
        display.addFilter(SWT.KeyDown, listener);
    }

    private static boolean doFocusNativeControl(Object nativeControl)
    {
        if (nativeControl instanceof Control control)
            return !control.isDisposed() && control.setFocus() && control.isFocusControl();
        Object focusSource = lwtKeyboardFocusSource(nativeControl);
        if (focusSource == null)
            return false;
        Object result = Global.invoke(nativeControl, "setFocus", focusSource); //$NON-NLS-1$
        return Boolean.TRUE.equals(result)
            || Boolean.TRUE.equals(Global.invoke(nativeControl, "isFocused")); //$NON-NLS-1$
    }

    private static Object lwtKeyboardFocusSource(Object nativeControl)
    {
        Object cached = lwtKeyboardFocusSource;
        if (cached != null)
            return cached;
        try
        {
            Class<?> focusSourceClass = Class.forName("com._1c.g5.lwt.FocusSource", true, //$NON-NLS-1$
                nativeControl.getClass().getClassLoader());
            cached = focusSourceClass.getField("Keyboard").get(null); //$NON-NLS-1$
            lwtKeyboardFocusSource = cached;
            return cached;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
