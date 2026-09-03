package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.texteditor.ITextEditor;

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
 *   <li>затем вызывает настоящий {@code preLaunchCheck}, временно выставив параметр
 *       в «never», чтобы штатное сохранение по всему рабочему пространству не спросило
 *       второй раз.</li>
 * </ul>
 *
 * <p>Предупреждение об ошибках конфигурации в открытых модулях (вторая часть issue 455)
 * пока не реализовано.
 */
public final class LaunchSaveDirtyEditorsHook implements IStartup
{
    private static final String TAG = "LaunchSaveDirtyEditors"; //$NON-NLS-1$

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
         * @return {@code TRUE} — продолжать запуск (редакторы сохранены либо пользователь
         *     выбрал «не сохранять»); {@code FALSE} — отменить запуск; {@code null} — мы не
         *     вмешиваемся, обычный ход.
         */
        private Boolean decide(ILaunchConfiguration config) throws CoreException
        {
            IProject project = resolveProject(config);
            if (project == null)
                return null;
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
            Display.getDefault().syncExec(() -> answer[0] = openDialog(projectName, dirty));
            if (answer[0] == SAVE_AND_LAUNCH_ID)
            {
                saveEditors(dirty);
                return Boolean.TRUE;
            }
            if (answer[0] == LAUNCH_WITHOUT_SAVE_ID)
                return Boolean.TRUE;
            return Boolean.FALSE;
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
                    IResource resource = editor.getEditorInput().getAdapter(IResource.class);
                    if (resource != null && project.equals(resource.getProject()) && !result.contains(editor))
                        result.add(editor);
                }
            }
        }
        return result;
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

    private static int openDialog(String projectName, List<IEditorPart> dirty)
    {
        Shell shell = Display.getDefault().getActiveShell();
        if (shell == null)
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            shell = window != null ? window.getShell() : null;
        }
        List<Row> rows = new ArrayList<>();
        for (IEditorPart editor : dirty)
            rows.add(new Row(editor, editorPresentation(editor)));
        rows.sort(Comparator.comparing(row -> row.text));
        return new SaveAndLaunchDialog(shell, projectName, rows).open();
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

    /** Строка списка: редактор и его представление (полное имя объекта метаданных). */
    private static final class Row
    {
        final IEditorPart editor;
        final String text;

        Row(IEditorPart editor, String text)
        {
            this.editor = editor;
            this.text = text;
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

    /** Список несохранённых редакторов проекта и три кнопки выбора действия. */
    private static final class SaveAndLaunchDialog extends Dialog
    {
        private final String projectName;
        private final List<Row> rows;

        SaveAndLaunchDialog(Shell parentShell, String projectName, List<Row> rows)
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

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite)super.createDialogArea(parent);

            Label header = new Label(area, SWT.WRAP);
            header.setText("В проекте «" + projectName //$NON-NLS-1$
                + "» есть несохранённые редакторы (двойной клик — открыть редактор):"); //$NON-NLS-1$
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
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, SAVE_AND_LAUNCH_ID, "Сохранить и запустить", true); //$NON-NLS-1$
            createButton(parent, LAUNCH_WITHOUT_SAVE_ID, "Не сохранять и запустить", false); //$NON-NLS-1$
            createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
        }

        @Override
        protected void buttonPressed(int buttonId)
        {
            setReturnCode(buttonId);
            close();
        }
    }
}
