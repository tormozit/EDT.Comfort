package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.databinding.DataBindingContext;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
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

/**
 * В панели «Свойства» (LWT/AEF-рендеринг — та же технология, что и в мастерах «Новый ...», см.
 * {@link NewAttributeNameIdentifierHook}) при уходе фокуса из строки «Имя» автоматически
 * преобразует введённое представление в валидный идентификатор — см.
 * {@link Global#identifierFromRepresentation}.
 *
 * <p>Независим от подсветки текущего свойства ({@link PropertySheetActivePropertyHook})
 * и прочих доработок панели «Свойства».
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

        // Панель перестраивает строки при смене выделенного объекта, не посылая событий части:
        // слушатель остаётся на прежнем контроле строки «Имя». Вход фокуса в панель — последний
        // момент до начала ввода, когда строку можно найти заново.
        Display display = Display.getCurrent();
        if (display != null)
        {
            display.addFilter(SWT.FocusIn, PropertyNameIdentifierHook::onFocusIn);
            display.addFilter(SWT.Show, RenameCancelSync::onShow);
        }
    }

    /** После отмены обновляет только поле имени в палитре и редакторе объекта. */
    private static final class RenameCancelSync
    {
        private static final String SHELL_KEY = "tormozit.propertyNameCancelSync"; //$NON-NLS-1$
        private static final String MODEL_CLASS =
            "com._1c.g5.v8.dt.md.ui.aef.models.BmMdObjectNameModel"; //$NON-NLS-1$
        private static final String LOG_TOPIC = "property-name-refactoring-cancel"; //$NON-NLS-1$

        private static void onShow(Event event)
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed()
                || !RefactoringPreviewHook.isRefactoringWizardDialog(shell)
                || shell.getData(SHELL_KEY) != null || !(shell.getData() instanceof Window dialog))
                return;
            Object wizard = Global.invoke(dialog, "getWizard"); //$NON-NLS-1$
            if (wizard == null || !"com._1c.g5.v8.dt.refactoring.ui.DtRefactoringRenameWizard" //$NON-NLS-1$
                .equals(wizard.getClass().getName()))
                return;
            shell.setData(SHELL_KEY, Boolean.TRUE);
            List<Runnable> updates = new ArrayList<>();
            ScanSummary scan = new ScanSummary();
            scan.context.append("parent=").append(identity(shell.getParent())); //$NON-NLS-1$
            IWorkbench workbench = PlatformUI.getWorkbench();
            IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
            IWorkbenchWindow unparentedWindow = null;
            // EDT может открыть мастер без родительского Shell. Тогда связь по дереву
            // контролов отсутствует: используем контекст рабочего окна Eclipse.
            if (shell.getParent() == null)
            {
                unparentedWindow = workbench.getActiveWorkbenchWindow();
                if (unparentedWindow == null && windows.length == 1)
                    unparentedWindow = windows[0];
                scan.context.append(", contextWindow=").append(identity( //$NON-NLS-1$
                    unparentedWindow != null ? unparentedWindow.getShell() : null));
            }
            for (IWorkbenchWindow window : windows)
            {
                boolean owner = window == unparentedWindow;
                for (Control parent = shell.getParent(); parent != null; parent = parent.getParent())
                    if (parent == window.getShell())
                        owner = true;
                scan.context.append("; window=").append(identity(window.getShell())) //$NON-NLS-1$
                    .append(", owner=").append(owner); //$NON-NLS-1$
                if (!owner)
                    continue;
                for (IWorkbenchPage page : window.getPages())
                {
                    if (page != window.getActivePage())
                        continue;
                    for (IViewReference ref : page.getViewReferences())
                    {
                        IViewPart view = ref.getView(false);
                        if (isPropertySheetView(view))
                            scan.context.append("; properties visible=").append(page.isPartVisible(view)); //$NON-NLS-1$
                        if (!isPropertySheetView(view) || !page.isPartVisible(view))
                            continue;
                        Object sheetPage = resolvePropertySheetPage(view);
                        Object scene = Global.invoke(sheetPage, "getScene"); //$NON-NLS-1$
                        collectScene(scene, updates, scan);
                    }
                    for (IEditorReference ref : page.getEditorReferences())
                    {
                        Object editor = ref.getEditor(false);
                        if (editor == null)
                            continue;
                        Object editorPage = Global.invoke(editor, "getActivePageInstance"); //$NON-NLS-1$
                        scan.context.append("; editor=").append(editor.getClass().getSimpleName()) //$NON-NLS-1$
                            .append(", page=").append(identity(editorPage)); //$NON-NLS-1$
                        collectScene(Global.invoke(editorPage, "getScene"), updates, scan); //$NON-NLS-1$
                    }
                }
            }
            Display display = shell.getDisplay();
            shell.addDisposeListener(e -> display.asyncExec(() -> {
                if (dialog.getReturnCode() != Window.CANCEL)
                    return;
                Global.tempLog(LOG_TOPIC, "cancel, fields=" + updates.size() //$NON-NLS-1$
                    + ", components=" + scan.components + ", nameModels=" + scan.nameModels //$NON-NLS-1$ //$NON-NLS-2$
                    + ", " + scan.context); //$NON-NLS-1$
                // UI закрывает мастер раньше, чем AEF завершит IChange.apply().
                // Перечитывание ставится в ту же очередь AEF, что и применение поля.
                for (Runnable update : updates)
                    update.run();
            }));
        }

        private static void collectScene(Object scene, List<Runnable> updates, ScanSummary scan)
        {
            Object root = Global.invoke(scene, "getComponent"); //$NON-NLS-1$
            scan.context.append(", root=").append(identity(root)); //$NON-NLS-1$
            collect(root, updates, 0, scan);
        }

        private static void collect(Object component, List<Runnable> updates, int depth, ScanSummary scan)
        {
            if (component == null || depth > 24)
                return;
            scan.components++;
            Object model = Global.invoke(component, "getModel"); //$NON-NLS-1$
            if (model != null && model.getClass().getName().contains("Name")) //$NON-NLS-1$
                scan.nameModels.add(model.getClass().getName());
            if (model != null && MODEL_CLASS.equals(model.getClass().getName()))
            {
                updates.add(() -> queueRefresh(component, model));
            }
            for (Object child : AefFieldFocus.existingComponents(component))
                collect(child, updates, depth + 1, scan);
        }

        /** Компактная диагностика поиска: одна строка на отмену, без чтения значений полей. */
        private static final class ScanSummary
        {
            private final StringBuilder context = new StringBuilder();
            private final Set<String> nameModels = new java.util.LinkedHashSet<>();
            private int components;
        }

        private static String identity(Object object)
        {
            return object == null ? "null" : object.getClass().getSimpleName() + '@' //$NON-NLS-1$
                + System.identityHashCode(object);
        }

        private static void queueRefresh(Object component, Object model)
        {
            try
            {
                Object queue = Global.invoke(model, "getRunnableQueue"); //$NON-NLS-1$
                Display display = Display.getCurrent();
                if (!Global.invokeVoid(queue, "queueRunnable", (Runnable) () -> refresh(component, model, display))) //$NON-NLS-1$
                    throw new IllegalStateException("Нет очереди AEF для поля имени"); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Global.tempLogException(LOG_TOPIC, "queue reload failed", e); //$NON-NLS-1$
                Global.logError(LOG_TAG, "RenameCancelSync.queueRefresh", e); //$NON-NLS-1$
            }
        }

        private static void refresh(Object component, Object model, Display display)
        {
            try
            {
                // За время работы мастера палитра могла сменить объект или закрыться.
                if (!Boolean.FALSE.equals(Global.invoke(component, "isDisposed")) //$NON-NLS-1$
                    || Global.invoke(component, "getModel") != model //$NON-NLS-1$
                    || !Boolean.TRUE.equals(Global.invoke(model, "isOnline"))) //$NON-NLS-1$
                {
                    return;
                }
                if (!(Global.invoke(model, "getObject") instanceof EObject object) //$NON-NLS-1$
                    || !(Global.invoke(model, "getProperty") instanceof EStructuralFeature feature)) //$NON-NLS-1$
                {
                    return;
                }
                Object name = object.eGet(feature);
                // Value.setValue обновляет обе копии значения, но не извещает поле.
                // External извещает AEF без нового commit/запуска переименования.
                Object external = model.getClass().getClassLoader()
                    .loadClass("com._1c.g5.aef2.models.ChangeOrigin").getField("External").get(null); //$NON-NLS-1$ //$NON-NLS-2$
                if (!Global.invokeVoid(model, "setValue", name) //$NON-NLS-1$
                    || !Global.invokeVoid(model, "notifyValueChanged", external)) //$NON-NLS-1$
                    throw new IllegalStateException("Не удалось обновить поле имени"); //$NON-NLS-1$
                // DtValueActionBarComponent привязан к ActionBarViewModel.value, не text.
                for (Object viewModel : AefFieldFocus.existingViewModels(component))
                    if (!Global.invokeVoid(viewModel, "setValue", name)) //$NON-NLS-1$
                        throw new IllegalStateException("Не удалось обновить представление имени"); //$NON-NLS-1$
                if (display != null && !display.isDisposed())
                    display.asyncExec(() -> refreshNameBinding(component, model));
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                Global.tempLogException(LOG_TOPIC, "reload failed", e); //$NON-NLS-1$
                Global.logError(LOG_TAG, "RenameCancelSync.refresh", e); //$NON-NLS-1$
            }
        }

        private static void refreshNameBinding(Object component, Object model)
        {
            try
            {
                if (!Boolean.FALSE.equals(Global.invoke(component, "isDisposed")) //$NON-NLS-1$
                    || Global.invoke(component, "getModel") != model) //$NON-NLS-1$
                {
                    return;
                }
                Object scene = Global.invoke(component, "getScene"); //$NON-NLS-1$
                Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
                Object views = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
                if (!(views instanceof java.util.Map<?, ?> map))
                    throw new IllegalStateException("Нет представлений поля имени"); //$NON-NLS-1$
                for (Object viewModel : AefFieldFocus.existingViewModels(component))
                {
                    Object view = map.get(viewModel);
                    Object context = Global.getField(view, "dataBindingContext"); //$NON-NLS-1$
                    if (!(context instanceof DataBindingContext binding))
                        throw new IllegalStateException("Нет привязки поля имени"); //$NON-NLS-1$
                    // View.bindValue передаёт нативный контрол как target. updateTargets
                    // повторно передаёт value в LightText, даже если value уже не меняется.
                    binding.updateTargets();
                }
            }
            catch (RuntimeException e)
            {
                Global.tempLogException(LOG_TOPIC, "binding refresh failed", e); //$NON-NLS-1$
                Global.logError(LOG_TAG, "RenameCancelSync.refreshNameBinding", e); //$NON-NLS-1$
            }
        }

    }

    private static void onFocusIn(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        for (IViewReference ref : page.getViewReferences())
        {
            IViewPart view = ref.getView(false);
            if (!isPropertySheetView(view))
                continue;
            Object sheetPage = resolvePropertySheetPage(view);
            if (!(Global.invoke(sheetPage, "getControl") instanceof Control pageControl)) //$NON-NLS-1$
                continue;
            for (Control current = control; current != null; current = current.getParent())
            {
                if (current == pageControl)
                {
                    // Одна попытка без серии повторов: строки к этому моменту уже построены, а
                    // у объекта без строки «Имя» серия на каждый вход фокуса шла бы впустую.
                    control.getDisplay().asyncExec(() -> tryAttach(view));
                    return;
                }
            }
        }
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

    static boolean isPropertySheetView(Object part)
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

        // Редактор строки «Имя» — ActionBar (см. javadoc класса), поэтому здесь он не
        // пропускается, в отличие от поиска комбобокса поля «Тип».
        java.util.Map.Entry<?, ?> nameEditorEntry = findValueViewAfterLabel(scene, NAME_PROPERTY_LABEL, false);
        if (nameEditorEntry == null)
            return false;
        Object nameEditorView = nameEditorEntry.getValue();

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
        Runnable onCommit = () -> onNameFocusLost(nativeControl, typeModel, initialName, view);
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
    // Package-private (не private) — переиспользуется в TypeComboOverlayHook (оверлей поля
    // «Тип» в панели «Свойства», тот же пакет tormozit).
    static Object findTypeDescriptionModel(Object scene)
    {
        Object root = Global.invoke(scene, "getComponent"); //$NON-NLS-1$
        return findTypeDescriptionModelInTree(root, 0);
    }

    static Object findTypeDescriptionModelInTree(Object component, int depth)
    {
        if (component == null || depth > 12)
            return null;
        Object model = Global.invoke(component, "getModel"); //$NON-NLS-1$
        if (implementsInterface(model, TYPE_DESCRIPTION_MODEL_INTERFACE))
            return model;
        for (Object child : AefFieldFocus.existingComponents(component))
        {
            Object found = findTypeDescriptionModelInTree(child, depth + 1);
            if (found != null)
                return found;
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

    static Object resolvePropertySheetPage(IViewPart view)
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

    static boolean isPropertySheetPage(Object page)
    {
        return page != null && page.getClass().getName().contains("PropertySheetPage"); //$NON-NLS-1$
    }

    /**
     * Ищет в {@code renderer.viewModelToView} (порядок-сохраняющая {@link java.util.LinkedHashMap})
     * {@code LabelViewModel} с текстом {@code displayName} и возвращает **следующую** запись
     * целиком — {@code entry.getValue()} это view редактора значения (см. javadoc класса),
     * {@code entry.getKey()} — его viewModel (нужен вызывающим, которым важны оба, например
     * {@code TypeComboOverlayHook} — оттуда берутся иконки типа через {@code viewModel.items}).
     * Package-private (не private) — переиспользуется в {@code TypeComboOverlayHook}.
     */
    static java.util.Map.Entry<?, ?> findValueViewAfterLabel(Object scene, String displayName)
    {
        return findValueViewAfterLabel(scene, displayName, true);
    }

    /**
     * @param skipActionBar {@code true} — запись {@code ActionBarViewModel} считается кнопкой
     *                      «...» при другом редакторе и пропускается (поле «Тип»); {@code false} —
     *                      она и есть редактор строки (поле «Имя», см. javadoc класса)
     */
    private static java.util.Map.Entry<?, ?> findValueViewAfterLabel(Object scene, String displayName,
        boolean skipActionBar)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map<?, ?> map))
            return null;

        boolean foundLabel = false;
        for (java.util.Map.Entry<?, ?> entry : map.entrySet())
        {
            if (foundLabel)
            {
                // Раньше здесь безусловно возвращалась САМАЯ ПЕРВАЯ запись сразу после подписи.
                // Домен: у поля «Тип» может быть 1 или 2 виджета — кнопка «...» (ActionBar,
                // открывает диалог выбора сложного типа) есть ВСЕГДА, а вот ComboSelectViewModel
                // (наш комбобокс с выпадающим списком простых/ссылочных типов) есть, только если
                // текущее значение ОДИНАРНОГО типа; при СОСТАВНОМ типе комбобокса в принципе нет,
                // остаётся только кнопка «...» — оверлею в этом случае накладывать нечего, это не
                // баг. Порядок ActionBar/Combo в карте не гарантирован (лог видел оба варианта),
                // поэтому:
                // 1) SectionViewModelImpl/LabelViewModel — граница текущей секции/следующая
                //    подпись, редактора между label и этой границей не нашли (составной тип, либо
                //    AEF временно убрал editor из карты при перестройке строки) — возвращаем null.
                // 2) ActionBarViewModelImpl/LightEditorBar — это кнопка «...», не комбобокс;
                //    пропускаем и продолжаем смотреть дальше — если следом всё же найдётся
                //    ComboSelectViewModel (одинарный тип), вернём его; если вместо этого сразу
                //    упрёмся в границу секции/подписи (составной тип) — вернём null по правилу 1.
                Object entryKey = entry.getKey();
                String entryKeyClass = entryKey == null ? "" : entryKey.getClass().getName(); //$NON-NLS-1$
                if (entryKeyClass.contains("LabelViewModel") || entryKeyClass.contains("SectionViewModel")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    // ВРЕМЕННАЯ диагностика: лог показал случай, когда findValueViewAfterLabel
                    // возвращает null НЕПРЕРЫВНО много секунд подряд после закрытия диалога
                    // выбора типа (F4/«...») — значит, каждый раз упирается в границу секции сразу
                    // после label, и надо увидеть, во что именно (какой именно класс), чтобы
                    // понять, надо ли для него отдельное правило пропуска (как для ActionBar).
                    TypeComboOverlayHook.diag("findValueViewAfterLabel: после подписи «" + displayName //$NON-NLS-1$
                        + "» сразу граница — entryKeyClass=" + entryKeyClass); //$NON-NLS-1$
                    return null; // дошли до границы секции/следующей подписи — редактора нет
                }
                if (skipActionBar && entryKeyClass.contains("ActionBarViewModel")) //$NON-NLS-1$
                    continue; // декорация — пропускаем, ищем дальше
                return entry;
            }

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

    /**
     * Строки событий палитры в порядке отображения: подпись → view поля ввода.
     *
     * <p>Событие отличается от обычного свойства редактором: у события это всегда
     * {@code EditableComboViewModel} (поле имени процедуры с выпадающим списком), у свойств —
     * {@code ActionBar}, {@code ComboSelect}, {@code Checkbox} и т.п. Искать события по подписи
     * нельзя: панель подписывает их человекочитаемо («При изменении»), а в модели имя события
     * пишется слитно («ПриИзменении»).
     */
    static java.util.List<java.util.Map.Entry<String, Object>> eventRows(Object scene)
    {
        java.util.List<java.util.Map.Entry<String, Object>> rows = new java.util.ArrayList<>();
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map<?, ?> map))
            return rows;
        String label = null;
        for (java.util.Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            String keyClass = key == null ? "" : key.getClass().getName(); //$NON-NLS-1$
            if (keyClass.contains("LabelViewModel")) //$NON-NLS-1$
            {
                Object text = Global.invoke(key, "getText"); //$NON-NLS-1$
                if (text == null)
                    text = Global.getField(key, "text"); //$NON-NLS-1$
                label = text instanceof String value && !value.isBlank() ? value : null;
                continue;
            }
            if (label != null && keyClass.contains("EditableComboViewModel")) //$NON-NLS-1$
                rows.add(new java.util.AbstractMap.SimpleEntry<>(label, entry.getValue()));
            label = null;
        }
        return rows;
    }

    private static void onNameFocusLost(Object nativeControl, Object typeModel, String initialName, IViewPart view)
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

        // Автоподбор типа по имени (ИР, иначе Напарник) — общий для всех мест плагина. Ответ
        // приходит с задержкой: к этому времени пользователь мог уйти из панели и выделить
        // другой объект — тогда подставлять тип уже некуда.
        if (modified)
            TypeByNameAdvisor.autofillDefaultType(typeModel, identifier, LOG_TAG, () -> isFocusInView(view),
                () -> currentTypeModel(view));
    }

    /** Модель типа, которую панель показывает сейчас; {@code null}, если строки «Тип» в ней нет. */
    private static Object currentTypeModel(IViewPart view)
    {
        Object page = resolvePropertySheetPage(view);
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        return scene != null ? findTypeDescriptionModel(scene) : null;
    }

    /** {@code true}, если панель — активная часть своего окна и фокус ввода сейчас в этом окне. */
    private static boolean isFocusInView(IViewPart view)
    {
        IWorkbenchPage page = view.getSite().getPage();
        if (page == null || page.getActivePart() != view)
            return false;
        Display display = Display.getCurrent();
        Control focus = display != null ? display.getFocusControl() : null;
        return focus != null && !focus.isDisposed() && focus.getShell() == view.getSite().getShell();
    }
}
