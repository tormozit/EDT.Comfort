package tormozit;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * В штатных диалогах создания объектов метаданных («Новый реквизит», «Новый реквизит табличной
 * части», «Новое измерение», «Новый ресурс», «Новый справочник», «Новая табличная часть» и т.д. —
 * все мастера-наследники {@code DtAefMdNewWizard}, см. {@link #isAttributeWizard}) — при уходе
 * фокуса из поля «Имя» автоматически преобразует введённое представление в валидный
 * идентификатор — см. {@link Global#identifierFromRepresentation}, порт 1С-функции
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
 * {@link Global#installLightControlFocusOutListener} (без compile-зависимости от бандла
 * {@code com._1c.g5.lwt} — по тому же принципу, что и весь остальной плагин, работающий с
 * internal-классами EDT только через {@link Global#getField}/{@link Global#invoke}). При
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
    private static final String LOG_TAG = "NewAttributeNameIdentifier"; //$NON-NLS-1$
    private static final String IR_TYPE_MODULE = "ирОбщий"; //$NON-NLS-1$
    private static final String IR_TYPE_FUNCTION = "ИмяТипаИзИмениПеременнойЛкс"; //$NON-NLS-1$
    private static final String DEFAULT_TYPE_NAME_RU = "Строка"; //$NON-NLS-1$
    private static final int DEFAULT_STRING_LENGTH = 10;

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

        // Модель структурного описания типа («Тип»/длина/т.п.) — общее поле предка-мастера
        // DtAefMdWithTypeNewWizard.model (ITypeDescriptionModel); может не быть у всех наследников
        // DtAefMdNewWizard (например, у CatalogWizard/TabularSectionWizard — они его не наследуют,
        // Global.getField тихо вернёт null, и автоподбор типа для них просто не сработает).
        Object typeModel = Global.getField(wizard, "model"); //$NON-NLS-1$

        // Исходное значение «Имя» на момент подключения слушателя (обычно дефолт вида
        // «Реквизит1») — чтобы подбор типа через ИР запускался по факту правки пользователем,
        // а не только когда наш конвертер идентификатора меняет текст (пробелы и т.п.).
        String initialName = readLightTextValue(lightText);

        if (!Global.installLightControlFocusOutListener(lightText,
            () -> onNameFocusLost(lightText, typeModel, initialName)))
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

    /** Текущий текст поля «Имя» (см. {@link #onNameFocusLost} про обёртку {@code LightEditorBar}). */
    private static String readLightTextValue(Object lightText)
    {
        Object textControl = Global.invoke(lightText, "getContent"); //$NON-NLS-1$
        if (textControl == null)
            return null;
        Object textObj = Global.invoke(textControl, "getText"); //$NON-NLS-1$
        return textObj instanceof String s ? s : null;
    }

    private static void onNameFocusLost(Object lightText, Object typeModel, String initialName)
    {
        String identifier;
        boolean modified;
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

            // Факт правки — сравнение с исходным значением на момент подключения слушателя
            // (обычно дефолт вида «Реквизит1»), а не с тем, поменял ли текст наш конвертер
            // идентификатора (identifier == text вполне может совпадать даже при реальной
            // правке, если введённое имя уже было валидным идентификатором без пробелов).
            modified = !text.equals(initialName);

            // specialCharReplacement = "" — недопустимые символы отбрасываются, а следующий
            // допустимый символ переводится в верхний регистр (как штатное автоформирование
            // имени в 1С), а не заменяется на "_". Чтобы получить подчёркивания вместо
            // camelCase-склейки, передайте "_" последним аргументом.
            identifier = Global.identifierFromRepresentation(text, "_", "", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!identifier.equals(text))
            {
                Global.invokeVoid(textControl, "setText", identifier); //$NON-NLS-1$
                Global.log(LOG_TAG, "«" + text + "» → «" + identifier + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        catch (Exception e)
        {
            Global.logError(LOG_TAG, "onNameFocusLost", e); //$NON-NLS-1$
            return;
        }

        if (modified)
            tryAutofillTypeFromIr(typeModel, identifier);
    }

    /**
     * Если поле «Тип» ещё не тронуто пользователем (тип = Строка, длина = 10 — 1С-дефолт для
     * нового реквизита) и подключено приложение ИР — запускает в фоне
     * {@code ирОбщий.ИмяТипаИзИмениПеременнойЛкс(Имя)} и, если по готовности результата тип
     * всё ещё дефолтный (пользователь не успел выбрать свой), подставляет предложенный ИР тип.
     */
    private static void tryAutofillTypeFromIr(Object typeModel, String name)
    {
        if (typeModel == null || name == null || name.isEmpty())
            return;
        if (!isDefaultStringType(typeModel))
            return;

        IDtProject project = resolveDtProject();
        if (project == null)
            return;

        Global.callIrFunctionInBackground(project, IR_TYPE_MODULE, IR_TYPE_FUNCTION, new Object[] { name },
            () -> isDefaultStringType(typeModel),
            result -> applyIrType(typeModel, result));
    }

    /** {@code true}, если {@code typeModel} сейчас описывает 1С-дефолт для нового реквизита — Строка(10). */
    private static boolean isDefaultStringType(Object typeModel)
    {
        try
        {
            Object singleTypeItemValue = Global.invoke(typeModel, "getSingleTypeItem"); //$NON-NLS-1$
            Object typeItem = Global.invoke(singleTypeItemValue, "get"); //$NON-NLS-1$
            Object nameRu = Global.invoke(typeItem, "getNameRu"); //$NON-NLS-1$
            Object stringLengthValue = Global.invoke(typeModel, "getStringLength"); //$NON-NLS-1$
            Object length = Global.invoke(stringLengthValue, "get"); //$NON-NLS-1$
            if (!DEFAULT_TYPE_NAME_RU.equals(nameRu))
                return false;
            return length instanceof Integer i && i == DEFAULT_STRING_LENGTH;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /** Активный проект как {@link IDtProject} — по установленному в плагине паттерну ({@code Global.getActiveProject}). */
    private static IDtProject resolveDtProject()
    {
        IProject project = Global.getActiveProject((org.eclipse.ui.IWorkbenchPage) null, false);
        return project != null ? Global.getDtProjectFromWorkspaceProject(project) : null;
    }

    /** Сопоставляет строку, возвращённую ИР, с {@link com._1c.g5.v8.dt.mcore.TypeItem} и подставляет в {@code typeModel}. */
    private static void applyIrType(Object typeModel, String irResult)
    {
        if (irResult == null || irResult.isBlank())
            return;
        try
        {
            Object typesObj = Global.invoke(typeModel, "getTypes", Boolean.FALSE); //$NON-NLS-1$
            if (!(typesObj instanceof List<?> types))
                return;

            Object matched = null;
            for (Object item : types)
            {
                Object nameRu = Global.invoke(item, "getNameRu"); //$NON-NLS-1$
                Object name = Global.invoke(item, "getName"); //$NON-NLS-1$
                if (irResult.equalsIgnoreCase(String.valueOf(nameRu)) || irResult.equalsIgnoreCase(String.valueOf(name)))
                {
                    matched = item;
                    break;
                }
            }
            if (matched == null)
                return;

            Object singleTypeItemValue = Global.invoke(typeModel, "getSingleTypeItem"); //$NON-NLS-1$
            Global.invokeVoid(singleTypeItemValue, "set", matched); //$NON-NLS-1$
            Global.log(LOG_TAG, "Тип подобран через ИР: " + irResult); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.logError(LOG_TAG, "applyIrType", e); //$NON-NLS-1$
        }
    }
}
