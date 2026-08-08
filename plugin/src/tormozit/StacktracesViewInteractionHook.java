package tormozit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IMemento;
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
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
 * текста ошибки с суффиксом «...»; дата ({@code getDetail()}) — в той же строке через пробел;</li>
 * <li>слева от стека — таблица списка трассировок ({@link FormTableInteraction}: колонки
 * «Ошибка», «Дата», «Проект»; мультивыбор; Del закрывает вкладки; сортировка по дате по
 * умолчанию; фильтр {@link FilterInputBox}; персист ширин/порядка колонок и разделителя);</li>
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
    private static final String STACKTRACES_BUNDLE = "com._1c.g5.v8.dt.stacktraces"; //$NON-NLS-1$
    private static final String STACKTRACES_PLUGIN =
            "com._1c.g5.v8.dt.internal.stacktraces.StacktracesPlugin"; //$NON-NLS-1$
    private static final String SOURCE_DISPLAY_IFACE =
            "com._1c.g5.v8.dt.internal.stacktraces.ui.bsl.IBslSourceDisplay"; //$NON-NLS-1$
    private static final String MODULE_LOCATOR_IFACE =
            "com._1c.g5.v8.dt.internal.stacktraces.ui.bsl.IBslModuleLocator"; //$NON-NLS-1$
    private static final String PAGE_ENHANCED_KEY = "tormozit.comfort.stacktraces.pageEnhanced"; //$NON-NLS-1$
    private static final String PAGE_STATE_KEY = "tormozit.comfort.stacktraces.pageState"; //$NON-NLS-1$
    private static final String LIST_PANE_KEY = "tormozit.comfort.stacktraces.listPane"; //$NON-NLS-1$
    private static final String LIST_SASH_KEY = "tormozit.comfort.stacktraces.listSash"; //$NON-NLS-1$
    private static final String LIST_INSTALLING_KEY = "tormozit.comfort.stacktraces.listInstalling"; //$NON-NLS-1$
    private static final String FOLDER_LISTENERS_KEY = "tormozit.comfort.stacktraces.folderListeners"; //$NON-NLS-1$
    private static final String REPO_PROBE_KEY = "tormozit.comfort.stacktraces.repoProbe"; //$NON-NLS-1$
    private static final String MEMENTO_GUARD_KEY = "tormozit.comfort.stacktraces.mementoGuard"; //$NON-NLS-1$
    private static final String FOLDER_TAB_PROBE_KEY = "tormozit.comfort.stacktraces.folderTabProbe"; //$NON-NLS-1$
    private static final String LIST_LOG_TOPIC = "stacktraces-list"; //$NON-NLS-1$
    /** Тип дочернего узла memento = {@code IStacktrace.class.getSimpleName()}. */
    private static final String MEMENTO_STACKTRACE_TYPE = "IStacktrace"; //$NON-NLS-1$
    private static final java.util.Set<IViewPart> MEMENTO_GUARD_VIEWS =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean REPO_LISTENER_WATCH_INSTALLED;
    /** Максимум символов в заголовке вкладки (первая строка текста ошибки). */
    private static final int TAB_TITLE_MAX_CHARS = 30;
    /** Максимальная ширина Combo выбора проекта, px. */
    private static final int PROJECT_COMBO_MAX_WIDTH = 300;

    private static final java.util.Set<IViewPart> COPY_HANDLER_INSTALLED =
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
        // До createPartControl/restoreState: init() регистрирует view как listener репозитория.
        installSingletonRepoListenerWatch();
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
        listLog("tryInstallView view@" + System.identityHashCode(view) //$NON-NLS-1$
                + " " + shortStack()); //$NON-NLS-1$
        tryInstallCopy(view);
        installMementoGuard(view);
        installRepositoryProbe(view);
        hookViewPages(view);
    }

    private static void hookViewPages(IViewPart view)
    {
        Object folderObj = Global.invoke(view, "getPageContainer"); //$NON-NLS-1$
        if (!(folderObj instanceof CTabFolder folder) || folder.isDisposed())
        {
            listLog("hookViewPages: no folder view@" + System.identityHashCode(view)); //$NON-NLS-1$
            return;
        }

        listLog("hookViewPages tabs=" + folder.getItemCount() //$NON-NLS-1$
                + " folder@" + System.identityHashCode(folder) //$NON-NLS-1$
                + " listeners=" + Boolean.TRUE.equals(folder.getData(FOLDER_LISTENERS_KEY)) //$NON-NLS-1$
                + " " + shortStack()); //$NON-NLS-1$
        installFolderTabProbe(folder);
        StacktracesListPane listPane = installListPane(view, folder);
        updateFolderTabTitles(folder);
        if (listPane != null)
            listPane.refreshFromFolder();
        enhanceActivePage(view, folder);

        if (Boolean.TRUE.equals(folder.getData(FOLDER_LISTENERS_KEY)))
            return;
        folder.setData(FOLDER_LISTENERS_KEY, Boolean.TRUE);
        listLog("hookViewPages: Selection listener installed tabs=" + folder.getItemCount()); //$NON-NLS-1$

        folder.addListener(SWT.Selection, event ->
        {
            updateFolderTabTitles(folder);
            StacktracesListPane pane = listPaneOf(folder);
            if (pane != null)
                pane.syncSelectionFromFolder();
            enhanceActivePage(view, folder);
        });
    }

    private static StacktracesListPane listPaneOf(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return null;
        Object data = folder.getData(LIST_PANE_KEY);
        return data instanceof StacktracesListPane pane ? pane : null;
    }

    private static StacktracesListPane installListPane(IViewPart view, CTabFolder folder)
    {
        if (Boolean.TRUE.equals(folder.getData(LIST_INSTALLING_KEY)))
        {
            listLog("installListPane: reentrant skip tabs=" + folder.getItemCount()); //$NON-NLS-1$
            return listPaneOf(folder);
        }

        if (folder.getData(LIST_PANE_KEY) instanceof StacktracesListPane existing)
        {
            if (existing.isAlive(folder))
            {
                listLog("installListPane: reuse alive tabs=" + folder.getItemCount()); //$NON-NLS-1$
                return existing;
            }
            listLog("installListPane: dead pane -> lift tabs=" + folder.getItemCount()); //$NON-NLS-1$
            liftFolderOutOfListSash(folder);
            folder.setData(LIST_PANE_KEY, null);
        }

        Composite parent = folder.getParent();
        if (parent == null || parent.isDisposed())
        {
            listLog("installListPane: no parent"); //$NON-NLS-1$
            return null;
        }
        folder.setData(LIST_INSTALLING_KEY, Boolean.TRUE);
        try
        {
            listLog("installListPane: create sash tabs=" + folder.getItemCount() //$NON-NLS-1$
                    + " parent=" + parent.getClass().getSimpleName() //$NON-NLS-1$
                    + " " + shortStack()); //$NON-NLS-1$
            StacktracesListPane pane = StacktracesListPane.install(view, parent, folder);
            folder.setData(LIST_PANE_KEY, pane);
            listLog("installListPane: done tabs=" + folder.getItemCount()); //$NON-NLS-1$
            return pane;
        }
        catch (Exception e)
        {
            listLog("installListPane: FAIL " + e); //$NON-NLS-1$
            Global.log("StacktracesViewInteraction", "installListPane: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        finally
        {
            if (!folder.isDisposed())
                folder.setData(LIST_INSTALLING_KEY, null);
        }
    }

    /** Вынуть {@code folder} из наших (в т.ч. вложенных) SashForm и уничтожить их. */
    private static void liftFolderOutOfListSash(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        Composite parent = folder.getParent();
        int lifted = 0;
        while (parent instanceof SashForm sash && Boolean.TRUE.equals(sash.getData(LIST_SASH_KEY)))
        {
            Composite up = sash.getParent();
            if (up == null || up.isDisposed())
                break;
            listLog("liftFolder: setParent out of sash#" + lifted //$NON-NLS-1$
                    + " tabs=" + folder.getItemCount()); //$NON-NLS-1$
            folder.setParent(up);
            if (!sash.isDisposed())
                sash.dispose();
            parent = up;
            lifted++;
        }
        if (lifted > 0)
            listLog("liftFolder: done lifted=" + lifted + " tabs=" + folder.getItemCount()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void listLog(String text)
    {
        Global.tempLog(LIST_LOG_TOPIC, text);
    }

    private static String shortStack()
    {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 2; i < st.length && n < 14; i++)
        {
            String cn = st[i].getClassName();
            if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || cn.startsWith("org.eclipse.swt.") || cn.startsWith("org.eclipse.jface.")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            if (n > 0)
                sb.append(" <- "); //$NON-NLS-1$
            String simple = cn;
            int dot = cn.lastIndexOf('.');
            if (dot >= 0)
                simple = cn.substring(dot + 1);
            sb.append(simple).append('.').append(st[i].getMethodName()).append(':')
                    .append(st[i].getLineNumber());
            n++;
        }
        return sb.toString();
    }

    private static int repoSizeOf(Object repository)
    {
        if (repository == null)
            return -1;
        Object all = Global.invoke(repository, "getStacktraces"); //$NON-NLS-1$
        return all instanceof List<?> list ? list.size() : -1;
    }

    private static String stacktraceLogId(Object stacktrace)
    {
        if (stacktrace == null)
            return "null"; //$NON-NLS-1$
        String detail = ""; //$NON-NLS-1$
        if (stacktrace instanceof IStacktrace st)
        {
            String d = st.getDetail();
            if (d != null)
                detail = d.strip();
            String name = st.getName();
            if (name == null)
                name = ""; //$NON-NLS-1$
            return Integer.toHexString(System.identityHashCode(st)) + " '" + name + "' " + detail; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return Integer.toHexString(System.identityHashCode(stacktrace));
    }

    /**
     * (1) save — в memento только уникальные по ключу «ошибка+дата», живой репозиторий не трогаем.
     * (2) load — если репозиторий уже не пуст, повторный add из memento пропускаем и восстанавливаем
     * вкладки из текущего репозитория (иначе singleton + load без очистки удваивает список).
     * Guard ставится в {@link #installSingletonRepoListenerWatch} на init() view — до createPartControl.
     */
    private static void installMementoGuard(IViewPart view)
    {
        if (view == null)
            return;
        Object already = Global.getField(view, "mementoManager"); //$NON-NLS-1$
        if (already == null)
            return;
        try
        {
            if (Proxy.isProxyClass(already.getClass()))
            {
                listLog("mementoGuard: already proxy view@" + System.identityHashCode(view)); //$NON-NLS-1$
                return;
            }
            if (!MEMENTO_GUARD_VIEWS.add(view))
            {
                listLog("mementoGuard: already tracked view@" + System.identityHashCode(view)); //$NON-NLS-1$
                return;
            }

            ClassLoader cl = already.getClass().getClassLoader();
            Class<?> mementoIface = Class.forName(
                    "com._1c.g5.v8.dt.internal.stacktraces.ui.view.IStacktracesMemento", true, cl); //$NON-NLS-1$
            Class<?> repoIface = Class.forName(
                    "com._1c.g5.v8.dt.stacktraces.model.IStacktraceRepository", true, cl); //$NON-NLS-1$
            Object real = already;
            Object repo = Global.getField(real, "repository"); //$NON-NLS-1$
            InvocationHandler handler = (proxy, method, args) ->
            {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class)
                {
                    if ("equals".equals(name)) //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    if ("hashCode".equals(name)) //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    if ("toString".equals(name)) //$NON-NLS-1$
                        return "ComfortMementoGuard(" + real + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    return method.invoke(real, args);
                }
                if ("save".equals(name) && args != null && args.length == 1 && args[0] instanceof IMemento memento) //$NON-NLS-1$
                {
                    int beforeKids = countMementoStacktraces(memento);
                    int cleared = clearMementoStacktraceChildren(memento);
                    Object liveRepo = Global.getField(real, "repository"); //$NON-NLS-1$
                    Object allObj = liveRepo != null ? Global.invoke(liveRepo, "getStacktraces") : null; //$NON-NLS-1$
                    List<?> all = allObj instanceof List<?> list ? list : List.of();
                    List<Object> unique = uniqueStacktracesForPersist(all);
                    listLog("memento.save beforeKids=" + beforeKids //$NON-NLS-1$
                            + " clearedKids=" + cleared //$NON-NLS-1$
                            + " repo=" + all.size() //$NON-NLS-1$
                            + " unique=" + unique.size() //$NON-NLS-1$
                            + " " + shortStack()); //$NON-NLS-1$
                    Object filterRepo = filteringRepositoryProxy(liveRepo, unique, repoIface, cl);
                    Global.setFieldForce(real, "repository", filterRepo); //$NON-NLS-1$
                    try
                    {
                        Object result = method.invoke(real, args);
                        listLog("memento.save afterKids=" + countMementoStacktraces(memento)); //$NON-NLS-1$
                        return result;
                    }
                    finally
                    {
                        Global.setFieldForce(real, "repository", liveRepo); //$NON-NLS-1$
                    }
                }
                if ("load".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                {
                    IMemento memento = args[0] instanceof IMemento m ? m : null;
                    Object liveRepo = Global.getField(real, "repository"); //$NON-NLS-1$
                    int kids = countMementoStacktraces(memento);
                    int repoBefore = repoSizeOf(liveRepo);
                    if (repoBefore > 0)
                    {
                        listLog("memento.load SKIP kids=" + kids //$NON-NLS-1$
                                + " repo=" + repoBefore //$NON-NLS-1$
                                + " " + shortStack()); //$NON-NLS-1$
                        recreatePagesFromRepository(view, liveRepo);
                        return null;
                    }
                    listLog("memento.load kids=" + kids //$NON-NLS-1$
                            + " repoBefore=" + repoBefore //$NON-NLS-1$
                            + " " + shortStack()); //$NON-NLS-1$
                    Object result = method.invoke(real, args);
                    listLog("memento.load afterRepo=" + repoSizeOf(liveRepo)); //$NON-NLS-1$
                    return result;
                }
                return method.invoke(real, args);
            };
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] { mementoIface }, handler);
            Global.setFieldForce(view, "mementoManager", proxy); //$NON-NLS-1$
            listLog("mementoGuard: installed view@" + System.identityHashCode(view) //$NON-NLS-1$
                    + " repoSize=" + repoSizeOf(repo)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            MEMENTO_GUARD_VIEWS.remove(view);
            listLog("mementoGuard: FAIL " + e); //$NON-NLS-1$
        }
    }

    /**
     * Перехват {@code addChangedListener} через подмену списка listeners singleton-репозитория:
     * init() view вызывает его до createPartControl/restoreState — успеваем обернуть mementoManager.
     */
    private static void installSingletonRepoListenerWatch()
    {
        if (REPO_LISTENER_WATCH_INSTALLED)
            return;
        try
        {
            Bundle bundle = Platform.getBundle(STACKTRACES_BUNDLE);
            if (bundle == null)
            {
                listLog("repoListenerWatch: bundle null"); //$NON-NLS-1$
                return;
            }
            Class<?> pluginClass = bundle.loadClass(STACKTRACES_PLUGIN);
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            if (plugin == null)
            {
                listLog("repoListenerWatch: plugin null"); //$NON-NLS-1$
                return;
            }
            Object injectorObj = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            if (!(injectorObj instanceof Injector injector))
            {
                listLog("repoListenerWatch: injector null"); //$NON-NLS-1$
                return;
            }
            Class<?> repoIface = bundle.loadClass(
                    "com._1c.g5.v8.dt.stacktraces.model.IStacktraceRepository"); //$NON-NLS-1$
            Object repo = injector.getInstance(repoIface);
            if (repo == null)
            {
                listLog("repoListenerWatch: repo null"); //$NON-NLS-1$
                return;
            }
            Object listenersObj = Global.getField(repo, "listeners"); //$NON-NLS-1$
            if (!(listenersObj instanceof java.util.List<?> existing))
            {
                listLog("repoListenerWatch: listeners field missing"); //$NON-NLS-1$
                return;
            }
            @SuppressWarnings({ "rawtypes", "unchecked" })
            java.util.concurrent.CopyOnWriteArrayList wrapped =
                    new java.util.concurrent.CopyOnWriteArrayList(existing)
                    {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public boolean add(Object listener)
                        {
                            boolean added = super.add(listener);
                            tryAttachMementoGuardFromRepoListener(listener);
                            return added;
                        }
                    };
            if (!Global.setFieldForce(repo, "listeners", wrapped)) //$NON-NLS-1$
            {
                listLog("repoListenerWatch: set listeners FAIL"); //$NON-NLS-1$
                return;
            }
            REPO_LISTENER_WATCH_INSTALLED = true;
            listLog("repoListenerWatch: installed existingListeners=" + wrapped.size()); //$NON-NLS-1$
            for (Object listener : wrapped)
                tryAttachMementoGuardFromRepoListener(listener);
        }
        catch (Exception e)
        {
            listLog("repoListenerWatch: FAIL " + e); //$NON-NLS-1$
        }
    }

    private static void tryAttachMementoGuardFromRepoListener(Object listener)
    {
        if (!(listener instanceof IViewPart view))
            return;
        try
        {
            if (view.getViewSite() == null || !VIEW_ID.equals(view.getViewSite().getId()))
                return;
        }
        catch (Exception e)
        {
            return;
        }
        installMementoGuard(view);
        // Регистрация view как listener'а репозитория (перехваченная здесь) — надёжный сигнал о
        // (пере)создании панели, срабатывает даже когда IPartListener2 (partOpened/partVisible/...)
        // по какой-то причине не переустанавливает hookViewPages/installListPane при закрытии-
        // переоткрытии штатного вида (закрытие+переоткрытие иначе оставляет нашу панель не установленной,
        // см. topic "stacktraces-list"). На этом этапе createPartControl/restoreState ещё не закончены
        // (страниц/CTabFolder может не быть) — откладываем на конец текущего цикла UI-потока.
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() ->
            {
                listLog("repoListenerWatch: deferred tryInstallView view@" //$NON-NLS-1$
                        + System.identityHashCode(view));
                tryInstallView(view);
            });
        }
    }

    /** Ключ как у CONTENT_DUP в refresh: первая строка ошибки + detail (дата). */
    private static String stacktracePersistKey(Object stacktraceObj)
    {
        if (stacktraceObj instanceof IStacktrace stacktrace)
        {
            String error = BreakpointListHook.firstLine(findStacktraceErrorText(stacktrace));
            if (error == null || error.isBlank())
            {
                String name = stacktrace.getName();
                error = name != null ? name : ""; //$NON-NLS-1$
            }
            String date = stacktrace.getDetail();
            if (date != null)
                date = date.strip();
            else
                date = ""; //$NON-NLS-1$
            return error + '\t' + date;
        }
        if (stacktraceObj == null)
            return ""; //$NON-NLS-1$
        String name = String.valueOf(Global.invoke(stacktraceObj, "getName")); //$NON-NLS-1$
        String detail = String.valueOf(Global.invoke(stacktraceObj, "getDetail")); //$NON-NLS-1$
        return name + '\t' + detail;
    }

    private static List<Object> uniqueStacktracesForPersist(List<?> all)
    {
        java.util.LinkedHashMap<String, Object> first = new java.util.LinkedHashMap<>();
        for (Object st : all)
        {
            if (st == null)
                continue;
            first.putIfAbsent(stacktracePersistKey(st), st);
        }
        return new ArrayList<>(first.values());
    }

    private static Object filteringRepositoryProxy(Object liveRepo, List<Object> unique,
            Class<?> repoIface, ClassLoader cl)
    {
        List<Object> snapshot = List.copyOf(unique);
        return Proxy.newProxyInstance(cl, new Class<?>[] { repoIface }, (proxy, method, args) ->
        {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class)
            {
                if ("equals".equals(name)) //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                if ("hashCode".equals(name)) //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                if ("toString".equals(name)) //$NON-NLS-1$
                    return "ComfortSaveFilterRepo(size=" + snapshot.size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                return method.invoke(liveRepo, args);
            }
            if ("getStacktraces".equals(name)) //$NON-NLS-1$
                return new ArrayList<>(snapshot);
            return method.invoke(liveRepo, args);
        });
    }

    private static void recreatePagesFromRepository(IViewPart view, Object repository)
    {
        if (view == null || repository == null)
            return;
        Object all = Global.invoke(repository, "getStacktraces"); //$NON-NLS-1$
        if (!(all instanceof List<?> list))
            return;
        int created = 0;
        for (Object st : list)
        {
            if (st == null)
                continue;
            Global.invokeVoid(view, "addStacktrace", st); //$NON-NLS-1$
            created++;
        }
        listLog("memento.load recreatedPages=" + created + " repo=" + list.size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int countMementoStacktraces(IMemento memento)
    {
        if (memento == null)
            return -1;
        IMemento[] kids = memento.getChildren(MEMENTO_STACKTRACE_TYPE);
        return kids != null ? kids.length : 0;
    }

    /** Удаляет дочерние {@code IStacktrace} из XMLMemento через DOM (публичного remove нет). */
    private static int clearMementoStacktraceChildren(IMemento memento)
    {
        if (memento == null)
            return 0;
        Object elementObj = Global.getField(memento, "element"); //$NON-NLS-1$
        if (!(elementObj instanceof Element element))
            return 0;
        List<Node> toRemove = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Node node = nodes.item(i);
            if (node != null && node.getNodeType() == Node.ELEMENT_NODE
                    && MEMENTO_STACKTRACE_TYPE.equals(node.getNodeName()))
                toRemove.add(node);
        }
        for (Node node : toRemove)
            element.removeChild(node);
        return toRemove.size();
    }

    /**
     * Зонд repository: логирует add/remove/addChangedListener (двойная регистрация listener =
     * два addPage на один add). Ставится при первом hook view.
     */
    private static void installRepositoryProbe(IViewPart view)
    {
        if (view == null)
            return;
        Object already = Global.getField(view, "repository"); //$NON-NLS-1$
        if (already == null)
            return;
        try
        {
            if (Proxy.isProxyClass(already.getClass()))
            {
                listLog("repoProbe: already proxy view@" + System.identityHashCode(view)); //$NON-NLS-1$
                return;
            }
            Object pageContainer = Global.invoke(view, "getPageContainer"); //$NON-NLS-1$
            if (pageContainer instanceof Control marked
                    && Boolean.TRUE.equals(marked.getData(REPO_PROBE_KEY)))
                return;

            ClassLoader cl = already.getClass().getClassLoader();
            Class<?> repoIface = Class.forName(
                    "com._1c.g5.v8.dt.stacktraces.model.IStacktraceRepository", true, cl); //$NON-NLS-1$
            Class<?> listenerIface = Class.forName(
                    "com._1c.g5.v8.dt.stacktraces.model.IStacktraceRepositoryChangedListener", true, cl); //$NON-NLS-1$
            Object real = already;
            InvocationHandler handler = (proxy, method, args) ->
            {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class)
                {
                    if ("equals".equals(name)) //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    if ("hashCode".equals(name)) //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    if ("toString".equals(name)) //$NON-NLS-1$
                        return "ComfortRepoProbe(" + real + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    return method.invoke(real, args);
                }
                if ("addChangedListener".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                {
                    Object listener = args[0];
                    listLog("repo.addChangedListener listener@" + System.identityHashCode(listener) //$NON-NLS-1$
                            + " " + (listener != null ? listener.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                            + " beforeListeners=" + listenerCount(real) //$NON-NLS-1$
                            + " " + shortStack()); //$NON-NLS-1$
                    Object result = method.invoke(real, args);
                    listLog("repo.addChangedListener afterListeners=" + listenerCount(real)); //$NON-NLS-1$
                    return result;
                }
                if ("removeChangedListener".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                {
                    Object listener = args[0];
                    listLog("repo.removeChangedListener listener@" + System.identityHashCode(listener) //$NON-NLS-1$
                            + " beforeListeners=" + listenerCount(real)); //$NON-NLS-1$
                    Object result = method.invoke(real, args);
                    listLog("repo.removeChangedListener afterListeners=" + listenerCount(real)); //$NON-NLS-1$
                    return result;
                }
                if (("add".equals(name) || "remove".equals(name)) && args != null && args.length == 1) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    listLog("repo." + name + " st=" + stacktraceLogId(args[0]) //$NON-NLS-1$ //$NON-NLS-2$
                            + " beforeSize=" + repoSizeOf(real) //$NON-NLS-1$
                            + " " + shortStack()); //$NON-NLS-1$
                }
                Object result = method.invoke(real, args);
                if ("add".equals(name) || "remove".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                    listLog("repo." + name + " afterSize=" + repoSizeOf(real)); //$NON-NLS-1$ //$NON-NLS-2$
                return result;
            };
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] { repoIface }, handler);
            Global.setFieldForce(view, "repository", proxy); //$NON-NLS-1$
            if (pageContainer instanceof Control marked)
                marked.setData(REPO_PROBE_KEY, Boolean.TRUE);
            installDiagnosticRepoListener(real, cl, listenerIface);
            listLog("repoProbe: installed view@" + System.identityHashCode(view) //$NON-NLS-1$
                    + " size=" + repoSizeOf(real) //$NON-NLS-1$
                    + " listeners~=" + listenerCount(real)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            listLog("repoProbe: FAIL " + e); //$NON-NLS-1$
        }
    }

    private static int listenerCount(Object realRepo)
    {
        Object listenersObj = Global.getField(realRepo, "listeners"); //$NON-NLS-1$
        return listenersObj instanceof java.util.List<?> list ? list.size() : -1;
    }

    /** Отдельный listener только для лога — не подменяет штатный (иначе dispose не снимет). */
    private static void installDiagnosticRepoListener(Object realRepo, ClassLoader cl, Class<?> listenerIface)
    {
        Object listenersObj = Global.getField(realRepo, "listeners"); //$NON-NLS-1$
        if (listenersObj instanceof java.util.List<?> list)
        {
            for (Object listener : list)
            {
                if (listener != null && Proxy.isProxyClass(listener.getClass())
                        && String.valueOf(listener).contains("ComfortRepoDiag")) //$NON-NLS-1$
                    return;
            }
            listLog("repoProbe: existingListeners=" + list.size()); //$NON-NLS-1$
            for (Object listener : list)
            {
                if (listener != null)
                    listLog("repoProbe: listener@" + System.identityHashCode(listener) //$NON-NLS-1$
                            + " " + listener.getClass().getName()); //$NON-NLS-1$
            }
        }
        Object diag = Proxy.newProxyInstance(cl, new Class<?>[] { listenerIface },
                (lp, lm, largs) ->
                {
                    if (lm.getDeclaringClass() == Object.class)
                    {
                        if ("equals".equals(lm.getName())) //$NON-NLS-1$
                            return Boolean.valueOf(lp == largs[0]);
                        if ("hashCode".equals(lm.getName())) //$NON-NLS-1$
                            return Integer.valueOf(System.identityHashCode(lp));
                        if ("toString".equals(lm.getName())) //$NON-NLS-1$
                            return "ComfortRepoDiag"; //$NON-NLS-1$
                        return null;
                    }
                    if ("repositoryChanged".equals(lm.getName()) && largs != null && largs.length == 1) //$NON-NLS-1$
                    {
                        Object ev = largs[0];
                        String evName = ev != null ? ev.getClass().getSimpleName() : "null"; //$NON-NLS-1$
                        Object st = Global.invoke(ev, "getStacktrace"); //$NON-NLS-1$
                        listLog("repo.repositoryChanged(diag) " + evName //$NON-NLS-1$
                                + " st=" + stacktraceLogId(st) //$NON-NLS-1$
                                + " repoSize=" + repoSizeOf(realRepo) //$NON-NLS-1$
                                + " listeners=" + listenerCount(realRepo) //$NON-NLS-1$
                                + " " + shortStack()); //$NON-NLS-1$
                    }
                    return null;
                });
        Global.invoke(realRepo, "addChangedListener", diag); //$NON-NLS-1$
        listLog("repoProbe: diagListener added listeners=" + listenerCount(realRepo)); //$NON-NLS-1$
    }

    /** Лог при появлении/удалении CTabItem (Dispose на item + периодический контроль через refresh). */
    private static void installFolderTabProbe(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        if (Boolean.TRUE.equals(folder.getData(FOLDER_TAB_PROBE_KEY)))
            return;
        folder.setData(FOLDER_TAB_PROBE_KEY, Boolean.TRUE);
        int[] lastCount = { folder.getItemCount() };
        folder.addListener(SWT.Resize, e ->
        {
            if (folder.isDisposed())
                return;
            int now = folder.getItemCount();
            if (now != lastCount[0])
            {
                listLog("folder.tabsChanged " + lastCount[0] + "->" + now //$NON-NLS-1$ //$NON-NLS-2$
                        + " " + shortStack()); //$NON-NLS-1$
                lastCount[0] = now;
                wireTabItemDisposeProbes(folder, lastCount);
            }
        });
        folder.addDisposeListener(e -> listLog("folder.dispose tabsWere=" + lastCount[0])); //$NON-NLS-1$
        wireTabItemDisposeProbes(folder, lastCount);
        listLog("folderTabProbe: installed tabs=" + lastCount[0]); //$NON-NLS-1$
    }

    private static void wireTabItemDisposeProbes(CTabFolder folder, int[] lastCount)
    {
        if (folder == null || folder.isDisposed())
            return;
        for (CTabItem item : folder.getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            if (Boolean.TRUE.equals(item.getData(FOLDER_TAB_PROBE_KEY)))
                continue;
            item.setData(FOLDER_TAB_PROBE_KEY, Boolean.TRUE);
            final CTabItem watched = item;
            item.addDisposeListener(e ->
            {
                int after = folder.isDisposed() ? -1 : folder.getItemCount();
                listLog("tab.dispose text='" + safeTabText(watched) + "' tabsAfter=" + after //$NON-NLS-1$ //$NON-NLS-2$
                        + " " + shortStack()); //$NON-NLS-1$
                if (!folder.isDisposed())
                    lastCount[0] = folder.getItemCount();
            });
        }
        // Нет штатного ItemAdded — ловим рост через async после Selection/refresh и Resize выше.
        int now = folder.getItemCount();
        if (now > lastCount[0])
        {
            listLog("folder.tabsGrew " + lastCount[0] + "->" + now); //$NON-NLS-1$ //$NON-NLS-2$
            lastCount[0] = now;
        }
    }

    private static String safeTabText(CTabItem item)
    {
        try
        {
            if (item == null || item.isDisposed())
                return "?"; //$NON-NLS-1$
            String t = item.getText();
            return t != null ? t : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "?"; //$NON-NLS-1$
        }
    }

    /**
     * Заголовки вкладок: обрезка первой строки ошибки (без резолва модулей — для всех вкладок).
     * Список слева не обновляем здесь — иначе Selection сбрасывает мультивыбор.
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
        String detail = stacktrace.getDetail();
        if (detail != null)
            detail = detail.strip();
        if (detail != null && detail.isEmpty())
            detail = null;

        String full = BreakpointListHook.firstLine(findStacktraceErrorText(stacktrace));
        if (full.isEmpty())
        {
            // Нет текста ошибки — штатное имя, дату всё равно в одну строку (сжатие вкладок).
            String name = stacktrace.getName();
            if (name == null || name.isBlank())
                name = BreakpointListHook.firstLine(item.getText());
            if (name == null || name.isBlank())
                return;
            String title = detail == null ? name : name + ' ' + detail;
            if (!title.equals(item.getText()))
                item.setText(title);
            return;
        }

        String shortError;
        boolean truncated = full.length() > TAB_TITLE_MAX_CHARS;
        if (truncated)
            shortError = full.substring(0, TAB_TITLE_MAX_CHARS) + "..."; //$NON-NLS-1$
        else
            shortError = full;
        // Одна строка: сжатый CTabFolder рисует только первую; перевод строки перед датой
        // виден лишь в меню переполнения вкладок.
        String title = detail == null ? shortError : shortError + ' ' + detail;
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

        boolean already = Boolean.TRUE.equals(page.getData(PAGE_ENHANCED_KEY));
        listLog("enhanceActivePage tabs=" + folder.getItemCount() //$NON-NLS-1$
                + " alreadyEnhanced=" + already //$NON-NLS-1$
                + " page@" + System.identityHashCode(page)); //$NON-NLS-1$
        if (already)
            refreshPageProjects(page);
        else
            enhancePage(page);
        ensureFrameStatusLabelProvider(page);

        // Если enhance уже был при нулевом размере — дерево осталось 0×0.
        Tree tree = findTree(page);
        if (tree != null && !tree.isDisposed()
                && (tree.getSize().x <= 0 || tree.getSize().y <= 0))
            schedulePageRelayout(page);

        // afterPageChange снова регистрирует страницу как selection listener —
        // снимаем сейчас и ещё раз async (addPage вызывает afterPageChange после
        // Selection от setActivePage). Провайдер иконок тоже может сброситься —
        // «Анализировать» создаёт страницу до готового TreeViewer.
        suppressPageSelectionOpen(view, page);
        scheduleEnsureFrameIcons(page);
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
        StacktracesListPane pane = listPaneOf(findFolder(page));
        if (pane != null)
            pane.refreshFromFolder();
    }

    private static void enhancePage(Composite page)
    {
        if (page == null || page.isDisposed())
            return;
        if (Boolean.TRUE.equals(page.getData(PAGE_ENHANCED_KEY)))
        {
            listLog("enhancePage: skip already page@" + System.identityHashCode(page)); //$NON-NLS-1$
            return;
        }

        IStacktrace stacktrace = resolveStacktrace(page);
        Tree tree = findTree(page);
        if (tree == null)
        {
            listLog("enhancePage: no tree page@" + System.identityHashCode(page) //$NON-NLS-1$
                    + " st=" + (stacktrace != null ? Integer.toHexString(System.identityHashCode(stacktrace)) : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        listLog("enhancePage: start page@" + System.identityHashCode(page) //$NON-NLS-1$
                + " st=" + (stacktrace != null ? Integer.toHexString(System.identityHashCode(stacktrace)) : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " " + shortStack()); //$NON-NLS-1$

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
        // Сразу, до combo/viewer: иначе повторный enhanceActivePage удвоит шапку «Проект».
        page.setData(PAGE_ENHANCED_KEY, Boolean.TRUE);

        installProjectCombo(page, tree, state);
        installFrameStatusLabelProvider(page, state);
        page.addDisposeListener(e -> PAGE_STATES.remove(page));
        scheduleEnsureFrameIcons(page);
        StacktracesListPane pane = listPaneOf(findFolder(page));
        if (pane != null)
            pane.refreshFromFolder();
        listLog("enhancePage: done page@" + System.identityHashCode(page)); //$NON-NLS-1$
    }

    /**
     * «Анализировать» / addPage: {@code viewer} на странице может появиться позже первого
     * {@link #enhancePage}, либо штатный код снова ставит свой label provider. Повторяем
     * обёртку {@link FrameStatusLabelProvider} (иконка корня — {@code runtime_debug_target}).
     */
    private static void scheduleEnsureFrameIcons(Composite page)
    {
        if (page == null || page.isDisposed())
            return;
        Display display = page.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Runnable retry = () ->
        {
            if (!page.isDisposed())
                ensureFrameStatusLabelProvider(page);
        };
        display.asyncExec(retry);
        display.timerExec(50, retry);
        display.timerExec(200, retry);
        display.timerExec(500, retry);
    }

    /** @return {@code true}, если на дереве уже наш провайдер иконок */
    private static boolean ensureFrameStatusLabelProvider(Composite page)
    {
        if (page == null || page.isDisposed())
            return false;
        Object data = page.getData(PAGE_STATE_KEY);
        if (!(data instanceof PageState state))
        {
            Global.tempLog("stacktraces-icons", "ensure: no PageState"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        return installFrameStatusLabelProvider(page, state);
    }

    private static boolean installFrameStatusLabelProvider(Composite page, PageState state)
    {
        Object viewerObj = Global.getField(page, "viewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer))
        {
            Global.tempLog("stacktraces-icons", "install: viewer not ready"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        IBaseLabelProvider current = viewer.getLabelProvider();
        if (current instanceof FrameStatusLabelProvider)
        {
            state.treeViewer = viewer;
            return true;
        }
        FrameStatusLabelProvider wrapped = new FrameStatusLabelProvider(current, state, page);
        viewer.setLabelProvider(wrapped);
        state.treeViewer = viewer;
        viewer.refresh();
        Global.tempLog("stacktraces-icons", "install: wrapped + refresh"); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
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
            StacktracesListPane pane = listPaneOf(findFolder(page));
            if (pane != null)
                pane.refreshFromFolder();
        });

        // «Анализировать» → showView/addPage: workbench ещё не доложил размеры страницы.
        // Синхронный layout при clientArea 0×0 оставляет дерево нулевой высоты — пустая
        // панель до смены вкладки. Откладываем layout до ненулевого размера.
        schedulePageRelayout(page);
    }

    private static CTabFolder findFolder(Control control)
    {
        Control current = control;
        while (current != null && !current.isDisposed())
        {
            if (current instanceof CTabFolder folder)
                return folder;
            current = current.getParent();
        }
        return null;
    }

    /**
     * Layout страницы вкладки после того, как {@code CTabFolder}/workbench задаст ей
     * ненулевой clientArea (сразу, async и одноразовый {@link SWT#Resize}).
     */
    private static void schedulePageRelayout(Composite page)
    {
        if (page == null || page.isDisposed())
            return;
        Runnable layoutOnce = () ->
        {
            if (page.isDisposed())
                return;
            org.eclipse.swt.graphics.Rectangle ca = page.getClientArea();
            if (ca.width <= 0 || ca.height <= 0)
                return;
            page.layout(true, true);
            Composite parent = page.getParent();
            if (parent != null && !parent.isDisposed())
                parent.layout(true, true);
        };
        layoutOnce.run();
        Display display = page.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(layoutOnce);
        if (page.getClientArea().width > 0 && page.getClientArea().height > 0)
            return;
        Listener[] resizeHold = new Listener[1];
        resizeHold[0] = e ->
        {
            if (page.isDisposed())
                return;
            if (page.getClientArea().width <= 0 || page.getClientArea().height <= 0)
                return;
            page.removeListener(SWT.Resize, resizeHold[0]);
            layoutOnce.run();
        };
        page.addListener(SWT.Resize, resizeHold[0]);
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

    /**
     * Левая панель списка трассировок: фильтр + таблица; вкладки CTabFolder скрыты
     * ({@code setTabHeight(0)}), активация/удаление строк синхронизированы со страницами.
     */
    private static final class StacktracesListPane
    {
        private static final int COL_DATE = 0;
        private static final int COL_ERROR = 1;
        private static final int COL_PROJECT = 2;

        /** Текст ячейки по элементу модели — общий для копирования и отбора по значению. */
        private static String stackRowText(Object element, int column)
        {
            if (!(element instanceof StackRow row))
                return ""; //$NON-NLS-1$
            if (column == COL_ERROR)
                return row.error;
            if (column == COL_DATE)
                return row.date;
            if (column == COL_PROJECT)
                return row.project;
            return ""; //$NON-NLS-1$
        }

        private static final int DEFAULT_ERROR_WIDTH = 220;
        private static final int DEFAULT_DATE_WIDTH = 160;
        private static final int DEFAULT_PROJECT_WIDTH = 120;
        private static final int MIN_COL_WIDTH = 40;
        private static final int DEFAULT_SASH_LEFT = 38;
        private static final int DEFAULT_SASH_RIGHT = 62;
        /** Второстепенные данные (в отличие от выбора проекта на стек — тот в {@link ComfortSettings})
         * — ширины/порядок/режим заполнения колонок таблицы и положение разделителя, персистятся в
         * {@link IDialogSettings} при закрытии панели (см. {@link #saveColumnLayout}), как у остальных
         * окон плагина. */
        private static final String SETTINGS_SECTION = "StacktracesListPane"; //$NON-NLS-1$
        private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
        private static final String KEY_COL_DATE_WIDTH = "colDateWidth"; //$NON-NLS-1$
        private static final String KEY_COL_ERROR_WIDTH = "colErrorWidth"; //$NON-NLS-1$
        private static final String KEY_COL_PROJECT_WIDTH = "colProjectWidth"; //$NON-NLS-1$
        private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
        private static final String KEY_SASH_LEFT = "sashLeft"; //$NON-NLS-1$
        private static final String KEY_SASH_RIGHT = "sashRight"; //$NON-NLS-1$
        /** Выделенная строка (стек) на момент закрытия — восстанавливается при следующем открытии по
         * содержимому колонки «Дата» (см. {@link StackRow#date}), т.к. сам {@link CTabItem} не переживает
         * закрытие/переоткрытие панели. */
        private static final String KEY_SELECTED_STACK_DATE = "selectedStackDate"; //$NON-NLS-1$

        private final IViewPart view;
        private final CTabFolder folder;
        private final SashForm sash;
        private final TableViewer viewer;
        private final FormTableInteraction interaction;
        private final FilterInputBox filterInput;
        private final TableColumn errorColumn;
        private final TableColumn dateColumn;
        private final TableColumn projectColumn;
        private final DateSortComparator comparator = new DateSortComparator();
        private final ListFilter listFilter = new ListFilter();

        private boolean syncingSelection;
        private String filterText = ""; //$NON-NLS-1$
        /** Восстановление выделения, сохранённого при закрытии — пробуем не более одного раза за жизнь панели. */
        private boolean initialSelectionApplied;
        /** Индекс строки после Del — не прыгать на первую из‑за folder.setSelection. */
        private int deleteAnchorIndex = -1;

        static StacktracesListPane install(IViewPart view, Composite parent, CTabFolder folder)
        {
            liftFolderOutOfListSash(folder);
            parent = folder.getParent();
            if (parent == null || parent.isDisposed())
                throw new IllegalStateException("stacktraces folder has no parent"); //$NON-NLS-1$

            SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
            sash.setData(LIST_SASH_KEY, Boolean.TRUE);
            folder.setParent(sash);

            StacktracesListPane pane = new StacktracesListPane(view, sash, folder);
            pane.root.moveAbove(folder);
            folder.setTabHeight(0);
            IDialogSettings sashSettings = dialogSettings();
            int left = FormTableColumnState.readWidth(sashSettings, KEY_SASH_LEFT, DEFAULT_SASH_LEFT, 1);
            int right = FormTableColumnState.readWidth(sashSettings, KEY_SASH_RIGHT, DEFAULT_SASH_RIGHT, 1);
            if (left < 1)
                left = DEFAULT_SASH_LEFT;
            if (right < 1)
                right = DEFAULT_SASH_RIGHT;
            sash.setWeights(new int[] { left, right });
            parent.layout(true, true);
            return pane;
        }

        private final Composite root;

        boolean isAlive(CTabFolder expectedFolder)
        {
            return expectedFolder == folder
                    && folder != null && !folder.isDisposed()
                    && sash != null && !sash.isDisposed()
                    && root != null && !root.isDisposed()
                    && viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()
                    && folder.getParent() == sash
                    && root.getParent() == sash
                    && Boolean.TRUE.equals(sash.getData(LIST_SASH_KEY));
        }

        private StacktracesListPane(IViewPart view, SashForm sash, CTabFolder folder)
        {
            this.view = view;
            this.sash = sash;
            this.folder = folder;

            root = new Composite(sash, SWT.NONE);
            GridLayout rootLayout = new GridLayout(1, false);
            rootLayout.marginWidth = 0;
            rootLayout.marginHeight = 0;
            rootLayout.verticalSpacing = 2;
            root.setLayout(rootLayout);

            filterInput = FilterInputBox.forStacktraces(root, this::applyFilter);

            Composite tableStack = new Composite(root, SWT.NONE);
            tableStack.setLayout(null);
            tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            Composite columnHost = new Composite(tableStack, SWT.NONE);
            TableColumnLayout columnLayout = new TableColumnLayout(true);
            columnHost.setLayout(columnLayout);

            viewer = new TableViewer(columnHost,
                SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
            Table table = viewer.getTable();
            table.setHeaderVisible(true);
            table.setLinesVisible(true);

            // Колонка 0 не должна быть StyledCell: у SWT/Win32 у первой колонки отступ под
            // иконку, owner-draw текст оставляет «обрезок» слева (эталон: RecentPlaces/ObjectSets —
            // сначала колонка-иконка, потом styled). Дата — обычный провайдер; подсветка фильтра
            // только в «Ошибка».
            IDialogSettings settings = dialogSettings();
            dateColumn = createColumn(viewer, columnLayout, "Дата", //$NON-NLS-1$
                settings, KEY_COL_DATE_WIDTH, DEFAULT_DATE_WIDTH, COL_DATE, false);
            errorColumn = createColumn(viewer, columnLayout, "Ошибка", //$NON-NLS-1$
                settings, KEY_COL_ERROR_WIDTH, DEFAULT_ERROR_WIDTH, COL_ERROR, true);
            projectColumn = createColumn(viewer, columnLayout, "Проект", //$NON-NLS-1$
                settings, KEY_COL_PROJECT_WIDTH, DEFAULT_PROJECT_WIDTH, COL_PROJECT, false);

            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setComparator(comparator);
            viewer.addFilter(listFilter);

            Control filterKeys = filterInput.inputControl();
            if (filterKeys == null)
                filterKeys = filterInput.widget();
            FilterInputBoxListNavigation.installTableOpenOnEnter(filterKeys, table, null, () ->
            {
                IStructuredSelection sel = viewer.getStructuredSelection();
                Object first = sel.getFirstElement();
                if (first instanceof StackRow row && row.item != null && !row.item.isDisposed())
                {
                    folder.setSelection(row.item);
                    enhanceActivePage(view, folder);
                }
            });

            interaction = new FormTableInteraction(table, viewer,
                (item, column) -> stackRowText(item.getData(), column));
            // Owner-draw только у «Ошибка» (StyledCell); Date/Project — нативный текст.
            interaction.setOwnerDrawColumns(errorColumn);
            // Отбор по значению ячейки работает по элементу модели — живого TableItem там ещё нет.
            interaction.setFilterTextResolver(StacktracesListPane::stackRowText);
            // «Отключить все отборы» снимает и отбор по подстроке из поля поиска.
            interaction.setSubstringFilterClearer(() ->
            {
                if (filterInput == null || filterInput.isDisposed() || filterInput.getText().isEmpty())
                    return;
                filterInput.setText(""); //$NON-NLS-1$
                applyFilter();
            });
            boolean hasSavedColumnWidths = FormTableColumnState.hasSavedColumnWidths(settings, KEY_COL_FILL_MODE,
                KEY_COL_DATE_WIDTH, KEY_COL_ERROR_WIDTH, KEY_COL_PROJECT_WIDTH);
            interaction.install(hasSavedColumnWidths);

            tableStack.addControlListener(new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    if (!columnHost.isDisposed())
                        columnHost.setBounds(tableStack.getClientArea());
                }
            });

            viewer.addSelectionChangedListener(event ->
            {
                if (syncingSelection)
                    return;
                Object first = viewer.getStructuredSelection().getFirstElement();
                if (!(first instanceof StackRow row) || row.item == null || row.item.isDisposed())
                    return;
                syncingSelection = true;
                try
                {
                    folder.setSelection(row.item);
                    enhanceActivePage(view, folder);
                }
                finally
                {
                    syncingSelection = false;
                }
            });

            table.addKeyListener(new KeyAdapter()
            {
                @Override
                public void keyPressed(KeyEvent e)
                {
                    if (e.keyCode == SWT.DEL)
                    {
                        deleteSelected();
                        e.doit = false;
                    }
                }
            });

            installColumnSort(errorColumn, COL_ERROR);
            installColumnSort(dateColumn, COL_DATE);
            installColumnSort(projectColumn, COL_PROJECT);
            comparator.sortColumn = COL_DATE;
            comparator.descending = true;
            table.setSortColumn(dateColumn);
            table.setSortDirection(SWT.DOWN);

            // НЕ folder — при пересоздании панели (liftFolderOutOfListSash) folder специально
            // вынимается ИЗ sash (setParent) ДО sash.dispose() и переживает пересоздание, чтобы
            // его можно было переиспользовать; удаляется именно sash (а с ним root/table/колонки).
            // Слушатель на folder.dispose() в этом (самом частом) сценарии не срабатывал вовсе —
            // сохранение ширины/порядка тихо никогда не происходило.
            sash.addDisposeListener(e ->
            {
                saveColumnLayout(table);
                saveSelectedStack();
            });
        }

        /** Сохранить выделенный стек (по строке колонки «Дата») — второстепенные данные, при закрытии. */
        private void saveSelectedStack()
        {
            if (viewer == null || viewer.getControl().isDisposed())
                return;
            Object first = viewer.getStructuredSelection().getFirstElement();
            IDialogSettings settings = dialogSettings();
            if (first instanceof StackRow row && !row.date.isEmpty())
                settings.put(KEY_SELECTED_STACK_DATE, row.date);
            else
                settings.put(KEY_SELECTED_STACK_DATE, ""); //$NON-NLS-1$
        }

        private TableColumn createColumn(TableViewer tableViewer, TableColumnLayout layout,
                String title, IDialogSettings settings, String widthKey, int defaultWidth,
                int modelIndex, boolean styled)
        {
            TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
            TableColumn column = col.getColumn();
            column.setText(title);
            column.setToolTipText(title + Global.pluginSignForTooltip());
            column.setMoveable(true);
            int width = FormTableColumnState.readWidth(settings, widthKey, defaultWidth, MIN_COL_WIDTH);
            layout.setColumnData(column, new ColumnPixelData(width, true, true));
            final int index = modelIndex;
            if (styled)
            {
                col.setLabelProvider(new SelectionAwareStyledCellLabelProvider(
                        new HighlightLabelProvider(index)));
            }
            else
            {
                col.setLabelProvider(new ColumnLabelProvider()
                {
                    @Override
                    public String getText(Object element)
                    {
                        return cellTextForColumn(element, index);
                    }
                });
            }
            return column;
        }

        private static String cellTextForColumn(Object element, int index)
        {
            if (!(element instanceof StackRow row))
                return ""; //$NON-NLS-1$
            if (index == COL_ERROR)
                return row.error;
            if (index == COL_DATE)
                return row.date;
            if (index == COL_PROJECT)
                return row.project;
            return ""; //$NON-NLS-1$
        }

        private final class HighlightLabelProvider extends LabelProvider implements IStyledLabelProvider
        {
            private final int index;

            HighlightLabelProvider(int index)
            {
                this.index = index;
            }

            @Override
            public StyledString getStyledText(Object element)
            {
                String text = cellTextForColumn(element, index);
                StyledString styled = new StyledString(text);
                SmartMatcher matcher = listFilter.matcher;
                if (matcher != null && !matcher.isEmpty && !text.isEmpty())
                    SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(text),
                            viewer.getControl());
                return styled;
            }

            @Override
            public String getText(Object element)
            {
                return cellTextForColumn(element, index);
            }
        }

        private void installColumnSort(TableColumn column, int modelIndex)
        {
            column.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    Table table = viewer.getTable();
                    if (comparator.sortColumn == modelIndex)
                        comparator.descending = !comparator.descending;
                    else
                    {
                        comparator.sortColumn = modelIndex;
                        comparator.descending = modelIndex == COL_DATE;
                    }
                    table.setSortColumn(column);
                    table.setSortDirection(comparator.descending ? SWT.DOWN : SWT.UP);
                    viewer.refresh();
                }
            });
        }

        private void applyFilter()
        {
            if (filterInput == null || filterInput.isDisposed())
                return;
            filterText = filterInput.getText() != null ? filterInput.getText() : ""; //$NON-NLS-1$
            listFilter.matcher = filterText.isBlank() ? null : new SmartMatcher(filterText);
            viewer.refresh();
            // Прежняя строка отфильтрована — переносим выделение на первую видимую.
            FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(viewer.getTable());
        }

        void refreshFromFolder()
        {
            if (viewer.getControl().isDisposed() || folder.isDisposed())
                return;
            List<StackRow> previousSelection = new ArrayList<>();
            for (Object o : viewer.getStructuredSelection().toList())
            {
                if (o instanceof StackRow row)
                    previousSelection.add(row);
            }
            Object repository = Global.getField(view, "repository"); //$NON-NLS-1$
            int repoSize = -1;
            if (repository != null)
            {
                Object all = Global.invoke(repository, "getStacktraces"); //$NON-NLS-1$
                if (all instanceof List<?> list)
                    repoSize = list.size();
            }
            List<StackRow> rows = new ArrayList<>();
            Map<String, Integer> contentFirst = new java.util.HashMap<>();
            IdentityHashMap<IStacktrace, Integer> stFirst = new IdentityHashMap<>();
            int tabIndex = 0;
            int contentDupMark = 0;
            int identityDupMark = 0;
            StringBuilder tabDump = new StringBuilder();
            for (CTabItem item : folder.getItems())
            {
                int idx = tabIndex++;
                if (item == null || item.isDisposed())
                    continue;
                Control control = item.getControl();
                IStacktrace stacktrace = control instanceof Composite page && !page.isDisposed()
                        ? resolveStacktrace(page) : null;
                int stId = stacktrace != null ? System.identityHashCode(stacktrace) : 0;
                StackRow row = StackRow.from(item);
                if (row == null)
                {
                    tabDump.append(" |#").append(idx).append(" nullRow stId=").append(stId); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                String contentKey = row.error + "\t" + row.date; //$NON-NLS-1$
                Integer contentPrev = contentFirst.putIfAbsent(contentKey, Integer.valueOf(idx));
                Integer stPrev = stacktrace != null
                        ? stFirst.putIfAbsent(stacktrace, Integer.valueOf(idx)) : null;
                tabDump.append(" |#").append(idx) //$NON-NLS-1$
                        .append(" st=").append(Integer.toHexString(stId)) //$NON-NLS-1$
                        .append(" '").append(shortLog(row.error)).append("' ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(row.date);
                if (stPrev != null)
                {
                    identityDupMark++;
                    tabDump.append(" ID_DUP_of#").append(stPrev.intValue()); //$NON-NLS-1$
                }
                if (contentPrev != null)
                {
                    contentDupMark++;
                    tabDump.append(" CONTENT_DUP_of#").append(contentPrev.intValue()); //$NON-NLS-1$
                }
                rows.add(row);
                if (item.getData("tormozit.comfort.stacktraces.listDispose") == null) //$NON-NLS-1$
                {
                    item.setData("tormozit.comfort.stacktraces.listDispose", Boolean.TRUE); //$NON-NLS-1$
                    item.addDisposeListener(e ->
                    {
                        Display display = Display.getCurrent();
                        if (display != null)
                            display.asyncExec(this::refreshFromFolder);
                    });
                }
            }
            CTabItem selected = folder.getSelection();
            installFolderTabProbe(folder);
            Global.tempLog("stacktraces-list", //$NON-NLS-1$
                    "refresh tabs=" + folder.getItemCount() //$NON-NLS-1$
                            + " repo=" + repoSize //$NON-NLS-1$
                            + " rows=" + rows.size() //$NON-NLS-1$
                            + " contentDupMark=" + contentDupMark //$NON-NLS-1$
                            + " idDupMark=" + identityDupMark //$NON-NLS-1$
                            + " tableItems=" + viewer.getTable().getItemCount() //$NON-NLS-1$
                            + " folderSel=" + (selected != null && !selected.isDisposed() //$NON-NLS-1$
                                    ? selected.getText() : "null") //$NON-NLS-1$
                            + tabDump);
            syncingSelection = true;
            try
            {
                viewer.setInput(rows);
                List<StackRow> keep = new ArrayList<>();
                for (StackRow prev : previousSelection)
                {
                    for (StackRow row : rows)
                    {
                        if (row.item == prev.item)
                        {
                            keep.add(row);
                            break;
                        }
                    }
                }
                StackRow folderRow = null;
                if (selected != null && !selected.isDisposed())
                {
                    for (StackRow row : rows)
                    {
                        if (row.item == selected)
                        {
                            folderRow = row;
                            break;
                        }
                    }
                }
                boolean folderInKeep = false;
                if (folderRow != null)
                {
                    for (StackRow row : keep)
                    {
                        if (row.item == folderRow.item)
                        {
                            folderInKeep = true;
                            break;
                        }
                    }
                }
                // Самый первый refresh новой панели — до чтения folder.getSelection() (см. выше)
                // штатная панель 1С уже сама восстановила СВОЮ активную вкладку по своей памяти,
                // поэтому folderRow тут почти всегда НЕ null и более поздняя проверка на "выбирать
                // больше не из чего" никогда не срабатывала бы. Сохранённую строку (saveSelectedStack)
                // проверяем и применяем ПЕРВОЙ, до остальной цепочки — если нашли, она и побеждает.
                boolean isInitialRefresh = !initialSelectionApplied;
                initialSelectionApplied = true;
                StackRow restoredRow = null;
                if (isInitialRefresh)
                {
                    String savedDate = dialogSettings().get(KEY_SELECTED_STACK_DATE);
                    if (savedDate != null && !savedDate.isBlank())
                    {
                        for (StackRow row : rows)
                        {
                            if (savedDate.equals(row.date))
                            {
                                restoredRow = row;
                                break;
                            }
                        }
                    }
                }
                if (restoredRow != null)
                {
                    viewer.setSelection(new StructuredSelection(restoredRow), true);
                    if (restoredRow.item != null && !restoredRow.item.isDisposed())
                        folder.setSelection(restoredRow.item);
                }
                else if (deleteAnchorIndex >= 0)
                {
                    StackRow byIndex = rowAtTableIndex(deleteAnchorIndex);
                    if (byIndex != null)
                    {
                        viewer.setSelection(new StructuredSelection(byIndex), true);
                        if (byIndex.item != null && !byIndex.item.isDisposed())
                            folder.setSelection(byIndex.item);
                    }
                    else if (folderRow != null)
                        viewer.setSelection(new StructuredSelection(folderRow), true);
                    else if (!keep.isEmpty())
                        viewer.setSelection(new StructuredSelection(keep), true);
                }
                // «Анализировать» / смена вкладки: folder уже на новом CTabItem —
                // не оставляем прежнее выделение таблицы.
                else if (folderRow != null && !folderInKeep)
                    viewer.setSelection(new StructuredSelection(folderRow), true);
                else if (!keep.isEmpty())
                    viewer.setSelection(new StructuredSelection(keep), true);
                else if (folderRow != null)
                    viewer.setSelection(new StructuredSelection(folderRow), true);
            }
            finally
            {
                syncingSelection = false;
            }
        }

        private static String shortLog(String text)
        {
            if (text == null || text.isEmpty())
                return ""; //$NON-NLS-1$
            String one = text.replace('\n', ' ').replace('\r', ' ');
            return one.length() <= 40 ? one : one.substring(0, 40) + "..."; //$NON-NLS-1$
        }

        private StackRow rowAtTableIndex(int index)
        {
            Table table = viewer.getTable();
            if (table.isDisposed())
                return null;
            int count = table.getItemCount();
            if (count <= 0)
                return null;
            int i = index;
            if (i < 0)
                i = 0;
            if (i >= count)
                i = count - 1;
            Object data = table.getItem(i).getData();
            return data instanceof StackRow row ? row : null;
        }

        private int minSelectedTableIndex()
        {
            Table table = viewer.getTable();
            if (table.isDisposed())
                return -1;
            int[] indices = table.getSelectionIndices();
            if (indices == null || indices.length == 0)
                return -1;
            int min = indices[0];
            for (int i = 1; i < indices.length; i++)
            {
                if (indices[i] < min)
                    min = indices[i];
            }
            return min;
        }

        void syncSelectionFromFolder()
        {
            if (syncingSelection || deleteAnchorIndex >= 0
                    || viewer.getControl().isDisposed() || folder.isDisposed())
                return;
            CTabItem selected = folder.getSelection();
            if (selected == null || selected.isDisposed())
                return;
            IStructuredSelection current = viewer.getStructuredSelection();
            for (Object o : current.toList())
            {
                if (o instanceof StackRow row && row.item == selected)
                    return;
            }
            Object input = viewer.getInput();
            if (!(input instanceof List<?> rows))
            {
                refreshFromFolder();
                return;
            }
            for (Object o : rows)
            {
                if (o instanceof StackRow row && row.item == selected)
                {
                    syncingSelection = true;
                    try
                    {
                        viewer.setSelection(new StructuredSelection(row), true);
                    }
                    finally
                    {
                        syncingSelection = false;
                    }
                    return;
                }
            }
            refreshFromFolder();
        }

        private void deleteSelected()
        {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (sel.isEmpty())
                return;
            Object repository = Global.getField(view, "repository"); //$NON-NLS-1$
            if (repository == null)
                return;
            int anchorIndex = minSelectedTableIndex();
            List<IStacktrace> toRemove = new ArrayList<>();
            for (Object o : sel.toList())
            {
                if (!(o instanceof StackRow row) || row.item == null || row.item.isDisposed())
                    continue;
                Control control = row.item.getControl();
                if (!(control instanceof Composite page) || page.isDisposed())
                    continue;
                IStacktrace stacktrace = resolveStacktrace(page);
                if (stacktrace != null)
                    toRemove.add(stacktrace);
            }
            if (toRemove.isEmpty())
                return;
            deleteAnchorIndex = anchorIndex;
            try
            {
                for (IStacktrace stacktrace : toRemove)
                    Global.invokeVoid(repository, "remove", stacktrace); //$NON-NLS-1$
                refreshFromFolder();
                StackRow byIndex = rowAtTableIndex(deleteAnchorIndex);
                if (byIndex != null)
                {
                    syncingSelection = true;
                    try
                    {
                        viewer.setSelection(new StructuredSelection(byIndex), true);
                        if (byIndex.item != null && !byIndex.item.isDisposed())
                        {
                            folder.setSelection(byIndex.item);
                            enhanceActivePage(view, folder);
                        }
                    }
                    finally
                    {
                        syncingSelection = false;
                    }
                }
            }
            finally
            {
                deleteAnchorIndex = -1;
            }
        }

        /** Второстепенные данные (ширины/порядок/режим заполнения колонок, положение разделителя) —
         * сохраняются при закрытии панели. */
        private void saveColumnLayout(Table table)
        {
            // Временная безусловная диагностика (topic "stacktraces-list") — на случай повторения
            // проблемы с переоткрытием панели после закрытия (причина пока не установлена).
            listLog("saveColumnLayout: enter table=" + (table == null ? "null" : System.identityHashCode(table))); //$NON-NLS-1$ //$NON-NLS-2$
            if (table == null || table.isDisposed()
                || dateColumn == null || errorColumn == null || projectColumn == null
                || dateColumn.isDisposed() || errorColumn.isDisposed() || projectColumn.isDisposed())
            {
                listLog("saveColumnLayout: skip (disposed/null)"); //$NON-NLS-1$
                return;
            }
            boolean fillMode = interaction != null && interaction.isColumnsExactFill();
            IDialogSettings settings = dialogSettings();
            FormTableColumnState.saveOrderAndWidths(settings, KEY_COL_ORDER, KEY_COL_FILL_MODE, fillMode,
                new String[] { KEY_COL_DATE_WIDTH, KEY_COL_ERROR_WIDTH, KEY_COL_PROJECT_WIDTH },
                new TableColumn[] { dateColumn, errorColumn, projectColumn }, table);
            if (sash != null && !sash.isDisposed())
            {
                int[] w = sash.getWeights();
                if (w.length == 2)
                {
                    settings.put(KEY_SASH_LEFT, w[0]);
                    settings.put(KEY_SASH_RIGHT, w[1]);
                }
            }
            listLog("saveColumnLayout: done"); //$NON-NLS-1$
        }

        private static IDialogSettings dialogSettings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            if (section == null)
                section = top.addNewSection(SETTINGS_SECTION);
            return section;
        }

        private final class ListFilter extends ViewerFilter
        {
            SmartMatcher matcher;

            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                if (matcher == null)
                    return true;
                if (!(element instanceof StackRow row))
                    return false;
                String hay = row.error + ' ' + row.date + ' ' + row.project;
                return matcher.matches(hay);
            }
        }

        private static final class DateSortComparator extends ViewerComparator
        {
            int sortColumn = COL_DATE;
            boolean descending = true;

            @Override
            public int compare(Viewer viewer, Object e1, Object e2)
            {
                if (!(e1 instanceof StackRow a) || !(e2 instanceof StackRow b))
                    return 0;
                int result;
                if (sortColumn == COL_DATE)
                    result = Long.compare(a.dateMillis, b.dateMillis);
                else if (sortColumn == COL_PROJECT)
                    result = a.project.compareToIgnoreCase(b.project);
                else
                    result = a.error.compareToIgnoreCase(b.error);
                return descending ? -result : result;
            }
        }

        private static final class StackRow
        {
            final CTabItem item;
            final String error;
            final String date;
            final String project;
            final long dateMillis;

            StackRow(CTabItem item, String error, String date, String project, long dateMillis)
            {
                this.item = item;
                this.error = error != null ? error : ""; //$NON-NLS-1$
                this.date = date != null ? date : ""; //$NON-NLS-1$
                this.project = project != null ? project : ""; //$NON-NLS-1$
                this.dateMillis = dateMillis;
            }

            static StackRow from(CTabItem item)
            {
                Control control = item.getControl();
                if (!(control instanceof Composite page) || page.isDisposed())
                    return null;
                IStacktrace stacktrace = resolveStacktrace(page);
                if (stacktrace == null)
                    return null;
                String error = BreakpointListHook.firstLine(findStacktraceErrorText(stacktrace));
                if (error.isEmpty())
                {
                    String name = stacktrace.getName();
                    error = name != null ? name : ""; //$NON-NLS-1$
                }
                String date = stacktrace.getDetail();
                if (date != null)
                    date = date.strip();
                else
                    date = ""; //$NON-NLS-1$
                String project = resolveProjectLabel(stacktrace, page);
                return new StackRow(item, error, date, project, parseDetailMillis(date));
            }
        }

        private static String resolveProjectLabel(IStacktrace stacktrace, Composite page)
        {
            Object stateObj = page.getData(PAGE_STATE_KEY);
            if (stateObj instanceof PageState state)
            {
                String selected = state.selectedProjectName();
                if (selected != null && !selected.isBlank())
                    return selected;
            }
            String project = stacktrace.getProjectName();
            if (project != null && !project.isBlank())
                return project;
            List<IStacktraceFrame> frames = collectFrames(stacktrace);
            for (IStacktraceFrame frame : frames)
            {
                String name = frame.getProjectName();
                if (name != null && !name.isBlank())
                    return name;
            }
            return ""; //$NON-NLS-1$
        }

        private static long parseDetailMillis(String detail)
        {
            if (detail == null || detail.isBlank())
                return 0L;
            try
            {
                DateFormat format = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault());
                Date date = format.parse(detail);
                return date != null ? date.getTime() : 0L;
            }
            catch (ParseException ex)
            {
                return 0L;
            }
        }
    }
}
