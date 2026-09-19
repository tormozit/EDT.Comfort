package tormozit;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.Hyperlink;
import org.eclipse.ui.forms.widgets.ScrolledForm;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseChangeListener;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com._1c.g5.v8.dt.ui.editor.input.DtEditorInputFactory;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;

/**
 * Хук редактора приложения ({@code applicationEditor}) и связки с {@code InfobaseEditor}.
 *
 * <p>Добавляет гиперссылку «Редактировать инфобазу» в область заголовка формы.
 * По клику открывает {@code com._1c.g5.v8.dt.platform.services.ui.InfobaseEditor}
 * для инфобазы текущего приложения.
 *
 * <h3>Технический путь к InfobaseEditor</h3>
 * <pre>
 *   editor.getEditorInput().getApplication().getInfobase()  →  InfobaseReference (EObject)
 *   OpenHelper.openEditor(EObject)                          →  InfobaseEditor
 * </pre>
 *
 * <h3>Получение OpenHelper (Guice-инжектируемый класс)</h3>
 * <p>EDT использует Google Guice (не e4 DI). Правильные пути по документации EDT:
 * <ol>
 *   <li>{@code ServiceAccess.get(Class)} — если OpenHelper зарегистрирован как OSGi-сервис.</li>
 *   <li>{@code BundleActivator.getDefault().getInjector().getInstance(Class)} — через
 *       Guice-инжектор бандла, имя активатора берём из {@code Bundle-Activator} в MANIFEST.MF.</li>
 * </ol>
 *
 * <h3>Обход штатного бага EDT в InfobaseEditor (issue #547)</h3>
 * <p>Сохранение {@code InfobaseEditor} штатно пересобирает список инфобаз: старый экземпляр
 * {@code InfobaseReference}, к которому привязан уже открытый {@code InfobaseEditor}, осиротевает
 * (в свежем списке по тому же UUID уже другой объект), но {@code InfobaseEditor.infobasesReloaded}
 * не перепривязывает к нему редактор — тот продолжает следить за осиротевшим старым экземпляром,
 * и повторные правки полей перестают взводить модифицированность. {@link
 * #installInfobaseReloadRebindHook()} пересоздаёт такой редактор через штатный
 * {@link DtEditorInputFactory}, если он не «грязный».
 *
 * <p>Сохранение {@code InfobaseEditor} заодно помечает старый экземпляр {@code IApplication}
 * той же инфобазы удалённым, отчего штатный {@code ApplicationEditor} (если открыт) сам себя
 * закрывает — это тоже штатное поведение EDT (так же ломается и обычное открытие
 * {@code InfobaseEditor} из панели «Приложения»), но автоматическое переоткрытие
 * {@code applicationEditor} в ответ оказалось ненадёжным: сам штатный редактор при пересоздании
 * перехватывает фокус вкладки изнутри своей инициализации независимо от {@code activate=false},
 * так что вернуть его тихо не получается — пробовать не будем, пользователь открывает вручную
 * через гиперссылку «Инфобаза» или панель «Приложения».
 */
public class ApplicationEditorHook implements IStartup
{
    private static final String EDITOR_ID =
        "com.e1c.g5.dt.applications.ui.editor.applicationEditor"; //$NON-NLS-1$

    private static final String INFOBASE_EDITOR_ID =
        "com._1c.g5.v8.dt.platform.services.ui.InfobaseEditor"; //$NON-NLS-1$

    private static final String OPEN_HELPER_CLASS =
        "com._1c.g5.v8.dt.ui.util.OpenHelper"; //$NON-NLS-1$

    // =======================================================================
    // IStartup
    // =======================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

//            // Даём EDT время завершить BuildMarkersJob перед обходом уже открытых редакторов.
//            Display.getDefault().timerExec(3000, () ->
//            {
                for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                    hookWindow(w);
//            });

