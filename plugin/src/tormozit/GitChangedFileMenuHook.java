package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.InvalidPreferencesFormatException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.op.DiscardChangesOperation;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.GitComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.git.FileDiff;
import com._1c.g5.v8.dt.compare.git.GitCompareUtils;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MergeSettings;
import com._1c.g5.v8.dt.compare.settings.model.RestoredMergeSettings;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.util.CompareUiUtils;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import com.google.inject.Injector;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;

import org.eclipse.ui.handlers.IHandlerService;

/**
 * Добавляет «Показать в навигаторе», «Показать в структуре проекта» и «Открыть объект» в подменю «Комфорт»
 * контекстного меню изменённых файлов в EGit/EDT-представлениях
 * ({@code StagingView}, {@code RepositoryExplorerView}, {@code GenericHistoryView}).
 */
public final class GitChangedFileMenuHook implements IStartup
{
    private static final String ROOT_MARKER = "tormozit.gitChangedFileRootHook"; //$NON-NLS-1$
    private static final String SUB_MARKER  = "tormozit.gitChangedFileSubHook";  //$NON-NLS-1$

    private static final String DT_STAGING_VIEW_ID  = "com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView"; //$NON-NLS-1$
    private static final String DT_TEAM_VIEW_ID     = "com._1c.g5.v8.dt.team.ui.development.view"; //$NON-NLS-1$
    private static final String EGIT_STAGING_VIEW_ID = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String EGIT_REPOS_VIEW_ID   = "org.eclipse.egit.ui.RepositoryExplorerView"; //$NON-NLS-1$
    private static final String EGIT_HISTORY_VIEW_ID  = "org.eclipse.egit.ui.HistoryView"; //$NON-NLS-1$
    private static final String TEAM_HISTORY_VIEW_ID   = "org.eclipse.team.ui.GenericHistoryView"; //$NON-NLS-1$

    private static final String NAV_ITEM_TEXT = "Показать в навигаторе"; //$NON-NLS-1$
    private static final String STRUCTURE_ITEM_TEXT = "Показать в структуре проекта"; //$NON-NLS-1$
    private static final String OBJ_ITEM_TEXT = "Открыть объект";       //$NON-NLS-1$
    /** Для файлов коммита (панель «История») — уточняем, что открывается рабочая версия, а не версия из коммита. */
    private static final String OBJ_ITEM_TEXT_COMMIT = "Открыть рабочий объект"; //$NON-NLS-1$
    private static final String ADD_TO_SET_CMD = "tormozit.git.addToObjectSet"; //$NON-NLS-1$
    private static final String ADD_TO_SET_MARKER = "tormozit.gitAddToObjectSetItem"; //$NON-NLS-1$

