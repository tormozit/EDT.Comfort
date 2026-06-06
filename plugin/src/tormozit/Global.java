package tormozit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.xtext.ide.server.ProjectManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;    
import org.eclipse.swt.widgets.*;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.IManagedService;

/**
 * Утилиты Java-рефлексии и общего назначения — единый источник правды для всего плагина.
 */
public final class Global
{
    /** ID навигатора EDT. */
    static final String NAVIGATOR_VIEW_ID  = "com._1c.g5.v8.dt.ui2.navigator";  //$NON-NLS-1$

    /** ID редактора сравнения конфигураций EDT. */
    static final String COMPARE_EDITOR_ID  = "com._1c.g5.v8.dt.compare.ui.editor"; //$NON-NLS-1$
   
    /** ID универсального редактора свойств объекта EDT. */
    static final String PROPERTIES_SHEET_ID  = "org.eclipse.ui.views.properties.PropertySheet"; //$NON-NLS-1$

    private Global() {}

    /**
     * Читает значение поля {@code fieldName} из объекта {@code obj},
     * поднимаясь по иерархии суперклассов.
     *
     * @return значение поля, или {@code null} при любой ошибке / отсутствии поля
     */
    public static Object getField(Object obj, String fieldName)
    {
        if (obj == null || fieldName == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) {}
            catch (Exception ignored)            { return null; }
        }
        return null;
    }
    
    /**
     * Записывает значение {@code value} в поле {@code fieldName} объекта {@code obj},
     * поднимаясь по иерархии суперклассов.
     */
    public static void setField(Object obj, String fieldName, Object value)
    {
        if (obj == null || fieldName == null) return;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            }
            catch (NoSuchFieldException ignored) {}
            catch (Exception ignored) { return; }
        }
    }
    
    /**
     * Вызывает метод {@code methodName} с переданными аргументами на объекте {@code obj}.
     * Метод ищется по имени и количеству аргументов по всей иерархии классов,
     * включая private и protected.
     *
     * @return результат вызова, или {@code null} при любой ошибке
     */
    public static Object invoke(Object obj, String methodName, Object... args)
    {
        if (obj == null || methodName == null) return null;
        int argc = args == null ? 0 : args.length;

        boolean isStatic = obj instanceof Class<?>;
        Class<?> startClass = isStatic ? (Class<?>) obj : obj.getClass();
        Object targetInstance = isStatic ? null : obj;

        for (Class<?> c = startClass; c != null; c = c.getSuperclass())
        {
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
            {
                if (m.getName().equals(methodName) && m.getParameterCount() == argc)
                {
                    try
                    {
                        m.setAccessible(true);
                        return m.invoke(targetInstance, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException e)
                    {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    }
                    catch (Exception ignored) { return null; }
                }
            }
        }
        return null;
    }

    /** Как {@link #invoke}, но {@code true} если метод найден и вызван (в т.ч. {@code void}). */
    public static boolean invokeVoid(Object obj, String methodName, Object... args)
    {
        if (obj == null || methodName == null) return false;
        int argc = args == null ? 0 : args.length;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
            {
                if (m.getName().equals(methodName) && m.getParameterCount() == argc)
                {
                    try
                    {
                        m.setAccessible(true);
                        m.invoke(obj, args);
                        return true;
                    }
                    catch (java.lang.reflect.InvocationTargetException e)
                    {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    }
                    catch (Exception ignored) { return false; }
                }
            }
        }
        return false;
    }

    /**
     * Вызывает публичный метод {@code methodName} без аргументов на объекте {@code obj}.
     *
     * @return результат вызова, или {@code null} при любой ошибке / отсутствии метода
     */
    public static Object call(Object obj, String methodName)
    {
        if (obj == null || methodName == null) return null;
        try { return obj.getClass().getMethod(methodName).invoke(obj); }
        catch (Exception ignored) { return null; }
    }

    // =========================================================================
    // OSGi / сервисы
    // =========================================================================

    public static BundleContext ourContext()
    {
        Bundle b = FrameworkUtil.getBundle(Global.class);
        return b != null ? b.getBundleContext() : null;
    }

    public static IManagedService getServiceByClass(Class<?> clazz)
    {
        BundleContext ctx = Global.ourContext();
        return (IManagedService) ctx.getService(ctx.getServiceReference(clazz));
    }

    // =========================================================================
    // Проекты
    // =========================================================================

    /**
     * Возвращает активный {@link IProject} из нескольких источников (в порядке приоритета):
     * <ol>
     *   <li>Файл, открытый в активном редакторе ({@code IEditorInput → IFile → IProject}).</li>
     *   <li>Редактор сравнения конфигураций EDT — через сессию сравнения
     *       ({@link CompareConfigOpenObjectHandler#getProjectFromEditor}).</li>
     *   <li>Выделенный элемент в навигаторе EDT.</li>
     * </ol>
     *
     * @param page рабочая страница; если {@code null} — берётся активная страница IDE
     * @return проект или {@code null}
     */
    public static IProject getActiveProject(IWorkbenchPage page, boolean showMessage)
    {
        if (page == null) 
            page = getActivePage();
        if (page == null) 
            return null;
        IEditorPart editor = page.getActiveEditor();
        if (editor != null)
        {
            IFile file = editorToFile(editor);
            if (file != null)
                return file.getProject();
        }

        // 2. Из редактора сравнения конфигураций
        if (editor != null && COMPARE_EDITOR_ID.equals(editor.getSite().getId()))
        {
            IProject p = CompareConfigOpenObjectHandler.getProjectFromEditor(editor);
            if (p != null) 
                return p;
        }

        // 3. Из навигатора EDT
        IProject navProject = getProjectFromNavigator();
        if (navProject != null) 
            return navProject;

        if (navProject == null && showMessage)
            ToastNotification.show("Проект", "Отсутствует активный проект");
        return null;
    }

    public static IProject getActiveEditorProject(boolean showMessage)
    {
        IProject project = getActiveProject(null, showMessage);
        return project;
    }

    /** Возвращает {@link IDtProject} из поля {@code project} произвольного редактора. */
    public static IDtProject getProjectFromEditor(Object editorPart)
    {
        Object project = Global.getField(editorPart, "project"); //$NON-NLS-1$
        if (project instanceof IDtProject) return (IDtProject) project;
        Object context = Global.getField(editorPart, "context"); //$NON-NLS-1$
        if (context != null)
        {
            Object p = Global.getField(context, "project"); //$NON-NLS-1$
            if (p instanceof IDtProject) return (IDtProject) p;
        }
        return null;
    }

    // =========================================================================
    // Вспомогательные
    // =========================================================================

    public static void log(String msg)
    {
        String ts = java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")); //$NON-NLS-1$
        System.out.println("[Tormozit " + ts + "] " + msg); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String readTextFromFile(File file) throws IOException
    {
        String text = normalizeLineSeparators(Files.readString(file.toPath()));
        return text.startsWith("\uFEFF") ? text.substring(1) : text; // strip BOM
    }

    // =========================================================================
    // Приватные утилиты
    // =========================================================================

    private static IWorkbenchPage getActivePage()
    {
        IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return w != null ? w.getActivePage() : null;
    }

    private static IFile editorToFile(IEditorPart editor)
    {
        if (editor == null) return null;
        IEditorInput input = editor.getEditorInput();
        return input != null ? input.getAdapter(IFile.class) : null;
    }

    private static IProject getProjectFromNavigator()
    {
        try
        {
            CommonNavigator nav = (CommonNavigator) getViewById(NAVIGATOR_VIEW_ID);
            if (nav == null) 
                return null;
            TreeSelection sel = (TreeSelection) nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty()) 
                return null;
            return (IProject) sel.getPaths()[0].getSegment(0);
        }
        catch (Exception ignored) { 
            return null; 
        }
    }
    
    public static IViewPart getViewById(String id) {
        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                IViewReference ref = page.findViewReference(id);
                if (ref != null) {
                    IViewPart view = ref.getView(false); // не создавать, если не открыт
                    if (view != null)
                        return view;
                }
            }
        }
        return null;
    }
    public static String normalizeLineSeparators(String text) {
        if (text == null) return null;
        // Сначала заменяем \r\n на \n (Windows)
        // Затем заменяем оставшиеся \r на \n (старые Mac)
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Найти первый Control по предикату в поддереве */
    public static <T extends Control> T findControl(Composite root, Class<T> type, java.util.function.Predicate<T> predicate) {
        for (Control c : root.getChildren()) {
            if (type.isInstance(c) && predicate.test(type.cast(c))) {
                return type.cast(c);
            }
            if (c instanceof Composite) {
                T found = findControl((Composite) c, type, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Удобный поиск Label по подстроке текста */
    public static Label findLabelByText(Composite root, String text) {
        return findControl(root, Label.class, lbl -> {
            String t = lbl.getText();
            return t != null && t.contains(text);
        });
    }

}
