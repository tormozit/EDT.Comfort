package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.part.FileEditorInput;

/**
 * Доработки дерева «Структура проекта» ({@code org.eclipse.ui.navigator.ProjectExplorer} —
 * подтверждено логом {@code isNavigatorView}, вопреки комментариям в остальном коде
 * плагина, где «Навигатор» — это {@code com._1c.g5.v8.dt.ui2.navigator},
 * {@link Global#NAVIGATOR_VIEW_ID}; это ДВА разных дерева):
 * <ul>
 * <li>«Открыть объект» и «Показать в навигаторе» в подменю «Комфорт» — те же команды,
 * что уже есть для изменённых файлов Git ({@link GitChangedFileMenuHook});</li>
 * <li>Ctrl+T подключён к выделению дерева (см. {@link #installFocusNavigatorOverride}) —
 * подменяет обработчик штатной {@value #FOCUS_NAVIGATOR_COMMAND_ID}, которая иначе
 * работает по активному редактору;</li>
 * <li>двойной клик по файлу-исходнику объекта ({@code .mdo}/{@code .form}/…) всегда
 * открывает текстовый (XML) редактор — штатно EDT активирует уже открытый объектный
 * редактор этого объекта (см. {@link #installOpenInTextEditorOverride}).</li>
 * </ul>
 */
public final class ProjectStructureViewHook implements IStartup
{
    private static final String PROJECT_EXPLORER_ID = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$
    private static final String FOCUS_NAVIGATOR_COMMAND_ID = "com._1c.g5.v8.dt.ui.commands.focusNavigator"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.navigatorOpenObjectHook"; //$NON-NLS-1$
    private static final String OPEN_OVERRIDE_MARKER = "tormozit.navigatorOpenInTextEditorHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT_OPEN = "Открыть объект"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP_OPEN =
            "Открыть редактор объекта метаданных выбранного элемента навигатора" //$NON-NLS-1$
            + Global.pluginSignForTooltip();
    private static final String ITEM_TEXT_REVEAL = "Показать в навигаторе"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP_REVEAL =
            "Выделить и прокрутить к объекту в дереве навигатора" //$NON-NLS-1$
            + Global.pluginSignForTooltip();

    /** ID встроенного в Eclipse простого текстового редактора; литералом — как в {@code FileSearchResultsHook}. */
    private static final String DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor"; //$NON-NLS-1$

    /** «Открыть с помощью → Редактор XML» (org.eclipse.wst.xml.ui). */
    private static final String XML_EDITOR_ID =
        "org.eclipse.wst.xml.ui.internal.tabletree.XMLMultiPageEditorPart"; //$NON-NLS-1$

    /** Исходники объектов метаданных, для которых двойной клик открывает XML-редактор. */
    private static final Set<String> XML_SOURCE_EXTENSIONS = Set.of(
        "mdo", "form", "cmi", "xml", "mxlx"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** Как в {@code org.eclipse.ui.actions.OpenWithMenu}: не переиспользовать чужой редактор по тому же input. */
    private static final int OPEN_WITH_MATCH =
        IWorkbenchPage.MATCH_INPUT | IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_IGNORE_SIZE;

    @Override
    public void earlyStartup()
    {
        // Те же пункты — во внешние меню объекта (например, меню имени в заголовке редактора МД)
        ComfortSubmenuHelper.addExternalMenuFiller(context -> hookComfortSubmenu(context.menu(), null));
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
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
                if (isNavigatorView(view))
                    tryHook(view);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void tryHookFromRef(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (isNavigatorView(part))
            tryHook((IViewPart) part);
    }

    private static boolean isNavigatorView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return PROJECT_EXPLORER_ID.equals(id);
    }

    private static void tryHook(IViewPart navigator)
    {
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        installOpenInTextEditorOverride(viewer);

        if (Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
            return;

        Menu menu = tree.getMenu();
        if (menu == null)
            return;

        MenuAdapter listener = new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                hookComfortSubmenu(menu, viewer);
            }
        };
        menu.addMenuListener(listener);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        tree.addDisposeListener(ev -> {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });

        installFocusNavigatorOverride(navigator, viewer);
    }

    /**
     * Штатно двойной клик по {@code .mdo} в дереве открывает текстовый редактор, но если
     * объектный редактор этого объекта уже открыт — EDT активирует его, а не текст. Снимаем
     * штатный open-listener ({@code OpenAndLinkWithEditorHelper$InternalListener}, срабатывает
     * по {@code SWT.DefaultSelection}) и ставим свой: для файлов-исходников объектов
     * ({@link #XML_SOURCE_EXTENSIONS}) явно открываем XML-редактор по его id (открытый объектный
     * редактор по {@code MATCH_ID} не подхватывается), для остальных элементов — делегируем
     * снятому listener'у, то есть прежнее поведение. «Связь с редактором» живёт в отдельном
     * {@code selectionChanged} того же объекта и не затрагивается. Приём — как в
     * {@code ProblemViewHook.installOpenOverride}.
     */
    private static void installOpenInTextEditorOverride(CommonViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (Boolean.TRUE.equals(tree.getData(OPEN_OVERRIDE_MARKER)))
            return;

        IOpenListener stock = findStockOpenListener(viewer);
        if (stock == null)
            return;
        tree.setData(OPEN_OVERRIDE_MARKER, Boolean.TRUE);

        viewer.removeOpenListener(stock);
        viewer.addOpenListener((OpenEvent event) -> {
            IFile file = sourceFileToOpenAsText(event.getSelection());
            if (file != null)
                openInTextEditor(file);
            else
                stock.open(event);
        });
        tree.addDisposeListener(e -> tree.setData(OPEN_OVERRIDE_MARKER, null));
    }

