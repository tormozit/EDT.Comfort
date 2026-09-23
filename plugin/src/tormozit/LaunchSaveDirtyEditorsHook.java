package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.nebula.widgets.tablecombo.TableCombo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.plugin.AbstractUIPlugin;
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
import com._1c.g5.v8.dt.common.localization.LocalizedEnumProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.search.core.SearchUtils;
import com._1c.g5.v8.dt.ui.DtUiUtil;
import com._1c.g5.v8.dt.ui.editor.IDtEditor;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;
import com._1c.g5.v8.dt.validation.git.IGitMarkerFilterManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;

/**
 * Перед запуском клиентского приложения 1С и перед отдельной синхронизацией предлагает
 * сохранить несохранённые редакторы того же проекта — по образцу штатного поведения при коммите
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
 *   <li>показывает собственный вопрос о редакторах проекта, поэтому «Отмена» возвращает
 *       {@code false} из {@code preLaunchCheck} и действительно прекращает запуск;</li>
 *   <li>вызывает настоящий {@code preLaunchCheck}, временно выставив параметр сохранения
 *       в «never» непосредственно в хранилище Debug UI, чтобы штатный вопрос не появился;</li>
 *   <li>помечает выбор обработанным на время {@code launch}, чтобы перед последующей
 *       синхронизацией не спрашивать второй раз.</li>
 * </ul>
 * <p>Для синхронизации вне запуска вопрос о сохранении остаётся непосредственно перед
 * вызовом менеджера обновления. После сохранения проверяются ошибки конфигурации у объектов
 * открытых редакторов. При значении
 * параметра «Предлагать» показывается
 * {@link ErrorsAndLaunchDialog} (<a href="https://github.com/tormozit/EDT.Comfort/issues/500">issue 500</a>).
 * Скрытые штатным отбором проблемы не учитываются
 * (<a href="https://github.com/tormozit/EDT.Comfort/issues/514">issue 514</a>).
 * Минимальная критичность задаётся на странице запуска через {@link LaunchPageAugmenter}
 * (<a href="https://github.com/tormozit/EDT.Comfort/issues/516">issue 516</a>).
 */
public final class LaunchSaveDirtyEditorsHook implements IStartup
{
    private static final String TAG = "LaunchSaveDirtyEditors"; //$NON-NLS-1$
    /** После любого сохранения редактора ждём догоняющий пересчёт маркеров, но не дольше этого. */
    private static final long CHECKS_WAIT_MS = 2_000;

    private static final String RUNTIME_CLIENT_TYPE = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    private static final String PREF_NODE = "org.eclipse.debug.ui"; //$NON-NLS-1$
    private static final String PREF_SAVE_DIRTY = "org.eclipse.debug.ui.save_dirty_editors_before_launch"; //$NON-NLS-1$
    /** «Продолжать выполнение в случае обнаружения ошибок проекта»: {@code always} / {@code prompt}. */
    private static final String PREF_CONTINUE_WITH_ERRORS = "org.eclipse.debug.ui.cancel_launch_with_compile_errors"; //$NON-NLS-1$
    /**
     * Минимальная критичность проблемы, о которой спрашиваем, если параметр не задан:
     * учитываются она и все более серьёзные. Более мягкие проблемы запуску не мешают.
     */
    private static final MarkerSeverity DEFAULT_MIN_SEVERITY = MarkerSeverity.CRITICAL;
    /** Штатная страница «Запуск/Отладка → Запуск»: там живут оба параметра запуска. */
    private static final String LAUNCH_PREF_PAGE_ID = "org.eclipse.debug.ui.LaunchingPreferencePage"; //$NON-NLS-1$
    private static final String ALWAYS = "always"; //$NON-NLS-1$
    private static final String NEVER = "never"; //$NON-NLS-1$
    private static final String PROMPT = "prompt"; //$NON-NLS-1$

    /** Через сколько после старта EDT подменять делегат: сервисы OSGi уже подняты. */
    private static final int STARTUP_PATCH_DELAY_MS = 10_000;

    private static final int SAVE_AND_CONTINUE_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int CONTINUE_WITHOUT_SAVE_ID = IDialogConstants.CLIENT_ID + 2;
    private static final int CONTINUE_WITH_ERRORS_ID = IDialogConstants.CLIENT_ID + 3;
    private static final int OPEN_PREFERENCES_ID = IDialogConstants.CLIENT_ID + 4;

    private static volatile boolean patched;
    /** Запуск, для которого собственный вопрос о сохранении уже обработан в {@code preLaunchCheck}. */
    private static final Map<ILaunchConfiguration, IProject> PENDING_LAUNCH_SAVE =
        Collections.synchronizedMap(new WeakHashMap<>());
    /** Проекты внутри текущего вызова делегата {@code launch}: перед синхронизацией второй раз не спрашивать. */
    private static final Map<IProject, Integer> ACTIVE_LAUNCH_SAVE = new ConcurrentHashMap<>();

    @Override
    public void earlyStartup()
    {
        // earlyStartup идёт в рабочем потоке — timerExec требует UI-поток.
        Display.getDefault().asyncExec(() ->
        {
            LaunchPageAugmenter.install(Display.getDefault());
            try
            {
                EditorSaveTracker.install();
            }
            catch (Throwable ignored)
            {
            }
            ApplicationUpdateGuard.install(Display.getDefault());
            Display.getDefault().timerExec(STARTUP_PATCH_DELAY_MS,
                LaunchSaveDirtyEditorsHook::installDelegateProxy);
        });
    }

