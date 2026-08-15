package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;

/**
 * Активация (передача фокуса ввода) поля AEF по его компоненту — единый механизм для всех
 * мест плагина: панель «Свойства» ({@code ConfigSearchResultsHook.PropertyFieldFocus},
 * {@code ProblemViewPropertyFocusHook}), редакторы объектов метаданных
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
    private static volatile Object lwtKeyboardFocusSource;

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