    private static IOpenListener findStockOpenListener(StructuredViewer viewer)
    {
        Object listenerList = Global.getField(viewer, "openListeners"); //$NON-NLS-1$
        Object raw = listenerList != null ? Global.invoke(listenerList, "getListeners") : null; //$NON-NLS-1$
        if (!(raw instanceof Object[] listeners))
            return null;
        for (Object listener : listeners)
        {
            if (listener instanceof IOpenListener open
                && "org.eclipse.ui.OpenAndLinkWithEditorHelper$InternalListener" //$NON-NLS-1$
                    .equals(listener.getClass().getName()))
                return open;
        }
        return null;
    }

    /**
     * Единственный выделенный элемент — файл-исходник объекта метаданных ({@code .mdo} и т.п.):
     * такой открываем текстом. Иначе {@code null} — штатное открытие.
     */
    private static IFile sourceFileToOpenAsText(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return null;
        IResource resource = NavigatorResourceResolver.resolve(structured.getFirstElement());
        if (!(resource instanceof IFile file) || !file.exists())
            return null;
        String ext = file.getFileExtension();
        return ext != null && XML_SOURCE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT)) ? file : null;
    }

    private static void openInTextEditor(IFile file)
    {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        if (page == null)
            return;
        String ext = file.getFileExtension();
        String editorId = ext != null && XML_SOURCE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))
            ? XML_EDITOR_ID : DEFAULT_TEXT_EDITOR_ID;
        IEditorInput input = new FileEditorInput(file);
        try
        {
            page.openEditor(input, editorId, true, OPEN_WITH_MATCH);
        }
        catch (PartInitException e)
        {
            // не мешаем пользователю: молча остаёмся без редактора
        }
    }

    /**
     * Штатная команда EDT {@value #FOCUS_NAVIGATOR_COMMAND_ID} («Сфокусировать в Навигаторе»)
     * висит на Ctrl+T в широком {@code org.eclipse.ui.contexts.window} и всегда действует по
     * активному редактору, а не по выделению в этом дереве. Подменяем её обработчик через
     * {@code IHandlerService} самого view — такая активация автоматически перекрывает штатную,
     * только пока «Структура проекта» в фокусе (тот же приём, что и
     * {@code StacktracesViewInteractionHook.tryInstallCopy} для Ctrl+C).
     */
    private static void installFocusNavigatorOverride(IViewPart navigator, CommonViewer viewer)
    {
        IHandlerService handlerService = navigator.getSite().getService(IHandlerService.class);
        if (handlerService == null)
            return;

        handlerService.activateHandler(FOCUS_NAVIGATOR_COMMAND_ID, new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                ISelection selection = viewer.getSelection();
                EObject eObject = resolveEObjectFromSelection(selection);
                if (eObject != null)
                    NavigatorReveal.revealAndActivateIfHidden(eObject);
                return null;
            }
        });
    }

    /**
     * У папки объекта верхнего уровня есть свой mdo — резолвим по нему. У папки дочернего
     * объекта (форма/команда/макет внутри {@code Forms} / {@code Commands} / {@code Templates})
     * mdo нет — там полное имя строится прямо по пути папки
     * ({@link GitChangedFileMenuHook#resolveEObjectForResource}).
     */
    private static EObject resolveEObjectFromSelection(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return null;

        IResource resource = NavigatorResourceResolver.resolveFirst(structured);
        if (resource instanceof IFile file)
            return GitChangedFileMenuHook.resolveEObject(file);
        if (!(resource instanceof IFolder folder))
            return null;

        IFile mdoFile = NavigatorResourceResolver.findMdoFileInFolder(folder);
        if (mdoFile != null)
            return GitChangedFileMenuHook.resolveEObject(mdoFile);

        return GitChangedFileMenuHook.resolveEObjectForResource(folder);
    }

    private static void hookComfortSubmenu(Menu contextMenu, CommonViewer viewer)
    {
        MenuItem anchor = ComfortSubmenuHelper.findAnchorAfterEditGroup(contextMenu);
        Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(
            contextMenu, contextMenu.getShell(), anchor);
        if (comfortSub == null || comfortSub.isDisposed())
            return;
        if (Boolean.TRUE.equals(comfortSub.getData(HOOK_MARKER)))
            return;

        MenuAdapter subListener = new MenuAdapter()
        {
            private final List<MenuItem> added = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                EObject eObject = resolveEObjectFromSelection(selection);
                if (eObject == null)
                    return;

                MenuItem openItem = ComfortSubmenuHelper.createSortedMenuItem(
                    comfortSub, SWT.PUSH, ITEM_TEXT_OPEN);
                openItem.setToolTipText(ITEM_TOOLTIP_OPEN);
                openItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        GitChangedFileMenuHook.openInEditor(eObject, null, comfortSub.getShell());
                    }
                });
                added.add(openItem);

                MenuItem revealItem = ComfortSubmenuHelper.createSortedMenuItem(
                    comfortSub, SWT.PUSH, ITEM_TEXT_REVEAL);
                revealItem.setToolTipText(ITEM_TOOLTIP_REVEAL);
                revealItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        NavigatorReveal.revealAndActivateIfHidden(eObject);
                    }
                });
                added.add(revealItem);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(added);
                added.clear();
                comfortSub.getDisplay().asyncExec(() -> {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };

        comfortSub.addMenuListener(subListener);
        comfortSub.setData(HOOK_MARKER, Boolean.TRUE);
        comfortSub.addDisposeListener(ev -> {
            if (!comfortSub.isDisposed())
                comfortSub.removeMenuListener(subListener);
        });
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
