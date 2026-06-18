package tormozit;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
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

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
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

    @SuppressWarnings("unchecked")
    public static <T> T getOsgiService(Class<T> clazz)
    {
        BundleContext ctx = ourContext();
        if (ctx == null || clazz == null)
            return null;
        org.osgi.framework.ServiceReference<T> ref = ctx.getServiceReference(clazz);
        return ref != null ? ctx.getService(ref) : null;
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

    /**
     * Активный проект с учётом {@linkplain org.eclipse.ui.handlers.HandlerUtil#getActivePart
     * активной части}: при фокусе в навигаторе — из его выделения, при фокусе в редакторе —
     * из редактора; иначе — {@link #getActiveProject(IWorkbenchPage, boolean)}.
     */
    public static IProject getActiveProject(IWorkbenchPart part, boolean showMessage)
    {
        IWorkbenchPage page = part != null ? part.getSite().getPage() : getActivePage();
        if (part != null && page != null)
        {
            IViewPart navigator = page.findView(NAVIGATOR_VIEW_ID);
            if (part == navigator && navigator instanceof CommonNavigator)
            {
                IProject navProject = getProjectFromNavigator((CommonNavigator) navigator);
                if (navProject != null)
                    return navProject;
                if (showMessage)
                    ToastNotification.show("Проект", "Отсутствует активный проект"); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            if (part instanceof IEditorPart)
            {
                IEditorPart editor = (IEditorPart) part;
                IFile file = editorToFile(editor);
                if (file != null)
                    return file.getProject();
                if (COMPARE_EDITOR_ID.equals(part.getSite().getId()))
                {
                    IProject p = CompareConfigOpenObjectHandler.getProjectFromEditor(editor);
                    if (p != null)
                        return p;
                }
            }
        }
        return getActiveProject(page, showMessage);
    }

    /** Навигатор EDT или Project Explorer. */
    public static boolean isNavigatorPart(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart))
            return false;
        IViewPart view = (IViewPart) part;
        if (view.getViewSite() == null)
            return false;
        String id = view.getViewSite().getId();
        return NAVIGATOR_VIEW_ID.equals(id)
            || "org.eclipse.ui.navigator.ProjectExplorer".equals(id); //$NON-NLS-1$
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

    /**
     * {@link IDtProject} для BSL-редактора: поле редактора / context, затем IFile → workspace.
     */
    public static IDtProject getDtProjectFromBslEditor(BslXtextEditor editor)
    {
        if (editor == null)
            return null;

        IDtProject fromField = getProjectFromEditor(editor);
        if (fromField != null)
            return fromField;

        try
        {
            IEditorInput input = editor.getEditorInput();
            if (input == null)
                return null;

            IFile file = input.getAdapter(IFile.class);
            if (file == null)
                return null;

            return getDtProjectFromWorkspaceProject(file.getProject());
        }
        catch (Exception e)
        {
            log("Global.getDtProjectFromBslEditor: " + e); //$NON-NLS-1$
        }
        return null;
    }

    /** {@link IDtProject} по {@link IProject} воркспейса. */
    public static IDtProject getDtProjectFromWorkspaceProject(IProject iProject)
    {
        if (iProject == null)
            return null;
        try
        {
            IV8ProjectManager projectManager =
                (IV8ProjectManager) getServiceByClass(IV8ProjectManager.class);
            if (projectManager == null)
                return null;

            Object result = invoke(projectManager, "getDtProject", iProject); //$NON-NLS-1$
            if (result instanceof IDtProject)
                return (IDtProject) result;

            Object allProjects = call(projectManager, "getProjects"); //$NON-NLS-1$
            if (allProjects instanceof Iterable<?>)
            {
                for (Object proj : (Iterable<?>) allProjects)
                {
                    IDtProject candidate = toDtProject(proj);
                    if (candidate != null && iProject.equals(candidate.getWorkspaceProject()))
                        return candidate;
                }
            }
        }
        catch (Exception e)
        {
            log("Global.getDtProjectFromWorkspaceProject: " + e); //$NON-NLS-1$
        }
        return null;
    }

    private static IDtProject toDtProject(Object proj)
    {
        if (proj instanceof IDtProject)
            return (IDtProject) proj;
        if (proj instanceof IV8Project)
        {
            Object dt = call(proj, "getDtProject"); //$NON-NLS-1$
            if (dt instanceof IDtProject)
                return (IDtProject) dt;
        }
        return null;
    }

    // =========================================================================
    // Логирование (журнал «Журнал Комфорт», {@link GlobalLogView})
    // =========================================================================

    /** Включение: Параметры → Комфорт → «Общее логирование». */
    public static boolean isLogEnabled()
    {
        return ComfortSettings.isDebugLogEnabled();
    }

    public static void log(String message)
    {
        log(null, message);
    }

    public static void log(String tag, String message)
    {
        if (!isLogEnabled() || message == null)
            return;
        GlobalLog.append(formatLogTag(tag) + message);
    }

    public static void logError(String tag, String message, Throwable error)
    {
        if (!isLogEnabled())
            return;
        String text = message != null ? message : ""; //$NON-NLS-1$
        Throwable root = unwrapLogCause(error);
        if (root != null)
        {
            String detail = root.getClass().getSimpleName();
            if (root.getMessage() != null && !root.getMessage().isBlank())
                detail += ": " + root.getMessage(); //$NON-NLS-1$
            text = text.isBlank() ? detail : text + ": " + detail; //$NON-NLS-1$
        }
        GlobalLog.append(formatLogTag(tag) + "ERROR " + text); //$NON-NLS-1$
        if (root != null)
            appendLogStackTrace(root);
    }

    private static void appendLogStackTrace(Throwable error)
    {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\n")) //$NON-NLS-1$
        {
            if (line.isBlank())
                continue;
            GlobalLog.append("    " + line); //$NON-NLS-1$
        }
    }

    private static String formatLogTag(String tag)
    {
        if (tag == null || tag.isBlank())
            return ""; //$NON-NLS-1$
        return "[" + tag + "] "; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Throwable unwrapLogCause(Throwable error)
    {
        if (error == null)
            return null;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root)
            root = root.getCause();
        return root;
    }

    // =========================================================================
    // Вспомогательные
    // =========================================================================

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
        return getProjectFromNavigator((CommonNavigator) getViewById(NAVIGATOR_VIEW_ID));
    }

    private static IProject getProjectFromNavigator(CommonNavigator nav)
    {
        try
        {
            if (nav == null)
                return null;
            TreeSelection sel = (TreeSelection) nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty())
                return null;
            return (IProject) sel.getPaths()[0].getSegment(0);
        }
        catch (Exception ignored)
        {
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

    /** Текст в LF и границы выделения в координатах этой строки (для COM EDT→ИР). */
    public static record LfTextSlice(String text, int start, int end) {}

    /**
     * Смещение в строке с любыми EOL → смещение в LF-нормализованной версии той же строки.
     * {@code offset} — индекс символа в {@code text} (0…length).
     */
    public static int remapOffsetToLf(String text, int offset)
    {
        if (text == null || offset <= 0)
            return Math.max(0, offset);
        int lfOffset = 0;
        int i = 0;
        int capped = Math.min(offset, text.length());
        while (i < capped)
        {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n')
                i += 2;
            else
                i++;
            lfOffset++;
        }
        return lfOffset;
    }

    /**
     * LF-offset → offset в исходной строке {@code rawText} (inverse к {@link #remapOffsetToLf}).
     * Для {@code doc.replace} после {@link #toLfWithSelection} при sync ИР→EDT.
     */
    public static int remapOffsetFromLf(String rawText, int lfOffset)
    {
        if (rawText == null || lfOffset <= 0)
            return Math.max(0, lfOffset);
        int lfPos = 0;
        int rawPos = 0;
        while (lfPos < lfOffset && rawPos < rawText.length())
        {
            char c = rawText.charAt(rawPos);
            if (c == '\r' && rawPos + 1 < rawText.length() && rawText.charAt(rawPos + 1) == '\n')
                rawPos += 2;
            else
                rawPos++;
            lfPos++;
        }
        return rawPos;
    }

    /** Нормализует текст к LF и пересчитывает границы выделения относительно исходной строки. */
    public static LfTextSlice toLfWithSelection(String raw, int start, int end)
    {
        String source = raw != null ? raw : ""; //$NON-NLS-1$
        int rawStart = Math.max(0, start);
        int rawEnd = Math.max(rawStart, end);
        String lfText = normalizeLineSeparators(source);
        return new LfTextSlice(
            lfText,
            remapOffsetToLf(source, rawStart),
            remapOffsetToLf(source, rawEnd));
    }

    /** Число пар {@code \\r\\n} в строке (для диагностики синхронизации с ИР). */
    public static int countCrlf(String text)
    {
        if (text == null || text.length() < 2)
            return 0;
        int count = 0;
        for (int i = 0; i < text.length() - 1; i++)
        {
            if (text.charAt(i) == '\r' && text.charAt(i + 1) == '\n')
                count++;
        }
        return count;
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

    public static String pluginSignForTooltip()
    {
        return " (Комфорт)";
    }

    /** Заголовок кастомного окна/диалога плагина (не дублирует суффикс). */
    public static String withPluginWindowTitle(String base)
    {
        return withPluginWindowTitle(base, null);
    }

    /**
     * Заголовок с суффиксом плагина у {@code basePrefix}, далее — {@code titleSuffix}.
     * Пример: {@code ("Коллекция", "Массив")} → «Коллекция (Комфорт) Массив».
     */
    public static String withPluginWindowTitle(String basePrefix, String titleSuffix)
    {
        if (basePrefix == null || basePrefix.isBlank())
            return basePrefix;
        String sign = pluginSignForTooltip();
        String prefix = basePrefix.trim();
        if (prefix.endsWith(sign))
        {
            if (titleSuffix == null || titleSuffix.isBlank())
                return prefix;
            return prefix + ' ' + titleSuffix.trim();
        }
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(sign);
        if (titleSuffix != null && !titleSuffix.isBlank())
            sb.append(' ').append(titleSuffix.trim());
        return sb.toString();
    }
}
