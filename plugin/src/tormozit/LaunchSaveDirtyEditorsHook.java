package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchDelegate;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate2;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerAnnotation;

import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.ui.editor.IDtEditor;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

/**
 * Перед запуском клиентского приложения 1С предлагает сохранить несохранённые
 * редакторы того же проекта — по образцу штатного поведения при коммите
 * (<a href="https://github.com/tormozit/EDT.Comfort/issues/455">issue 455</a>).
 *
 * <h3>Почему хук, а не настройка</h3>
 * У Eclipse есть штатный механизм «Сохранять изменённые редакторы перед запуском»
 * ({@code SaveScopeResourcesHandler}, статус-код 222). Он показывает список грязных
 * редакторов <b>только затронутых проектов</b>, но полагается на
 * {@code LaunchConfigurationDelegate.getBuildOrder()}. Делегат запуска EDT
 * ({@code RuntimeClientLaunchDelegate}) этот метод не переопределяет — поэтому
 * {@code getBuildOrder()} возвращает {@code null}, срабатывает запасная ветка
 * {@code DebugUIPlugin.preLaunchSave()} и сохранение идёт по <b>всему рабочему
 * пространству</b> штатным диалогом «Сохранить ресурсы» с двумя кнопками.
 *
 * <p>Пакет с делегатом EDT ({@code ...internal.launching.core.launchconfigurations})
 * не экспортируется, унаследоваться от него нельзя. Поэтому у зарегистрированного
 * {@code ILaunchDelegate} подменяется приватное поле {@code fDelegate} на
 * {@link Proxy} ({@link DelegateHandler}), который в {@code preLaunchCheck}:
 * <ul>
 *   <li>находит проект запуска (атрибут {@code com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME});</li>
 *   <li>собирает несохранённые редакторы этого проекта;</li>
 *   <li>при значении параметра «prompt» показывает {@link SaveAndLaunchDialog}
 *       со списком и кнопками «Сохранить и запустить» / «Не сохранять и запустить» / «Отмена»
 *       (при «always» — сохраняет молча, при «never» — не вмешивается);</li>
 *   <li>если в открытых редакторах того же проекта есть ошибки конфигурации
 *       ({@link MarkerSeverity#ERRORS}, в том числе у вложенных объектов — как значок
 *       на вкладке), показывает {@link ErrorsAndLaunchDialog} со списком и кнопками
 *       «Запустить» / «Отмена»
 *       (<a href="https://github.com/tormozit/EDT.Comfort/issues/500">issue 500</a>);</li>
 *   <li>затем вызывает настоящий {@code preLaunchCheck}, временно выставив параметр
 *       в «never», чтобы штатное сохранение по всему рабочему пространству не спросило
 *       второй раз.</li>
 * </ul>
 */
public final class LaunchSaveDirtyEditorsHook implements IStartup
{
    private static final String TAG = "LaunchSaveDirtyEditors"; //$NON-NLS-1$
    private static final String ERROR_LOG = "launch-errors"; //$NON-NLS-1$
    private static final String BSL_EXTENSION = "bsl"; //$NON-NLS-1$
    /** После сохранения ждём догоняющий пересчёт маркеров, но не дольше этого. */
    private static final long CHECKS_WAIT_MS = 2_000;

    private static final String RUNTIME_CLIENT_TYPE = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    private static final String PREF_NODE = "org.eclipse.debug.ui"; //$NON-NLS-1$
    private static final String PREF_SAVE_DIRTY = "org.eclipse.debug.ui.save_dirty_editors_before_launch"; //$NON-NLS-1$
    private static final String ALWAYS = "always"; //$NON-NLS-1$
    private static final String NEVER = "never"; //$NON-NLS-1$
    private static final String PROMPT = "prompt"; //$NON-NLS-1$

    /** Через сколько после старта EDT подменять делегат: сервисы OSGi уже подняты. */
    private static final int STARTUP_PATCH_DELAY_MS = 10_000;

    private static final int SAVE_AND_LAUNCH_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int LAUNCH_WITHOUT_SAVE_ID = IDialogConstants.CLIENT_ID + 2;
    private static final int LAUNCH_WITH_ERRORS_ID = IDialogConstants.CLIENT_ID + 3;

    private static volatile boolean patched;

    @Override
    public void earlyStartup()
    {
        // earlyStartup идёт в рабочем потоке — timerExec требует UI-поток.
        Display.getDefault().asyncExec(() ->
            Display.getDefault().timerExec(STARTUP_PATCH_DELAY_MS,
                LaunchSaveDirtyEditorsHook::installDelegateProxy));
    }