    /** Время последнего сохранения любого редактора проекта, независимо от команды сохранения. */
    private static final class EditorSaveTracker
    {
        private static final Map<IProject, Long> LAST_SAVE = new ConcurrentHashMap<>();
        private static final Map<IEditorPart, IPropertyListener> LISTENERS = new IdentityHashMap<>();
        private static final Set<IWorkbenchWindow> WINDOWS =
            Collections.newSetFromMap(new IdentityHashMap<>());

        static void install()
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) { WINDOWS.remove(window); }
            });
        }

        private static void hookWindow(IWorkbenchWindow window)
        {
            if (window == null || !WINDOWS.add(window))
                return;
            for (IWorkbenchPage page : window.getPages())
                for (IEditorReference ref : page.getEditorReferences())
                    watch(ref);
            window.getPartService().addPartListener(new IPartListener2()
            {
                @Override public void partOpened(IWorkbenchPartReference ref) { watch(ref); }
                @Override public void partActivated(IWorkbenchPartReference ref) { watch(ref); }
                @Override public void partClosed(IWorkbenchPartReference ref)
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part instanceof IEditorPart editor)
                    {
                        IPropertyListener listener = LISTENERS.remove(editor);
                        if (listener != null)
                            editor.removePropertyListener(listener);
                    }
                }
                @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
                @Override public void partDeactivated(IWorkbenchPartReference ref) {}
                @Override public void partHidden(IWorkbenchPartReference ref) {}
                @Override public void partVisible(IWorkbenchPartReference ref) {}
                @Override public void partInputChanged(IWorkbenchPartReference ref) {}
            });
        }

        private static void watch(IWorkbenchPartReference ref)
        {
            if (!(ref instanceof IEditorReference editorRef))
                return;
            IEditorPart editor = editorRef.getEditor(false);
            if (editor == null || LISTENERS.containsKey(editor))
                return;
            boolean[] wasDirty = { editor.isDirty() };
            IPropertyListener listener = (source, propertyId) ->
            {
                if (propertyId != IEditorPart.PROP_DIRTY)
                    return;
                boolean dirty;
                try
                {
                    dirty = editor.isDirty();
                    if (wasDirty[0] && !dirty)
                    {
                        IResource resource = editorResource(editor);
                        IFile file = resource == null ? platformFile(editorModel(editor)) : null;
                        IProject project = resource != null ? resource.getProject()
                            : file == null ? null : file.getProject();
                        if (project != null)
                            LAST_SAVE.put(project, System.nanoTime());
                    }
                }
                catch (RuntimeException ignored)
                {
                    return;
                }
                wasDirty[0] = dirty;
            };
            LISTENERS.put(editor, listener);
            editor.addPropertyListener(listener);
        }

        static long remainingWaitMs(IProject project)
        {
            Long lastSave = LAST_SAVE.get(project);
            if (lastSave == null)
                return 0;
            long remainingNs = CHECKS_WAIT_MS * 1_000_000L - (System.nanoTime() - lastSave);
            return remainingNs <= 0 ? 0 : (remainingNs + 999_999L) / 1_000_000L;
        }

        static long remainingWaitMs(Set<IProject> projects)
        {
            long remaining = 0;
            for (IProject project : projects)
                remaining = Math.max(remaining, remainingWaitMs(project));
            return remaining;
        }

        static boolean hasSaved(IProject project)
        {
            return LAST_SAVE.containsKey(project);
        }

        static boolean hasSaved(Set<IProject> projects)
        {
            for (IProject project : projects)
                if (hasSaved(project))
                    return true;
            return false;
        }
    }

    /**
     * EDT вызывает {@code IApplicationManager.update} после выбора «Обновить и запустить».
     * Подменяем менеджер только в singleton {@code ApplicationUiSupport}: остальные пути обновления
     * остаются у настоящего менеджера, а у вызова есть {@link IApplication} с точным проектом.
     */
    private static final class ApplicationUpdateGuard
    {
        private static final String DIALOG_TITLE = "Обновление приложения"; //$NON-NLS-1$
        private static final String BUNDLE_ID = "com.e1c.g5.dt.applications.ui"; //$NON-NLS-1$
        private static final String PLUGIN_CLASS =
            "com.e1c.g5.dt.internal.applications.ui.ApplicationsUiPlugin"; //$NON-NLS-1$
        private static final String SUPPORT_CLASS =
            "com.e1c.g5.dt.applications.ui.IApplicationUiSupport"; //$NON-NLS-1$
        private static volatile boolean installed;

        static void install(Display display)
        {
            Listener listener = event ->
            {
                if (!(event.widget instanceof Shell shell) || !DIALOG_TITLE.equals(shell.getText()))
                    return;
                ensureInstalled();
            };
            display.addFilter(SWT.Show, listener);
            display.addFilter(SWT.Activate, listener);
        }

        private static void ensureInstalled()
        {
            if (installed)
                return;
            try
            {
                Bundle bundle = Platform.getBundle(BUNDLE_ID);
                if (bundle == null)
                    return;
                Class<?> pluginClass = bundle.loadClass(PLUGIN_CLASS);
                Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
                Object injectorValue = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
                if (!(injectorValue instanceof Injector injector))
                    return;
                Object support = injector.getInstance(bundle.loadClass(SUPPORT_CLASS));
                Object managerValue = Global.getField(support, "applicationManager"); //$NON-NLS-1$
                if (!(managerValue instanceof IApplicationManager real))
                    return;
                IApplicationManager proxy = (IApplicationManager) Proxy.newProxyInstance(
                    IApplicationManager.class.getClassLoader(), new Class<?>[] { IApplicationManager.class },
                    (self, method, args) -> invokeManager(real, self, method, args));
                installed = Global.setFieldForce(support, "applicationManager", proxy); //$NON-NLS-1$
            }
            catch (Throwable ignored)
            {
            }
        }

        private static Object invokeManager(IApplicationManager real, Object self, Method method, Object[] args)
            throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
            {
                if ("equals".equals(method.getName())) //$NON-NLS-1$
                    return self == (args == null ? null : args[0]);
                if ("hashCode".equals(method.getName())) //$NON-NLS-1$
                    return System.identityHashCode(self);
                return "ComfortApplicationManagerProxy(" + real + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if ("update".equals(method.getName()) && args != null && args.length == 4 //$NON-NLS-1$
                && args[0] instanceof IApplication application && args[3] instanceof IProgressMonitor monitor)
            {
                IProject project = application.getProject();
                if (project != null && !approveInfobaseSynchronization(project))
                {
                    monitor.setCanceled(true);
                    return ApplicationUpdateState.UPDATED;
                }
            }
            try
            {
                return method.invoke(real, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
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
            return false;
        }
    }

    /** Проксирует делегат запуска, заменяя штатное сохранение собственным вопросом. */
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
                && args[0] instanceof ILaunchConfiguration)
            {
                return preLaunchCheck(method, args);
            }
            if ("launch".equals(method.getName()) && args != null && args.length >= 1 //$NON-NLS-1$
                && args[0] instanceof ILaunchConfiguration config)
            {
                LaunchConfigurationHook.prepareCheckModal(config);
                IProject handledProject = PENDING_LAUNCH_SAVE.remove(config);
                if (handledProject != null)
                    enterLaunchSave(handledProject);
                try
                {
                    return forward(method, args);
                }
                finally
                {
                    if (handledProject != null)
                        leaveLaunchSave(handledProject);
                }
            }
            return forward(method, args);
        }

        private Object preLaunchCheck(Method method, Object[] args)
            throws Throwable
        {
            ILaunchConfiguration config = (ILaunchConfiguration)args[0];
            PENDING_LAUNCH_SAVE.remove(config);
            if (NEVER.equals(readPrefEffective()))
                return forward(method, args);

            IProject project = resolveProject(config);
            if (project != null)
            {
                Boolean decision = decideSave(project);
                if (Boolean.FALSE.equals(decision))
                    return Boolean.FALSE;
                if (Boolean.TRUE.equals(decision))
                    PENDING_LAUNCH_SAVE.put(config, project);
            }

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
         * @return {@code TRUE} — сохранение обработано нами; {@code FALSE} — отмена;
         *     {@code null} — несохранённых нет или параметр «never».
         */
        private static Boolean decideSave(IProject project)
        {
            Set<IProject> projects = linkedProjects(project);
            List<IEditorPart> dirty = new ArrayList<>();
            Display.getDefault().syncExec(() -> dirty.addAll(scopedDirtyEditors(projects)));
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
            if (answer[0] == SAVE_AND_CONTINUE_ID)
            {
                saveEditors(dirty);
                return Boolean.TRUE;
            }
            if (answer[0] == CONTINUE_WITHOUT_SAVE_ID)
                return Boolean.TRUE;
            return Boolean.FALSE;
        }

        /**
         * @return {@code TRUE} — пользователь подтвердил запуск при ошибках;
         *     {@code FALSE} — отмена; {@code null} — ошибок в открытых редакторах нет.
         */
        private static Boolean decideErrors(IProject project)
        {
            if (!errorsPromptEnabled())
                return null;
            Set<IProject> projects = linkedProjects(project);
            try
            {
                IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
                IGitMarkerFilterManager baseline = gitBaselineFilter();
                if (markerManager == null)
                    return null;

                List<Row> open = new ArrayList<>();
                Display.getDefault().syncExec(() ->
                {
                    for (IEditorPart editor : scopedOpenEditors(project))
                        open.add(rowOf(editor));
                });

                List<Row> rows = collectErrorRows(markerManager, baseline, project, open,
                    EditorSaveTracker.hasSaved(projects));
                long remainingWait = EditorSaveTracker.remainingWaitMs(projects);
                if (remainingWait > 0)
                    rows = waitAndRecheck(markerManager, baseline, project, projects, open, rows.isEmpty(), rows);
                if (rows.isEmpty())
                    return null;

                rows.sort(Comparator.comparing(row -> row.text));
                List<Row> errorRows = rows;
                int[] answer = { IDialogConstants.CANCEL_ID };
                Display.getDefault().syncExec(() -> answer[0] = openErrorsDialog(project.getName(), errorRows));
                return answer[0] == CONTINUE_WITH_ERRORS_ID ? Boolean.TRUE : Boolean.FALSE;
            }
            catch (Throwable t)
            {
                Global.logError(TAG, "ошибка проверки ошибок конфигурации", t); //$NON-NLS-1$
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
        private static List<Row> waitAndRecheck(IMarkerManager markerManager, IGitMarkerFilterManager baseline,
            IProject project, Set<IProject> projects, List<Row> open, boolean appearing, List<Row> rows)
        {
            long remaining;
            while ((remaining = EditorSaveTracker.remainingWaitMs(projects)) > 0)
            {
                waitForChecks(project, remaining);
                open.clear();
                Display.getDefault().syncExec(() ->
                {
                    for (IEditorPart editor : scopedOpenEditors(project))
                        open.add(rowOf(editor));
                });
                rows = collectErrorRows(markerManager, baseline, project, open, true);
                if (appearing && !rows.isEmpty())
                    return rows;
                if (!appearing && rows.isEmpty())
                    return rows;
                remaining = EditorSaveTracker.remainingWaitMs(projects);
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

        private static List<Row> collectErrorRows(IMarkerManager markerManager, IGitMarkerFilterManager baseline,
            IProject project, List<Row> open, boolean afterSave)
        {
            List<Row> rows = new ArrayList<>();
            for (Row row : open)
            {
                try
                {
                    if (!"none".equals(configErrorHit(markerManager, baseline, project, row, afterSave))) //$NON-NLS-1$
                        rows.add(row);
                }
                catch (RuntimeException e)
                {
                    Global.logError(TAG, "не удалось проверить редактор " + row.text, e); //$NON-NLS-1$
                }
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

    private static void enterLaunchSave(IProject project)
    {
        ACTIVE_LAUNCH_SAVE.merge(project, Integer.valueOf(1), Integer::sum);
    }

    private static void leaveLaunchSave(IProject project)
    {
        ACTIVE_LAUNCH_SAVE.computeIfPresent(project,
            (key, count) -> count.intValue() <= 1 ? null : Integer.valueOf(count.intValue() - 1));
    }

    private static boolean launchSaveAlreadyHandled(IProject project)
    {
        return ACTIVE_LAUNCH_SAVE.containsKey(project);
    }

    private static Set<IProject> linkedProjects(IProject project)
    {
        Set<IProject> projects = new LinkedHashSet<>();
        projects.add(project);
        IV8ProjectManager manager = Global.getOsgiService(IV8ProjectManager.class);
        if (manager != null)
            projects.addAll(SearchUtils.getLinkedProjects(project, manager));
        return projects;
    }

    private static List<IEditorPart> scopedDirtyEditors(Set<IProject> projects)
    {
        List<IEditorPart> result = new ArrayList<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getDirtyEditors())
                {
                    for (IProject project : projects)
                    {
                        if (belongsToProject(editor, project) && !result.contains(editor))
                        {
                            result.add(editor);
                            break;
                        }
                    }
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

    /** @return причина попадания ({@code markers}) или {@code none} */
    private static String configErrorHit(IMarkerManager markers, IGitMarkerFilterManager baseline, IProject project,
        Row row, boolean afterSave)
    {
        // Считаем только проблемы конфигурации самого объекта редактора: у них известна
        // критичность. Подчёркивания в тексте модуля и маркеры рабочего пространства на его
        // файлах поводом не являются — критичности у них нет, а ошибкой в модуле EDT рисует
        // и предупреждения критичности «Значительная».
        // Сразу после сохранения маркеры EDT ещё держат уже исправленную проблему:
        // если живые аннотации модуля уже пустые — им верим.
        if (afterSave && row.liveTrusted && "none".equals(row.liveHit)) //$NON-NLS-1$
            return "none"; //$NON-NLS-1$
        if (hasMarkerErrors(markers, baseline, project, row.ids, minSeverity()))
            return "markers"; //$NON-NLS-1$
        // IMarkerManager обновляется волнами с периодом порядка 20–30 с (issue 564, замерено
        // логом) — ждать эту волну на каждом запуске отладки нельзя. Живая ошибочная аннотация
        // Xtext доступна сразу, но несёт только бинарный признак «красная» критичность — какая
        // именно из BLOCKER/CRITICAL/MAJOR сработала, не известно. Доверяем ей напрямую только
        // когда выбранный порог не строже MAJOR (самой слабой из «красных»): тогда любая красная
        // ошибка модуля заведомо проходит фильтр критичности, и IMarkerManager не нужен.
        if (row.liveTrusted && hasLiveError(row.liveHit) && liveConfirmsSeverity())
            return "live"; //$NON-NLS-1$
        return "none"; //$NON-NLS-1$
    }

    /**
     * @return {@code true}, если выбранный минимум критичности не строже {@link MarkerSeverity#MAJOR}
     *     — самой слабой из критичностей, которые EDT показывает в модуле ошибкой
     *     ({@link LaunchPageAugmenter#choosableSeverities}). В этом случае любая живая ошибочная
     *     аннотация модуля гарантированно проходит порог, какая бы из «красных» критичностей за
     *     ней ни стояла.
     */
    private static boolean liveConfirmsSeverity()
    {
        return minSeverity().ordinal() >= MarkerSeverity.MAJOR.ordinal();
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
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (manager != null && timeoutMs > 0)
        {
            try
            {
                manager.waitAllComputations(timeoutMs);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (RuntimeException e)
            {
            }
        }
    }

    /**
     * @return минимальная учитываемая критичность; учитываются она и все более серьёзные
     *     ({@link MarkerSeverity#moreSevere} считает серьёзнее ту, что объявлена в перечислении раньше)
     */
    static MarkerSeverity minSeverity()
    {
        String name = ComfortSettings.getLaunchErrorsMinSeverity();
        for (MarkerSeverity severity : MarkerSeverity.values())
        {
            if (severity.name().equals(name))
                return severity;
        }
        return DEFAULT_MIN_SEVERITY;
    }

    /** @return штатное название критичности («Ошибка», «Критическая», …) */
    static String localizedSeverity(MarkerSeverity severity)
    {
        String name = LocalizedEnumProvider.getLocalizedString(severity);
        return name == null || name.isBlank() ? severity.name() : name;
    }

    /** @return {@code true}, если критичность маркера не мягче выбранного минимума */
    private static boolean severityAccepted(MarkerSeverity severity, MarkerSeverity min)
    {
        return severity != null && severity != MarkerSeverity.NONE && severity.ordinal() <= min.ordinal();
    }

    private static boolean hasMarkerErrors(IMarkerManager markers, IGitMarkerFilterManager baseline,
        IProject project, List<Object> objectIds, MarkerSeverity minSeverity)
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
        return queryMarkerErrors(markers, baseline, project, bmIds, minSeverity)
            || queryMarkerErrors(markers, baseline, project, pathIds, minSeverity);
    }

    /**
     * Критичность проверяется здесь, а не штатным {@code createSeverityFilter}: тот отбирает
     * маркеры ровно одной критичности, а нам нужны все не мягче выбранной.
     */
    private static boolean queryMarkerErrors(IMarkerManager markers, IGitMarkerFilterManager baseline,
        IProject project, List<Object> ids, MarkerSeverity minSeverity)
    {
        if (ids == null || ids.isEmpty())
            return false;
        MarkerFilter filter = MarkerFilter.createObjectFilter(project, ids);
        return markers.markers(filter).anyMatch(marker ->
            severityAccepted(marker.getSeverity(), minSeverity)
                && !hiddenByGitBaseline(baseline, project, marker));
    }

    /** @return служба штатного отбора проблем базовой ветки git или {@code null}, если недоступна */
    private static IGitMarkerFilterManager gitBaselineFilter()
    {
        try
        {
            return Global.getOsgiService(IGitMarkerFilterManager.class);
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /**
     * Ошибка, скрытая штатным отбором «Скрыть языковые проблемы из базовой ветки git»,
     * пользователю не видна ни в панели «Проблемы», ни значком на вкладке редактора —
     * предупреждать о ней перед запуском нельзя (issue 514).
     *
     * <p>{@code shouldSkipMarker} отвечает лишь на вопрос «маркер пришёл из базовой ветки»:
     * по этому признаку {@code BranchChangesIndexProvider} строит отбор панели «Проблемы»
     * (см. {@link GitBaselineFilterHook}). Скрыт маркер на самом деле или нет, говорит
     * {@code isFilterApplied}: пока отбор не применён, проблемы видны, и подавлять вопрос
     * из-за них нельзя — иначе отсеиваются все проблемы проекта с базовой веткой.
     */
    private static boolean hiddenByGitBaseline(IGitMarkerFilterManager baseline, IProject project, Marker marker)
    {
        if (baseline == null || marker == null)
            return false;
        try
        {
            return baseline.isFilterApplied() && baseline.shouldSkipMarker(marker, project);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
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
        return new SaveEditorsDialog(dialogShell(), projectName, rows).open();
    }

    /** Проверка перед синхронизацией с ожиданием, если редактор проекта недавно сохраняли. */
    static boolean approveInfobaseSynchronization(IProject project)
    {
        try
        {
            if (!launchSaveAlreadyHandled(project)
                && Boolean.FALSE.equals(DelegateHandler.decideSave(project)))
                return false;
        }
        catch (Throwable t)
        {
            return false;
        }
        return !Boolean.FALSE.equals(DelegateHandler.decideErrors(project));
    }

    /** Проверяет вне UI-потока, чтобы сохранённый модуль успел обновить живые маркеры. */
    static void approveInfobaseSynchronizationAsync(IProject project, Runnable onApproved)
    {
        Display display = Display.getDefault();
        Job job = new Job("Проверка ошибок перед синхронизацией") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (approveInfobaseSynchronization(project) && !display.isDisposed())
                    display.asyncExec(() ->
                    {
                        if (!display.isDisposed())
                            onApproved.run();
                    });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
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

    private static IPreferenceStore debugUiStore()
    {
        try
        {
            Bundle bundle = Platform.getBundle(PREF_NODE);
            Class<?> pluginClass = bundle == null ? null
                : bundle.loadClass("org.eclipse.debug.internal.ui.DebugUIPlugin"); //$NON-NLS-1$
            Object plugin = pluginClass == null ? null : Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            if (plugin instanceof AbstractUIPlugin uiPlugin)
                return uiPlugin.getPreferenceStore();
        }
        catch (Exception | LinkageError e)
        {
            Global.tempLog("launch-save", "debugUiStore: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new ScopedPreferenceStore(InstanceScope.INSTANCE, PREF_NODE);
    }

    /** Действующее значение параметра (с учётом значения по умолчанию). */
    private static String readPrefEffective()
    {
        String value = debugUiStore().getString(PREF_SAVE_DIRTY);
        return value == null || value.isEmpty() ? PROMPT : value;
    }

    /**
     * @return {@code true}, если штатный параметр «Продолжать выполнение в случае обнаружения
     *     ошибок проекта» стоит в «Предлагать» — только тогда спрашиваем про ошибки
     */
    private static boolean errorsPromptEnabled()
    {
        String value = debugUiStore().getString(PREF_CONTINUE_WITH_ERRORS);
        return PROMPT.equals(value);
    }

    /**
     * На время вызова настоящего {@code preLaunchCheck} держит параметр
     * «Сохранять изменённые редакторы перед запуском» в значении «never», затем
     * возвращает прежнее (или сбрасывает в значение по умолчанию, если оно не было задано).
     */
    private static final class PrefGuard
    {
        private final IPreferenceStore store;
        private final boolean wasDefault;
        private final String previous;

        private PrefGuard(IPreferenceStore store, boolean wasDefault, String previous)
        {
            this.store = store;
            this.wasDefault = wasDefault;
            this.previous = previous;
        }

        static PrefGuard suppress()
        {
            IPreferenceStore store = debugUiStore();
            boolean wasDefault = store.isDefault(PREF_SAVE_DIRTY);
            String previous = store.getString(PREF_SAVE_DIRTY);
            store.setValue(PREF_SAVE_DIRTY, NEVER);
            return new PrefGuard(store, wasDefault, previous);
        }

        void restore()
        {
            if (wasDefault)
                store.setToDefault(PREF_SAVE_DIRTY);
            else
                store.setValue(PREF_SAVE_DIRTY, previous);
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Дополняет штатную страницу параметров «Запуск/Отладка → Запуск»: рядом с переключателем
     * «Продолжать выполнение в случае обнаружения ошибок проекта» добавляет выбор минимальной
     * критичности проблемы, о которой спрашивать
     * (<a href="https://github.com/tormozit/EDT.Comfort/issues/516">issue 516</a>).
     *
     * <p>Страница штатная и не наша: контрол вставляется в группу самого переключателя, найденную
     * через {@code FieldEditorPreferencePage.fields} по имени параметра, — так вставка не зависит
     * ни от порядка групп на странице, ни от перевода их подписей.
     */
    private static final class LaunchPageAugmenter
    {
        private static final String PAGE_CLASS_NAME =
            "org.eclipse.debug.internal.ui.preferences.LaunchingPreferencePage"; //$NON-NLS-1$
        private static final String PATCHED_KEY = "tormozit.launchMinSeverity"; //$NON-NLS-1$
        private static final int MAX_ATTEMPTS = 30;
        private static final int RETRY_MS = 100;

        private static final String LABEL = "Минимальная критичность:"; //$NON-NLS-1$
        private static final String TOOLTIP =
            "Минимальная критичность проблемы открытых редакторов, о которой спрашивать при"
                + " значении «Предлагать». «Значительная» — предупреждение без задержки, строже —"
                + " точнее, но может сработать не сразу, а на следующем запуске.";

        private static final WeakHashMap<Shell, Boolean> wired = new WeakHashMap<>();

        private LaunchPageAugmenter()
        {
        }

        static void install(Display display)
        {
            if (display == null || display.isDisposed())
                return;
            Listener listener = event ->
            {
                if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                    return;
                PreferenceDialog dialog = findPreferenceDialog(shell);
                if (dialog != null)
                    scheduleWireOnce(display, shell, dialog);
            };
            display.addFilter(SWT.Show, listener);
            display.addFilter(SWT.Activate, listener);
        }

        private static PreferenceDialog findPreferenceDialog(Shell shell)
        {
            Shell current = shell;
            while (current != null && !current.isDisposed())
            {
                if (current.getData() instanceof PreferenceDialog dialog)
                    return dialog;
                current = current.getParent() instanceof Shell parent ? parent : null;
            }
            return null;
        }

        private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
        {
            synchronized (wired)
            {
                if (Boolean.TRUE.equals(wired.get(shell)))
                    return;
                wired.put(shell, Boolean.TRUE);
            }
            dialog.addPageChangedListener(event -> tryPatch(dialog.getSelectedPage()));
            scheduleRetry(display, shell, dialog, 0);
        }

        /** Страница создаётся не мгновенно: пробуем, пока переключатель не найден. */
        private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog, int attempt)
        {
            if (shell.isDisposed())
                return;
            if (tryPatch(dialog.getSelectedPage()) || attempt >= MAX_ATTEMPTS)
                return;
            display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
        }

        /** @return {@code true}, если страница не наша, уже дополнена или дополнена сейчас */
        private static boolean tryPatch(Object selected)
        {
            if (!(selected instanceof IPreferencePage page) || !PAGE_CLASS_NAME.equals(page.getClass().getName()))
                return true;
            Composite radioBox = findRadioBox(page);
            if (radioBox == null || radioBox.isDisposed())
                return false;
            if (Boolean.TRUE.equals(radioBox.getData(PATCHED_KEY)))
                return true;

            addSeverityChooser(radioBox);
            radioBox.setData(PATCHED_KEY, Boolean.TRUE);
            radioBox.layout(true, true);
            if (radioBox.getParent() != null)
                radioBox.getParent().layout(true, true);
            return true;
        }

        /** @return группа переключателя «Продолжать выполнение…» или {@code null}, если ещё не создана */
        private static Composite findRadioBox(IPreferencePage page)
        {
            if (!(Global.getField(page, "fields") instanceof List<?> fields)) //$NON-NLS-1$
                return null;
            for (Object field : fields)
            {
                if (!(field instanceof FieldEditor editor)
                    || !PREF_CONTINUE_WITH_ERRORS.equals(editor.getPreferenceName()))
                    continue;
                return Global.getField(editor, "radioBox") instanceof Composite box ? box : null; //$NON-NLS-1$
            }
            return null;
        }

        private static void addSeverityChooser(Composite radioBox)
        {
            Label label = new Label(radioBox, SWT.NONE);
            label.setText(LABEL);
            label.setToolTipText(TooltipText.wrap(label, TOOLTIP + Global.pluginSignForTooltip()));

            // Combo в SWT картинок не показывает, поэтому берём TableCombo: в выпадающем
            // списке у каждой строки штатный значок критичности.
            TableCombo combo = new TableCombo(radioBox, SWT.BORDER | SWT.READ_ONLY);
            combo.setToolTipText(TooltipText.wrap(combo, TOOLTIP + Global.pluginSignForTooltip()));
            combo.setShowTableLines(false);
            combo.setShowTableHeader(false);

            List<MarkerSeverity> severities = choosableSeverities();
            for (MarkerSeverity severity : severities)
            {
                TableItem item = new TableItem(combo.getTable(), SWT.NONE);
                item.setText(localizedSeverity(severity));
                Image image = severityImage(severity);
                if (image != null)
                    item.setImage(image);
            }
            combo.select(Math.max(0, severities.indexOf(minSeverity())));
            combo.addSelectionListener(SelectionListener.widgetSelectedAdapter(event ->
            {
                int index = combo.getSelectionIndex();
                if (index >= 0 && index < severities.size())
                    ComfortSettings.saveLaunchErrorsMinSeverity(severities.get(index).name());
            }));
        }

        /**
         * Только «красные» критичности — те, которые EDT показывает в модуле ошибкой
         * ({@link ValidationChecksFilterHook#moduleAnnotation}). Остальные ({@code MINOR},
         * {@code TRIVIAL}) запуску не мешают в любом случае, выбирать их незачем.
         */
        private static List<MarkerSeverity> choosableSeverities()
        {
            List<MarkerSeverity> result = new ArrayList<>();
            for (MarkerSeverity severity : MarkerSeverity.values())
            {
                if (severity == MarkerSeverity.ERRORS || isModuleError(severity))
                    result.add(severity);
            }
            return result;
        }

        /** @return штатный значок критичности или {@code null}, если его нет */
        private static Image severityImage(MarkerSeverity severity)
        {
            try
            {
                return DtUiUtil.getImageByMarkerSeverity(severity);
            }
            catch (RuntimeException | LinkageError ignored)
            {
                return null;
            }
        }

        /** @return {@code true}, если проблема такой критичности подчёркивается в модуле как ошибка */
        private static boolean isModuleError(MarkerSeverity severity)
        {
            for (IssueSeverity issue : IssueSeverity.values())
            {
                if (issue.name().equals(severity.name()))
                    return ValidationChecksFilterHook.moduleAnnotation(
                        issue) == ValidationChecksFilterHook.ModuleAnnotation.ERROR;
            }
            return false;
        }
    }

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

        /** @return пояснение под списком или {@code null}, если пояснения нет */
        protected String footerText()
        {
            return null;
        }

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

            String footer = footerText();
            if (footer != null)
            {
                Label label = new Label(area, SWT.WRAP);
                label.setText(footer);
                GridData footerData = new GridData(SWT.FILL, SWT.TOP, true, false);
                footerData.widthHint = gd.widthHint;
                label.setLayoutData(footerData);
            }

            applyDialogFont(area);
            return area;
        }

        /**
         * Кнопка перехода к штатному параметру, управляющему этим вопросом
         * (<a href="https://github.com/tormozit/EDT.Comfort/issues/516">issue 516</a>).
         */
        protected void createPreferencesButton(Composite parent)
        {
            Button button = createButton(parent, OPEN_PREFERENCES_ID, "", false); //$NON-NLS-1$
            Image gear = gearImage(button);
            if (gear != null)
                button.setImage(gear);
            else
                button.setText("Настройки…"); //$NON-NLS-1$
            button.setToolTipText(TooltipText.wrap(button,
                "Открыть страницу параметров «Запуск/Отладка → Запуск»: там задаётся, сохранять ли " //$NON-NLS-1$
                    + "изменённые редакторы и спрашивать ли об ошибках проекта " //$NON-NLS-1$
                    + "перед запуском и синхронизацией с базой.")); //$NON-NLS-1$
            shrinkToContent(button);
        }

        /**
         * {@code Dialog.createButton} тянет кнопку до штатной ширины
         * ({@link IDialogConstants#BUTTON_WIDTH}) — кнопке со значком столько места не нужно.
         */
        private static void shrinkToContent(Button button)
        {
            if (button.getLayoutData() instanceof GridData data)
            {
                data.widthHint = button.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
                data.horizontalAlignment = SWT.BEGINNING;
                data.grabExcessHorizontalSpace = false;
            }
        }

        /**
         * Кнопка со значком — у левого края окна, в своей панели. Штатную панель кнопок
         * не трогаем: у неё {@code makeColumnsEqualWidth}, и любое её растягивание разносит
         * ширину остальных кнопок.
         */
        @Override
        protected Control createButtonBar(Composite parent)
        {
            Composite host = new Composite(parent, SWT.NONE);
            GridLayout hostLayout = new GridLayout(2, false);
            // Поля окна держит host: у вложенной штатной панели они обнуляются, иначе двойные.
            hostLayout.marginWidth = convertHorizontalDLUsToPixels(IDialogConstants.HORIZONTAL_MARGIN);
            hostLayout.marginHeight = convertVerticalDLUsToPixels(IDialogConstants.VERTICAL_MARGIN);
            hostLayout.horizontalSpacing = 0;
            host.setLayout(hostLayout);
            host.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            host.setFont(parent.getFont());

            Composite left = new Composite(host, SWT.NONE);
            GridLayout leftLayout = new GridLayout(0, false);
            leftLayout.marginWidth = 0;
            leftLayout.marginHeight = 0;
            left.setLayout(leftLayout);
            left.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
            left.setFont(parent.getFont());
            createPreferencesButton(left);

            Control bar = super.createButtonBar(host);
            if (bar.getLayoutData() instanceof GridData barData)
            {
                barData.horizontalAlignment = SWT.END;
                barData.grabExcessHorizontalSpace = true;
            }
            if (bar instanceof Composite barComposite && barComposite.getLayout() instanceof GridLayout barLayout)
            {
                barLayout.marginWidth = 0;
                barLayout.marginHeight = 0;
            }
            return host;
        }

        /**
         * Значок шестерёнки берём штатный ({@code org.eclipse.egit.ui}): своей иконки настроек
         * у плагина нет, а у EDT в {@code icons} её тоже нет.
         *
         * @return значок, снимаемый вместе с кнопкой, или {@code null}, если загрузить не вышло
         */
        private static Image gearImage(Button button)
        {
            ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(
                "org.eclipse.egit.ui", "icons/obj16/settings.png"); //$NON-NLS-1$ //$NON-NLS-2$
            if (descriptor == null)
                return null;
            Image image = descriptor.createImage(false, button.getDisplay());
            if (image != null)
                button.addDisposeListener(event -> image.dispose());
            return image;
        }

        @Override
        protected void buttonPressed(int buttonId)
        {
            if (buttonId == OPEN_PREFERENCES_ID)
            {
                openLaunchPreferences(getShell());
                return;
            }
            setReturnCode(buttonId);
            close();
        }

        /** Окно вопроса остаётся открытым: параметры показываются поверх него. */
        private static void openLaunchPreferences(Shell parent)
        {
            try
            {
                PreferencesUtil
                    .createPreferenceDialogOn(parent, LAUNCH_PREF_PAGE_ID, new String[] { LAUNCH_PREF_PAGE_ID }, null)
                    .open();
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "не удалось открыть параметры запуска", e); //$NON-NLS-1$
            }
        }
    }

    /** Список несохранённых редакторов проекта перед запуском или отдельной синхронизацией. */
    private static final class SaveEditorsDialog extends EditorListDialog
    {
        SaveEditorsDialog(Shell parentShell, String projectName, List<Row> rows)
        {
            super(parentShell, projectName, rows);
        }

        @Override
        protected void configureShell(Shell shell)
        {
            super.configureShell(shell);
            shell.setText(Global.withPluginWindowTitle("Сохранение редакторов")); //$NON-NLS-1$
            shell.setImage(PlatformUI.getWorkbench().getSharedImages()
                .getImage(ISharedImages.IMG_ETOOL_SAVE_EDIT));
        }

        @Override
        protected String headerText()
        {
            return "В проекте «" + projectName //$NON-NLS-1$
                + "» и связанных проектах есть несохранённые редакторы (двойной клик — открыть редактор):"; //$NON-NLS-1$
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, SAVE_AND_CONTINUE_ID, "Сохранить и продолжить", true); //$NON-NLS-1$
            createButton(parent, CONTINUE_WITHOUT_SAVE_ID, "Продолжить без сохранения", false); //$NON-NLS-1$
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
        protected void configureShell(Shell shell)
        {
            super.configureShell(shell);
            shell.setText(Global.withPluginWindowTitle("Ошибки конфигурации")); //$NON-NLS-1$
            shell.setImage(shell.getDisplay().getSystemImage(SWT.ICON_ERROR));
        }

        @Override
        protected String headerText()
        {
            return "В открытых редакторах проекта «" + projectName //$NON-NLS-1$
                + "» есть ошибки конфигурации (двойной клик — открыть редактор):"; //$NON-NLS-1$
        }

        @Override
        protected String footerText()
        {
            return "Учитываются проблемы критичности «" //$NON-NLS-1$
                + localizedSeverity(minSeverity()) + "» и серьёзнее, кроме скрытых отбором «Скрыть языковые проблемы из базовой ветки git»"; //$NON-NLS-1$
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, CONTINUE_WITH_ERRORS_ID, "Продолжить", true); //$NON-NLS-1$
            createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
        }
    }
}
