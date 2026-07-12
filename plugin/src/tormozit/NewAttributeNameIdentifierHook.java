package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * В штатных диалогах создания объектов метаданных («Новый реквизит», «Новый реквизит табличной
 * части», «Новое измерение», «Новый ресурс», «Новый справочник», «Новая табличная часть» и т.д. —
 * все мастера-наследники {@code DtAefMdNewWizard}, см. {@link #isAttributeWizard}) — при уходе
 * фокуса из поля «Имя» автоматически преобразует введённое представление в валидный
 * идентификатор — см. {@link IdentifierFromRepresentation}, порт 1С-функции
 * {@code ИдентификаторИзПредставленияЛкс}.
 *
 * <p>Поле «Имя» в этом диалоге рендерится через LWT ({@code DtAefNewWizardPage} с
 * {@code LwtRenderingParameters}), поэтому штатного SWT-виджета для него нет и подход
 * остальных {@code *DialogHook} (поиск {@link org.eclipse.swt.widgets.Text} по дереву) здесь
 * не работает. Хук достаёт live-объект {@code com._1c.g5.lwt.controls.LightText} рефлексией
 * по цепочке (все имена полей/методов проверены декомпиляцией жаров EDT):
 * <pre>
 * Shell.getData()                           → IWizardContainer (JFace, стандартный setData(this) в Window.create())
 * IWizardContainer.getCurrentPage()          → главная страница мастера (DtAefNewWizardPage.AefPage)
 * page.component            (reflection)     → MdModelNewWizardPageComponent (AEF-компонент страницы)
 * component.nameComponent   (reflection)     → DtTextComponent (AEF-компонент поля «Имя»)
 * nameComponent.getViewModels().iterator().next() → TextViewModel (EMF view-model поля)
 * page.scene                (reflection)     → Scene
 * scene.renderer            (reflection)     → MdLwtRenderer (extends LwtRenderer)
 * renderer.viewModelToView  (reflection)     → Map&lt;IViewModel, IView&gt;
 * view.getNativeControl()   (reflection)     → LightText — искомый light-контрол.
 * </pre>
 * На {@code LightText} вешается {@code com._1c.g5.lwt.ILightControlListener} через
 * {@link Proxy} (без compile-зависимости от бандла {@code com._1c.g5.lwt} — по тому же
 * принципу, что и весь остальной плагин, работающий с internal-классами EDT только через
 * {@link Global#getField}/{@link Global#invoke}). В {@code eventReceived} при
 * {@code event.type == SWT.FocusOut} (это событие {@code LightText} рассылает синхронно и
 * безусловно при потере фокуса — см. {@code LightText.OverlayFocusListener.focusLost},
 * ДО разрушения overlay-редактора) текущий текст поля читается через
 * {@code LightText.getText()} и, если преобразование меняет его, записывается обратно через
 * {@code LightText.setText(String)} — этот метод сам обновляет overlay и уведомляет
 * AEF-модель, так что новое значение попадает в реквизит как обычное редактирование.
 */
public class NewAttributeNameIdentifierHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.newAttributeNameIdentifierPatched"; //$NON-NLS-1$
    /**
     * Общий предок всех мастеров «Новый ...» для объектов метаданных ({@code AttributeWizard},
     * {@code TabularSectionAttributeWizard}, {@code RegisterDimensionWizard},
     * {@code RegisterResourceWizard}, {@code CatalogWizard}, {@code TabularSectionWizard},
     * {@code ChartOfCalculationTypesWizard} и т.д. — см. декомпиляцию
     * {@code com._1c.g5.v8.dt.md.ui} 30.0.1). У всех страница — один и тот же класс
     * {@code DtAefNewWizard$AefPage}, но структура {@code page.component} может отличаться
     * (у мастеров с выбором типа — доп. уровень {@code ...WithType...}), поэтому
     * {@link #resolveNameLightText} может не находить поле «Имя» для части наследников —
     * это не ошибка, а штатный отказ.
     */
    private static final String WIZARD_ANCESTOR_CLASS =
        "com._1c.g5.v8.dt.md.ui.wizards.base.aef.DtAefMdNewWizard"; //$NON-NLS-1$
    private static final String LIGHT_CONTROL_LISTENER_CLASS =
        "com._1c.g5.lwt.ILightControlListener"; //$NON-NLS-1$
    private static final String LOG_TAG = "NewAttributeNameIdentifier"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (!isNewAttributeShell(shell))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    /**
     * Дешёвый предварительный фильтр на уровне shell (заголовок не проверяем — у разных
     * мастеров он разный, см. {@link #WIZARD_ANCESTOR_CLASS}); точная проверка — по классу
     * мастера в {@link #tryPatch}.
     */
    private static boolean isNewAttributeShell(Shell shell)
    {
        return shell.getData() instanceof IWizardContainer;
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 60;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 15)
                schedulePatchAttempt(display, shell, attempt + 1);
            else
                Global.log(LOG_TAG, "не удалось найти поле «Имя» после серии попыток"); //$NON-NLS-1$
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object data = shell.getData();
        if (!(data instanceof IWizardContainer wizardContainer))
            return false;

        IWizardPage page = wizardContainer.getCurrentPage();
        if (page == null)
            return false;

        IWizard wizard = page.getWizard();
        if (!isAttributeWizard(wizard))
        {
            // Shell с IWizardContainer, но мастер не из DtAefMdNewWizard — не наш диалог,
            // дальше не пытаемся.
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }

        Object lightText = resolveNameLightText(page);
        if (lightText == null)
            return false;

        if (!installFocusOutListener(lightText))
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        Global.log(LOG_TAG, "поле «Имя» подключено: " + wizard.getClass().getSimpleName()); //$NON-NLS-1$
        return true;
    }

    private static boolean isAttributeWizard(IWizard wizard)
    {
        if (wizard == null)
            return false;
        for (Class<?> c = wizard.getClass(); c != null; c = c.getSuperclass())
        {
            if (WIZARD_ANCESTOR_CLASS.equals(c.getName()))
                return true;
        }
        return false;
    }

    /** Достаёт {@code LightText} поля «Имя» из главной страницы мастера (см. комментарий класса). */
    private static Object resolveNameLightText(IWizardPage page)
    {
        Object component = Global.getField(page, "component"); //$NON-NLS-1$
        if (component == null)
            return null;
        Object nameComponent = Global.getField(component, "nameComponent"); //$NON-NLS-1$
        if (nameComponent == null)
            return null;

        Object viewModel = firstViewModel(nameComponent);
        if (viewModel == null)
            return null;

        Object scene = Global.getField(page, "scene"); //$NON-NLS-1$
        if (scene == null)
            return null;
        Object renderer = Global.getField(scene, "renderer"); //$NON-NLS-1$
        if (renderer == null)
            return null;
        Object viewModelToViewObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(viewModelToViewObj instanceof Map<?, ?> viewModelToView))
            return null;

        Object view = viewModelToView.get(viewModel);
        if (view == null)
            return null;

        return Global.invoke(view, "getNativeControl"); //$NON-NLS-1$
    }

    private static Object firstViewModel(Object component)
    {
        Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
        if (viewModels instanceof Iterable<?> iterable)
        {
            Iterator<?> it = iterable.iterator();
            if (it.hasNext())
                return it.next();
        }
        return null;
    }

    private static boolean installFocusOutListener(Object lightText)
    {
        try
        {
            ClassLoader lwtLoader = lightText.getClass().getClassLoader();
            Class<?> listenerClass = Class.forName(LIGHT_CONTROL_LISTENER_CLASS, true, lwtLoader);

            InvocationHandler handler = (proxy, method, args) ->
            {
                String name = method.getName();
                if ("eventReceived".equals(name) && args != null && args.length == 2 //$NON-NLS-1$
                    && args[1] instanceof Event event)
                {
                    if (event.type == SWT.FocusOut)
                        onNameFocusLost(lightText);
                    return null;
                }
                // Proxy пересылает handler'у и служебные Object-методы (hashCode/equals/toString),
                // которые может вызвать инфраструктура EDT при добавлении/поиске слушателя в
                // коллекции; для примитивных возвращаемых типов null недопустим (NPE на
                // автоунбоксинге), поэтому отвечаем на них явно.
                if ("hashCode".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return System.identityHashCode(proxy);
                if ("equals".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                    return proxy == args[0];
                if ("toString".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy)); //$NON-NLS-1$
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class)
                    return Boolean.FALSE;
                if (returnType == int.class || returnType == long.class || returnType == short.class
                    || returnType == byte.class)
                    return 0;
                if (returnType == char.class)
                    return '\0';
                if (returnType == float.class || returnType == double.class)
                    return 0.0;
                return null;
            };

            Object listenerProxy = Proxy.newProxyInstance(lwtLoader, new Class<?>[] { listenerClass }, handler);
            return Global.invokeVoid(lightText, "addControlListener", listenerProxy); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.logError(LOG_TAG, "installFocusOutListener", e); //$NON-NLS-1$
            return false;
        }
    }

    private static void onNameFocusLost(Object lightText)
    {
        try
        {
            // lightText — это LightEditorBar (обёртка-бар, на ней и висит слушатель фокуса),
            // сам текст хранит вложенный LightText, доступный через getContent() —
            // у LightEditorBar нет своих getText()/setText() (см. декомпиляцию
            // com._1c.g5.lwt.controls.LightEditorBar / AbstractLightContentComposite).
            Object textControl = Global.invoke(lightText, "getContent"); //$NON-NLS-1$
            if (textControl == null)
                return;

            Object textObj = Global.invoke(textControl, "getText"); //$NON-NLS-1$
            if (!(textObj instanceof String text) || text.isEmpty())
                return;

            // specialCharReplacement = "" — недопустимые символы отбрасываются, а следующий
            // допустимый символ переводится в верхний регистр (как штатное автоформирование
            // имени в 1С), а не заменяется на "_". Чтобы получить подчёркивания вместо
            // camelCase-склейки, передайте "_" последним аргументом.
            String identifier = IdentifierFromRepresentation.convert(text, "_", "", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (identifier.equals(text))
                return;

            Global.invokeVoid(textControl, "setText", identifier); //$NON-NLS-1$
            Global.log(LOG_TAG, "«" + text + "» → «" + identifier + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (Exception e)
        {
            Global.logError(LOG_TAG, "onNameFocusLost", e); //$NON-NLS-1$
        }
    }
}