    private static void installDelegateProxy()
    {
        if (patched)
            return;
        try
        {
            DebugPlugin debug = DebugPlugin.getDefault();
            if (debug == null)
                return;
            ILaunchManager manager = debug.getLaunchManager();
            ILaunchConfigurationType type = manager == null ? null
                : manager.getLaunchConfigurationType(RUNTIME_CLIENT_TYPE);
            if (type == null)
                return;

            boolean done = false;
            for (String mode : new String[] { ILaunchManager.RUN_MODE, ILaunchManager.DEBUG_MODE })
            {
                ILaunchDelegate[] delegates;
                try
                {
                    delegates = type.getDelegates(Set.of(mode));
                }
                catch (CoreException e)
                {
                    continue;
                }
                for (ILaunchDelegate delegate : delegates)
                    done |= patchDelegate(delegate);
            }
            if (done)
                patched = true;
        }
        catch (Throwable t)
        {
            Global.logError(TAG, "не удалось подменить делегат запуска", t); //$NON-NLS-1$
            Global.tempLogException("launch-save", "installDelegateProxy", t); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean patchDelegate(ILaunchDelegate delegate)
    {
        try
        {
            Object real = delegate.getDelegate();
            if (!(real instanceof ILaunchConfigurationDelegate) || Proxy.isProxyClass(real.getClass()))
                return false;

            Set<Class<?>> ifaces = new LinkedHashSet<>();
            ifaces.add(ILaunchConfigurationDelegate.class);
            ifaces.add(ILaunchConfigurationDelegate2.class);
            for (Class<?> c = real.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                for (Class<?> i : c.getInterfaces())
                {
                    if (Modifier.isPublic(i.getModifiers()))
                        ifaces.add(i);
                }
            }

            Object proxy = Proxy.newProxyInstance(LaunchSaveDirtyEditorsHook.class.getClassLoader(),
                ifaces.toArray(new Class<?>[0]), new DelegateHandler(real));
            return Global.setFieldForce(delegate, "fDelegate", proxy); //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            Global.logError(TAG, "не удалось подменить делегат " + delegate, t); //$NON-NLS-1$
            Global.tempLogException("launch-save", "patchDelegate", t); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /** Проксирует делегат запуска, вклиниваясь только в {@code preLaunchCheck}. */
    private static final class DelegateHandler implements InvocationHandler
    {
        private final Object real;

        DelegateHandler(Object real)
        {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
                return invokeObjectMethod(proxy, method, args);

            if ("preLaunchCheck".equals(method.getName()) && args != null && args.length == 3 //$NON-NLS-1$
                && args[0] instanceof ILaunchConfiguration config)
            {
                return preLaunchCheck(config, method, args);
            }
            if ("launch".equals(method.getName()) && args != null && args.length >= 1 //$NON-NLS-1$
                && args[0] instanceof ILaunchConfiguration config)
            {
                LaunchConfigurationHook.prepareCheckModal(config);
            }
            return forward(method, args);
        }

        private Object preLaunchCheck(ILaunchConfiguration config, Method method, Object[] args)
            throws Throwable
        {
            Boolean decision;
            try
            {
                decision = decide(config);
            }
            catch (Throwable t)
            {
                Global.logError(TAG, "ошибка подготовки списка редакторов", t); //$NON-NLS-1$
                Global.tempLogException("launch-save", "decide", t); //$NON-NLS-1$ //$NON-NLS-2$
                decision = null;
            }
            if (Boolean.FALSE.equals(decision))
                return Boolean.FALSE;
            if (decision == null)
                return forward(method, args);

            // Решение принято нами — глушим повторный штатный вопрос по всему рабочему пространству.
            PrefGuard guard = PrefGuard.suppress();
            try
            {
                return forward(method, args);
            }
            finally
            {
                guard.restore();
            }
        }

        /**
         * @return {@code TRUE} — продолжать запуск (сохранение обработано нами);
         *     {@code FALSE} — отменить запуск; {@code null} — мы не вмешиваемся, обычный ход.
         */
        private Boolean decide(ILaunchConfiguration config) throws CoreException
        {
            IProject project = resolveProject(config);
            Global.tempLog(ERROR_LOG, "decide project=" //$NON-NLS-1$
                + (project == null ? "null" : project.getName()) //$NON-NLS-1$
                + " config=" + config.getName()); //$NON-NLS-1$
            if (project == null)
                return null;

            Boolean saveDecision = decideSave(project);
            if (Boolean.FALSE.equals(saveDecision))
                return Boolean.FALSE;

            Boolean errorDecision = decideErrors(project, Boolean.TRUE.equals(saveDecision));
            if (Boolean.FALSE.equals(errorDecision))
                return Boolean.FALSE;

            // PrefGuard нужен, только если мы сами обработали сохранение.
            return Boolean.TRUE.equals(saveDecision) ? Boolean.TRUE : null;
        }

        /**
         * @return {@code TRUE} — сохранение обработано нами; {@code FALSE} — отмена;
         *     {@code null} — несохранённых нет или параметр «never».
         */
        private Boolean decideSave(IProject project)
        {
            List<IEditorPart> dirty = scopedDirtyEditors(project);
            if (dirty.isEmpty())
                return null;

            String pref = readPrefEffective();
            if (NEVER.equals(pref))
                return null;
            if (ALWAYS.equals(pref))
            {
                saveEditors(dirty);
                return Boolean.TRUE;
            }

            int[] answer = { IDialogConstants.CANCEL_ID };
            String projectName = project.getName();
            Display.getDefault().syncExec(() -> answer[0] = openSaveDialog(projectName, dirty));
            if (answer[0] == SAVE_AND_LAUNCH_ID)
            {
                saveEditors(dirty);
                return Boolean.TRUE;
            }
            if (answer[0] == LAUNCH_WITHOUT_SAVE_ID)
                return Boolean.TRUE;
            return Boolean.FALSE;
        }

        /**
         * @return {@code TRUE} — пользователь подтвердил запуск при ошибках;
         *     {@code FALSE} — отмена; {@code null} — ошибок в открытых редакторах нет.
         */
        private Boolean decideErrors(IProject project, boolean savedNow)
        {
            try
            {
                IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
                Global.tempLog(ERROR_LOG, "start project=" + project.getName() //$NON-NLS-1$
                    + " markerManager=" + (markerManager != null) //$NON-NLS-1$
                    + " savedNow=" + savedNow); //$NON-NLS-1$
                if (markerManager == null)
                    return null;

                List<Row> open = new ArrayList<>();
                Display.getDefault().syncExec(() ->
                {
                    for (IEditorPart editor : scopedOpenEditors(project))
                        open.add(rowOf(editor));
                });

                List<Row> rows = collectErrorRows(markerManager, project, open, savedNow);
                if (savedNow)
                    rows = waitAndRecheck(markerManager, project, open, rows.isEmpty(), rows);
                Global.tempLog(ERROR_LOG, "open=" + open.size() + " hits=" + rows.size()); //$NON-NLS-1$ //$NON-NLS-2$
                if (rows.isEmpty())
                    return null;

                rows.sort(Comparator.comparing(row -> row.text));
                List<Row> errorRows = rows;
                int[] answer = { IDialogConstants.CANCEL_ID };
                Display.getDefault().syncExec(() -> answer[0] = openErrorsDialog(project.getName(), errorRows));
                Global.tempLog(ERROR_LOG, "dialog=" + answer[0]); //$NON-NLS-1$
                return answer[0] == LAUNCH_WITH_ERRORS_ID ? Boolean.TRUE : Boolean.FALSE;
            }
            catch (Throwable t)
            {
                Global.logError(TAG, "ошибка проверки ошибок конфигурации", t); //$NON-NLS-1$
                Global.tempLogException(ERROR_LOG, "decideErrors", t); //$NON-NLS-1$
                return null;
            }
        }

        /**
         * После сохранения и подчёркивания, и маркеры EDT отстают: новая ошибка
         * появляется с задержкой, исправленная ещё какое-то время висит.
         *
         * @param appearing {@code true} — в первом проходе ошибок не было, ждём появления;
         *     {@code false} — ошибки уже были, ждём, пока устаревшие пропадут.
         */
        private List<Row> waitAndRecheck(IMarkerManager markerManager, IProject project, List<Row> open,
            boolean appearing, List<Row> rows)
        {
            long deadline = System.currentTimeMillis() + CHECKS_WAIT_MS;
            int attempt = 0;
            Global.tempLog(ERROR_LOG, "wait appearing=" + appearing + " startHits=" + rows.size()); //$NON-NLS-1$ //$NON-NLS-2$
            while (System.currentTimeMillis() < deadline)
            {
                attempt++;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                    break;
                waitForChecks(project, remaining);
                open.clear();
                Display.getDefault().syncExec(() ->
                {
                    for (IEditorPart editor : scopedOpenEditors(project))
                        open.add(rowOf(editor));
                });
                rows = collectErrorRows(markerManager, project, open, true);
                Global.tempLog(ERROR_LOG, "afterWait attempt=" + attempt //$NON-NLS-1$
                    + " hits=" + rows.size() //$NON-NLS-1$
                    + " appearing=" + appearing); //$NON-NLS-1$
                if (appearing && !rows.isEmpty())
                    return rows;
                if (!appearing && rows.isEmpty())
                    return rows;
                remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                    break;
                try
                {
                    Thread.sleep(Math.min(200L, remaining));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return rows;
        }

        private List<Row> collectErrorRows(IMarkerManager markerManager, IProject project, List<Row> open,
            boolean afterSave)
        {
            List<Row> rows = new ArrayList<>();
            for (Row row : open)
            {
                String hit = "none"; //$NON-NLS-1$
                try
                {
                    hit = configErrorHit(markerManager, project, row, afterSave);
                    if (!"none".equals(hit)) //$NON-NLS-1$
                        rows.add(row);
                }
                catch (RuntimeException e)
                {
                    hit = "ex:" + e.getClass().getSimpleName(); //$NON-NLS-1$
                    Global.logError(TAG, "не удалось проверить редактор " + row.text, e); //$NON-NLS-1$
                    Global.tempLogException(ERROR_LOG, "editor " + row.text, e); //$NON-NLS-1$
                }
                Global.tempLog(ERROR_LOG, "editor text=" + row.text //$NON-NLS-1$
                    + " class=" + row.editor.getClass().getSimpleName() //$NON-NLS-1$
                    + " ids=" + row.ids //$NON-NLS-1$
                    + " files=" + fileNames(row.files) //$NON-NLS-1$
                    + " live=" + row.liveHit //$NON-NLS-1$
                    + " trusted=" + row.liveTrusted //$NON-NLS-1$
                    + " afterSave=" + afterSave //$NON-NLS-1$
                    + " hit=" + hit); //$NON-NLS-1$
            }
            return rows;
        }

        private Object forward(Method method, Object[] args) throws Throwable
        {
            try
            {
                return method.invoke(real, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        private Object invokeObjectMethod(Object proxy, Method method, Object[] args)
        {
            switch (method.getName())
            {
                case "hashCode": //$NON-NLS-1$
                    return System.identityHashCode(proxy);
                case "equals": //$NON-NLS-1$
                    return proxy == (args != null ? args[0] : null);
                default:
                    return "ComfortLaunchDelegateProxy(" + real + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    // -----------------------------------------------------------------------

    private static IProject resolveProject(ILaunchConfiguration config) throws CoreException
    {
        String name = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        if (name != null && !name.isBlank())
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (project.exists())
                return project;
        }
        IResource[] mapped = config.getMappedResources();
        if (mapped != null)
        {
            for (IResource resource : mapped)
            {
                IProject project = resource.getProject();
                if (project != null && project.exists())
                    return project;
            }
        }
        return null;
    }

    private static List<IEditorPart> scopedDirtyEditors(IProject project)
    {
        List<IEditorPart> result = new ArrayList<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getDirtyEditors())
                {
                    IResource resource = editorResource(editor);
                    if (resource != null && project.equals(resource.getProject()) && !result.contains(editor))
                        result.add(editor);
                }
            }
        }
        return result;
    }

    private static List<IEditorPart> scopedOpenEditors(IProject project)
    {
        List<IEditorPart> result = new ArrayList<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference reference : page.getEditorReferences())
                {
                    IEditorPart editor = reference.getEditor(false);
                    if (editor != null && belongsToProject(editor, project) && !result.contains(editor))
                        result.add(editor);
                }
            }
        }
        return result;
    }

    private static boolean belongsToProject(IEditorPart editor, IProject project)
    {
        IResource resource = editorResource(editor);
        if (resource != null)
            return project.equals(resource.getProject());
        EObject model = editorModel(editor);
        IFile file = platformFile(model);
        return file != null && project.equals(file.getProject());
    }

    private static IResource editorResource(IEditorPart editor)
    {
        IEditorInput input = editor != null ? editor.getEditorInput() : null;
        return input != null ? input.getAdapter(IResource.class) : null;
    }

    private static Row rowOf(IEditorPart editor)
    {
        LinkedHashSet<Object> ids = new LinkedHashSet<>();
        LinkedHashSet<IFile> files = new LinkedHashSet<>();
        collectEditorTargets(editor, ids, files);
        String liveHit = liveErrorHit(editor);
        return new Row(editor, editorPresentation(editor), new ArrayList<>(ids), new ArrayList<>(files), liveHit,
            isLiveTrusted(editor, liveHit));
    }

    private static void collectEditorTargets(IEditorPart editor, LinkedHashSet<Object> ids,
        LinkedHashSet<IFile> files)
    {
        EObject model = editorModel(editor);
        if (model instanceof IBmObject bm)
            ids.add(Long.valueOf(bm.bmGetId()));
        addFileTarget(editorResource(editor), ids, files);
        addModuleFiles(model, ids, files);

        BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
        if (bsl != null)
        {
            addFileTarget(bsl.getEditorInput() != null ? bsl.getEditorInput().getAdapter(IResource.class) : null,
                ids, files);
            addBmIdFromBsl(bsl, ids);
        }
    }

    private static void addFileTarget(IResource resource, LinkedHashSet<Object> ids, LinkedHashSet<IFile> files)
    {
        if (!(resource instanceof IFile file) || !file.exists())
            return;
        files.add(file);
        addFileIds(ids, file);
    }

    /**
     * Идентификаторы языковых маркеров: {@code IFile.getFullPath} и оба варианта
     * {@code URI.toPlatformString} (как у контекста языковых проверок и у {@code PlainEObjectMarker}).
     */
    private static void addFileIds(LinkedHashSet<Object> ids, IFile file)
    {
        String path = file.getFullPath().toString();
        if (path == null || path.isBlank())
            return;
        ids.add(path);
        URI uri = URI.createPlatformResourceURI(path, true);
        String decoded = uri.toPlatformString(true);
        String encoded = uri.toPlatformString(false);
        if (decoded != null && !decoded.isBlank())
            ids.add(decoded);
        if (encoded != null && !encoded.isBlank())
            ids.add(encoded);
        ids.add(uri.toString());
        ids.add(uri.trimFragment().toString());
    }

    /** {@code *.bsl} папки объекта — языковые проверки живут на пути файла, не на BM-id родителя. */
    private static void addModuleFiles(EObject model, LinkedHashSet<Object> ids, LinkedHashSet<IFile> files)
    {
        IFile objectFile = platformFile(model);
        IContainer folder = objectFile != null ? objectFile.getParent() : null;
        if (folder == null || !folder.isAccessible())
            return;
        try
        {
            folder.accept(resource ->
            {
                if (resource instanceof IFile file && BSL_EXTENSION.equalsIgnoreCase(file.getFileExtension()))
                    addFileTarget(file, ids, files);
                return true;
            });
        }
        catch (CoreException ignored)
        {
        }
    }

    private static IFile platformFile(EObject model)
    {
        if (model == null)
            return null;
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return null;
        try
        {
            return lookup.getPlatformResource(model);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static void addBmIdFromBsl(BslXtextEditor editor, LinkedHashSet<Object> ids)
    {
        if (editor == null || !(editor.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            EObject root = document.readOnly((IUnitOfWork<EObject, XtextResource>) resource ->
            {
                if (resource == null || resource.getContents().isEmpty())
                    return null;
                return resource.getContents().get(0);
            });
            for (EObject current = root; current != null; current = current.eContainer())
            {
                if (current instanceof IBmObject bm)
                {
                    ids.add(Long.valueOf(bm.bmGetId()));
                    return;
                }
            }
        }
        catch (RuntimeException ignored)
        {
        }
    }

    private static EObject editorModel(IEditorPart editor)
    {
        if (editor instanceof IDtEditor<?> dt)
        {
            try
            {
                EObject model = dt.getModel();
                if (model != null)
                    return model;
            }
            catch (RuntimeException ignored)
            {
            }
        }
        IEditorInput input = editor.getEditorInput();
        if (input instanceof IDtEditorInput<?> dtInput)
        {
            try
            {
                return dtInput.getModel();
            }
            catch (RuntimeException ignored)
            {
            }
        }
        return null;
    }

    /**
     * @return причина попадания или {@code none}: живые аннотации редактора,
     *     языковые маркеры по пути файла, вложенные модельные, либо {@code IMarker.PROBLEM}
     */
    private static String configErrorHit(IMarkerManager markers, IProject project, Row row, boolean afterSave)
    {
        if (hasLiveError(row.liveHit))
            return "live"; //$NON-NLS-1$
        // Сразу после сохранения маркеры EDT ещё держат уже исправленную ошибку.
        // Если живые аннотации модуля уже пустые — им верим.
        if (afterSave && row.liveTrusted && "none".equals(row.liveHit)) //$NON-NLS-1$
            return "none"; //$NON-NLS-1$
        if (hasMarkerErrors(markers, project, row.ids))
            return "markers"; //$NON-NLS-1$
        for (Object id : row.ids)
        {
            if (id instanceof Long && containsConfigError(markers.getNestedMarkers(project, id)))
                return "nested"; //$NON-NLS-1$
        }
        if (hasProblemErrors(row.files))
            return "problem"; //$NON-NLS-1$
        return "none"; //$NON-NLS-1$
    }

    private static boolean hasLiveError(String liveHit)
    {
        return liveHit != null && !"none".equals(liveHit) //$NON-NLS-1$
            && !liveHit.startsWith("no-text") && !liveHit.startsWith("no-model"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isLiveTrusted(IEditorPart editor, String liveHit)
    {
        if (!"none".equals(liveHit) && !hasLiveError(liveHit)) //$NON-NLS-1$
            return false;
        return GetRef.getActiveBslEditor(editor) != null;
    }

    /**
     * Красные аннотации в открытом редакторе — живая валидация Xtext, ещё до записи
     * маркеров в {@link IMarkerManager} после сохранения.
     */
    private static String liveErrorHit(IEditorPart editor)
    {
        IAnnotationModel model = annotationModelOf(editor);
        if (model == null)
        {
            ITextEditor text = textEditorOf(editor);
            return text == null ? "no-text" : "no-model:" + text.getClass().getSimpleName(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            Iterator<?> iterator = model.getAnnotationIterator();
            while (iterator.hasNext())
            {
                Object next = iterator.next();
                if (!(next instanceof Annotation annotation) || annotation.isMarkedDeleted())
                    continue;
                if (!isErrorAnnotation(annotation, model))
                    continue;
                String type = annotation.getType();
                return type != null ? type : "error"; //$NON-NLS-1$
            }
        }
        catch (RuntimeException ignored)
        {
        }
        return "none"; //$NON-NLS-1$
    }

    private static boolean isErrorAnnotation(Annotation annotation, IAnnotationModel model)
    {
        if (annotation instanceof MarkerAnnotation markerAnnotation)
        {
            IMarker marker = markerAnnotation.getMarker();
            if (marker != null && marker.exists()
                && marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR)
                return true;
        }
        String type = annotation.getType();
        if (type == null || !type.contains("error") || type.contains("warning")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        Position position = model.getPosition(annotation);
        return position != null && !position.isDeleted();
    }

    private static IAnnotationModel annotationModelOf(IEditorPart editor)
    {
        ITextEditor text = textEditorOf(editor);
        if (text != null)
        {
            IDocumentProvider provider = text.getDocumentProvider();
            IEditorInput input = text.getEditorInput();
            IAnnotationModel fromProvider = provider != null && input != null
                ? provider.getAnnotationModel(input) : null;
            if (fromProvider != null)
                return fromProvider;
        }
        Object adapted = editor.getAdapter(ISourceViewer.class);
        return adapted instanceof ISourceViewer viewer ? viewer.getAnnotationModel() : null;
    }

    private static ITextEditor textEditorOf(IEditorPart editor)
    {
        if (editor instanceof ITextEditor text)
            return text;
        BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
        if (bsl != null)
            return bsl;
        Object adapted = editor.getAdapter(ITextEditor.class);
        return adapted instanceof ITextEditor text ? text : null;
    }

    private static void waitForChecks(IProject project, long timeoutMs)
    {
        long started = System.currentTimeMillis();
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        boolean idle = manager == null;
        if (manager != null && timeoutMs > 0)
        {
            try
            {
                idle = manager.waitAllComputations(timeoutMs);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (RuntimeException e)
            {
                Global.tempLog(ERROR_LOG, "waitChecks ex=" + e.getClass().getSimpleName()); //$NON-NLS-1$
            }
        }
        Global.tempLog(ERROR_LOG, "waitChecks ms=" + (System.currentTimeMillis() - started) //$NON-NLS-1$
            + " idle=" + idle); //$NON-NLS-1$
    }

    private static boolean hasMarkerErrors(IMarkerManager markers, IProject project, List<Object> objectIds)
    {
        List<Object> bmIds = new ArrayList<>();
        List<Object> pathIds = new ArrayList<>();
        for (Object id : objectIds)
        {
            if (id instanceof Long)
                bmIds.add(id);
            else if (id != null)
                pathIds.add(id);
        }
        return queryMarkerErrors(markers, project, bmIds) || queryMarkerErrors(markers, project, pathIds);
    }

    private static boolean queryMarkerErrors(IMarkerManager markers, IProject project, List<Object> ids)
    {
        if (ids == null || ids.isEmpty())
            return false;
        MarkerFilter filter = MarkerFilter.createObjectFilter(project, ids)
            .combine(MarkerFilter.createSeverityFilter(MarkerSeverity.ERRORS));
        return markers.markers(filter).findAny().isPresent();
    }

    private static boolean hasProblemErrors(List<IFile> files)
    {
        for (IFile file : files)
        {
            if (file == null || !file.exists())
                continue;
            try
            {
                for (IMarker marker : file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
                {
                    if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR)
                        return true;
                }
            }
            catch (CoreException ignored)
            {
            }
        }
        return false;
    }

    private static boolean containsConfigError(Marker[] batch)
    {
        if (batch == null)
            return false;
        for (Marker marker : batch)
        {
            if (marker != null && marker.getSeverity() == MarkerSeverity.ERRORS)
                return true;
        }
        return false;
    }

    private static String fileNames(List<IFile> files)
    {
        List<String> names = new ArrayList<>();
        for (IFile file : files)
            names.add(file.getProjectRelativePath().toString());
        return names.toString();
    }

    /**
     * Сохраняет редакторы поштучно ({@code IEditorPart.doSave}), а не через
     * {@code IDE.saveAllEditors}: последний привлекает механизм {@code Saveable}
     * и на редакторах, открытых сразу в нескольких местах, показывает свой
     * (непереведённый) диалог‑подтверждение.
     */
    private static void saveEditors(List<IEditorPart> editors)
    {
        Display.getDefault().syncExec(() ->
        {
            for (IEditorPart editor : editors)
            {
                if (editor.isDirty())
                    editor.doSave(new NullProgressMonitor());
            }
        });
    }

    private static int openSaveDialog(String projectName, List<IEditorPart> dirty)
    {
        List<Row> rows = new ArrayList<>();
        for (IEditorPart editor : dirty)
            rows.add(new Row(editor, editorPresentation(editor)));
        rows.sort(Comparator.comparing(row -> row.text));
        return new SaveAndLaunchDialog(dialogShell(), projectName, rows).open();
    }

    private static int openErrorsDialog(String projectName, List<Row> rows)
    {
        return new ErrorsAndLaunchDialog(dialogShell(), projectName, rows).open();
    }

    private static Shell dialogShell()
    {
        Shell shell = Display.getDefault().getActiveShell();
        if (shell != null)
            return shell;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window != null ? window.getShell() : null;
    }

    /**
     * Полное имя объекта метаданных по пути файла редактора
     * ({@code Справочник.Валюты.Форма.ФормаЭлемента}). Суффикс типа компонента
     * ({@code …ФормаЭлемента.Модуль}) добавляется, только если открыт <b>отдельный</b>
     * редактор модуля ({@link ITextEditor}); у полного редактора объекта/формы
     * (многостраничного, с модулём и структурой) суффикса нет — файлом редактора у него
     * тоже может быть {@code Module.bsl}. Запасной вариант — заголовок вкладки редактора.
     */
    private static String editorPresentation(IEditorPart editor)
    {
        IResource resource = editor.getEditorInput().getAdapter(IResource.class);
        if (resource != null)
        {
            String rel = resource.getProjectRelativePath().toString();
            String fullName = GetRef.pathToFullName(rel);
            if (fullName != null && !fullName.isBlank())
            {
                String moduleType = editor instanceof ITextEditor
                    ? MdTypeMapping.bslFilenameToModuleRu(resource.getName()) : null;
                if (moduleType != null && !fullName.endsWith("." + moduleType)) //$NON-NLS-1$
                    fullName += "." + moduleType; //$NON-NLS-1$
                return fullName;
            }
        }
        return editor.getTitle();
    }

    private static void activateEditor(IEditorPart editor)
    {
        if (editor.getSite() == null)
            return;
        IWorkbenchPage page = editor.getSite().getPage();
        if (page != null)
            page.activate(editor);
    }

    /** Строка списка: редактор, представление, идентификаторы маркеров и файлы модуля. */
    private static final class Row
    {
        final IEditorPart editor;
        final String text;
        final List<Object> ids;
        final List<IFile> files;
        final String liveHit;
        final boolean liveTrusted;

        Row(IEditorPart editor, String text)
        {
            this(editor, text, List.of(), List.of(), "none", false); //$NON-NLS-1$
        }

        Row(IEditorPart editor, String text, List<Object> ids, List<IFile> files, String liveHit,
            boolean liveTrusted)
        {
            this.editor = editor;
            this.text = text;
            this.ids = ids;
            this.files = files;
            this.liveHit = liveHit;
            this.liveTrusted = liveTrusted;
        }
    }

    // -----------------------------------------------------------------------
    // Параметр «Сохранять изменённые редакторы перед запуском»
    // -----------------------------------------------------------------------

    private static ScopedPreferenceStore debugUiStore()
    {
        return new ScopedPreferenceStore(InstanceScope.INSTANCE, PREF_NODE);
    }

    /** Действующее значение параметра (с учётом значения по умолчанию). */
    private static String readPrefEffective()
    {
        String value = debugUiStore().getString(PREF_SAVE_DIRTY);
        return value == null || value.isEmpty() ? PROMPT : value;
    }

    /**
     * На время вызова настоящего {@code preLaunchCheck} держит параметр
     * «Сохранять изменённые редакторы перед запуском» в значении «never», затем
     * возвращает прежнее (или сбрасывает в значение по умолчанию, если оно не было задано).
     */
    private static final class PrefGuard
    {
        private final ScopedPreferenceStore store;
        private final boolean wasDefault;
        private final String previous;

        private PrefGuard(ScopedPreferenceStore store, boolean wasDefault, String previous)
        {
            this.store = store;
            this.wasDefault = wasDefault;
            this.previous = previous;
        }

        static PrefGuard suppress()
        {
            ScopedPreferenceStore store = debugUiStore();
            boolean wasDefault = store.isDefault(PREF_SAVE_DIRTY);
            String previous = store.getString(PREF_SAVE_DIRTY);
            store.setValue(PREF_SAVE_DIRTY, NEVER);
            save(store);
            return new PrefGuard(store, wasDefault, previous);
        }

        void restore()
        {
            if (wasDefault)
                store.setToDefault(PREF_SAVE_DIRTY);
            else
                store.setValue(PREF_SAVE_DIRTY, previous);
            save(store);
        }

        private static void save(ScopedPreferenceStore store)
        {
            try
            {
                store.save();
            }
            catch (Exception e)
            {
                Global.logError(TAG, "не удалось сохранить параметр сохранения редакторов", e); //$NON-NLS-1$
            }
        }
    }

    // -----------------------------------------------------------------------

    /** Общий список редакторов перед запуском: двойной клик отменяет запуск и открывает строку. */
    private abstract static class EditorListDialog extends Dialog
    {
        protected final String projectName;
        protected final List<Row> rows;

        EditorListDialog(Shell parentShell, String projectName, List<Row> rows)
        {
            super(parentShell);
            this.projectName = projectName;
            this.rows = rows;
            setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        }

        @Override
        protected void configureShell(Shell shell)
        {
            super.configureShell(shell);
            shell.setText(Global.withPluginWindowTitle("Запуск клиентского приложения")); //$NON-NLS-1$
        }

        @Override
        protected boolean isResizable()
        {
            return true;
        }

        protected abstract String headerText();

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite)super.createDialogArea(parent);

            Label header = new Label(area, SWT.WRAP);
            header.setText(headerText());
            header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            org.eclipse.swt.widgets.List list = new org.eclipse.swt.widgets.List(area,
                SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.SINGLE);
            for (Row row : rows)
                list.add(row.text);
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            int visibleRows = Math.min(14, Math.max(3, rows.size()));
            gd.heightHint = list.getItemHeight() * visibleRows + 8;
            gd.widthHint = 480;
            list.setLayoutData(gd);
            CopyCommandSupport.wireCopyOverride(list);
            list.addSelectionListener(SelectionListener.widgetDefaultSelectedAdapter(e ->
            {
                int index = list.getSelectionIndex();
                if (index < 0 || index >= rows.size())
                    return;
                setReturnCode(IDialogConstants.CANCEL_ID);
                close();
                activateEditor(rows.get(index).editor);
            }));

            applyDialogFont(area);
            return area;
        }

        @Override
        protected void buttonPressed(int buttonId)
        {
            setReturnCode(buttonId);
            close();
        }
    }

    /** Список несохранённых редакторов проекта и три кнопки выбора действия. */
    private static final class SaveAndLaunchDialog extends EditorListDialog
    {
        SaveAndLaunchDialog(Shell parentShell, String projectName, List<Row> rows)
        {
            super(parentShell, projectName, rows);
        }

        @Override
        protected String headerText()
        {
            return "В проекте «" + projectName //$NON-NLS-1$
                + "» есть несохранённые редакторы (двойной клик — открыть редактор):"; //$NON-NLS-1$
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, SAVE_AND_LAUNCH_ID, "Сохранить и запустить", true); //$NON-NLS-1$
            createButton(parent, LAUNCH_WITHOUT_SAVE_ID, "Не сохранять и запустить", false); //$NON-NLS-1$
            createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
        }
    }

    /** Список открытых редакторов с ошибками конфигурации. */
    private static final class ErrorsAndLaunchDialog extends EditorListDialog
    {
        ErrorsAndLaunchDialog(Shell parentShell, String projectName, List<Row> rows)
        {
            super(parentShell, projectName, rows);
        }

        @Override
        protected String headerText()
        {
            return "В открытых редакторах проекта «" + projectName //$NON-NLS-1$
                + "» есть ошибки конфигурации (двойной клик — открыть редактор):"; //$NON-NLS-1$
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, LAUNCH_WITH_ERRORS_ID, "Запустить", true); //$NON-NLS-1$
            createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
        }
    }
}
