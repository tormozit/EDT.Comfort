package tormozit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.stacktraces.model.IStacktrace;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceElement;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceError;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceFrame;
import com.google.inject.Injector;

/**
 * Доработки панели «Трассировки стека»:
 * <ul>
 * <li>заголовок вкладки — вместо «Трассировка стека» обрезка до 30 символов первой строки
 * текста ошибки с суффиксом «...»; дата ({@code getDetail()}) сохраняется второй строкой как
 * у EDT; при обрезке подсказка при наведении показывает полную первую строку ошибки;</li>
 * <li>поле выбора проекта над деревом каждой вкладки — по умолчанию проект с максимальным
 * покрытием модулей кадров стека (issue 253); заполнение Combo и резолв кадров — только для
 * активной вкладки; при смене проекта имя записывается в элементы трассировки, кэш модуля
 * кадра сбрасывается;</li>
 * <li>одинарный клик / смена выделения по кадру стека ({@link IStacktraceFrame}) больше не
 * открывает модуль (штатно {@code StacktracesViewPage.selectionChanged} вызывал
 * {@code IBslSourceDisplay.displayBslSource(..., forceSelect=false)}); открытие — по двойному
 * клику с учётом выбранного проекта;</li>
 * <li>двойной клик по строке узла причины ({@link IStacktraceError}) — берёт причину ошибки
 * трассировки и запускает штатное действие EDT «Добавить точку останова по исключению» (открывшийся
 * диалог «Остановка по ошибке» сам подставит причину, см. {@link ExceptionSelectionDialogHook#setPendingReason},
 * без буфера обмена);</li>
 * <li>Ctrl+C — копирует текст текущей (выделенной) строки дерева, а не всю трассировку целиком
 * (штатное {@code org.eclipse.ui.edit.copy} этой панели — {@code CopyStacktraceHandler} — кладёт
 * в буфер весь дамп через {@code IStacktracesClipboardSupport.putStacktrace});</li>
 * <li>иконки кадров стека: красный крестик, если модуль не найден в <em>выбранном</em> проекте;
 * предупреждение, если модуль найден, но текст после «:» в строке стека не совпадает с текстом
 * той же строки модуля. Открытие и проверка — только по выбранному проекту (без обхода других);
 * если модуль есть в другом проекте, тост при двойном клике это сообщает.</li>
 * </ul>
 *
 * <p>Панель многостраничная (по вкладке на трассировку, {@code MultiPageViewPart}), поэтому двойной
 * клик — общий {@code SWT.MouseDoubleClick} фильтр Display (с проверкой, что активная часть
 * workbench — именно эта панель; двойной клик по строке сам активирует часть), а не привязка
 * к дереву конкретной страницы. Копирование — переопределение обработчика команды на самом view
 * ({@code IViewSite.getService(IHandlerService.class).activateHandler(...)}) — активация на этом
 * уровне перебивает штатный обработчик, зарегистрированный EDT через
 * {@code org.eclipse.ui.handlers}.
 */
public final class StacktracesViewInteractionHook implements IStartup
{
    private static final String VIEW_ID = "com._1c.g5.v8.dt.stacktraces.ui.StacktracesView"; //$NON-NLS-1$
    private static final String STACKTRACES_UI_BUNDLE = "com._1c.g5.v8.dt.stacktraces.ui"; //$NON-NLS-1$
    private static final String STACKTRACES_UI_PLUGIN =
            "com._1c.g5.v8.dt.internal.stacktraces.ui.StacktracesUiPlugin"; //$NON-NLS-1$
    private static final String SOURCE_DISPLAY_IFACE =
            "com._1c.g5.v8.dt.internal.stacktraces.ui.bsl.IBslSourceDisplay"; //$NON-NLS-1$
    private static final String MODULE_LOCATOR_IFACE =
            "com._1c.g5.v8.dt.internal.stacktraces.ui.bsl.IBslModuleLocator"; //$NON-NLS-1$
    private static final String PAGE_ENHANCED_KEY = "tormozit.comfort.stacktraces.pageEnhanced"; //$NON-NLS-1$
    private static final String PAGE_STATE_KEY = "tormozit.comfort.stacktraces.pageState"; //$NON-NLS-1$
    /** Максимум символов в заголовке вкладки (первая строка текста ошибки). */
    private static final int TAB_TITLE_MAX_CHARS = 30;
    /** Максимальная ширина Combo выбора проекта, px. */
    private static final int PROJECT_COMBO_MAX_WIDTH = 300;

    private static final java.util.Set<IViewPart> COPY_HANDLER_INSTALLED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<IViewPart> VIEW_HOOKED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final WeakHashMap<Composite, PageState> PAGE_STATES = new WeakHashMap<>();