    private static final String STAGING_ENTRY_CLASS = "org.eclipse.egit.ui.internal.staging.StagingEntry"; //$NON-NLS-1$
    private static final String REPLACE_WITH_HEAD_ITEM_TEXT = "Заменить на HEAD-ревизию"; //$NON-NLS-1$
    /**
     * Состояния {@code StagingEntry.State}, для которых EGit-приватный
     * {@code StagingView.getAvailableActions()} включает {@code REPLACE_WITH_HEAD_REVISION}
     * (см. декомпилированный {@code StagingEntry$State}, {@code .tmp/bundles/egit-ui/}):
     * файл существует в HEAD, поэтому его можно заменить на версию из HEAD. Для ADDED/
     * UNTRACKED/MODIFIED_AND_ADDED/MISSING_AND_CHANGED в HEAD файла нет — там действие
     * недоступно даже у самого EGit.
     */
    private static final java.util.Set<String> REPLACE_WITH_HEAD_STATES = java.util.Set.of(
        "CHANGED", "REMOVED", "MISSING", "MODIFIED", "MODIFIED_AND_CHANGED", "CONFLICTING"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    /**
     * Размер чанка для {@link DiscardChangesOperation} при больших выделениях.
     * {@code execute()} держит правило планирования на всю операцию (один batched
     * JGit checkout + один resource-refresh на все пути сразу, см. декомпилированный
     * {@code .tmp/bundles/egit-core/DiscardChangesOperation.javap.txt}) — при тысячах
     * файлов это ощущается как зависание всего окна на время удержания правила.
     * Чанкинг ограничивает время одного удержания и делает отмену возможной.
     */
    private static final int REPLACE_WITH_HEAD_CHUNK_SIZE = 100;

    private static final String EGIT_UI_BUNDLE_ID = "org.eclipse.egit.ui"; //$NON-NLS-1$
    private static final String EGIT_UITEXT_CLASS = "org.eclipse.egit.ui.internal.UIText"; //$NON-NLS-1$
    private static final String EGIT_UITEXT_REPLACE_FIELD = "StagingView_replaceWithHeadRevision"; //$NON-NLS-1$
    private static final String CONFIGURATION_MDO_NAME = "Configuration.mdo"; //$NON-NLS-1$
    private static final String ORPHANED_MDO_NOTICE =
        "Среди восстанавливаемых объектов есть отсутствующие в Configuration.mdo — ссылки на них будут "
        + "автоматически добавлены в Configuration.mdo (неявная правка файла)."; //$NON-NLS-1$

    private static final String COMPARE_WITH_COMMIT_TEXT =
        "Сравнить рабочий каталог с коммитом"; //$NON-NLS-1$
    private static final String COMPARE_UI_BUNDLE_ID = "com._1c.g5.v8.dt.compare.ui"; //$NON-NLS-1$
    private static final String COMPARE_DIALOG_CLASS =
        "com._1c.g5.v8.dt.internal.compare.git.ui.dialogs.DtCommitSelectionDialog"; //$NON-NLS-1$
    /**
     * Русский заголовок окна «Выбор коммита». Поставщик (EGit {@code nl_ru})
     * его не переводит: в {@code uitext_ru.properties} ключа
     * {@code CommitSelectionDialog_WindowTitle} нет, поэтому даже в русском EDT
     * окно называется «Select a Commit».
     */
    private static final String COMMIT_DIALOG_RUSSIAN_TITLE = "Выбор коммита"; //$NON-NLS-1$
    /** Секция {@code IDialogSettings} с запомненным размером окна «Выбор коммита». */
    private static final String COMMIT_DIALOG_SETTINGS_SECTION = "tormozit.compareWithCommitDialog"; //$NON-NLS-1$
    private static final String KEY_COMMIT_DIALOG_WIDTH = "width"; //$NON-NLS-1$
    private static final String KEY_COMMIT_DIALOG_HEIGHT = "height"; //$NON-NLS-1$
    private static final int COMMIT_DIALOG_MIN_WIDTH = 400;
    private static final int COMMIT_DIALOG_MIN_HEIGHT = 300;
    private static final String COMPARE_UTILS_CLASS =
        "com._1c.g5.v8.dt.internal.compare.git.ui.handler.Utils"; //$NON-NLS-1$
    private static final String COMPARE_UI_PLUGIN_CLASS =
        "com._1c.g5.v8.dt.internal.compare.ui.CompareUiPlugin"; //$NON-NLS-1$
    private static final String COMPARISON_EDITOR_OPEN_HELPER_CLASS =
        "com._1c.g5.v8.dt.internal.compare.ui.editor.IComparisonEditorOpenHelper"; //$NON-NLS-1$

    /** Русский текст чекбокса диалога (как {@code DataSourcesAndStrategyPage_Main_side_objects_deletion_allowed_checkbox_text}). */
    private static final String MAIN_SIDE_OBJECTS_DELETION_ALLOWED_TEXT =
        "Разрешить удаление объектов главного источника"; //$NON-NLS-1$
    /** Подсказка чекбокса: по умолчанию включён — без этого плагин блокирует такие удаления. */
    private static final String MAIN_SIDE_OBJECTS_DELETION_ALLOWED_TOOLTIP =
        "При выключении слияние объектов, присутствующих только в главном источнике " //$NON-NLS-1$
        + "(рабочем каталоге), будет запрещено"; //$NON-NLS-1$
    /** Окно «изменения не найдены» (как у {@code CompareWithPerformer}). */
    private static final String NOTHING_TO_COMPARE_TITLE = "Синхронизация с Git завершена"; //$NON-NLS-1$
    private static final String NOTHING_TO_COMPARE_MESSAGE = "Синхронизация с Git: изменения не найдены."; //$NON-NLS-1$
    /** Имя стороны «рабочий каталог» и подтверждение запуска слияния (как у {@code CompareWithPerformer}). */
    private static final String WORKING_TREE_NAME = "Рабочий каталог"; //$NON-NLS-1$
    private static final String NO_MERGE_CONFIRM_MESSAGE =
        "По завершении объединения не будет создан коммит слияния. Запустить процедуру объединения?"; //$NON-NLS-1$
    /** Задача поиска изменений в проекте (как {@code Messages.AbstractCompareCommitsPerformer_Search_changes_in_project__0}). */
    private static final String SEARCH_CHANGES_TASK_NAME = "Поиск изменений в проекте {0}"; //$NON-NLS-1$
    /**
     * Максимальное ожидание завершения сравнения перед блокировкой удаления объектов
     * главного источника (мс). Для больших конфигураций сравнение может идти дольше
     * минуты, поэтому запас большой — реальным ограничителем служит закрытие
     * редактора сравнения (проверка disposed в цикле опроса).
     */
    private static final long SESSION_WAIT_TIMEOUT_MILLIS = 30 * 60_000L;
    /** Период опроса готовности сессии сравнения (мс). */
    private static final long SESSION_WAIT_POLL_MILLIS = 200L;

    /**
     * Snapshot мультивыделения, сохранённый при открытии меню.
     * Handler читает его при клике по пункту «Добавить в набор».
     */
    static IStructuredSelection multiSelectionSnapshot;

    private static final String GIT_CONTEXT_ID = "tormozit.gitChangedFile.context"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.addFilter(SWT.MenuDetect, GitChangedFileMenuHook::handleMenuDetect);
            Global.log("GitChangedFileMenu: MenuDetect filter installed"); //$NON-NLS-1$
        });

        // Установка слушателя активации/деактивации контекста для git-представлений
        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow w) { hookPartListener(w); }
            @Override
            public void windowActivated(IWorkbenchWindow w) {}
            @Override
            public void windowDeactivated(IWorkbenchWindow w) {}
            @Override
            public void windowClosed(IWorkbenchWindow w) { unhookPartListener(w); }
        });
        for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookPartListener(w);
    }

    private static void hookPartListener(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(gitViewPartListener);
    }

    private static void unhookPartListener(IWorkbenchWindow window)
    {
        window.getPartService().removePartListener(gitViewPartListener);
    }

    private static IContextActivation gitContextActivation;
    private static IContextService contextService;

    private static IContextService contextService()
    {
        if (contextService == null)
            contextService = PlatformUI.getWorkbench().getService(IContextService.class);
        return contextService;
    }

    private static final IPartListener2 gitViewPartListener = new IPartListener2()
    {
        @Override
        public void partActivated(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (!(part instanceof IWorkbenchPart))
                return;

            IContextService cs = contextService();
            if (cs == null)
                return;

            if (isGitView(part))
            {
                if (gitContextActivation == null)
                {
                    gitContextActivation = cs.activateContext(GIT_CONTEXT_ID);
                    Global.log("GitChangedFileMenu: context activated for " + part.getSite().getId()); //$NON-NLS-1$
                }
            }
            else if (gitContextActivation != null)
            {
                cs.deactivateContext(gitContextActivation);
                gitContextActivation = null;
                Global.log("GitChangedFileMenu: context deactivated (non-git part=" + part.getSite().getId() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof IWorkbenchPart && isGitView(part) && gitContextActivation != null)
            {
                IContextService cs = contextService();
                if (cs != null)
                {
                    cs.deactivateContext(gitContextActivation);
                    gitContextActivation = null;
                    Global.log("GitChangedFileMenu: context deactivated (closed)"); //$NON-NLS-1$
                }
            }
        }

        @Override public void partOpened(IWorkbenchPartReference ref) {}
        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partVisible(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}
    };

    // ========================================================================
    // Entry point — диагностика event.widget
    // ========================================================================

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control target) || target.isDisposed())
        {
            Global.log("GitChangedFileMenu: event.widget is not a Control: "
                + (event.widget != null ? event.widget.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Global.log("GitChangedFileMenu: MenuDetect widget="
            + target.getClass().getName() //$NON-NLS-1$
            + " hasMenu=" + (target.getMenu() != null)); //$NON-NLS-1$

        // Ищем control, у которого есть меню (может быть на родителе)
        Control menuControl = target;
        while (menuControl != null && !menuControl.isDisposed() && menuControl.getMenu() == null)
            menuControl = menuControl.getParent();

        if (menuControl == null || menuControl.isDisposed())
        {
            Global.log("GitChangedFileMenu: no control with menu found"); //$NON-NLS-1$
            return;
        }

        if (menuControl != target)
        {
            Global.log("GitChangedFileMenu: menu on parent class="
                + menuControl.getClass().getName()); //$NON-NLS-1$
        }

        IViewPart view = findGitViewContaining(target);
        if (view == null)
            return;

        tryAttachMenuListener(menuControl, view, 0);
    }

    // ========================================================================
    // Поиск git-представления по control (4 подхода)
    // ========================================================================

    private static IViewPart findGitViewContaining(Control control)
    {
        IViewPart result;

        // 1) Walk parent chain: ищем IEclipseContext с MPart
        result = findByContextWalk(control);
        if (result != null)
        {
            Global.log("GitChangedFileMenu: found by contextWalk view="
                + result.getSite().getId()); //$NON-NLS-1$
            return result;
        }
        Global.log("GitChangedFileMenu: contextWalk failed"); //$NON-NLS-1$

        // 2) ViewPart.getControl() via reflection → isDescendantOf
        result = findByViewPartGetControl(control);
        if (result != null)
        {
            Global.log("GitChangedFileMenu: found by ViewPart.getControl view="
                + result.getSite().getId()); //$NON-NLS-1$
            return result;
        }
        Global.log("GitChangedFileMenu: ViewPart.getControl failed"); //$NON-NLS-1$

        // 3) MPart.getWidget() → isDescendantOf
        result = findByMPartWidget(control);
        if (result != null)
        {
            Global.log("GitChangedFileMenu: found by MPart.getWidget view="
                + result.getSite().getId()); //$NON-NLS-1$
            return result;
        }
        Global.log("GitChangedFileMenu: MPart.getWidget failed"); //$NON-NLS-1$

        return null;
    }

    // ========================================================================
    // Approach 1: контекстный walk (parent chain, IEclipseContext → MPart)
    // ========================================================================

    private static IViewPart findByContextWalk(Control control)
    {
        IWorkbenchPage page = activePage();
        if (page == null)
            return null;

        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            Object ctx = c.getData(
                "org.eclipse.e4.ui.workbench.IPresentationEngine.ACTIVE_CONTEXT"); //$NON-NLS-1$
            if (ctx == null)
                continue;

            try
            {
                Method getMethod = ctx.getClass().getMethod("get", Class.class); //$NON-NLS-1$
                Object mpart = getMethod.invoke(ctx, MPart.class);
                if (mpart == null)
                    continue;

                Method getElementId = mpart.getClass().getMethod("getElementId"); //$NON-NLS-1$
                String elementId = (String) getElementId.invoke(mpart);
                if (elementId == null)
                    continue;

                IViewPart view = page.findView(elementId);
                if (isGitView(view))
                    return view;
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    // ========================================================================
    // Approach 2: ViewPart.getControl() via reflection
    // ========================================================================

    private static IViewPart findByViewPartGetControl(Control control)
    {
        try
        {
            IWorkbenchPage page = activePage();
            if (page == null)
                return null;

            logAllViewReferences(page, "ViewPart");

            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || !isGitView(view))
                    continue;

                Control root = getViewControlViaViewPart(view);
                if (root == null)
                {
                    Global.log("GitChangedFileMenu: ViewPart.getControl null for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    continue;
                }

                if (isDescendantOf(control, root))
                {
                    Global.log("GitChangedFileMenu: ViewPart.getControl OK for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    return view;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: ViewPart.getControl error: " + e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Пытается получить корневой Control представления через
     * {@code ViewPart.getControl()} (protected, через рефлексию).
     */
    private static Control getViewControlViaViewPart(IViewPart view)
    {
        try
        {
            // Пробуем getControl() — protected в ViewPart
            for (java.lang.reflect.Method m : view.getClass().getMethods())
            {
                if (!"getControl".equals(m.getName()) || m.getParameterCount() != 0) //$NON-NLS-1$
                    continue;
                m.setAccessible(true);
                Object result = m.invoke(view);
                if (result instanceof Control c)
                    return c;
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    // ========================================================================
    // Approach 3: MPart.getWidget()
    // ========================================================================

    private static IViewPart findByMPartWidget(Control control)
    {
        try
        {
            IWorkbenchPage page = activePage();
            if (page == null)
                return null;

            logAllViewReferences(page, "MPart");

            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || !isGitView(view))
                    continue;

                Control root = getViewControlViaMPart(view);
                if (root == null)
                {
                    Global.log("GitChangedFileMenu: MPart.getWidget null for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    continue;
                }

                if (isDescendantOf(control, root))
                {
                    Global.log("GitChangedFileMenu: MPart.getWidget OK for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    return view;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: MPart.getWidget error: " + e); //$NON-NLS-1$
        }
        return null;
    }

    private static Control getViewControlViaMPart(IViewPart view)
    {
        try
        {
            Object mpart = view.getSite().getService(MPart.class);
            if (mpart == null)
            {
                Global.log("GitChangedFileMenu: MPart service null for "
                    + view.getSite().getId()); //$NON-NLS-1$
                return null;
            }

            Method getWidget = MPart.class.getMethod("getWidget"); //$NON-NLS-1$
            Object widget = getWidget.invoke(mpart);
            if (widget instanceof Control c)
                return c;

            Global.log("GitChangedFileMenu: MPart.getWidget returned non-Control: "
                + (widget != null ? widget.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: MPart.getWidget exception: " + e); //$NON-NLS-1$
        }
        return null;
    }

    // ========================================================================
    // Utilities: isDescendantOf
    // ========================================================================

    private static boolean isDescendantOf(Control child, Control ancestor)
    {
        if (child == null || ancestor == null || child.isDisposed() || ancestor.isDisposed())
            return false;
        for (Control c = child; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == ancestor)
                return true;
        }
        return false;
    }

    // ========================================================================
    // tryAttachMenuListener
    // ========================================================================

    private static void tryAttachMenuListener(Control control, IViewPart view, int attempt)
    {
        if (control == null || control.isDisposed())
            return;

        Menu menu = control.getMenu();
        if (menu == null || menu.isDisposed())
        {
            if (attempt < 3)
            {
                final int nextAttempt = attempt + 1;
                control.getDisplay().asyncExec(() ->
                    tryAttachMenuListener(control, view, nextAttempt));
            }
            return;
        }

        if (Boolean.TRUE.equals(menu.getData(ROOT_MARKER)))
            return;

        Global.log("GitChangedFileMenu: attach root listener view="
            + view.getSite().getId() + " attempt=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$

        menu.setData(ROOT_MARKER, Boolean.TRUE);
        menu.setData("menuControl", control); //$NON-NLS-1$
        menu.addMenuListener(buildRootMenuListener(view));
    }

    // ========================================================================
    // Root menu — создаёт/находит подменю «Комфорт»
    // ========================================================================

    private static MenuAdapter buildRootMenuListener(IViewPart view)
    {
        return new MenuAdapter()
        {
            /** Наш пункт «Заменить на HEAD-ревизию», вставленный на место штатного EGit. */
            private final List<MenuItem> replaceItems = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                Menu contextMenu = (Menu) e.widget;

                ISelection clickedSelection = selectionOfClickedControl(contextMenu);
                ISelection selection = clickedSelection instanceof IStructuredSelection cs && !cs.isEmpty()
                    ? clickedSelection : selectionOf(view);
                if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
                {
                    List<IFile> headReplaceFiles = computeHeadReplaceFiles(structured, view);
                    if (!headReplaceFiles.isEmpty())
                    {
                        int nativeIndex = hideNativeReplaceWithHeadItem(contextMenu);
                        MenuItem replaceItem = new MenuItem(contextMenu, SWT.PUSH,
                            nativeIndex >= 0 ? nativeIndex : contextMenu.getItemCount());
                        replaceItem.setText(REPLACE_WITH_HEAD_ITEM_TEXT);
                        replaceItem.setToolTipText(
                            "Заменить содержимое выбранных файлов на состояние из HEAD (текущего коммита)"
                                + Global.pluginSignForTooltip());
                        replaceItem.addSelectionListener(new SelectionAdapter()
                        {
                            @Override
                            public void widgetSelected(SelectionEvent ev)
                            {
                                replaceWithHeadRevision(headReplaceFiles, contextMenu.getShell());
                            }
                        });
                        replaceItems.add(replaceItem);
                    }
                }

                Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(
                    contextMenu, contextMenu.getShell());
                if (comfortSub == null || comfortSub.isDisposed())
                    return;

                if (Boolean.TRUE.equals(comfortSub.getData(SUB_MARKER)))
                    return;

                Global.log("GitChangedFileMenu: attach items listener"); //$NON-NLS-1$
                comfortSub.setData(SUB_MARKER, Boolean.TRUE);
                comfortSub.addMenuListener(buildItemsMenuListener(view));
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(replaceItems);
                replaceItems.clear();
                ((Menu) e.widget).getDisplay().asyncExec(() ->
                {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
    }

    /**
     * Удаляет штатный пункт EGit «Replace with HEAD Revision» ({@code StagingView$53.menuAboutToShow},
     * {@code StagingView$ReplaceAction}) из переданного контекстного меню, чтобы на его месте показать
     * свой пункт с дополнительной проверкой отвязанных mdo. У {@code MenuItem} в SWT нет
     * {@code setVisible} (в отличие от {@code Menu}) — единственный способ убрать пункт — dispose.
     * Текст штатного пункта берётся из {@code UIText.StagingView_replaceWithHeadRevision} бандла
     * {@code org.eclipse.egit.ui} — не хардкодится, чтобы не зависеть от локализации.
     *
     * @return индекс, на котором стоял штатный пункт (для вставки нашего на то же место), или
     *         {@code -1}, если штатный пункт не найден (другая версия EGit / другое меню) —
     *         в этом случае наш пункт добавляется в конец меню.
     */
    private static int hideNativeReplaceWithHeadItem(Menu contextMenu)
    {
        String nativeText = nativeReplaceWithHeadRevisionText();
        if (nativeText == null)
            return -1;
        MenuItem[] items = contextMenu.getItems();
        for (int i = 0; i < items.length; i++)
        {
            MenuItem item = items[i];
            if (!item.isDisposed() && nativeText.equals(item.getText()))
            {
                item.dispose();
                return i;
            }
        }
        return -1;
    }

    private static String nativeReplaceWithHeadRevisionText()
    {
        try
        {
            Bundle bundle = Platform.getBundle(EGIT_UI_BUNDLE_ID);
            if (bundle == null)
                return null;
            Class<?> uiTextCls = bundle.loadClass(EGIT_UITEXT_CLASS);
            Object value = uiTextCls.getField(EGIT_UITEXT_REPLACE_FIELD).get(null);
            return value instanceof String s ? s : null;
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: nativeReplaceWithHeadRevisionText error: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Файлы из выделения, для которых штатное действие EGit «Заменить на HEAD-ревизию»
     * доступно (см. {@link #REPLACE_WITH_HEAD_STATES}).
     */
    private static List<IFile> computeHeadReplaceFiles(IStructuredSelection structured, IWorkbenchPart view)
    {
        List<IFile> headReplaceFiles = new ArrayList<>();
        for (Object element : structured.toList())
        {
            if (!STAGING_ENTRY_CLASS.equals(element.getClass().getName()))
                continue;
            Object state = Global.call(element, "getState"); //$NON-NLS-1$
            String stateName = state != null ? (String) Global.call(state, "name") : null; //$NON-NLS-1$
            if (stateName == null || !REPLACE_WITH_HEAD_STATES.contains(stateName))
                continue;
            IFile f = resolveFile(view, element);
            if (f != null)
                headReplaceFiles.add(f);
        }
        return headReplaceFiles;
    }

    // ========================================================================
    // Подменю «Комфорт» — добавляет пункты при каждом открытии
    // ========================================================================

    /**
     * Берёт selection напрямую из Table/Tree под фокусом.
     * Используется handler'ами клавиатурных привязок как fallback,
     * когда {@code page.getSelection()} возвращает неверный тип
     * (например, SWTCommit вместо FileDiff в HistoryView).
     */
    public static ISelection selectionFromFocusControl()
    {
        Display d = Display.getCurrent();
        if (d == null)
            return null;
        Control control = d.getFocusControl();
        if (control == null || control.isDisposed())
            return null;
        Control c = control;
        while (c != null && !c.isDisposed() && !(c instanceof Table) && !(c instanceof Tree))
            c = c.getParent();
        if (c == null || c.isDisposed())
            return null;
        if (c instanceof Table table)
        {
            TableItem[] items = table.getSelection();
            if (items.length > 0)
            {
                List<Object> list = new ArrayList<>(items.length);
                for (TableItem ti : items)
                {
                    Object d2 = ti.getData();
                    if (d2 != null)
                        list.add(d2);
                }
                return new StructuredSelection(list);
            }
        }
        else if (c instanceof Tree tree)
        {
            TreeItem[] items = tree.getSelection();
            if (items.length > 0)
            {
                List<Object> list = new ArrayList<>(items.length);
                for (TreeItem ti : items)
                {
                    Object d2 = ti.getData();
                    if (d2 != null)
                        list.add(d2);
                }
                return new StructuredSelection(list);
            }
        }
        return null;
    }

    /** @return full selection (all items) from the widget's own Table/Tree, or null */
    private static ISelection selectionOfClickedControl(Widget menuWidget)
    {
        if (!(menuWidget instanceof Menu menu))
            return null;
        // Поднимаемся до корневого контекстного меню (у него нет parentItem) — там хранится
        // control, из которого реально вызвано меню (см. tryAttachMenuListener). Для самого
        // корневого меню цикл не выполняется ни разу.
        Menu rootMenu = menu;
        for (MenuItem parentItem = rootMenu.getParentItem(); parentItem != null;
            parentItem = rootMenu.getParentItem())
        {
            rootMenu = parentItem.getParent();
            if (rootMenu == null)
                return null;
        }
        Object data = rootMenu.getData("menuControl"); //$NON-NLS-1$
        if (!(data instanceof Control control) || control.isDisposed())
            return null;

        List<Object> elements = new ArrayList<>();
        if (control instanceof Table table)
        {
            for (TableItem ti : table.getSelection())
            {
                if (ti.getData() != null)
                    elements.add(ti.getData());
            }
        }
        else if (control instanceof Tree tree)
        {
            for (TreeItem ti : tree.getSelection())
            {
                if (ti.getData() != null)
                    elements.add(ti.getData());
            }
        }

        if (!elements.isEmpty())
            return new StructuredSelection(elements);
        return null;
    }

    private static MenuAdapter buildItemsMenuListener(IViewPart view)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(3);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = selectionOf(view);

                // Селекция control'а, из которого реально вызвано меню, всегда точнее,
                // чем selection provider части (у HistoryView он отражает выделение
                // таблицы коммитов, даже если меню открыто над списком файлов коммита).
                ISelection viewerSel = selectionOfClickedControl(e.widget);
                if (viewerSel instanceof IStructuredSelection vs && !vs.isEmpty())
                    selection = vs;

                if (selection == null)
                {
                    Global.log("GitChangedFileMenu: selection is null"); //$NON-NLS-1$
                    return;
                }
                if (!(selection instanceof IStructuredSelection structured))
                {
                    Global.log("GitChangedFileMenu: selection class="
                        + selection.getClass().getName()); //$NON-NLS-1$
                    return;
                }
                // Snapshot для handler'а «Добавить в набор» (выполняется после клика по пункту)
                multiSelectionSnapshot = structured;

                Menu submenu = (Menu) e.widget;
                Shell shell = submenu.getShell();

                // === Одиночное выделение: "Показать в навигаторе", "Показать в структуре проекта" и "Открыть объект" ===
                if (structured.size() == 1)
                {
                    Object element = structured.getFirstElement();
                    Global.log("GitChangedFileMenu: resolve single element class="
                        + element.getClass().getName() + " toString=" + element); //$NON-NLS-1$

                    IFile file = resolveFile(view, element);
                    if (file != null && file.exists())
                    {
                        EObject eObject = resolveEObject(file);
                        if (eObject != null)
                        {
                            Global.log("GitChangedFileMenu: resolved IFile=" + file.getFullPath() //$NON-NLS-1$
                                + " EObject=" + eObject); //$NON-NLS-1$

                            IFile capturedFile = file;
                            EObject captured = eObject;

                            MenuItem navItem = new MenuItem(submenu, SWT.PUSH);
                            navItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                                NAV_ITEM_TEXT, "tormozit.git.showInNavigator", GIT_CONTEXT_ID));
                            navItem.addSelectionListener(new SelectionAdapter()
                            {
                                @Override
                                public void widgetSelected(SelectionEvent ev)
                                {
                                    NavigatorReveal.reveal(captured, true);
                                }
                            });
                            addedItems.add(navItem);

                            MenuItem structureItem = new MenuItem(submenu, SWT.PUSH);
                            structureItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                                STRUCTURE_ITEM_TEXT, "tormozit.git.showInProjectStructure", GIT_CONTEXT_ID));
                            structureItem.addSelectionListener(new SelectionAdapter()
                            {
                                @Override
                                public void widgetSelected(SelectionEvent ev)
                                {
                                    NavigatorShowInProjectStructureHandler.showInProjectStructure(
                                        new StructuredSelection(capturedFile));
                                }
                            });
                            addedItems.add(structureItem);

                            MenuItem objItem = new MenuItem(submenu, SWT.PUSH);
                            String objItemText = isHistoryView(view) ? OBJ_ITEM_TEXT_COMMIT : OBJ_ITEM_TEXT;
                            objItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                                objItemText, "tormozit.git.openObject", GIT_CONTEXT_ID));
                            objItem.addSelectionListener(new SelectionAdapter()
                            {
                                @Override
                                public void widgetSelected(SelectionEvent ev)
                                {
                                    openInEditor(captured, capturedFile, shell);
                                }
                            });
                            addedItems.add(objItem);

                            addIrHistoryItemIfNeeded(submenu, view, capturedFile, addedItems);
                        }
                    }
                    else if (isHistoryView(view) && isCommitRowElement(element)
                        && HistoryViewHandler.extractCommitSha(element) != null)
                    {
                        addCompareWithCommitItem(submenu, view, element, addedItems);
                    }
                }

                // === Любое выделение: "Добавить в набор" ===
                MenuItem addToSetItem = new MenuItem(submenu, SWT.PUSH);
                // Определяем проект из первого подходящего элемента selection
                String pName = null;
                for (Object element : structured.toList())
                {
                    IFile f = resolveFile(view, element);
                    if (f != null && f.exists())
                    {
                        IProject p = f.getProject();
                        if (p != null)
                        {
                            pName = p.getName();
                            break;
                        }
                    }
                }
                if (pName == null)
                {
                    IProject p = ActiveProjectTracker.resolveContextProject(view.getViewSite().getPage());
                    if (p != null)
                        pName = p.getName();
                }

                ObjectSets.SetDef addTarget = null;
                if (pName != null)
                {
                    ObjectSetsAddTargetState.getInstance().ensureForProject(pName);
                    addTarget = ObjectSetsAddTargetState.getInstance().getAddTargetSet(pName);
                }
                if (addTarget != null)
                {
                    addToSetItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                        "Добавить в набор <" + addTarget.name + ">", ADD_TO_SET_CMD, GIT_CONTEXT_ID));
                    addToSetItem.setToolTipText("Добавить выбранные объекты метаданных в набор \u00ab" + addTarget.name + "\u00bb" + Global.pluginSignForTooltip());
                    addToSetItem.setEnabled(true);
                }
                else
                {
                    addToSetItem.setText("Добавить в набор <\u2026>");
                    addToSetItem.setToolTipText("Выберите активный набор в панели \u00abНаборы объектов\u00bb" + Global.pluginSignForTooltip());
                    addToSetItem.setEnabled(false);
                }
                addToSetItem.setData(ADD_TO_SET_MARKER, Boolean.TRUE);
                addToSetItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        IHandlerService handlerService =
                            view.getViewSite().getService(IHandlerService.class);
                        if (handlerService == null)
                            return;
                        try
                        {
                            handlerService.executeCommand(ADD_TO_SET_CMD, null);
                        }
                        catch (Exception ex)
                        {
                            Global.log("GitAddToObjectSet: " + ex);
                        }
                    }
                });
                addedItems.add(addToSetItem);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(addedItems);
                addedItems.clear();
                ((Menu) e.widget).getDisplay().asyncExec(() ->
                {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
    }

    // ========================================================================
    // "Заменить на HEAD-ревизию" для частично совместимого выделения
    // ========================================================================

    private static void replaceWithHeadRevision(List<IFile> files, Shell shell)
    {
        if (files.isEmpty())
            return;

        StringBuilder messageBuilder = new StringBuilder();
        List<OrphanedMdo> orphaned = findOrphanedMdo(files);
        Global.tempLog("orphanedMdoFix", "replaceWithHeadRevision: files=" + files.size() //$NON-NLS-1$ //$NON-NLS-2$
            + " orphaned=" + orphaned.stream().map(OrphanedMdo::enFullName).toList()); //$NON-NLS-1$
        if (!orphaned.isEmpty())
        {
            List<String> names = orphaned.stream().map(OrphanedMdo::ruFullName).toList();
            messageBuilder.append(ORPHANED_MDO_NOTICE)
                .append("\nОбъекты: ").append(String.join(", ", names)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        messageBuilder.append("Заменить содержимое " + files.size() + " файл(ов) на состояние из HEAD?\n"
            + "Несохранённые изменения будут потеряны без возможности восстановления.");
        if (files.size() > REPLACE_WITH_HEAD_CHUNK_SIZE)
            messageBuilder.append("\nБольшое количество файлов — операция будет выполняться по частям "
                + "и может занять продолжительное время.");
        String message = messageBuilder.toString();

        boolean confirmed = MessageDialog.openQuestion(shell, REPLACE_WITH_HEAD_ITEM_TEXT, message);
        if (!confirmed)
            return;

        WorkspaceJob job = new WorkspaceJob(REPLACE_WITH_HEAD_ITEM_TEXT)
        {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor)
            {
                IStatus discardStatus = discardInChunks(files, monitor);
                if (monitor.isCanceled())
                    return discardStatus;

                MultiStatus fixErrors = new MultiStatus(Activator.PLUGIN_ID, IStatus.ERROR,
                    "Не удалось привязать все восстановленные объекты к Configuration.mdo", null); //$NON-NLS-1$
                applyOrphanFixes(orphaned, fixErrors);

                if (discardStatus.isOK())
                    return fixErrors.isOK() ? Status.OK_STATUS : fixErrors;
                if (fixErrors.isOK())
                    return discardStatus;
                MultiStatus combined = new MultiStatus(Activator.PLUGIN_ID, IStatus.ERROR,
                    REPLACE_WITH_HEAD_ITEM_TEXT, null);
                combined.merge(discardStatus);
                combined.merge(fixErrors);
                return combined;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Объектный mdo, отсутствующий в {@code Configuration.mdo} — данные для автопривязки. */
    private record OrphanedMdo(IFile objectFile, String ruFullName, String typeEn, String enFullName,
        IFile configurationMdo)
    {
    }

    /**
     * Объекты (mdo), которые операция «Заменить на HEAD-ревизию» создаст на диске
     * (файл сейчас отсутствует — состояние REMOVED/MISSING), но которые не упомянуты
     * в финальной версии {@code Configuration.mdo} — после checkout такой mdo окажется
     * на диске, но не привязанным к конфигурации. Финальная версия {@code Configuration.mdo} —
     * это его HEAD-содержимое, если он сам входит в этот же батч замены, иначе — текущее
     * содержимое рабочей копии (эта операция его не тронет).
     */
    private static List<OrphanedMdo> findOrphanedMdo(List<IFile> files)
    {
        List<OrphanedMdo> orphaned = new ArrayList<>();
        Map<IFile, String> configurationContentCache = new HashMap<>();

        for (IFile file : files)
        {
            if (file.exists())
                continue; // операция не создаёт файл заново — не наш случай
            if (!"mdo".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                continue;
            if (CONFIGURATION_MDO_NAME.equalsIgnoreCase(file.getName()))
                continue;

            String relPath = file.getProjectRelativePath().toString().replace('\\', '/');
            String ruFullName = GetRef.pathToFullName(relPath);
            if (ruFullName == null)
            {
                Global.tempLog("orphanedMdoFix", "findOrphanedMdo: pathToFullName(null) для " + relPath); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            int dot = ruFullName.indexOf('.');
            if (dot < 0)
                continue;
            String typeEn = MdTypeMapping.ruToEnSingRequired(ruFullName.substring(0, dot));
            if (typeEn == null)
            {
                Global.tempLog("orphanedMdoFix", "findOrphanedMdo: ruToEnSingRequired(null) для " //$NON-NLS-1$ //$NON-NLS-2$
                    + ruFullName.substring(0, dot));
                continue;
            }
            String enFullName = typeEn + "." + ruFullName.substring(dot + 1); //$NON-NLS-1$

            IFile configurationMdo = findConfigurationMdo(file);
            if (configurationMdo == null)
            {
                Global.tempLog("orphanedMdoFix", "findOrphanedMdo: findConfigurationMdo(null) для " //$NON-NLS-1$ //$NON-NLS-2$
                    + file.getFullPath());
                continue;
            }

            String configurationContent = configurationContentCache.computeIfAbsent(configurationMdo, cfg ->
                files.contains(cfg) ? readHeadContent(cfg) : readWorkingCopyContent(cfg));
            if (configurationContent == null)
            {
                Global.tempLog("orphanedMdoFix", "findOrphanedMdo: не удалось прочитать " //$NON-NLS-1$ //$NON-NLS-2$
                    + configurationMdo.getFullPath() + " (headBatch=" + files.contains(configurationMdo) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                continue; // не удалось прочитать — не блокируем операцию нашей проверкой
            }

            boolean alreadyPresent = configurationContent.contains(">" + enFullName + "<"); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog("orphanedMdoFix", "findOrphanedMdo: file=" + file.getFullPath() //$NON-NLS-1$ //$NON-NLS-2$
                + " enFullName=" + enFullName + " configurationMdo=" + configurationMdo.getFullPath() //$NON-NLS-1$ //$NON-NLS-2$
                + " alreadyPresent=" + alreadyPresent); //$NON-NLS-1$
            if (!alreadyPresent)
                orphaned.add(new OrphanedMdo(file, ruFullName, typeEn, enFullName, configurationMdo));
        }
        return orphaned;
    }

    /**
     * Привязывает восстановленные объекты из {@code orphaned} к их {@code Configuration.mdo}
     * через BM-транзакцию записи ({@code Configuration.get<Тип>().add(...)} через рефлексию —
     * прямое добавление в ту же коллекцию, которая сериализуется в плоские теги вида
     * {@code <reports>Report.Имя</reports>}). Применяется только к объектам, чей файл
     * реально существует на диске после checkout (защита от файлов из неудачного чанка).
     * Один объект {@code Configuration.mdo} обновляется одной транзакцией на все свои объекты
     * из батча. Ошибки не прерывают обработку остальных — собираются в {@code errors}.
     */
    private static void applyOrphanFixes(List<OrphanedMdo> orphaned, MultiStatus errors)
    {
        if (orphaned.isEmpty())
            return;
        Map<IFile, List<OrphanedMdo>> byConfig = new HashMap<>();
        for (OrphanedMdo o : orphaned)
        {
            if (!o.objectFile().exists())
                continue; // checkout не восстановил файл (сбой чанка) — не привязываем несуществующее
            byConfig.computeIfAbsent(o.configurationMdo(), k -> new ArrayList<>()).add(o);
        }
        for (Map.Entry<IFile, List<OrphanedMdo>> entry : byConfig.entrySet())
            applyOrphanFixesToOneConfiguration(entry.getKey(), entry.getValue(), errors);
    }

    /**
     * Привязывает объекты из {@code fixes} к {@code configurationMdo} текстовой правкой XML —
     * BM write-транзакция (см. историю разработки) не сработала: свежевосстановленный git
     * checkout'ом объект не резолвится через {@code IBmPlatformTransaction.getTopObjectByFqn}
     * даже после {@code refreshLocal}+{@code waitModelSynchronization} (курица-и-яйцо: top-object
     * не индексируется, пока не привязан к Configuration — а привязать не через что, раз не резолвится).
     * Текстовая вставка — та же самая проверка на чтение уже используется и проверена
     * ({@link #findOrphanedMdo}, {@link #isAttachedToConfiguration}): плоские теги вида
     * {@code <reports>Report.Имя</reports>}, одна запись — один тег, без вложенности.
     */
    private static void applyOrphanFixesToOneConfiguration(IFile configurationMdo, List<OrphanedMdo> fixes,
        MultiStatus errors)
    {
        String topic = "orphanedMdoFix"; //$NON-NLS-1$
        Global.tempLog(topic, "applyOrphanFixesToOneConfiguration: start configurationMdo=" //$NON-NLS-1$ //$NON-NLS-2$
            + configurationMdo.getFullPath() + " fixes=" //$NON-NLS-1$
            + fixes.stream().map(OrphanedMdo::enFullName).toList());
        try
        {
            configurationMdo.refreshLocal(IResource.DEPTH_ZERO, null);
            String content = readWorkingCopyContent(configurationMdo);
            if (content == null)
            {
                errors.add(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                    "Не удалось привязать к Configuration.mdo (" + configurationMdo.getFullPath() //$NON-NLS-1$
                        + "): не удалось прочитать файл")); //$NON-NLS-1$
                return;
            }
            String eol = content.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\r\n|\n", -1))); //$NON-NLS-1$
            Set<String> insertedNow = new HashSet<>();
            boolean changed = false;

            for (OrphanedMdo fix : fixes)
            {
                if (content.contains(">" + fix.enFullName() + "<") || !insertedNow.add(fix.enFullName())) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    Global.tempLog(topic, "пропущен (уже есть): " + fix.enFullName()); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                String folder = MdTypeMapping.enSingToFolder(fix.typeEn());
                if (folder == null || folder.isEmpty())
                {
                    errors.add(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                        "Не удалось определить тег Configuration.mdo для типа " + fix.typeEn() //$NON-NLS-1$
                            + " (" + fix.ruFullName() + ") — не привязан к Configuration.mdo")); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                String tag = Character.toLowerCase(folder.charAt(0)) + folder.substring(1);
                String openTag = "<" + tag + ">"; //$NON-NLS-1$ //$NON-NLS-2$
                String closeTag = "</" + tag + ">"; //$NON-NLS-1$ //$NON-NLS-2$

                int insertAt = -1;
                String indent = "  "; //$NON-NLS-1$ (запасной отступ — как у остальных полей верхнего уровня)
                for (int i = 0; i < lines.size(); i++)
                {
                    String trimmed = lines.get(i).trim();
                    if (trimmed.startsWith(openTag) && trimmed.endsWith(closeTag))
                    {
                        insertAt = i + 1;
                        int firstAngle = lines.get(i).indexOf('<');
                        if (firstAngle > 0)
                            indent = lines.get(i).substring(0, firstAngle);
                    }
                }
                if (insertAt < 0)
                {
                    for (int i = 0; i < lines.size(); i++)
                    {
                        if (lines.get(i).trim().equals("</mdclass:Configuration>")) //$NON-NLS-1$
                        {
                            insertAt = i;
                            break;
                        }
                    }
                }
                if (insertAt < 0)
                {
                    errors.add(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                        "Не найдено место вставки в " + configurationMdo.getFullPath() //$NON-NLS-1$
                            + " для " + fix.ruFullName())); //$NON-NLS-1$
                    continue;
                }

                String newLine = indent + openTag + fix.enFullName() + closeTag;
                lines.add(insertAt, newLine);
                changed = true;
                Global.tempLog(topic, "вставлено на позицию " + insertAt + ": " + newLine); //$NON-NLS-1$ //$NON-NLS-2$
            }

            if (!changed)
            {
                Global.tempLog(topic, "нет изменений для записи"); //$NON-NLS-1$
                return;
            }

            String newContent = String.join(eol, lines);
            if (!isWellFormedXml(newContent))
            {
                Global.tempLog(topic, "валидация XML провалена — запись отменена, файл не тронут"); //$NON-NLS-1$ //$NON-NLS-2$
                errors.add(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                    "Автопривязка отменена: результат текстовой вставки в " + configurationMdo.getFullPath() //$NON-NLS-1$
                        + " не прошёл проверку на валидность XML — файл НЕ изменён")); //$NON-NLS-1$
                return;
            }

            try (java.io.ByteArrayInputStream in =
                new java.io.ByteArrayInputStream(newContent.getBytes(StandardCharsets.UTF_8)))
            {
                configurationMdo.setContents(in, IResource.FORCE, null);
            }
            Global.tempLog(topic, "записано: modificationStamp=" + configurationMdo.getModificationStamp() //$NON-NLS-1$ //$NON-NLS-2$
                + " length=" + newContent.length()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLog(topic, "исключение: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            errors.add(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                "Ошибка автопривязки к Configuration.mdo (" + configurationMdo.getFullPath() + "): " + e, e)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Проверка на валидность XML настоящим парсером — последняя страховка перед записью
     * результата текстовой вставки строки в {@code Configuration.mdo}. DOCTYPE запрещён
     * (защита от XXE) — самому файлу он не нужен, у него только XML-декларация.
     */
    private static boolean isWellFormedXml(String content)
    {
        try
        {
            javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(content)));
            return true;
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: isWellFormedXml: " + e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Привязан ли mdo-объект к конфигурации: его полное имя (EN-тип из пути + имя из FQN,
     * см. {@link GetRef#pathToFullName}) должно встречаться в {@code Configuration.mdo}
     * рабочей копии как {@code >Тип.Имя<} — тот же признак, что и в
     * {@link #findOrphanedMdo}. {@code Configuration.mdo} и неопределимые случаи
     * считаются привязанными (без уверенности «отвязан» не показываем).
     */
    public static boolean isAttachedToConfiguration(IFile mdoFile)
    {
        if (mdoFile == null || !"mdo".equalsIgnoreCase(mdoFile.getFileExtension())) //$NON-NLS-1$
            return true;
        if (CONFIGURATION_MDO_NAME.equalsIgnoreCase(mdoFile.getName()))
            return true;

        String relPath = mdoFile.getProjectRelativePath().toString().replace('\\', '/');
        String ruFullName = GetRef.pathToFullName(relPath);
        if (ruFullName == null)
            return true;
        int dot = ruFullName.indexOf('.');
        if (dot < 0)
            return true;
        String typeEn = MdTypeMapping.ruToEnSingRequired(ruFullName.substring(0, dot));
        if (typeEn == null)
            return true;
        String enFullName = typeEn + "." + ruFullName.substring(dot + 1); //$NON-NLS-1$

        IFile configurationMdo = findConfigurationMdo(mdoFile);
        if (configurationMdo == null)
            return true;
        String content = readConfigurationMdoCached(configurationMdo);
        if (content == null)
            return true;
        return content.contains(">" + enFullName + "<"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Кэш содержимого {@code Configuration.mdo} по штампу модификации (декоратор зовёт часто). */
    private static final Map<IFile, String> CONFIGURATION_MDO_CACHE = new HashMap<>();
    private static final Map<IFile, Long> CONFIGURATION_MDO_STAMP = new HashMap<>();

    private static String readConfigurationMdoCached(IFile configurationMdo)
    {
        long stamp = configurationMdo.exists() ? configurationMdo.getModificationStamp() : Long.MIN_VALUE;
        Long cachedStamp = CONFIGURATION_MDO_STAMP.get(configurationMdo);
        if (cachedStamp != null && cachedStamp.longValue() == stamp)
            return CONFIGURATION_MDO_CACHE.get(configurationMdo);
        String content = readWorkingCopyContent(configurationMdo);
        CONFIGURATION_MDO_STAMP.put(configurationMdo, stamp);
        CONFIGURATION_MDO_CACHE.put(configurationMdo, content);
        return content;
    }

    /**
     * {@code src/Configuration/Configuration.mdo} проекта, либо
     * {@code src/ext/<расширение>/Configuration/Configuration.mdo}. Корневой описатель лежит
     * ВНУТРИ папки {@code Configuration} (не рядом с ней) — проверено на реальных проектах
     * (например {@code runtime-EclipseApplication/Конфигурация1/src/Configuration/Configuration.mdo}).
     */
    private static IFile findConfigurationMdo(IFile referenceFile)
    {
        IProject project = referenceFile.getProject();
        String rel = referenceFile.getProjectRelativePath().toString().replace('\\', '/');
        if (rel.startsWith("src/ext/")) //$NON-NLS-1$
        {
            String rest = rel.substring("src/ext/".length()); //$NON-NLS-1$
            int slash = rest.indexOf('/');
            if (slash < 0)
                return null;
            return project.getFile(
                "src/ext/" + rest.substring(0, slash) + "/Configuration/Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (rel.startsWith("src/")) //$NON-NLS-1$
            return project.getFile("src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        return null;
    }

    private static String readWorkingCopyContent(IFile file)
    {
        if (!file.exists())
            return null;
        try (InputStream in = file.getContents())
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: readWorkingCopyContent(" + file.getFullPath() + "): " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /** HEAD-содержимое файла через JGit blob по его репозиторному пути ({@link RepositoryMapping}). */
    private static String readHeadContent(IFile file)
    {
        try
        {
            RepositoryMapping mapping = RepositoryMapping.getMapping(file);
            if (mapping == null)
                return null;
            Repository repository = mapping.getRepository();
            String repoPath = mapping.getRepoRelativePath(file);
            if (repository == null || repoPath == null)
                return null;

            try (RevWalk walk = new RevWalk(repository))
            {
                ObjectId headId = repository.resolve("HEAD"); //$NON-NLS-1$
                if (headId == null)
                    return null;
                RevCommit commit = walk.parseCommit(headId);
                try (TreeWalk treeWalk = TreeWalk.forPath(repository, repoPath, commit.getTree()))
                {
                    if (treeWalk == null)
                        return null;
                    ObjectId blobId = treeWalk.getObjectId(0);
                    byte[] bytes = repository.open(blobId).getBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: readHeadContent(" + file.getFullPath() + "): " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Выполняет {@link DiscardChangesOperation} чанками по
     * {@link #REPLACE_WITH_HEAD_CHUNK_SIZE} файлов вместо одного вызова на всё
     * выделение — см. обоснование у {@link #REPLACE_WITH_HEAD_CHUNK_SIZE}.
     * Отмена — по границе чанка (сам {@code DiscardChangesOperation.execute()}
     * не проверяет {@code isCanceled()} внутри себя). Ошибка одного чанка не
     * прерывает остальные: к моменту {@code CoreException} checkout чанка уже
     * применён (падать может только последующий resource-refresh), поэтому
     * прерывать необработанные файлы из-за сбоя в одном чанке не в пользу пользователя.
     */
    private static IStatus discardInChunks(List<IFile> files, IProgressMonitor monitor)
    {
        int total = files.size();
        int chunkCount = (total + REPLACE_WITH_HEAD_CHUNK_SIZE - 1) / REPLACE_WITH_HEAD_CHUNK_SIZE;
        monitor.beginTask(REPLACE_WITH_HEAD_ITEM_TEXT, chunkCount);

        MultiStatus errors = new MultiStatus(Activator.PLUGIN_ID, IStatus.ERROR,
            "Не удалось заменить некоторые файлы на HEAD-ревизию", null); //$NON-NLS-1$

        try
        {
            for (int start = 0; start < total; start += REPLACE_WITH_HEAD_CHUNK_SIZE)
            {
                if (monitor.isCanceled())
                    return Status.CANCEL_STATUS;

                int end = Math.min(start + REPLACE_WITH_HEAD_CHUNK_SIZE, total);
                monitor.subTask("Файлы " + (start + 1) + "–" + end + " из " + total); //$NON-NLS-1$

                IResource[] chunkResources = files.subList(start, end).toArray(new IResource[0]);
                try
                {
                    new DiscardChangesOperation(chunkResources, "HEAD").execute(monitor); //$NON-NLS-1$
                }
                catch (CoreException e)
                {
                    errors.add(e.getStatus());
                }

                monitor.worked(1);
            }
        }
        finally
        {
            monitor.done();
        }

        return errors.isOK() ? Status.OK_STATUS : errors;
    }

    // ========================================================================
    // HistoryView: «История ИР»
    // ========================================================================

    private static void addIrHistoryItemIfNeeded(Menu submenu, IViewPart view,
        IFile capturedFile, List<MenuItem> addedItems)
    {
        if (!isHistoryView(view))
            return;

        MenuItem irItem = new MenuItem(submenu, SWT.PUSH);
        irItem.setText("История ИР");
        irItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                String commitSha = extractCommitShaFrom(view);
                HistoryViewHandler.executeIrHistoryWithFile(capturedFile, commitSha);
            }
        });
        addedItems.add(irItem);
    }

    // ========================================================================
    // HistoryView: «Сравнить рабочий каталог с коммитом» — полное сравнение
    // конфигурации (как «Групповая разработка / Сравнить / Коммит...» в
    // Навигаторе). Открывает окно «Select a Commit» с активацией в нём коммита
    // из выбранной строки панели «История»; сравнение запускается по «Открыть».
    // ========================================================================

    /**
     * Отличает строку коммита (верхняя таблица панели «История») от строки
     * изменённого файла (нижний список файлов коммита) — у обеих
     * {@link HistoryViewHandler#extractCommitSha} может успешно вернуть SHA
     * (у файловой строки — через её собственный {@code getCommit()}), поэтому
     * одного {@code extractCommitSha != null} недостаточно. Строка коммита сама
     * является {@code AnyObjectId} ({@code getId()} есть), но не описывает путь
     * файла ({@code getPath()} нет) — в отличие от {@code FileDiff}.
     */
    private static boolean isCommitRowElement(Object element)
    {
        return Global.call(element, "getPath") == null && Global.call(element, "getId") != null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addCompareWithCommitItem(Menu submenu, IViewPart view,
        Object commitElement, List<MenuItem> addedItems)
    {
        MenuItem item = new MenuItem(submenu, SWT.PUSH);
        item.setText(COMPARE_WITH_COMMIT_TEXT);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                performCompareWithCommit(view, commitElement, submenu.getShell());
            }
        });
        addedItems.add(item);
    }

    /**
     * Репозиторий, с которым сейчас работает панель «История»
     * ({@code GenericHistoryView}/{@code HistoryView}), через рефлексию к его
     * {@code IHistoryPage.currentRepo}.
     */
    public static Repository resolveRepository(IWorkbenchPart view)
    {
        try
        {
            Object historyPage = Global.call(view, "getHistoryPage"); //$NON-NLS-1$
            Object repoObj = historyPage != null ? Global.getField(historyPage, "currentRepo") : null; //$NON-NLS-1$
            if (repoObj instanceof Repository repository)
                return repository;
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    /**
     * Повторяет поведение EDT-обработчика {@code DtCompareWithCommitHandler}
     * («Групповая разработка / Сравнить / Коммит...» в Навигаторе): открывает
     * {@code DtCommitSelectionDialog} с активированным в нём коммитом из строки
     * панели «История», а по «Открыть» вызывает ту же готовую функцию
     * {@code Utils.performCompareWith(...)} бандла {@code com._1c.g5.v8.dt.compare.ui}.
     */
    private static void performCompareWithCommit(IViewPart view, Object commitElement, Shell shell)
    {
        try
        {
            String sha = HistoryViewHandler.extractCommitSha(commitElement);
            if (sha == null)
            {
                Global.log("CompareWithCommit: не удалось извлечь SHA коммита"); //$NON-NLS-1$
                return;
            }

            Repository repository = resolveRepository(view);
            if (repository == null)
            {
                Global.log("CompareWithCommit: repository не найден"); //$NON-NLS-1$
                return;
            }

            IProject project = resolveProject(view, commitElement);
            if (project == null)
            {
                Global.log("CompareWithCommit: project не найден"); //$NON-NLS-1$
                return;
            }

            Bundle compareUiBundle = Platform.getBundle(COMPARE_UI_BUNDLE_ID);
            if (compareUiBundle == null)
            {
                Global.log("CompareWithCommit: бандл " + COMPARE_UI_BUNDLE_ID + " не найден"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Class<?> dialogCls = compareUiBundle.loadClass(COMPARE_DIALOG_CLASS);
            Class<?> utilsCls = compareUiBundle.loadClass(COMPARE_UTILS_CLASS);

            Constructor<?> ctor = dialogCls.getConstructor(
                Shell.class, Repository.class, IResource[].class);
            Object dialog = ctor.newInstance(shell, repository, new IResource[] { project });

            // create() до open(): строит диалог и запускает фоновую загрузку
            // списка коммитов (updateUi() выполнится уже в event loop окна).
            Global.invoke(dialog, "create"); //$NON-NLS-1$
            translateCommitDialogTitle(dialog);
            preselectCommit(dialog, repository, sha);

            // Чекбокс «Разрешить удаление объектов главного источника» — до open(),
            // в сетку диалога под штатными настройками (после create()).
            Button deletionAllowedCheckBox = createMainSideObjectsDeletionAllowedCheckBox(dialog);
            // По умолчанию включён (см. setSelection(true) в createMainSideObjectsDeletionAllowedCheckBox).
            boolean[] mainSideObjectsDeletionAllowedHolder = { true };
            if (deletionAllowedCheckBox != null)
            {
                deletionAllowedCheckBox.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        mainSideObjectsDeletionAllowedHolder[0] = deletionAllowedCheckBox.getSelection();
                    }
                });
            }

            // Размер окна — после всех pack()/layout() выше, иначе его затрёт штатная упаковка.
            installCommitDialogSizeMemory(dialog);

            if ((int) Global.call(dialog, "open") != IDialogConstants.OK_ID) //$NON-NLS-1$
                return;

            Object commitIdObj = Global.call(dialog, "getCommitId"); //$NON-NLS-1$
            if (!(commitIdObj instanceof ObjectId commitId))
            {
                Global.log("CompareWithCommit: коммит не выбран"); //$NON-NLS-1$
                return;
            }
            String chosenSha = commitId.name();
            MatchingStrategy strategy = (MatchingStrategy) Global.call(dialog, "getMatchingStrategy"); //$NON-NLS-1$
            boolean parseBsl = (boolean) Global.call(dialog, "isParseBslModuleStructure"); //$NON-NLS-1$
            boolean readOnly = (boolean) Global.call(dialog, "isReadOnlyModeComparison"); //$NON-NLS-1$
            String mergeSettingsFileName = (String) Global.call(dialog, "getMergeSettingsFileName"); //$NON-NLS-1$

            Class<?> pluginCls = compareUiBundle.loadClass(COMPARE_UI_PLUGIN_CLASS);
            Class<?> helperCls = compareUiBundle.loadClass(COMPARISON_EDITOR_OPEN_HELPER_CLASS);

            Object plugin = Global.invoke(pluginCls, "getDefault"); //$NON-NLS-1$
            Object injectorObj = plugin != null ? Global.invoke(plugin, "getInjector") : null; //$NON-NLS-1$
            if (!(injectorObj instanceof Injector injector))
            {
                Global.log("CompareWithCommit: injector бандла compare.ui не найден"); //$NON-NLS-1$
                return;
            }

            Object comparisonEditorOpenHelper = injector.getInstance(helperCls);

            BundleContext ctx = Global.ourContext();
            ServiceReference<IComparisonManager> comparisonManagerRef =
                ctx.getServiceReference(IComparisonManager.class);
            IComparisonManager comparisonManager = ctx.getService(comparisonManagerRef);
            IQualifiedNameFilePathConverter filePathConverter =
                (IQualifiedNameFilePathConverter) Global.getField(comparisonManager,
                    "qualifiedNameFilePathConverter"); //$NON-NLS-1$

            IV8ProjectManager v8ProjectManager =
                (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);

            Object commitDisplayRepr = Global.invoke(utilsCls, "getCommitUserRepresentation", //$NON-NLS-1$
                repository, chosenSha);
            String displayRepr = commitDisplayRepr instanceof String s ? s : chosenSha;

            boolean mainSideObjectsDeletionAllowed = mainSideObjectsDeletionAllowedHolder[0];
            Global.log("CompareWithCommit: флажок \"" + MAIN_SIDE_OBJECTS_DELETION_ALLOWED_TEXT //$NON-NLS-1$
                + "\"=" + mainSideObjectsDeletionAllowed); //$NON-NLS-1$

            new CompareWithCommitWorker(repository, project, chosenSha, displayRepr, strategy,
                readOnly, parseBsl, mergeSettingsFileName, filePathConverter, v8ProjectManager,
                comparisonManager, comparisonEditorOpenHelper, helperCls, shell,
                mainSideObjectsDeletionAllowed).schedule();
        }
        catch (Exception ex)
        {
            Global.log("CompareWithCommit: " + ex); //$NON-NLS-1$
        }
    }

    /**
     * Русский заголовок окна «Выбор коммита». Поставщик (EGit {@code nl_ru}) его
     * не переводит — в {@code uitext_ru.properties} ключа
     * {@code CommitSelectionDialog_WindowTitle} нет, поэтому даже в русском EDT
     * окно называется «Select a Commit». Заголовок принудительно заменяется на
     * русский всегда.
     */
    private static void translateCommitDialogTitle(Object dialog)
    {
        Shell dialogShell = (Shell) Global.call(dialog, "getShell"); //$NON-NLS-1$
        if (dialogShell == null || dialogShell.isDisposed())
            return;
        dialogShell.setText(COMMIT_DIALOG_RUSSIAN_TITLE);
    }

    /**
     * Активирует (выделяет) коммит {@code sha} в списке окна «Select a Commit».
     * Через {@code CommitGraphTable.selectCommitStored(...)}: коммит сразу
     * сохраняется в поле {@code commitToShow}, поэтому когда фоновая загрузка
     * закончится и {@code CommitSelectionDialog.updateUi()} выполнит
     * {@code setInput(...)}, строка коммита будет автоматически выделена.
     */
    private static void preselectCommit(Object dialog, Repository repository, String sha)
    {
        try
        {
            Object table = Global.getField(dialog, "table"); //$NON-NLS-1$
            if (table == null)
                return;
            try (RevWalk walk = new RevWalk(repository))
            {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
                Global.invoke(table, "selectCommitStored", commit); //$NON-NLS-1$
            }
        }
        catch (Exception ex)
        {
            Global.log("CompareWithCommit: preselect: " + ex); //$NON-NLS-1$
        }
    }

    private static String extractCommitShaFrom(IViewPart view)
    {
        ISelection sel = selectionOf(view);
        if (!(sel instanceof IStructuredSelection ss) || ss.size() != 1)
            return "";
        Object element = ss.getFirstElement();
        return HistoryViewHandler.extractCommitSha(element);
    }

    // ========================================================================
    // HistoryView: файлы, изменённые коммитом (для «Добавить в набор» по
    // выделению в списке коммитов)
    // ========================================================================

    /**
     * Файлы, изменённые коммитом {@code sha} (diff с первым родителем;
     * для коммита без родителей — diff с пустым деревом). Удалённые файлы
     * не включаются — их уже не с чем сопоставить в наборе объектов.
     */
    public static List<IFile> resolveFilesChangedByCommit(Repository repository, String sha)
    {
        List<IFile> result = new ArrayList<>();
        if (repository == null || sha == null || sha.isBlank())
            return result;
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repository))
        {
            org.eclipse.jgit.revwalk.RevCommit commit =
                walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(sha));
            org.eclipse.jgit.revwalk.RevCommit parent = commit.getParentCount() > 0
                ? walk.parseCommit(commit.getParent(0).getId()) : null;
            walk.parseHeaders(commit);
            org.eclipse.jgit.lib.ObjectId oldTree = parent != null ? parent.getTree() : null;

            try (org.eclipse.jgit.diff.DiffFormatter df =
                new org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.NullOutputStream.INSTANCE))
            {
                df.setRepository(repository);
                df.setDetectRenames(true);
                List<org.eclipse.jgit.diff.DiffEntry> diffs = df.scan(oldTree, commit.getTree());
                for (org.eclipse.jgit.diff.DiffEntry entry : diffs)
                {
                    if (entry.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE)
                        continue;
                    IFile file = fileFromRepoPath(repository, entry.getNewPath());
                    if (file != null && file.exists())
                        result.add(file);
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: resolveFilesChangedByCommit(" + sha + "): " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    private static IFile fileFromRepoPath(Repository repository, String relativePath)
    {
        if (relativePath == null || relativePath.isBlank())
            return null;
        java.io.File absolute = new java.io.File(repository.getWorkTree(), relativePath);
        IPath location = org.eclipse.core.runtime.Path.fromOSString(absolute.getAbsolutePath());
        IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocation(location);
        return files != null && files.length > 0 ? files[0] : null;
    }

    // ========================================================================
    // Resolution
    // ========================================================================

    public static IFile resolveFile(IWorkbenchPart view, Object element)
    {
        if (element instanceof IFile file)
            return file;

        // IAdaptable (PlatformObject subclasses like StagingEntry)
        if (element instanceof org.eclipse.core.runtime.IAdaptable adaptable)
        {
            IFile adapted = adaptable.getAdapter(IFile.class);
            if (adapted != null)
                return adapted;
        }

        // StagingEntry.getFile() via reflection — returns IFile directly
        try
        {
            Object f = Global.call(element, "getFile"); //$NON-NLS-1$
            if (f instanceof IFile file)
                return file;
        }
        catch (Exception ignored)
        {
        }

        // StagingEntry.getLocation() via reflection — returns absolute IPath
        try
        {
            Object loc = Global.call(element, "getLocation"); //$NON-NLS-1$
            if (loc instanceof IPath p)
            {
                IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocation(p);
                if (files != null && files.length > 0)
                    return files[0];
            }
        }
        catch (Exception ignored)
        {
        }

        // Fallback: getPath() → project-relative path → IFile
        String path = null;

        if (element instanceof String str)
            path = str;

        if (path == null)
        {
            try
            {
                Object p = Global.call(element, "getPath"); //$NON-NLS-1$
                if (p instanceof String s)
                    path = s;
            }
            catch (Exception ignored)
            {
            }
        }

        if (path != null)
        {
            IProject project = resolveProject(view, element);
            if (project != null)
            {
                String normalized = path.replace('\\', '/');
                IFile file = project.getFile(normalized);
                if (file.exists())
                    return file;
            }
        }

        return null;
    }

    private static IProject resolveProject(IWorkbenchPart view, Object element)
    {
        // Try to get project from the part that triggered the menu (e.g. history view)
        if (view != null)
        {
            IProject p = Global.getActiveProject(view, false);
            if (p != null)
                return p;
        }

        IWorkbenchPage page = activePage();
        if (page == null)
            return null;

        // Try from active page
        IProject project = Global.getActiveProject(page, false);
        if (project != null)
            return project;

        // Fallback: try to extract project from StagingEntry's repository path
        try
        {
            Object repo = Global.call(element, "getRepository"); //$NON-NLS-1$
            if (repo != null)
            {
                Object workTree = Global.call(repo, "getWorkTree"); //$NON-NLS-1$
                if (workTree instanceof java.io.File workDir)
                {
                    String repoPath = workDir.getAbsolutePath().replace('\\', '/');
                    for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                    {
                        if (!p.isOpen())
                            continue;
                        String projPath = p.getLocation().toFile().getAbsolutePath().replace('\\', '/');
                        if (repoPath.equals(projPath) || repoPath.startsWith(projPath + "/"))
                            return p;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    /**
     * Резолвит {@link EObject}-владельца файла (форма, команда, макет и т.п.,
     * а не только объект верхнего уровня). Полное имя строится тем же способом,
     * что и для «Добавить в набор» ({@link ObjectSetsItems#fromFilePath}) —
     * прежде всего через {@link GetRef#pathToFullName} (по пути файла в проекте):
     * он корректно останавливается на владеющем объекте (например,
     * {@code Forms/<Форма>/Module.bsl} → сама форма), не спускаясь глубже.
     * {@link GoToDefinition#fullNameFromFile} (FQN-конвертер Xtext) — запасной
     * вариант: для модулей форм он на практике даёт FQN с лишней парой сегментов
     * («…Форма.<Имя>.Форма.Module»), из-за чего {@link GoToDefinition#resolveEObjectByQualifiedName}
     * не находит объект — {@link MdTypeMapping#bmFqnToRuFullName} отбрасывает
     * висячий сегмент только при нечётной длине FQN, а тут она чётная.
     */
    public static EObject resolveEObject(IFile file)
    {
        try
        {
            String relPath = file.getProjectRelativePath().toString();
            if (GetRef.isConfigurationRootPath(relPath))
                return null;

            IProject project = file.getProject();
            IV8ProjectManager projectManager =
                (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            if (projectManager == null)
                return null;
            IV8Project v8Project = projectManager.getProject(project);
            if (v8Project == null)
                return null;

            String fullName = GetRef.pathToFullName(relPath);
            if (fullName == null || fullName.isBlank())
                fullName = GoToDefinition.fullNameFromFile(file);
            if (fullName == null || fullName.isBlank())
                return null;

            return GoToDefinition.resolveEObjectByQualifiedName(fullName, v8Project);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // ========================================================================
    // OpenHelper — открытие объекта в редакторе EDT
    // ========================================================================

    public static void openInEditor(EObject eObject, IFile file, Shell shell)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper"); //$NON-NLS-1$
            Object helper = cls.getConstructor().newInstance();

            // For .bsl files: use platform URI to open parent object with module page
            if (file != null && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
            {
                URI moduleUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true)
                                   .appendFragment("/0"); //$NON-NLS-1$
                for (java.lang.reflect.Method m : cls.getMethods())
                {
                    if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2) //$NON-NLS-1$
                        continue;
                    if (m.getParameterTypes()[0].equals(URI.class)
                        && m.getParameterTypes()[1].equals(ISelection.class))
                    {
                        m.invoke(helper, moduleUri, null);
                        return;
                    }
                }
            }

            // Default: openEditor(EObject)
            for (java.lang.reflect.Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 1) //$NON-NLS-1$
                    continue;
                if (m.getParameterTypes()[0].isAssignableFrom(eObject.getClass()))
                {
                    m.invoke(helper, eObject);
                    return;
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void logAllViewReferences(IWorkbenchPage page, String tag)
    {
        try
        {
            StringBuilder sb = new StringBuilder("GitChangedFileMenu: " + tag + " views=["); //$NON-NLS-1$ //$NON-NLS-2$
            for (IViewReference ref : page.getViewReferences())
            {
                if (sb.length() > 40)
                    sb.append(", "); //$NON-NLS-1$
                IViewPart v = ref.getView(false);
                sb.append(v != null ? v.getSite().getId() : ref.getId() + "(not-created)");
            }
            sb.append("]"); //$NON-NLS-1$
            Global.log(sb.toString());
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: logViews error: " + e); //$NON-NLS-1$
        }
    }

    // ========================================================================
    // Workbench helpers
    // ========================================================================

    private static IWorkbenchWindow activeWindow()
    {
        try
        {
            return PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static IWorkbenchPage activePage()
    {
        IWorkbenchWindow window = activeWindow();
        return window != null ? window.getActivePage() : null;
    }

    private static boolean isHistoryView(IWorkbenchPart part)
    {
        if (part == null || part.getSite() == null)
            return false;
        String id = part.getSite().getId();
        return EGIT_HISTORY_VIEW_ID.equals(id) || TEAM_HISTORY_VIEW_ID.equals(id);
    }

    public static boolean isGitView(IWorkbenchPart part)
    {
        if (part == null || part.getSite() == null)
            return false;
        String id = part.getSite().getId();
        return DT_STAGING_VIEW_ID.equals(id) || DT_TEAM_VIEW_ID.equals(id)
            || EGIT_STAGING_VIEW_ID.equals(id) || EGIT_REPOS_VIEW_ID.equals(id)
            || EGIT_HISTORY_VIEW_ID.equals(id) || TEAM_HISTORY_VIEW_ID.equals(id);
    }

    private static ISelection selectionOf(IWorkbenchPart view)
    {
        ISelectionProvider provider = view.getSite().getSelectionProvider();
        return provider != null ? provider.getSelection() : null;
    }

    /**
     * Чекбокс «Разрешить удаление объектов главного источника» под штатными
     * настройками диалога «Выбор коммита». Родитель берётся у
     * {@code matchingStrategySelectionControl} (появляется только после
     * {@code create()}); сетка у него — из одной колонки, как у соседних
     * элементов диалога.
     *
     * @return созданный чекбокс или {@code null}, если добавить не удалось
     */
    private static Button createMainSideObjectsDeletionAllowedCheckBox(Object dialog)
    {
        try
        {
            Object strategyControl = Global.getField(dialog, "matchingStrategySelectionControl"); //$NON-NLS-1$
            if (!(strategyControl instanceof Control control))
                return null;
            Composite parent = control.getParent();
            if (parent == null || parent.isDisposed())
                return null;
            Button button = new Button(parent, SWT.CHECK);
            button.setText(MAIN_SIDE_OBJECTS_DELETION_ALLOWED_TEXT);
            button.setToolTipText(MAIN_SIDE_OBJECTS_DELETION_ALLOWED_TOOLTIP + Global.pluginSignForTooltip());
            // По умолчанию включён — без участия плагина сравнение с коммитом ведёт себя штатно.
            button.setSelection(true);
            GridDataFactory.fillDefaults().applyTo(button);
            // Под штатным чекбоксом «Сравнить без объединения» (ReadOnlyModeComparisonComposite),
            // а не в конец списка контролов.
            Object readOnlyComposite = Global.getField(dialog, "readOnlyModeComparisonComposite"); //$NON-NLS-1$
            if (readOnlyComposite instanceof Control readOnlyControl
                && !readOnlyControl.isDisposed()
                && readOnlyControl.getParent() == parent)
                button.moveBelow(readOnlyControl);
            parent.layout();
            Shell dialogShell = (Shell) Global.call(dialog, "getShell"); //$NON-NLS-1$
            if (dialogShell != null && !dialogShell.isDisposed())
                dialogShell.pack();
            return button;
        }
        catch (Exception ex)
        {
            Global.log("CompareWithCommit: checkbox: " + ex); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Запоминание размера окна «Выбор коммита»: штатный
     * {@code DtCommitSelectionDialog} размер не сохраняет (нет
     * {@code getDialogBoundsSettings()}), поэтому окно каждый раз открывается
     * упакованным по минимуму. Восстанавливаем сохранённый размер до
     * {@code open()} (центр окна сохраняется), а при закрытии запоминаем текущий.
     */
    private static void installCommitDialogSizeMemory(Object dialog)
    {
        try
        {
            Shell shell = (Shell) Global.call(dialog, "getShell"); //$NON-NLS-1$
            if (shell == null || shell.isDisposed())
                return;
            applyStoredCommitDialogSize(shell);
            shell.addDisposeListener(e -> saveCommitDialogSize((Shell) e.widget));
        }
        catch (Exception ex)
        {
            Global.log("CompareWithCommit: size memory: " + ex); //$NON-NLS-1$
        }
    }

    private static void applyStoredCommitDialogSize(Shell shell)
    {
        IDialogSettings settings = commitDialogSettings();
        if (settings.get(KEY_COMMIT_DIALOG_WIDTH) == null || settings.get(KEY_COMMIT_DIALOG_HEIGHT) == null)
            return;

        int width = settings.getInt(KEY_COMMIT_DIALOG_WIDTH);
        int height = settings.getInt(KEY_COMMIT_DIALOG_HEIGHT);
        if (width <= 0 || height <= 0)
            return;

        Rectangle old = shell.getBounds();
        // Центр окна не сдвигаем — иначе увеличенное окно «уползает» вправо-вниз.
        Rectangle target = new Rectangle(old.x + (old.width - width) / 2,
            old.y + (old.height - height) / 2, width, height);
        shell.setBounds(clampCommitDialogToMonitor(shell, target));
    }

    private static void saveCommitDialogSize(Shell shell)
    {
        if (shell == null || shell.isDisposed() || shell.getMinimized())
            return;
        Rectangle bounds = shell.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
            return;
        IDialogSettings settings = commitDialogSettings();
        settings.put(KEY_COMMIT_DIALOG_WIDTH, bounds.width);
        settings.put(KEY_COMMIT_DIALOG_HEIGHT, bounds.height);
    }

    /** Не даёт восстановленному окну вылезти за границы монитора, на котором оно открывается. */
    private static Rectangle clampCommitDialogToMonitor(Shell shell, Rectangle bounds)
    {
        Rectangle area = shell.getMonitor().getClientArea();
        int width = Math.max(COMMIT_DIALOG_MIN_WIDTH, Math.min(bounds.width, area.width));
        int height = Math.max(COMMIT_DIALOG_MIN_HEIGHT, Math.min(bounds.height, area.height));
        int x = Math.max(area.x, Math.min(bounds.x, area.x + area.width - width));
        int y = Math.max(area.y, Math.min(bounds.y, area.y + area.height - height));
        return new Rectangle(x, y, width, height);
    }

    private static IDialogSettings commitDialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(COMMIT_DIALOG_SETTINGS_SECTION);
        return section != null ? section : top.addNewSection(COMMIT_DIALOG_SETTINGS_SECTION);
    }

    /**
     * Повторяет конвейер {@code Utils.performCompareWith(...)} → {@code CompareWithPerformer}
     * бандла {@code com._1c.g5.v8.dt.compare.ui} (декомпиляция — {@code .tmp/bundles/compare-ui/}):
     * находит изменения между выбранным коммитом и рабочим каталогом, строит
     * {@code CompareMergeProcessDescriptor} с настройками и запускает сравнение.
     * В отличие от штатного конвейера умеет передать флаг «Разрешить удаление объектов
     * главного источника» в настройки процесса.
     */
    private static final class CompareWithCommitWorker extends Job
    {
        private final Repository repository;
        private final IProject project;
        private final String revisionToCompareWith;
        private final String revisionToCompareWithName;
        private final MatchingStrategy matchingStrategy;
        private final boolean noMerge;
        private final boolean parseBslModuleStructure;
        private final String mergeSettingsFileName;
        private final IQualifiedNameFilePathConverter filePathConverter;
        private final IV8ProjectManager v8ProjectManager;
        private final IComparisonManager comparisonManager;
        private final Object comparisonEditorOpenHelper;
        private final Class<?> helperCls;
        private final Shell shell;
        private final boolean mainSideObjectsDeletionAllowed;

        CompareWithCommitWorker(Repository repository, IProject project, String revisionToCompareWith,
            String revisionToCompareWithName, MatchingStrategy matchingStrategy, boolean noMerge,
            boolean parseBslModuleStructure, String mergeSettingsFileName,
            IQualifiedNameFilePathConverter filePathConverter, IV8ProjectManager v8ProjectManager,
            IComparisonManager comparisonManager, Object comparisonEditorOpenHelper, Class<?> helperCls,
            Shell shell, boolean mainSideObjectsDeletionAllowed)
        {
            super(COMPARE_WITH_COMMIT_TEXT);
            this.repository = repository;
            this.project = project;
            this.revisionToCompareWith = revisionToCompareWith;
            this.revisionToCompareWithName = revisionToCompareWithName;
            this.matchingStrategy = matchingStrategy;
            this.noMerge = noMerge;
            this.parseBslModuleStructure = parseBslModuleStructure;
            this.mergeSettingsFileName = mergeSettingsFileName;
            this.filePathConverter = filePathConverter;
            this.v8ProjectManager = v8ProjectManager;
            this.comparisonManager = comparisonManager;
            this.comparisonEditorOpenHelper = comparisonEditorOpenHelper;
            this.helperCls = helperCls;
            this.shell = shell;
            this.mainSideObjectsDeletionAllowed = mainSideObjectsDeletionAllowed;
        }

        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
            try
            {
                RevCommit commitToCompareWith = null;
                RevCommit baseCommit = null;
                try (RevWalk walk = new RevWalk(repository))
                {
                    RevCommit headCommit =
                        walk.parseCommit(repository.findRef("HEAD").getObjectId()); //$NON-NLS-1$
                    commitToCompareWith =
                        GitCompareUtils.getRevCommit(repository, walk, revisionToCompareWith, true);
                    if (commitToCompareWith != null)
                        baseCommit = GitCompareUtils.getBaseCommit(headCommit, commitToCompareWith, walk);
                }

                List<CompareMergeProcessDescriptor> descriptors = new ArrayList<>(1);
                SubMonitor subMonitor = SubMonitor.convert(monitor, 1);
                descriptors.add(buildDescriptor(project, commitToCompareWith, baseCommit, subMonitor.split(1)));
                CompareMergeProcessBatch batch = new CompareMergeProcessBatch(descriptors);

                if (descriptors.stream()
                    .map(CompareMergeProcessDescriptor::getHandle)
                    .allMatch(this::hasNothingToCompare))
                {
                    onNothingToCompare();
                    return Status.OK_STATUS;
                }

                runComparison(batch);
                return Status.OK_STATUS;
            }
            catch (OperationCanceledException ex)
            {
                return Status.CANCEL_STATUS;
            }
            catch (Exception ex)
            {
                return new Status(IStatus.ERROR, "tormozit.comfort", "CompareWithCommit: " + ex, ex); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private boolean hasNothingToCompare(ComparisonProcessHandle handle)
        {
            return handle.getScope(ComparisonSide.MAIN).isEmpty()
                && handle.getScope(ComparisonSide.OTHER).isEmpty();
        }

        private void onNothingToCompare()
        {
            CompareUiUtils.syncExec(() -> MessageDialog.openInformation(
                shell, NOTHING_TO_COMPARE_TITLE, NOTHING_TO_COMPARE_MESSAGE));
        }

        private void runComparison(CompareMergeProcessBatch batch)
        {
            final IEditorPart[] openedEditor = new IEditorPart[1];
            CompareUiUtils.syncExec(() -> {
                try
                {
                    Method open = helperCls.getMethod("openComparisonEditor", //$NON-NLS-1$
                        List.class, boolean.class, String.class, String.class, String.class);
                    Object result = open.invoke(comparisonEditorOpenHelper, batch.getDescriptors(),
                        Boolean.valueOf(noMerge), WORKING_TREE_NAME, revisionToCompareWithName,
                        NO_MERGE_CONFIRM_MESSAGE);
                    if (result instanceof IEditorPart editorPart)
                        openedEditor[0] = editorPart;
                }
                catch (Exception ex)
                {
                    Global.log("CompareWithCommit: openComparisonEditor: " + ex); //$NON-NLS-1$
                }
            });
            if (!mainSideObjectsDeletionAllowed)
                disableMainSideDeletions(openedEditor[0]);
        }

        /**
         * При выключенном «Разрешить удаление объектов главного источника» запрещает
         * слияние узлам, где объект есть только в главном источнике (рабочий каталог), —
         * применение коммита удалило бы такой объект. Вендорский флаг
         * {@code mainSideObjectsDeletionAllowed} в сравнении с коммитом (всегда
         * трёхстороннее) не используется, поэтому блокировка делается здесь: после
         * завершения сравнения по дереву сессии отключается слияние таких узлов.
         */
        private void disableMainSideDeletions(IEditorPart editor)
        {
            if (editor == null)
                return;
            Job disableJob = new Job("CompareWithCommit: блокировка удаления объектов главного источника") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    long startTime = System.currentTimeMillis();
                    long deadline = startTime + SESSION_WAIT_TIMEOUT_MILLIS;
                    IComparisonSession session = null;
                    while (System.currentTimeMillis() < deadline)
                    {
                        if (editor.getSite() == null || editor.getSite().getPage() == null
                            || editor.getSite().getShell().isDisposed())
                        {
                            Global.log("CompareWithCommit: редактор сравнения закрыт до завершения " //$NON-NLS-1$
                                + "сравнения, блокировка удаления отменена"); //$NON-NLS-1$
                            return Status.CANCEL_STATUS;
                        }
                        session = CompareConfigSelectionListener.getSession(editor);
                        if (session != null)
                        {
                            ComparisonProcessStatus status = session.getStatus();
                            if (status == ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED)
                                break;
                            if (status == ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED)
                            {
                                Global.log("CompareWithCommit: сравнение отменено пользователем, " //$NON-NLS-1$
                                    + "блокировка удаления не выполняется"); //$NON-NLS-1$
                                return Status.CANCEL_STATUS;
                            }
                        }
                        try
                        {
                            Thread.sleep(SESSION_WAIT_POLL_MILLIS);
                        }
                        catch (InterruptedException ex)
                        {
                            return Status.CANCEL_STATUS;
                        }
                    }
                    long waitedMillis = System.currentTimeMillis() - startTime;
                    if (session == null || session.getStatus() != ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED)
                    {
                        Global.log("CompareWithCommit: не удалось дождаться завершения сравнения за " //$NON-NLS-1$
                            + waitedMillis + " мс (таймаут " + SESSION_WAIT_TIMEOUT_MILLIS //$NON-NLS-1$
                            + " мс), блокировка удаления не выполнена, статус=" //$NON-NLS-1$
                            + (session != null ? session.getStatus() : null)); //$NON-NLS-1$
                    }
                    else
                    {
                        Global.log("CompareWithCommit: сравнение завершено за " + waitedMillis + " мс"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    final IComparisonSession finishedSession = session;
                    CompareUiUtils.syncExec(() -> {
                        try
                        {
                            if (finishedSession == null
                                || finishedSession.getStatus() != ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED)
                                return;
                            Object comparisonManager = Global.getField(finishedSession, "comparisonManager"); //$NON-NLS-1$
                            int[] disabledHolder = { 0 };
                            if (comparisonManager != null)
                            {
                                AbstractBmTask<Object> task = new AbstractBmTask<>(
                                    "CompareWithCommit: блокировка удаления объектов главного источника") //$NON-NLS-1$
                                {
                                    @Override
                                    public Object execute(IBmTransaction transaction, IProgressMonitor progressMonitor)
                                    {
                                        disabledHolder[0] = disableDeletionNodes(finishedSession.getRootNode());
                                        return null;
                                    }
                                };
                                Global.invoke(comparisonManager, "runComparisonTreeBmModelTask", //$NON-NLS-1$
                                    finishedSession, task);
                            }
                            int disabled = disabledHolder[0];
                            Global.log("CompareWithCommit: заблокировано слияние для " //$NON-NLS-1$
                                + disabled + " узлов удаления объектов главного источника"); //$NON-NLS-1$
                            if (editor.getSite() != null && !editor.getSite().getShell().isDisposed())
                                refreshComparisonTree(editor);
                        }
                        catch (Exception ex)
                        {
                            Global.log("CompareWithCommit: блокировка удаления: " + ex); //$NON-NLS-1$
                        }
                    });
                    return Status.OK_STATUS;
                }
            };
            disableJob.setSystem(true);
            disableJob.schedule();
        }

        /**
         * Обновляет дерево редактора сравнения (у {@link IEditorPart} нет метода
         * {@code refresh()} — обновлять нужно tree viewer панели сравнения).
         */
        private static void refreshComparisonTree(IEditorPart editor)
        {
            Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
            if (!(view instanceof DtComparisonView))
                return;
            Object treeControl = ((DtComparisonView) view).getTreeControl();
            if (treeControl == null)
                return;
            Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
            if (viewer instanceof AbstractTreeViewer)
                ((AbstractTreeViewer) viewer).refresh();
        }

        /**
         * Проходит по дереву сессии и запрещает слияние узлам, где объект присутствует
         * только в главном источнике (односторонние узлы главной стороны). Каскадом
         * блокирует и узел-контейнер, если этим же проходом заблокированы все его дети
         * (иначе у контейнера остаётся «пустой» чекбокс, хотя внутри нечего сливать).
         * Возвращает число заблокированных узлов.
         */
        private static int disableDeletionNodes(ComparisonNode root)
        {
            if (root == null)
                return 0;
            int[] disabled = { 0 };
            disableDeletionNodesRecursive(root, disabled);
            return disabled[0];
        }

        /** @return {@code true}, если узел заблокирован этим проходом (сам или каскадом от детей). */
        private static boolean disableDeletionNodesRecursive(ComparisonNode node, int[] disabled)
        {
            boolean isLeafDeletion = node.isOneSideNode() && ComparisonSide.MAIN.equals(node.getNodeSide());
            if (isLeafDeletion)
                blockMergeSettings(node, disabled);

            List<ComparisonNode> children = node.getChildren();
            boolean allChildrenBlocked = !children.isEmpty();
            for (ComparisonNode child : children)
            {
                if (!disableDeletionNodesRecursive(child, disabled))
                    allChildrenBlocked = false;
            }

            if (isLeafDeletion)
                return true;
            if (allChildrenBlocked)
            {
                blockMergeSettings(node, disabled);
                return true;
            }
            return false;
        }

        private static void blockMergeSettings(ComparisonNode node, int[] disabled)
        {
            MergeSettings settings = node.getMergeSettings();
            if (settings != null && settings.isCanBeMerged())
            {
                settings.setCanBeMerged(false);
                if (settings.isMustBeMerged())
                    settings.setMustBeMerged(false);
                disabled[0]++;
            }
        }

        private CompareMergeProcessDescriptor buildDescriptor(IProject project,
            RevCommit commitToCompareWith, RevCommit baseCommit, IProgressMonitor monitor)
            throws IOException, InvalidPreferencesFormatException
        {
            Path workTree = repository.getWorkTree().toPath();
            monitor.setTaskName(MessageFormat.format(SEARCH_CHANGES_TASK_NAME, project.getName()));
            Path projectDir = project.getLocation().toFile().toPath();
            String projectRelativePath =
                GitCompareUtils.replaceBackslash(workTree.relativize(projectDir).toString());
            boolean isIndex = "Index".equals(revisionToCompareWith); //$NON-NLS-1$
            List<FileDiff> projectDiffs = GitCompareUtils.findProjectDiffs(repository, null,
                commitToCompareWith, isIndex, projectRelativePath, Collections.emptyList(), monitor);
            ComparisonScope scope = GitCompareUtils.buildComparisonScope(projectRelativePath,
                projectDiffs, filePathConverter, true);
            IV8Project v8project = v8ProjectManager.getProject(project);
            if (v8project == null)
                throw new IllegalStateException("Не найден IV8Project для " + project.getName()); //$NON-NLS-1$
            V8ProjectComparisonDataSourceDescriptor v8DataSource =
                new V8ProjectComparisonDataSourceDescriptor(v8project);
            GitComparisonDataSourceDescriptor gitDataSource =
                new GitComparisonDataSourceDescriptor(workTree, revisionToCompareWith, projectDir);
            GitComparisonDataSourceDescriptor baseDataSource = baseCommit != null
                ? new GitComparisonDataSourceDescriptor(workTree, baseCommit.getName(), projectDir) : null;
            ComparisonProcessHandle handle =
                new ComparisonProcessHandle(v8DataSource, gitDataSource, baseDataSource, scope);
            ComparisonProcessSettings settings = createSettings(
                comparisonManager.deserializeMergeSettings(handle, mergeSettingsFileName));
            return new CompareMergeProcessDescriptor(handle, settings);
        }

        private ComparisonProcessSettings createSettings(RestoredMergeSettings restored)
        {
            ComparisonProcessSettings.ComparisonSettingsBuilder builder =
                ComparisonProcessSettings.builder(matchingStrategy)
                    .mergeObjectsContent(true)
                    .parseBslModuleStructure(parseBslModuleStructure);
            if (mainSideObjectsDeletionAllowed)
                builder = builder.mainSideObjectsDeletionAllowed(true);
            if (restored != null)
                builder = builder
                    .correspondences(restored.getComparedObjectsCorrespondences())
                    .mergeSettingsModel(restored.getMergeSettingsModel());
            return builder.build();
        }
    }
}