            installInfobaseReloadRebindHook();
        });
    }

    // =======================================================================
    // Подключение к окну
    // =======================================================================

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window.getActivePage() != null)
            for (IEditorPart ed : window.getActivePage().getEditors())
                if (EDITOR_ID.equals(ed.getSite().getId()))
                    hookEditor(ed);

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!EDITOR_ID.equals(ref.getId())) return;
                Display.getDefault().asyncExec(() ->
                {
                    IEditorPart ed = (IEditorPart) ref.getPart(false);
                    if (ed != null) hookEditor(ed);
                });
            }
            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    // =======================================================================
    // Хук редактора
    // =======================================================================

    private static void hookEditor(IEditorPart editor)
    {
        try
        {
            Object managedForm = Global.getField(editor, "managedForm"); //$NON-NLS-1$
            if (managedForm == null) return;

            FormToolkit toolkit       = (FormToolkit)  Global.call(managedForm, "getToolkit"); //$NON-NLS-1$
            ScrolledForm scrolledForm = (ScrolledForm) Global.call(managedForm, "getForm");    //$NON-NLS-1$
            if (toolkit == null || scrolledForm == null) return;

            addHyperlinkToHead(scrolledForm.getForm(), toolkit, editor);
        }
        catch (Exception e)
        {
            Global.log("ApplicationEditorHook.hookEditor: " + e); //$NON-NLS-1$
        }
    }

    private static void addHyperlinkToHead(Form form, FormToolkit toolkit, IEditorPart editor)
    {
        Composite existing = (Composite) Global.call(form, "getHeadClient"); //$NON-NLS-1$
        if (existing != null)
        {
            for (var child : existing.getChildren())
                if (child instanceof Hyperlink
                    && LINK_TEXT.equals(((Hyperlink) child).getText()))
                    return; // уже добавлено
            createHyperlink(existing, toolkit, editor);
            existing.layout(true, true);
            return;
        }

        Composite headClient = toolkit.createComposite(form.getHead());
        headClient.setLayout(new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL));
        createHyperlink(headClient, toolkit, editor);
        form.setHeadClient(headClient);
        form.getHead().layout(true, true);
    }

    private static final String LINK_TEXT = "Инфобаза"; //$NON-NLS-1$
    private static void createHyperlink(Composite parent, FormToolkit toolkit, IEditorPart editor)
    {
        Hyperlink link = toolkit.createHyperlink(parent, LINK_TEXT, SWT.NONE);
        link.setToolTipText("Открыть редактор инфобазы"); //$NON-NLS-1$
        link.addHyperlinkListener(new HyperlinkAdapter()
        {
            @Override public void linkActivated(HyperlinkEvent e) { openInfobaseEditor(editor); }
        });
    }

    private static void openInfobaseEditor(IEditorPart editor)
    {
        // 1. Извлекаем InfobaseReference из входных данных редактора
        Object input = editor.getEditorInput();
        Object application = Global.call(input, "getApplication"); //$NON-NLS-1$
        Object infobase = Global.call(application, "getInfobase"); //$NON-NLS-1$
        IWorkbenchPage workbenchPage = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IDtEditorInput<?> input2 = DtEditorInputFactory.create((EObject)infobase);
        try
        {
            workbenchPage.openEditor(input2, INFOBASE_EDITOR_ID);
        }
        catch (PartInitException e)
        {
            Global.logError("ApplicationEditor", "open InfobaseEditor", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // =======================================================================
    // Обход #547: повторные правки в InfobaseEditor не взводят модифицированность
    // =======================================================================

    private static boolean infobaseReloadRebindHookInstalled;

    /**
     * Слушает {@link IInfobaseManager} и после пересборки списка инфобаз проверяет уже открытые
     * {@code InfobaseEditor}: если объект, к которому привязан редактор, осиротел (в свежем
     * списке по тому же UUID найден другой экземпляр), пересоздаёт редактор через штатный
     * {@link DtEditorInputFactory} — так редактор снова следит за живым объектом и корректно
     * взводит модифицированность. Редактор с несохранёнными правками не трогаем.
     */
    private static void installInfobaseReloadRebindHook()
    {
        if (infobaseReloadRebindHookInstalled)
            return;
        infobaseReloadRebindHookInstalled = true;

        IInfobaseManager infobaseManager = Global.getOsgiService(IInfobaseManager.class);
        if (infobaseManager == null)
            return;

        infobaseManager.addInfobaseChangeListener(new IInfobaseChangeListener()
        {
            @Override
            public void infobasesReloaded(List<Section> sections)
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> rebindStaleInfobaseEditors(infobaseManager));
            }

            @Override
            public void sectionAdded(Section section)
            {
            }
        });
    }

    private static void rebindStaleInfobaseEditors(IInfobaseManager infobaseManager)
    {
        for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            IWorkbenchPage page = w.getActivePage();
            if (page == null)
                continue;
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (!INFOBASE_EDITOR_ID.equals(ref.getId()))
                    continue;
                IEditorPart editor = ref.getEditor(false);
                if (editor == null || editor.isDirty())
                    continue; // не трогаем несохранённые правки

                Object model = Global.call(editor, "getModel"); //$NON-NLS-1$
                if (!(model instanceof InfobaseReference stale))
                    continue;

                Optional<InfobaseReference> fresh = infobaseManager.findInfobaseByUuid(stale.getUuid());
                if (fresh.isEmpty() || fresh.get() == stale)
                    continue; // объект не подменился — редактор в порядке

                reopenInfobaseEditor(page, editor, fresh.get());
            }
        }
    }

    /**
     * Пересоздаёт {@code InfobaseEditor} безопасно: сначала открывает новый редактор со свежим
     * объектом (пока старый ещё жив) и только при успехе закрывает старый. Открывать новый
     * первым обязательно с {@link IWorkbenchPage#MATCH_NONE} — иначе штатный
     * {@code InfobaseEditorMatchingStrategy} посчитает его «тем же самым» по UUID и просто
     * активирует старый (осиротевший) редактор вместо пересоздания.
     *
     * <p>Порядок «открыть-потом-закрыть», а не «закрыть-потом-открыть», нужен из-за гонки:
     * иногда сразу после пересборки списка свежий объект ещё не привязан к
     * {@code TransactionalEditingDomain}, и открытие бросает {@code PartInitException}
     * («Transaction editing domain expected for …») — при обратном порядке пользователь в этот
     * момент оставался вовсе без редактора (issue #547). Здесь же при неудаче старый редактор
     * остаётся как есть — со старым багом модифицированности, но живой.
     */
    private static void reopenInfobaseEditor(IWorkbenchPage page, IEditorPart staleEditor,
        InfobaseReference infobase)
    {
        Object scene = Global.call(staleEditor, "getScene"); //$NON-NLS-1$
        Object rootComponent = Global.call(staleEditor, "getContainerComponent"); //$NON-NLS-1$
        List<Integer> focusedPath = scene != null && rootComponent != null
            ? findFocusedPath(scene, rootComponent, new ArrayList<>()) : null;

        // Активируем старую вкладку — так штатное место вставки новой вкладки оказывается
        // рядом с ней, а не в произвольном месте панели редакторов.
        page.activate(staleEditor);

        IDtEditorInput<?> input = DtEditorInputFactory.create((EObject)infobase);
        IEditorPart freshEditor;
        try
        {
            freshEditor = page.openEditor(input, INFOBASE_EDITOR_ID, false, IWorkbenchPage.MATCH_NONE);
        }
        catch (PartInitException e)
        {
            Global.logError("ApplicationEditorHook", "rebind InfobaseEditor", e); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        page.closeEditor(staleEditor, false);
        page.activate(freshEditor);

        if (focusedPath != null)
            restoreFocus(freshEditor, focusedPath);
    }

    /** Ищет по дереву компонентов AEF путь (индексы детей от корня) до компонента с фокусом ввода. */
    private static List<Integer> findFocusedPath(Object scene, Object component, List<Integer> prefix)
    {
        if (component == null)
            return null;
        if (isOwnControlFocused(scene, component))
            return prefix;
        List<Object> children = AefFieldFocus.childComponents(component);
        for (int i = 0; i < children.size(); i++)
        {
            List<Integer> next = new ArrayList<>(prefix);
            next.add(i);
            List<Integer> found = findFocusedPath(scene, children.get(i), next);
            if (found != null)
                return found;
        }
        return null;
    }

    /** Как {@link AefFieldFocus#isComponentFocused}, но без рекурсии в дочерние компоненты. */
    private static boolean isOwnControlFocused(Object scene, Object component)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map<?, ?> viewModelToView))
            return false;
        Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
        if (!(viewModels instanceof Iterable<?> it))
            return false;
        for (Object viewModel : it)
        {
            if (viewModel == null || viewModel.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                continue;
            Object view = viewModelToView.get(viewModel);
            Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
            if (nativeControl != null && AefFieldFocus.hasFocusNow(nativeControl))
                return true;
        }
        return false;
    }

    private static void restoreFocus(IEditorPart freshEditor, List<Integer> path)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            Object scene = Global.call(freshEditor, "getScene"); //$NON-NLS-1$
            Object rootComponent = Global.call(freshEditor, "getContainerComponent"); //$NON-NLS-1$
            if (scene == null || rootComponent == null)
                return;
            Object target = resolveComponentPath(rootComponent, path);
            if (target != null)
                AefFieldFocus.focusComponent(scene, target);
        });
    }

    private static Object resolveComponentPath(Object rootComponent, List<Integer> path)
    {
        Object current = rootComponent;
        for (int index : path)
        {
            List<Object> children = AefFieldFocus.childComponents(current);
            if (index < 0 || index >= children.size())
                return null;
            current = children.get(index);
        }
        return current;
    }
}