    private static final String ADD_ACTION_BUNDLE = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$
    private static final String ADD_ACTION_CLASS =
            "com._1c.g5.v8.dt.internal.debug.ui.actions.AddBslExceptionBreakpointAction"; //$NON-NLS-1$
    private static final String STOP_ON_ERROR_TITLE = "Остановка по ошибке"; //$NON-NLS-1$
    private static final String NO_REASON_MESSAGE =
            "В выбранной трассировке не найдено описания ошибки"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            installDoubleClick(Display.getDefault());
            hookWindows();
        });
    }

    // -----------------------------------------------------------------------
    // Двойной клик: причина → «Остановка по ошибке»; кадр → открыть модуль
    // -----------------------------------------------------------------------

    private static void installDoubleClick(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDoubleClick, event ->
        {
            if (!(event.widget instanceof Tree tree) || tree.isDisposed())
                return;
            PageState state = findPageState(tree);
            // Дерево нашей доработанной страницы — не зависим от activePart (при открытом
            // сравнении фокус/active part часто не панель трассировок).
            if (state == null && !isStacktracesViewActive())
                return;
            handleTreeDoubleClick(tree, state);
        });
    }

    private static boolean isStacktracesViewActive()
    {
        IWorkbenchPart part = activePart();
        return part != null && VIEW_ID.equals(part.getSite().getId());
    }

    private static void handleTreeDoubleClick(Tree tree, PageState state)
    {
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        Object data = selection[0].getData();
        if (data instanceof IStacktraceError error)
        {
            triggerStopOnException(error);
            return;
        }
        if (data instanceof IStacktraceFrame frame)
            openFrameModule(tree, state, frame);
    }

    /** Причина ошибки трассировки, к которой относится {@code element} → диалог «Остановка по ошибке». */
    private static void triggerStopOnException(IStacktraceElement element)
    {
        String errorText = findErrorText(element);
        String reason = BreakpointListHook.firstLine(errorText);
        if (reason.isEmpty())
        {
            ToastNotification.show(STOP_ON_ERROR_TITLE, NO_REASON_MESSAGE, 4_000);
            return;
        }

        ExceptionSelectionDialogHook.setPendingReason(reason);
        runAddExceptionBreakpointAction();
    }

    /** Текст ошибки трассировки, к которой относится {@code element} (см. {@link IStacktraceError}). */
    private static String findErrorText(IStacktraceElement element)
    {
        IStacktrace root = element.getStacktrace();
        if (root == null)
            return null;
        for (IStacktraceElement child : root.getChilden())
        {
            if (child instanceof IStacktraceError errorNode)
                return errorNode.getName();
        }
        return null;
    }

    /**
     * Штатное действие «Добавить точку останова по исключению» не привязано ни к одной команде
     * EDT (нет {@code definitionId}) — открываем тот же диалог, что и оно, напрямую его вызовом
     * через рефлексию (как {@code CreateDebuggerBreakpoints.resolveFactory()} для {@code DebugCorePlugin}).
     */
    private static void runAddExceptionBreakpointAction()
    {
        try
        {
            Bundle bundle = Platform.getBundle(ADD_ACTION_BUNDLE);
            if (bundle == null)
                return;
            Class<?> actionClass = bundle.loadClass(ADD_ACTION_CLASS);
            Object action = actionClass.getDeclaredConstructor().newInstance();
            Global.invokeVoid(action, "run", new Object[] { null }); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log("StacktracesViewInteraction", "runAddExceptionBreakpointAction: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void openFrameModule(Tree tree, PageState state, IStacktraceFrame frame)
    {
        Composite pageComposite = findPageComposite(tree);
        if (state == null && pageComposite != null)
        {
            enhancePage(pageComposite);
            state = findPageState(tree);
        }
        if (state == null)
            state = findPageState(tree);

        String projectName = state != null ? state.selectedProjectName() : null;
        if ((projectName == null || projectName.isBlank()) && frame.getProjectName() != null)
            projectName = frame.getProjectName();
        if (projectName != null && !projectName.isBlank())
            applyProjectToFrame(frame, projectName);

        IWorkbenchPage workbenchPage = resolveWorkbenchPage(pageComposite);
        if (workbenchPage == null)
            return;
        Shell shell = workbenchPage.getWorkbenchWindow() != null
                ? workbenchPage.getWorkbenchWindow().getShell()
                : tree.getShell();

        Module module = resolveFrameModuleInSelectedProject(state, frame);
        IFile file = module != null ? moduleToFile(module) : null;
        if (file == null)
        {
            ToastNotification.show(
                    "Трассировка стека", //$NON-NLS-1$
                    missingModuleToastMessage(frame, projectName),
                    5_000);
            return;
        }

        int stackLine = frame.getLineNumber();
        boolean opened = GoToDefinition.openBslModuleAtLine(file, stackLine, workbenchPage, shell);
        if (!opened)
        {
            ToastNotification.show(
                    "Трассировка стека", //$NON-NLS-1$
                    "Не удалось открыть модуль", //$NON-NLS-1$
                    4_000);
            return;
        }

        String expected = extractStackLineCode(frame.getName());
        if (expected == null || expected.isEmpty())
            return;
        String actual = readModuleLineText(module, stackLine);
        if (actual != null && expected.equals(actual))
            return;

        // Знак вопроса / рассинхрон: после активации строки стека ищем дальше совпадение текста.
        Display display = tree.getDisplay();
        if (display != null && !display.isDisposed())
        {
            final IFile fileFinal = file;
            final String expectedFinal = expected;
            final int stackLineFinal = stackLine;
            final IWorkbenchPage pageFinal = workbenchPage;
            final Shell shellFinal = shell;
            display.asyncExec(() -> offerJumpToMatchingLine(
                    fileFinal, expectedFinal, stackLineFinal, pageFinal, shellFinal));
        }
    }

    /**
     * Ищет в модуле ниже {@code afterLine1Based} строку с текстом {@code expected} (после trim)
     * и предлагает перейти к ней.
     */
    private static void offerJumpToMatchingLine(
            IFile file, String expected, int afterLine1Based, IWorkbenchPage page, Shell shell)
    {
        int matchLine = findMatchingLineBelow(file, expected, afterLine1Based);
        if (matchLine <= 0)
            return;

        String title = Global.withPluginWindowTitle("Трассировка стека"); //$NON-NLS-1$
        String message = "Текст в строке " + afterLine1Based //$NON-NLS-1$
                + " не совпадает со стеком.\nНайдена строка " + matchLine //$NON-NLS-1$
                + " с тем же текстом. Перейти к ней?"; //$NON-NLS-1$
        if (!MessageDialog.openQuestion(shell, title, message))
            return;
        GoToDefinition.openBslModuleAtLine(file, matchLine, page, shell);
    }

    /** Первая строка строго ниже {@code afterLine1Based} с текстом {@code expected} (1-based). */
    private static int findMatchingLineBelow(IFile file, String expected, int afterLine1Based)
    {
        if (file == null || expected == null || expected.isEmpty())
            return -1;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(true), StandardCharsets.UTF_8)))
        {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null)
            {
                lineNo++;
                if (lineNo <= afterLine1Based)
                    continue;
                if (expected.equals(line.strip()))
                    return lineNo;
            }
        }
        catch (Exception ignored)
        {
        }
        return -1;
    }

    private static Composite findPageComposite(Tree tree)
    {
        Composite parent = tree.getParent();
        while (parent != null && !parent.isDisposed())
        {
            if (parent.getData(PAGE_STATE_KEY) != null
                    || parent.getClass().getName().contains("StacktracesViewPage")) //$NON-NLS-1$
                return parent;
            parent = parent.getParent();
        }
        return tree.getParent();
    }

    private static IWorkbenchPage resolveWorkbenchPage(Composite pageComposite)
    {
        IWorkbenchPart part = activePart();
        if (part != null && VIEW_ID.equals(part.getSite().getId()))
            return part.getSite().getPage();
        if (pageComposite != null)
        {
            Object viewPart = Global.invoke(pageComposite, "getViewPart"); //$NON-NLS-1$
            if (viewPart instanceof IWorkbenchPart wp)
                return wp.getSite().getPage();
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window != null ? window.getActivePage() : null;
    }

    private static PageState findPageState(Tree tree)
    {
        Composite parent = tree.getParent();
        while (parent != null && !parent.isDisposed())
        {
            Object data = parent.getData(PAGE_STATE_KEY);
            if (data instanceof PageState state)
                return state;
            parent = parent.getParent();
        }
        return null;
    }

    private static void applyProjectToFrame(IStacktraceFrame frame, String projectName)
    {
        frame.setProjectName(projectName);
        frame.setModule(null);
    }

    private static void applyProjectToStacktrace(IStacktrace stacktrace, String projectName)
    {
        if (stacktrace == null || projectName == null || projectName.isBlank())
            return;
        stacktrace.setProjectName(projectName);
        applyProjectRecursive(stacktrace, projectName);
    }

    private static void applyProjectRecursive(IStacktraceElement element, String projectName)
    {
        if (element == null)
            return;
        element.setProjectName(projectName);
        if (element instanceof IStacktraceFrame frame)
            frame.setModule(null);
        List<IStacktraceElement> children = element.getChilden();
        if (children == null)
            return;
        for (IStacktraceElement child : children)
            applyProjectRecursive(child, projectName);
    }

    // -----------------------------------------------------------------------
    // Поле проекта + подавление открытия по выделению + Ctrl+C
    // -----------------------------------------------------------------------

    private static void hookWindows()
    {
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
                tryInstallView(ref.getView(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                tryInstallView(ref != null ? ref.getPart(false) : null);
            }
        });
    }

    private static void tryInstallView(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart view) || !VIEW_ID.equals(view.getViewSite().getId()))
            return;
        tryInstallCopy(view);
        hookViewPages(view);
    }

    private static void hookViewPages(IViewPart view)
    {
        Object folderObj = Global.invoke(view, "getPageContainer"); //$NON-NLS-1$
        if (!(folderObj instanceof org.eclipse.swt.custom.CTabFolder folder) || folder.isDisposed())
            return;

        updateFolderTabTitles(folder);
        enhanceActivePage(view, folder);

        if (!VIEW_HOOKED.add(view))
            return;

        folder.addListener(SWT.Selection, event ->
        {
            updateFolderTabTitles(folder);
            enhanceActivePage(view, folder);
        });
        folder.addDisposeListener(e -> VIEW_HOOKED.remove(view));
    }

    /**
     * Заголовки вкладок: обрезка первой строки ошибки (без резолва модулей — для всех вкладок).
     */
    private static void updateFolderTabTitles(org.eclipse.swt.custom.CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        for (org.eclipse.swt.custom.CTabItem item : folder.getItems())
            updateTabTitleFromError(item);
    }

    private static void updateTabTitleFromError(org.eclipse.swt.custom.CTabItem item)
    {
        if (item == null || item.isDisposed())
            return;
        Control control = item.getControl();
        if (!(control instanceof Composite page) || page.isDisposed())
            return;
        IStacktrace stacktrace = resolveStacktrace(page);
        if (stacktrace == null)
            return;
        String full = BreakpointListHook.firstLine(findStacktraceErrorText(stacktrace));
        if (full.isEmpty())
            return;

        String shortError;
        boolean truncated = full.length() > TAB_TITLE_MAX_CHARS;
        if (truncated)
            shortError = full.substring(0, TAB_TITLE_MAX_CHARS) + "..."; //$NON-NLS-1$
        else
            shortError = full;
        // Штатно EDT: getName() + "\n" + getDetail() (дата). Меняем только первую строку.
        String detail = stacktrace.getDetail();
        String title = detail == null || detail.isBlank()
                ? shortError
                : shortError + '\n' + detail;
        if (!title.equals(item.getText()))
            item.setText(title);
        if (truncated)
            item.setToolTipText(full);
        else if (item.getToolTipText() != null)
            item.setToolTipText(null);
    }

    /** Текст узла причины ({@link IStacktraceError}) у корня трассировки. */
    private static String findStacktraceErrorText(IStacktrace stacktrace)
    {
        if (stacktrace == null)
            return null;
        List<IStacktraceElement> children = stacktrace.getChilden();
        if (children == null)
            return null;
        for (IStacktraceElement child : children)
        {
            if (child instanceof IStacktraceError errorNode)
                return errorNode.getName();
        }
        return null;
    }

    /**
     * Доработка и резолв (Combo проектов, иконки кадров) — только для видимой вкладки.
     * Неактивные стеки не трогаем, пока пользователь на них не переключится.
     */
    private static void enhanceActivePage(IViewPart view, org.eclipse.swt.custom.CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        org.eclipse.swt.custom.CTabItem selected = folder.getSelection();
        if (selected == null || selected.isDisposed())
            return;
        Control control = selected.getControl();
        if (!(control instanceof Composite page) || page.isDisposed())
            return;

        if (Boolean.TRUE.equals(page.getData(PAGE_ENHANCED_KEY)))
            refreshPageProjects(page);
        else
            enhancePage(page);

        // afterPageChange снова регистрирует страницу как selection listener —
        // снимаем сейчас и ещё раз async (addPage вызывает afterPageChange после
        // Selection от setActivePage).
        suppressPageSelectionOpen(view, page);
        Display display = page.getDisplay();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() ->
            {
                if (!page.isDisposed())
                    suppressPageSelectionOpen(view, page);
            });
        }
    }

    /** Перечитать список V8-проектов и обновить иконки (только для активной страницы). */
    private static void refreshPageProjects(Composite page)
    {
        if (page == null || page.isDisposed())
            return;
        Object data = page.getData(PAGE_STATE_KEY);
        if (!(data instanceof PageState state))
            return;
        String previous = state.selectedProjectName();
        fillProjectCombo(state, previous);
        state.clearIconCache();
        if (state.treeViewer != null && !state.treeViewer.getControl().isDisposed())
            state.treeViewer.refresh();
    }

    private static void enhancePage(Composite page)
    {
        if (page == null || page.isDisposed())
            return;
        if (Boolean.TRUE.equals(page.getData(PAGE_ENHANCED_KEY)))
            return;

        IStacktrace stacktrace = resolveStacktrace(page);
        Tree tree = findTree(page);
        if (tree == null)
            return;

        Object realDisplay = Global.getField(page, "sourceDisplay"); //$NON-NLS-1$
        if (realDisplay != null)
        {
            Object wrapped = wrapSourceDisplay(realDisplay);
            if (wrapped != null)
                Global.setFieldForce(page, "sourceDisplay", wrapped); //$NON-NLS-1$
        }

        PageState state = new PageState(stacktrace, realDisplay);
        PAGE_STATES.put(page, state);
        page.setData(PAGE_STATE_KEY, state);
        page.setData(PAGE_ENHANCED_KEY, Boolean.TRUE);

        installProjectCombo(page, tree, state);
        installFrameStatusLabelProvider(page, state);
        page.addDisposeListener(e -> PAGE_STATES.remove(page));
    }

    private static void installFrameStatusLabelProvider(Composite page, PageState state)
    {
        Object viewerObj = Global.getField(page, "viewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer))
            return;
        IBaseLabelProvider current = viewer.getLabelProvider();
        if (current instanceof FrameStatusLabelProvider)
            return;
        FrameStatusLabelProvider wrapped = new FrameStatusLabelProvider(current, state, page);
        viewer.setLabelProvider(wrapped);
        state.treeViewer = viewer;
    }

    /** Страница вкладки видна (активна в {@code CTabFolder}). */
    private static boolean isPageVisible(Composite page)
    {
        if (page == null || page.isDisposed())
            return false;
        Control current = page;
        while (current != null && !current.isDisposed())
        {
            if (current.getParent() instanceof org.eclipse.swt.custom.CTabFolder folder)
            {
                org.eclipse.swt.custom.CTabItem selected = folder.getSelection();
                return selected != null && selected.getControl() == page;
            }
            current = current.getParent();
        }
        return page.isVisible();
    }

    private static IStacktrace resolveStacktrace(Composite page)
    {
        Object value = Global.invoke(page, "getStacktrace"); //$NON-NLS-1$
        return value instanceof IStacktrace stacktrace ? stacktrace : null;
    }

    private static Tree findTree(Composite page)
    {
        for (Control child : page.getChildren())
        {
            if (child instanceof Tree tree)
                return tree;
        }
        return null;
    }

    /**
     * Штатный {@code StacktracesViewPage} слушает selection provider view и при выделении кадра
     * открывает модуль. Снимаем его с провайдера после каждой активации вкладки
     * ({@code afterPageChange} снова регистрирует страницу).
     */
    private static void suppressPageSelectionOpen(IViewPart view, Composite page)
    {
        Object provider = Global.invoke(view, "getSelectionProvider"); //$NON-NLS-1$
        if (provider == null)
            return;
        Global.invoke(provider, "removeSelectionChangedListener", page); //$NON-NLS-1$
    }

    private static Object wrapSourceDisplay(Object delegate)
    {
        try
        {
            Bundle bundle = Platform.getBundle(STACKTRACES_UI_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> iface = bundle.loadClass(SOURCE_DISPLAY_IFACE);
            InvocationHandler handler = (proxy, method, args) ->
            {
                if (method.getDeclaringClass() == Object.class)
                {
                    if ("equals".equals(method.getName())) //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    if ("hashCode".equals(method.getName())) //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    return method.invoke(delegate, args);
                }
                // forceSelect=false — штатный переход по выделению; глушим (открытие — по dblclick).
                if ("displayBslSource".equals(method.getName()) //$NON-NLS-1$
                        && args != null && args.length == 3
                        && Boolean.FALSE.equals(args[2]))
                    return null;
                return method.invoke(delegate, args);
            };
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, handler);
        }
        catch (Exception e)
        {
            Global.log("StacktracesViewInteraction", "wrapSourceDisplay: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static void installProjectCombo(Composite page, Tree tree, PageState state)
    {
        Composite header = new Composite(page, SWT.NONE);
        GridData headerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        header.setLayoutData(headerData);
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 2;
        headerLayout.horizontalSpacing = 6;
        header.setLayout(headerLayout);
        header.moveAbove(tree);

        Label label = new Label(header, SWT.NONE);
        label.setText("Проект:"); //$NON-NLS-1$

        Combo combo = new Combo(header, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData comboData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        comboData.widthHint = PROJECT_COMBO_MAX_WIDTH;
        combo.setLayoutData(comboData);
        combo.setToolTipText(
                "Проект для перехода к модулям стека" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        state.projectCombo = combo;

        fillProjectCombo(state, null);
        combo.addListener(SWT.Selection, e ->
        {
            String name = state.selectedProjectName();
            applyProjectToStacktrace(state.stacktrace, name);
            state.clearIconCache();
            if (state.treeViewer != null && !state.treeViewer.getControl().isDisposed())
                state.treeViewer.refresh();
        });

        page.layout(true, true);
    }

    /**
     * Заполняет Combo всеми доступными V8-проектами. Если {@code preferredName} ещё в списке —
     * оставляет его; иначе выбирает проект с максимальным покрытием кадров.
     */
    private static void fillProjectCombo(PageState state, String preferredName)
    {
        Combo combo = state.projectCombo;
        if (combo == null || combo.isDisposed())
            return;
        combo.removeAll();
        state.projectNames.clear();

        List<ProjectCoverage> ranked = rankProjectsByCoverage(state.stacktrace);
        String activeName = resolveActiveProjectName();
        String defaultName = null;
        int bestScore = -1;
        for (ProjectCoverage item : ranked)
        {
            combo.add(item.name);
            state.projectNames.add(item.name);
            if (item.score > bestScore)
            {
                bestScore = item.score;
                defaultName = item.name;
            }
            else if (item.score == bestScore && activeName != null && activeName.equals(item.name))
                defaultName = item.name;
        }

        if (combo.getItemCount() == 0)
            return;

        int index = -1;
        if (preferredName != null && !preferredName.isBlank())
            index = state.projectNames.indexOf(preferredName);
        if (index < 0 && defaultName != null)
            index = state.projectNames.indexOf(defaultName);
        if (index < 0)
            index = 0;
        combo.select(index);
        applyProjectToStacktrace(state.stacktrace, state.selectedProjectName());
    }

    private static String resolveActiveProjectName()
    {
        try
        {
            IProject project = Global.getActiveProject((IWorkbenchPage) null, false);
            return project != null ? project.getName() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static List<ProjectCoverage> rankProjectsByCoverage(IStacktrace stacktrace)
    {
        List<IStacktraceFrame> frames = collectFrames(stacktrace);
        IV8ProjectManager projectManager =
                (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        Object locator = resolveModuleLocator();
        if (projectManager == null)
            return List.of();

        // Все открытые V8-проекты workspace (не только то, что вернул getProjects() менеджера —
        // у части конфигураций менеджер отдаёт урезанный набор).
        List<IV8Project> projects = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (IProject workspaceProject : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (workspaceProject == null || !workspaceProject.isAccessible())
                continue;
            IV8Project v8 = projectManager.getProject(workspaceProject);
            if (v8 == null)
                continue;
            String name = workspaceProject.getName();
            if (!seen.add(name))
                continue;
            projects.add(v8);
        }
        // Дополнить тем, что знает менеджер (на случай проектов без IProject в root — маловероятно).
        try
        {
            for (IV8Project v8 : projectManager.getProjects())
            {
                if (v8 == null || v8.getProject() == null || !v8.getProject().isAccessible())
                    continue;
                String name = v8.getProject().getName();
                if (!seen.add(name))
                    continue;
                projects.add(v8);
            }
        }
        catch (Exception ignored)
        {
        }

        List<ProjectCoverage> result = new ArrayList<>();
        for (IV8Project v8 : projects)
        {
            String name = v8.getProject().getName();
            int score = 0;
            if (locator != null)
            {
                for (IStacktraceFrame frame : frames)
                {
                    if (moduleExistsInProject(locator, frame, v8))
                        score++;
                }
            }
            result.add(new ProjectCoverage(name, score));
        }

        result.sort(Comparator
                .comparingInt((ProjectCoverage p) -> p.score).reversed()
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static boolean moduleExistsInProject(Object locator, IStacktraceFrame frame, IV8Project project)
    {
        if (frame == null)
            return false;
        String symlink = frame.getSymlink();
        if (symlink == null || symlink.isBlank())
            return false;
        Object module = Global.invoke(locator, "getModule", symlink, project, //$NON-NLS-1$
                Boolean.valueOf(frame.isExtension()));
        return module != null;
    }

    private static List<IStacktraceFrame> collectFrames(IStacktrace stacktrace)
    {
        if (stacktrace == null)
            return List.of();
        List<IStacktraceFrame> frames = new ArrayList<>();
        collectFramesRecursive(stacktrace, frames);
        return frames;
    }

    private static void collectFramesRecursive(IStacktraceElement element, List<IStacktraceFrame> out)
    {
        if (element == null)
            return;
        if (element instanceof IStacktraceFrame frame)
            out.add(frame);
        List<IStacktraceElement> children = element.getChilden();
        if (children == null)
            return;
        for (IStacktraceElement child : children)
            collectFramesRecursive(child, out);
    }

    private static Object resolveModuleLocator()
    {
        try
        {
            Bundle bundle = Platform.getBundle(STACKTRACES_UI_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> pluginClass = bundle.loadClass(STACKTRACES_UI_PLUGIN);
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            Object injectorObj = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            if (!(injectorObj instanceof Injector injector))
                return null;
            Class<?> locatorClass = bundle.loadClass(MODULE_LOCATOR_IFACE);
            return injector.getInstance(locatorClass);
        }
        catch (Exception e)
        {
            Global.log("StacktracesViewInteraction", "resolveModuleLocator: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Ctrl+C — текст текущей строки, а не вся трассировка
    // -----------------------------------------------------------------------

    private static void tryInstallCopy(IViewPart view)
    {
        if (!COPY_HANDLER_INSTALLED.add(view))
            return;

        IHandlerService handlerService = view.getSite().getService(IHandlerService.class);
        if (handlerService == null)
        {
            COPY_HANDLER_INSTALLED.remove(view);
            return;
        }

        handlerService.activateHandler("org.eclipse.ui.edit.copy", new AbstractHandler() //$NON-NLS-1$
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                copyActiveRow();
                return null;
            }
        });
    }

    private static void copyActiveRow()
    {
        Display display = Display.getCurrent();
        Control focus = display != null ? display.getFocusControl() : null;
        if (!(focus instanceof Tree tree) || tree.isDisposed())
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        String text = selection[0].getText();
        if (text == null || text.isBlank())
            return;

        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    private static IWorkbenchPart activePart()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        return page != null ? page.getActivePart() : null;
    }

    // -----------------------------------------------------------------------
    // Иконки кадров: модуль не найден / текст строки не совпал
    // -----------------------------------------------------------------------

    private enum FrameIconKind
    {
        DEFAULT,
        MISSING,
        MISMATCH
    }

    private static FrameIconKind resolveFrameIconKind(PageState state, IStacktraceFrame frame)
    {
        if (state == null || frame == null)
            return FrameIconKind.DEFAULT;
        FrameIconKind cached = state.iconCache.get(frame);
        if (cached != null)
            return cached;

        FrameIconKind kind = computeFrameIconKind(state, frame);
        // MISSING не кэшируем: проект мог ещё не быть готов в менеджере при первой отрисовке.
        if (kind != FrameIconKind.MISSING)
            state.iconCache.put(frame, kind);
        return kind;
    }

    private static FrameIconKind computeFrameIconKind(PageState state, IStacktraceFrame frame)
    {
        Module module = resolveFrameModuleInSelectedProject(state, frame);
        if (module == null)
            return FrameIconKind.MISSING;

        String expected = extractStackLineCode(frame.getName());
        if (expected == null || expected.isEmpty())
            return FrameIconKind.DEFAULT;

        String actual = readModuleLineText(module, frame.getLineNumber());
        if (actual == null)
            return FrameIconKind.MISMATCH;
        return expected.equals(actual) ? FrameIconKind.DEFAULT : FrameIconKind.MISMATCH;
    }

    /**
     * Модуль кадра только в выбранном (или указанном на кадре) проекте — без обхода остальных.
     */
    private static Module resolveFrameModuleInSelectedProject(PageState state, IStacktraceFrame frame)
    {
        if (frame == null)
            return null;

        Object locator = resolveModuleLocator();
        if (locator == null)
            return null;
        String symlink = frame.getSymlink();
        if (symlink == null || symlink.isBlank())
            return null;

        IV8Project project = resolveSelectedV8Project(state);
        if (project == null)
            project = resolveV8ProjectByName(frame.getProjectName());
        if (project == null)
            return null;

        Object module = Global.invoke(locator, "getModule", symlink, project, //$NON-NLS-1$
                Boolean.valueOf(frame.isExtension()));
        return module instanceof Module found ? found : null;
    }

    /** Имена других V8-проектов, где тот же symlink кадра находится. */
    private static List<String> findOtherProjectsWithModule(IStacktraceFrame frame, String excludeProjectName)
    {
        if (frame == null)
            return List.of();
        Object locator = resolveModuleLocator();
        if (locator == null)
            return List.of();
        Object all = Global.invoke(locator, "getModules", frame); //$NON-NLS-1$
        if (!(all instanceof List<?> modules) || modules.isEmpty())
            return List.of();

        List<String> names = new ArrayList<>();
        for (Object item : modules)
        {
            if (!(item instanceof Module module))
                continue;
            IFile file = moduleToFile(module);
            if (file == null)
                continue;
            String name = file.getProject().getName();
            if (name == null || name.isBlank())
                continue;
            if (excludeProjectName != null && excludeProjectName.equals(name))
                continue;
            if (!names.contains(name))
                names.add(name);
        }
        return names;
    }

    private static String missingModuleToastMessage(IStacktraceFrame frame, String selectedProjectName)
    {
        StringBuilder message = new StringBuilder("Модуль не найден в выбранном проекте"); //$NON-NLS-1$
        if (selectedProjectName != null && !selectedProjectName.isBlank())
            message.append(" «").append(selectedProjectName).append('»'); //$NON-NLS-1$
        List<String> others = findOtherProjectsWithModule(frame, selectedProjectName);
        if (!others.isEmpty())
        {
            message.append(". Найден в: "); //$NON-NLS-1$
            message.append(String.join(", ", others)); //$NON-NLS-1$
        }
        return message.toString();
    }

    private static IV8Project resolveSelectedV8Project(PageState state)
    {
        String name = state != null ? state.selectedProjectName() : null;
        return resolveV8ProjectByName(name);
    }

    private static IV8Project resolveV8ProjectByName(String name)
    {
        if (name == null || name.isBlank())
            return null;
        IV8ProjectManager projectManager =
                (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        if (projectManager == null)
            return null;
        // Не Global.invoke("getProject", name): у менеджера несколько getProject(1 arg),
        // invoke берёт первый попавшийся overload и с String часто падает → null.
        IProject workspaceProject = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (workspaceProject == null || !workspaceProject.isAccessible())
            return null;
        return projectManager.getProject(workspaceProject);
    }

    /**
     * Текст после «:» в подписи кадра (формат ошибки {@code {Модуль(N)}: код}). Для отладочного
     * формата {@code ... line: N} / {@code ... строка: N} возвращает {@code null} — сверка
     * исходника не применяется.
     */
    private static String extractStackLineCode(String frameName)
    {
        if (frameName == null || frameName.isBlank())
            return null;
        int braceColon = frameName.indexOf("}:"); //$NON-NLS-1$
        if (braceColon >= 0)
        {
            String code = frameName.substring(braceColon + 2).strip();
            return code.isEmpty() ? null : code;
        }
        int colon = frameName.lastIndexOf(':');
        if (colon < 0 || colon + 1 >= frameName.length())
            return null;
        String after = frameName.substring(colon + 1).strip();
        if (after.isEmpty() || after.chars().allMatch(Character::isDigit))
            return null;
        return after;
    }

    /** Текст строки модуля (1-based), без крайних пробелов; {@code null} если прочитать нельзя. */
    private static String readModuleLineText(Module module, int lineNumber1Based)
    {
        if (module == null || lineNumber1Based < 1)
            return null;
        IFile file = moduleToFile(module);
        if (file == null || !file.exists())
            return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(true), StandardCharsets.UTF_8)))
        {
            String line = null;
            for (int i = 1; i <= lineNumber1Based; i++)
            {
                line = reader.readLine();
                if (line == null)
                    return null;
            }
            return line.strip();
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static IFile moduleToFile(Module module)
    {
        try
        {
            URI uri = EcoreUtil.getURI(module);
            if (uri == null)
                return null;
            URI noFragment = uri.trimFragment();
            if (!noFragment.isPlatformResource())
                return null;
            String platform = noFragment.toPlatformString(true);
            if (platform == null || platform.isBlank())
                return null;
            return ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platform));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Image sharedImage(String key)
    {
        try
        {
            return PlatformUI.getWorkbench().getSharedImages().getImage(key);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Image questionImage()
    {
        // Не Dialog.DLG_IMG_QUESTION — слишком крупная/контрастная для дерева.
        Image warn = sharedImage(ISharedImages.IMG_OBJS_WARN_TSK);
        if (warn != null)
            return warn;
        return sharedImage(ISharedImages.IMG_OBJS_INFO_TSK);
    }

    private static Image stacktracesUiImage(String path)
    {
        try
        {
            Bundle bundle = Platform.getBundle(STACKTRACES_UI_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> pluginClass = bundle.loadClass(STACKTRACES_UI_PLUGIN);
            Object image = Global.invoke(pluginClass, "getImage", path); //$NON-NLS-1$
            return image instanceof Image img ? img : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Состояние страницы
    // -----------------------------------------------------------------------

    private static final class PageState
    {
        final IStacktrace stacktrace;
        final Object realSourceDisplay;
        final List<String> projectNames = new ArrayList<>();
        final Map<IStacktraceFrame, FrameIconKind> iconCache = new IdentityHashMap<>();
        Combo projectCombo;
        TreeViewer treeViewer;

        PageState(IStacktrace stacktrace, Object realSourceDisplay)
        {
            this.stacktrace = stacktrace;
            this.realSourceDisplay = realSourceDisplay;
        }

        String selectedProjectName()
        {
            if (projectCombo == null || projectCombo.isDisposed())
                return null;
            int index = projectCombo.getSelectionIndex();
            if (index < 0 || index >= projectNames.size())
                return projectCombo.getText();
            return projectNames.get(index);
        }

        void clearIconCache()
        {
            iconCache.clear();
        }
    }

    /**
     * Подмена штатного {@code StacktracesElementLabelProvider}: для кадров стека подставляет
     * иконку статуса модуля/строки, остальное делегирует.
     */
    private static final class FrameStatusLabelProvider extends ColumnLabelProvider
    {
        private final IBaseLabelProvider delegate;
        private final PageState state;
        private final Composite page;

        FrameStatusLabelProvider(IBaseLabelProvider delegate, PageState state, Composite page)
        {
            this.delegate = delegate;
            this.state = state;
            this.page = page;
        }

        @Override
        public Image getImage(Object element)
        {
            if (element instanceof IStacktraceFrame frame && isPageVisible(page))
            {
                FrameIconKind kind = resolveFrameIconKind(state, frame);
                if (kind == FrameIconKind.MISSING)
                {
                    Image image = sharedImage(ISharedImages.IMG_OBJS_ERROR_TSK);
                    if (image != null)
                        return image;
                }
                else if (kind == FrameIconKind.MISMATCH)
                {
                    Image image = questionImage();
                    if (image != null)
                        return image;
                }
            }
            else if (element instanceof IStacktraceElement)
            {
                // Кадры — отдельно (stacktrace / крестик / «?»); у причины EDT даёт error.png,
                // тот же вид что у MISSING — всем не-кадрам runtime_debug_target из registry.
                Image structural = stacktracesUiImage("/icons/obj16/runtime_debug_target.png"); //$NON-NLS-1$
                if (structural != null)
                    return structural;
            }
            if (delegate instanceof ILabelProvider labelProvider)
                return labelProvider.getImage(element);
            return super.getImage(element);
        }

        @Override
        public String getText(Object element)
        {
            if (delegate instanceof ILabelProvider labelProvider)
                return labelProvider.getText(element);
            return super.getText(element);
        }

        @Override
        public String getToolTipText(Object element)
        {
            if (element instanceof IStacktraceFrame frame && isPageVisible(page))
            {
                FrameIconKind kind = resolveFrameIconKind(state, frame);
                if (kind == FrameIconKind.MISSING)
                    return "Модуль не найден в выбранном проекте" + Global.pluginSignForTooltip(); //$NON-NLS-1$
                if (kind == FrameIconKind.MISMATCH)
                    return "Текст строки модуля не совпадает со стеком" + Global.pluginSignForTooltip(); //$NON-NLS-1$
            }
            if (delegate instanceof ColumnLabelProvider columnLabelProvider)
                return columnLabelProvider.getToolTipText(element);
            if (delegate instanceof ILabelProvider labelProvider)
                return labelProvider.getText(element);
            return super.getToolTipText(element);
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            super.addListener(listener);
            if (delegate != null)
                delegate.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            super.removeListener(listener);
            if (delegate != null)
                delegate.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate != null && delegate.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            // Штатный провайдер EDT не держит собственных Image — можно dispose;
            // shared images Eclipse dispose нельзя.
            if (delegate != null)
                delegate.dispose();
            super.dispose();
        }
    }

    private static final class ProjectCoverage
    {
        final String name;
        final int score;

        ProjectCoverage(String name, int score)
        {
            this.name = name;
            this.score = score;
        }
    }
}
