package tormozit;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
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

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * В панели «Свойства» (LWT/AEF-рендеринг — та же технология, что и в мастерах «Новый ...», см.
 * {@link NewAttributeNameIdentifierHook}) при уходе фокуса из строки «Имя» автоматически
 * преобразует введённое представление в валидный идентификатор — см.
 * {@link Global#identifierFromRepresentation}.
 *
 * <p>Независим от остальной инфраструктуры панели «Свойства» ({@link PropertySheetHook} и др.) —
 * та отключена автором ({@code if (true) return; // не осилил}) как чрезмерно сложная попытка
 * canvas-hit-test подсветки по клику мыши.
 *
 * <p>Редактор строки «Имя» находится через {@code renderer.viewModelToView} —
 * это {@link java.util.LinkedHashMap}, сохраняющий порядок вставки строк палитры: подпись
 * ({@code LabelViewModel} с текстом «Имя», рендерится как нередактируемый
 * {@code LightLabel} — сам по себе не годится) и её редактор (в наблюдаемых случаях —
 * {@code ActionBarViewModel}/{@code DtActionBarView}, аналогично мастеру, см.
 * {@link NewAttributeNameIdentifierHook}) идут в карте строго подряд: редактор — это запись,
 * непосредственно следующая за подписью в порядке итерации (подтверждено логом debug-сборки).
 */
public class PropertyNameIdentifierHook implements IStartup
{
    private static final String NAME_PROPERTY_LABEL = "Имя"; //$NON-NLS-1$
    private static final String LOG_TAG = "PropertyNameIdentifier"; //$NON-NLS-1$
    private static final String IR_TYPE_MODULE = "ирОбщий"; //$NON-NLS-1$
    private static final String IR_TYPE_FUNCTION = "ИмяТипаИзИмениПеременнойЛкс"; //$NON-NLS-1$
    private static final String DEFAULT_TYPE_NAME_RU = "Строка"; //$NON-NLS-1$
    private static final int DEFAULT_STRING_LENGTH = 10;
    private static final String TYPE_DESCRIPTION_MODEL_INTERFACE =
        "com._1c.g5.v8.dt.md.ui.aef.models.type.ITypeDescriptionModel"; //$NON-NLS-1$

    /** Нативные light-контролы, на которых уже висит наш слушатель — не переустанавливать. */
    private static final Set<Object> ATTACHED =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(PropertyNameIdentifierHook::install);
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
                if (isPropertySheetView(view))
                    scheduleAttach(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)        { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)       { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)     { tryFromRef(ref); }
            @Override public void partInputChanged(IWorkbenchPartReference ref)  { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref)  {}
            @Override public void partClosed(IWorkbenchPartReference ref)        {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)   {}
            @Override public void partHidden(IWorkbenchPartReference ref)        {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isPropertySheetView(part))
                    scheduleAttach((IViewPart) part, 0);
            }
        });
    }

    private static boolean isPropertySheetView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.PROPERTIES_SHEET_ID.equals(id)
            || "org.eclipse.ui.views.PropertySheet".equals(id); //$NON-NLS-1$
    }

    private static void scheduleAttach(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            if (tryAttach(view))
                return;
            if (attempt < 100) // строки палитры свойств подгружаются лениво — окно ожидания ~10с
                scheduleAttach(view, attempt + 1);
        });
    }

    private static boolean tryAttach(IViewPart view)
    {
        Object page = resolvePropertySheetPage(view);
        if (page == null)
            return false;

        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (scene == null)
            return false;

        Object nameEditorView = findValueViewAfterLabel(scene, NAME_PROPERTY_LABEL);
        if (nameEditorView == null)
            return false;

        Object nativeControl = Global.invoke(nameEditorView, "getNativeControl"); //$NON-NLS-1$
        if (nativeControl == null)
            return false;

        if (ATTACHED.contains(nativeControl))
            return true;

        Object typeModel = findTypeDescriptionModel(scene);
        String initialName = readLightTextValue(nativeControl);

        // В отличие от мастеров «Новый ...» — здесь обработчик срабатывает и на Enter (не только
        // на потерю фокуса), т.к. в панели «Свойства» пользователь обычно правит одно поле и
        // не уходит из него сразу; фокус при Enter не теряется.
        Runnable onCommit = () -> onNameFocusLost(nativeControl, typeModel, initialName);
        if (!Global.installLightControlListener(nativeControl, onCommit, onCommit))
            return false;

        ATTACHED.add(nativeControl);
        Global.log(LOG_TAG, "строка «Имя» подключена в панели «Свойства»"); //$NON-NLS-1$
        return true;
    }

    /** Текущий текст поля «Имя» ({@code getContent()} для обёрток вроде {@code LightEditorBar}, иначе сам контрол). */
    private static String readLightTextValue(Object nativeControl)
    {
        Object content = Global.invoke(nativeControl, "getContent"); //$NON-NLS-1$
        Object textControl = content != null ? content : nativeControl;
        Object textObj = Global.invoke(textControl, "getText"); //$NON-NLS-1$
        return textObj instanceof String s ? s : null;
    }

    /**
     * Обходит дерево AEF-компонентов {@code scene.getComponent()} (рекурсивно через
     * {@code IComponent.getComponents()}) и ищет первый компонент, чья модель
     * ({@code IComponent.getModel()}) реализует {@code ITypeDescriptionModel} — так находится
     * структурное описание типа для строки «Тип», т.к. панель «Свойства» рендерится generic
     * definition-driven компонентом без именованных полей вроде {@code typeComponent}
     * (в отличие от мастеров, см. {@link NewAttributeNameIdentifierHook}).
     */
    private static Object findTypeDescriptionModel(Object scene)
    {
        Object root = Global.invoke(scene, "getComponent"); //$NON-NLS-1$
        return findTypeDescriptionModelInTree(root, 0);
    }

    private static Object findTypeDescriptionModelInTree(Object component, int depth)
    {
        if (component == null || depth > 12)
            return null;
        Object model = Global.invoke(component, "getModel"); //$NON-NLS-1$
        if (implementsInterface(model, TYPE_DESCRIPTION_MODEL_INTERFACE))
            return model;
        Object children = Global.invoke(component, "getComponents"); //$NON-NLS-1$
        if (children instanceof Iterable<?> iterable)
        {
            for (Object child : iterable)
            {
                Object found = findTypeDescriptionModelInTree(child, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean implementsInterface(Object obj, String interfaceName)
    {
        if (obj == null)
            return false;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            for (Class<?> i : c.getInterfaces())
            {
                if (interfaceName.equals(i.getName()))
                    return true;
            }
        }
        return false;
    }

    private static Object resolvePropertySheetPage(IViewPart view)
    {
        Object page = Global.invoke(view, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;
        Object pageBook = Global.invoke(view, "getPageBook"); //$NON-NLS-1$
        page = Global.invoke(pageBook, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;
        return null;
    }

    private static boolean isPropertySheetPage(Object page)
    {
        return page != null && page.getClass().getName().contains("PropertySheetPage"); //$NON-NLS-1$
    }

    /**
     * Ищет в {@code renderer.viewModelToView} (порядок-сохраняющая {@link java.util.LinkedHashMap})
     * {@code LabelViewModel} с текстом {@code displayName} и возвращает view **следующей** записи —
     * это и есть view редактора значения (см. javadoc класса).
     */
    private static Object findValueViewAfterLabel(Object scene, String displayName)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map<?, ?> map))
            return null;

        boolean foundLabel = false;
        for (java.util.Map.Entry<?, ?> entry : map.entrySet())
        {
            if (foundLabel)
                return entry.getValue();

            Object key = entry.getKey();
            if (key != null && key.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
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

    private static void onNameFocusLost(Object nativeControl, Object typeModel, String initialName)
    {
        String identifier;
        boolean modified;
        try
        {
            // Как и в мастере (NewAttributeNameIdentifierHook): если нативный контрол — обёртка
            // вроде LightEditorBar, сам текст хранит getContent(); если это уже LightText —
            // getContent() не найдётся, и используется сам nativeControl.
            Object content = Global.invoke(nativeControl, "getContent"); //$NON-NLS-1$
            Object textControl = content != null ? content : nativeControl;

            Object textObj = Global.invoke(textControl, "getText"); //$NON-NLS-1$
            if (!(textObj instanceof String text) || text.isEmpty())
                return;

            modified = !text.equals(initialName);

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
     * Если поле «Тип» ещё не тронуто пользователем (тип = Строка, длина = 10 — 1С-дефолт) и
     * подключено приложение ИР — запускает в фоне {@code ирОбщий.ИмяТипаИзИмениПеременнойЛкс(Имя)}
     * и, если по готовности результата тип всё ещё дефолтный, подставляет предложенный ИР тип.
     * См. аналогичную (пока не объединённую в общий helper) логику в
     * {@link NewAttributeNameIdentifierHook}.
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

    private static IDtProject resolveDtProject()
    {
        IProject project = Global.getActiveProject((IWorkbenchPage) null, false);
        return project != null ? Global.getDtProjectFromWorkspaceProject(project) : null;
    }

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
