

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.IManagedService;
import com._1c.g5.v8.dt.navigator.AbstractDtNavigator;
import com._1c.g5.v8.dt.md.ui.navigator.adapters.CommonNavigatorAdapter;
/**
 * Утилиты Java-рефлексии — единый источник правды для всего плагина.
 *
 * <p>Заменяет дублирующиеся реализации, которые были рассыпаны по четырём классам:
 * <ul>
 *   <li>{@code CompareConfigMenuHook.getField} / {@code invokeNoArg}</li>
 *   <li>{@code CompareConfigOpenObjectHandler.getField} / {@code invokeNoArg}</li>
 *   <li>{@code IRApplicationRegistry.readField} / {@code tryCall}</li>
 *   <li>{@code DesignerSessionPoolAccessor.readField} / {@code tryCall}</li>
 * </ul>
 */
public final class Global
{
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
     * Вызывает метод {@code methodName} с переданными аргументами на объекте {@code obj}.
     * Метод ищется по имени и количеству аргументов по всей иерархии классов,
     * включая private и protected.
     *
     * @return результат вызова, или {@code null} при любой ошибке
     * @throws RuntimeException если метод бросает проверяемое исключение (причина из {@code InvocationTargetException})
     */
    public static Object invoke(Object obj, String methodName, Object... args)
    {
        if (obj == null || methodName == null) return null;
        int argc = args == null ? 0 : args.length;
        
        // Если передан экземпляр класса Class, значит мы вызываем статический метод этого класса.
        // В противном случае — обычный метод экземпляра объекта.
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

    public static BundleContext ourContext()
    {
        Bundle b = FrameworkUtil.getBundle(Global.class);
        return b != null ? b.getBundleContext() : null;
    }
    
    public static IDtProject getProjectFromEditor(Object editorPart) {
        Object project = Global.getField(editorPart, "project");
        
        if (project instanceof IDtProject) {
            return (IDtProject) project;
        }
        Object context = Global.getField(editorPart, "context");
        if (context != null) {
            Object p = Global.getField(context, "project");
            if (p instanceof IDtProject) return (IDtProject) p;
        }
        return null;
    }

    public static IManagedService getServiceByClass(Class<?> clazz)
    {
        BundleContext ourContext = Global.ourContext();
        return (IManagedService) ourContext.getService(ourContext.getServiceReference(clazz));
    }
        
    public static void log(String msg)
    {
       String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
       System.out.println("[Tormozit " + timestamp + "] " + msg);
    }
    
    public static IProject getActiveEditorProject(boolean showMessage) {
        IEditorPart activeEditor = null;
        IWorkbenchPage activePage = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        if (activePage != null)
        {
            activeEditor = activePage.getActiveEditor();
            if (activeEditor != null)
            {
                IEditorInput input = activeEditor.getEditorInput();
                if (input != null)
                {
                    IFile file = input.getAdapter(IFile.class);
                    if (file != null)
                    {
                        return file.getProject();
                    }
                }
            }
        }
        if (activeEditor != null)
        {
            CommonNavigator navigator = (CommonNavigator) activePage.findView("com._1c.g5.v8.dt.ui2.navigator"); // ID Навигатора EDT com._1c.g5.v8.dt.internal.navigator.ui.Navigator
            IStructuredSelection  selection = (IStructuredSelection ) navigator.getSite().getSelectionProvider().getSelection();
            Object firstElement = selection.getFirstElement();
            IProject project = (IProject) Adapters.adapt(firstElement, IProject.class);
            if (project!=null)
            {
                return project;
            }
            String z = "";
        }
        if (showMessage)
        {
            ToastNotification.show("Проект", "Отсутствует активный проект");
        }
        return null;
    }

    public static String readTextFromFile(File commandFile) throws IOException
    {
        String text;
        text = Files.readString(commandFile.toPath());
        if (text.startsWith("\uFEFF")) {
           // Удаляем BOM 
            text = text.substring(1); 
        }
        return text;
    }
 }
