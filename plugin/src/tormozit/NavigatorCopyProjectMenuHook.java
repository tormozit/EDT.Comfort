package tormozit;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Добавляет «Скопировать проект» в подменю «Комфорт» навигатора EDT — копирует
 * все файлы выбранного проекта в новый проект (с новым именем и расположением)
 * и отвязывает копию от git (не копируя папку {@code .git}).
 */
public final class NavigatorCopyProjectMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.navigatorCopyProjectHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Скопировать проект..."; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
            "Скопировать все файлы проекта в новый проект с другим именем, без git" //$NON-NLS-1$
            + Global.pluginSignForTooltip();

    private static final String OVERLAY_LABEL_KEY = "tormozit.NavigatorCopyProjectMenuHook.overlay.label"; //$NON-NLS-1$
    private static final String OVERLAY_BAR_KEY = "tormozit.NavigatorCopyProjectMenuHook.overlay.bar"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
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
        return Global.NAVIGATOR_VIEW_ID.equals(id)
                || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void tryHook(IViewPart navigator)
    {
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
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
            private final List<MenuItem> added = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = viewer.getSelection();
                if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
                    return;
                IProject project = resolveSingleProject(structured);
                if (project == null)
                    return;

                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT);
                item.setToolTipText(ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        copyProject(project, comfortSub.getShell());
                    }
                });
                added.add(item);
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

    private static IProject resolveSingleProject(IStructuredSelection selection)
    {
        IResource resource = NavigatorResourceResolver.resolveFirst(selection);
        return resource instanceof IProject ? (IProject) resource : null;
    }

    // -----------------------------------------------------------------------
    // Копирование проекта
    // -----------------------------------------------------------------------

    private static void copyProject(IProject sourceProject, Shell shell)
    {
        if (sourceProject == null || sourceProject.getLocation() == null)
            return;

        String newName = askNewProjectName(shell, sourceProject.getName());
        if (newName == null)
            return;

        File sourceDir = sourceProject.getLocation().toFile();
        File parentDir = askParentDirectory(shell, sourceDir.getParentFile());
        if (parentDir == null)
            return;

        File targetDir = new File(parentDir, newName);
        if (targetDir.exists())
        {
            ToastNotification.show("Комфорт", //$NON-NLS-1$
                "Папка «" + targetDir.getAbsolutePath() + "» уже существует", 5000); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Display display = shell.getDisplay();
        Shell overlay = createProgressOverlay(shell);
        Thread worker = new Thread(
            () -> runCopy(sourceProject, newName, sourceDir, targetDir, display, overlay),
            "tormozit-copy-project"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
    }

    private static void runCopy(IProject sourceProject, String newName, File sourceDir, File targetDir,
            Display display, Shell overlay)
    {
        IOException[] copyError = new IOException[1];
        try
        {
            copyFiles(sourceDir.toPath(), targetDir.toPath(), display, overlay);
        }
        catch (IOException e)
        {
            copyError[0] = e;
        }

        display.asyncExec(() -> {
            if (!overlay.isDisposed())
                overlay.dispose();

            if (copyError[0] != null)
            {
                ToastNotification.show("Комфорт", //$NON-NLS-1$
                    "Ошибка копирования файлов: " + copyError[0].getMessage(), 6000); //$NON-NLS-1$
                return;
            }
            try
            {
                IProject created = importCopiedProject(newName, targetDir);
                ToastNotification.show("Комфорт", //$NON-NLS-1$
                    "Проект «" + created.getName() + "» создан из «" + sourceProject.getName() + "»", 5000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            catch (CoreException e)
            {
                ToastNotification.show("Комфорт", "Ошибка создания проекта: " + e.getMessage(), 6000); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static String askNewProjectName(Shell shell, String sourceName)
    {
        IInputValidator validator = value -> {
            if (value == null || value.isBlank())
                return "Введите имя проекта"; //$NON-NLS-1$
            String trimmed = value.trim();
            if (!trimmed.matches("[^\\\\/:*?\"<>|]+")) //$NON-NLS-1$
                return "Имя проекта содержит недопустимые символы"; //$NON-NLS-1$
            if (ResourcesPlugin.getWorkspace().getRoot().getProject(trimmed).exists())
                return "Проект с таким именем уже существует в рабочей области"; //$NON-NLS-1$
            return null;
        };
        InputDialog dialog = new InputDialog(shell,
            Global.withPluginWindowTitle("Скопировать проект"), //$NON-NLS-1$
            "Имя нового проекта:", sourceName + " - копия", validator); //$NON-NLS-1$ //$NON-NLS-2$
        if (dialog.open() != org.eclipse.jface.window.Window.OK)
            return null;
        return dialog.getValue().trim();
    }

    private static File askParentDirectory(Shell shell, File initialDir)
    {
        DirectoryDialog dialog = new DirectoryDialog(shell);
        dialog.setText(Global.withPluginWindowTitle("Скопировать проект")); //$NON-NLS-1$
        dialog.setMessage("Выберите папку, в которой будет создана папка нового проекта:"); //$NON-NLS-1$
        if (initialDir != null && initialDir.isDirectory())
            dialog.setFilterPath(initialDir.getAbsolutePath());
        String selected = dialog.open();
        return selected != null ? new File(selected) : null;
    }

    /**
     * Рекурсивно копирует файлы проекта, пропуская папки {@code .git} — так копия
     * получается сразу отвязанной от git (без истории и рабочего дерева репозитория).
     * Сначала собирает списки папок/файлов (без учёта {@code .git}), чтобы знать
     * общее количество файлов для прогресс-бара {@code overlay}.
     */
    private static void copyFiles(Path sourceDir, Path targetDir, Display display, Shell overlay) throws IOException
    {
        List<Path> directories = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                if (".git".equals(dir.getFileName().toString())) //$NON-NLS-1$
                    return FileVisitResult.SKIP_SUBTREE;
                directories.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });

        for (Path dir : directories)
            Files.createDirectories(targetDir.resolve(sourceDir.relativize(dir)));

        int total = files.size();
        int done = 0;
        for (Path file : files)
        {
            Files.copy(file, targetDir.resolve(sourceDir.relativize(file)));
            done++;
            int doneSnapshot = done;
            if (doneSnapshot == total || doneSnapshot % 25 == 0)
            {
                if (!display.isDisposed())
                    display.asyncExec(() -> updateOverlayProgress(overlay, doneSnapshot, total));
            }
        }
    }

    /**
     * Компактная плавающая панель с прогресс-баром поверх окна навигатора на время
     * копирования — по образцу {@code PreferenceSearchFilterAugmenter.createBlockingOverlay}.
     */
    private static Shell createProgressOverlay(Shell parentShell)
    {
        Shell overlay = new Shell(parentShell, SWT.NO_TRIM | SWT.ON_TOP);
        overlay.setLayout(new GridLayout(1, false));
        overlay.setBackground(parentShell.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

        Label label = new Label(overlay, SWT.CENTER);
        label.setText("Копирование файлов проекта: 0/0"); //$NON-NLS-1$
        label.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        ProgressBar bar = new ProgressBar(overlay, SWT.SMOOTH | SWT.HORIZONTAL);
        GridData barData = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        barData.widthHint = 320;
        bar.setLayoutData(barData);
        bar.setMinimum(0);
        bar.setMaximum(1);

        overlay.setData(OVERLAY_LABEL_KEY, label);
        overlay.setData(OVERLAY_BAR_KEY, bar);

        overlay.pack();
        Rectangle parentBounds = parentShell.getBounds();
        Point size = overlay.getSize();
        overlay.setLocation(
            parentBounds.x + (parentBounds.width - size.x) / 2,
            parentBounds.y + (parentBounds.height - size.y) / 2);
        overlay.open();
        return overlay;
    }

    private static void updateOverlayProgress(Shell overlay, int done, int total)
    {
        if (overlay == null || overlay.isDisposed())
            return;
        Object labelObj = overlay.getData(OVERLAY_LABEL_KEY);
        Object barObj = overlay.getData(OVERLAY_BAR_KEY);
        if (labelObj instanceof Label label && !label.isDisposed())
            label.setText("Копирование файлов проекта: " + done + "/" + total); //$NON-NLS-1$ //$NON-NLS-2$
        if (barObj instanceof ProgressBar bar && !bar.isDisposed())
        {
            bar.setMaximum(Math.max(total, 1));
            bar.setSelection(done);
        }
    }

    private static IProject importCopiedProject(String newName, File targetDir) throws CoreException
    {
        org.eclipse.core.resources.IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IPath location = new org.eclipse.core.runtime.Path(targetDir.getAbsolutePath());
        IPath dotProjectPath = location.append(".project"); //$NON-NLS-1$
        IProjectDescription description = dotProjectPath.toFile().isFile()
            ? workspace.loadProjectDescription(dotProjectPath)
            : workspace.newProjectDescription(newName);
        description.setName(newName);
        description.setLocation(location);

        IProject project = workspace.getRoot().getProject(newName);
        project.create(description, new NullProgressMonitor());
        project.open(IResource.NONE, new NullProgressMonitor());
        return project;
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
