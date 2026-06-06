package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Отслеживает «активное место»: если редактор (метод BSL или объект МД)
 * держит фокус ввода 3 и более секунд — место фиксируется в {@link RecentPlaces}.
 *
 * <p><b>Ключ дедупликации</b> (стабильный, без номера строки):
 * <ul>
 *   <li>Каретка внутри метода → {@code "МодульПолный.ИмяМетода"},
 *       например {@code "Справочник.Валюты.МодульОбъекта.Записать"}.</li>
 *   <li>Каретка вне метода  → только объект-владелец модуля (без суффикса типа),
 *       например {@code "ОбщийМодуль.Общий1"} или {@code "Справочник.Валюты"}.</li>
 *   <li>Объект МД (не BSL)  → полная ссылка объекта.</li>
 * </ul>
 *
 * <p><b>navRef</b> (для навигации) — расширенная ссылка с номером строки,
 * обновляется при каждом посещении.
 */
public class RecentPlacesTracker implements IStartup
{
    /** Задержка в мс, после которой место считается «посещённым». */
    private static final int DWELL_MS = 3000;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed()) return;

        final Runnable[] pending = { null };

        Listener focusListener = new Listener()
        {
            @Override
            public void handleEvent(Event event)
            {
                if (pending[0] != null)
                {
                    display.timerExec(-1, pending[0]);
                    pending[0] = null;
                }
                pending[0] = () -> { pending[0] = null; recordCurrentPlace(); };
                display.timerExec(DWELL_MS, pending[0]);
            }
        };

        display.addFilter(SWT.FocusIn,  focusListener);
        display.addFilter(SWT.Activate, focusListener);
    }

    // =========================================================================
    // Определение текущего места
    // =========================================================================

    private static void recordCurrentPlace()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) return;
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return;
        IEditorPart editor = page.getActiveEditor();
        BslXtextEditor bslEditor = null;
        if (editor != null) 
            bslEditor = GetRef.getActiveBslEditor(editor);
        if (bslEditor != null)
        {
            recordBslPlace(bslEditor);
        }
        else
        {
            String ref = GetRef.getRefFromPart(page.getActivePart());
            if (ref == null || ref.isBlank()) return;
            String ownName = lastSegment(ref);
            RecentPlaces.getInstance().add(ref, ref, ref, ownName);
            Global.log("RecentPlaces add (MD): " + ref); //$NON-NLS-1$
        }
    }

    // =========================================================================
    // Случай 1: BSL-редактор
    // =========================================================================

    private static void recordBslPlace(BslXtextEditor bslEditor)
    {
        org.eclipse.ui.IEditorInput input = bslEditor.getEditorInput();
        if (input == null) return;
        org.eclipse.core.resources.IFile file =
            input.getAdapter(org.eclipse.core.resources.IFile.class);
        if (file == null) return;

        GetRef.ModuleRef moduleRef =
            GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null) return;

        // modulePath, например "Справочник.Валюты.МодульОбъекта"
        String modulePath = moduleRef.modulePath;

        // navRef — расширенная ссылка с позицией строки (для навигации)
        String navRef = GetRef.buildExtendedRef(bslEditor, false);

        // Имя метода извлекаем из navRef
        String methodName = (navRef != null) ? extractMethodName(navRef) : null;

        final String key;
        final String displayName;
        final String ownName;

        if (methodName != null)
        {
            // Каретка внутри метода
            // Ключ: "МодульПолный.ИмяМетода"
            key         = modulePath + ": " + methodName; //$NON-NLS-1$
            displayName = modulePath + ": " + methodName; //$NON-NLS-1$
            ownName     = methodName;
        }
        else
        {
            // Каретка вне метода — запоминаем объект-владелец модуля
            // Убираем суффикс типа модуля: "Справочник.Валюты.МодульОбъекта" → "Справочник.Валюты"
            String ownerRef = stripModuleTypeSuffix(modulePath);
            key         = ownerRef;
            displayName = ownerRef;
            ownName     = lastSegment(ownerRef);
            // navRef для перехода — просто путь модуля без строки
            navRef      = moduleRef.toRefPrefix();
        }

        RecentPlaces.getInstance().add(key, navRef != null ? navRef : key,
                                        displayName, ownName);
        Global.log("RecentPlaces add (BSL): " + displayName); //$NON-NLS-1$
    }

    /**
     * Извлекает имя метода из расширенной ссылки вида
     * {@code {МодульПолный(42,0:ИмяМетода,5)}}.
     * Возвращает {@code null} если ссылка не содержит имени метода
     * (каретка вне метода).
     */
    private static String extractMethodName(String ref)
    {
        if (ref == null) return null;
        // Шаблон: ...(строка,колонка:ИмяМетода,смещение)...
        // Ищем двоеточие после открывающей скобки
        int brace = ref.indexOf('{');
        if (brace < 0) return null;
        int colon = ref.indexOf(':', brace);
        if (colon < 0) return null;
        // Убеждаемся что перед двоеточием стоит цифра (это колонка, не разделитель расширения)
        if (colon == 0 || !Character.isDigit(ref.charAt(colon - 1))) return null;
        int end = ref.indexOf(',', colon + 1);
        if (end < 0) end = ref.indexOf(')', colon + 1);
        if (end < 0) end = ref.indexOf('}', colon + 1);
        if (end < 0) return null;
        String name = ref.substring(colon + 1, end).trim();
        // Имя метода — идентификатор: буквы/цифры/подчёркивание
        return name.isEmpty() || !Character.isLetter(name.charAt(0)) ? null : name;
    }

    /**
     * Убирает суффикс типа модуля из пути:
     * {@code "Справочник.Валюты.МодульОбъекта"} → {@code "Справочник.Валюты"},
     * {@code "ОбщийМодуль.Общий1.Модуль"}       → {@code "ОбщийМодуль.Общий1"},
     * {@code "ОбщийМодуль.Общий1"}              → {@code "ОбщийМодуль.Общий1"} (без изменений).
     */
    private static final java.util.Set<String> MODULE_SUFFIXES =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "МодульОбъекта", "МодульМенеджера", "МодульНабораЗаписей", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Модуль", "Форма" //$NON-NLS-1$ //$NON-NLS-2$
        ));

    private static String stripModuleTypeSuffix(String modulePath)
    {
        if (modulePath == null) return ""; //$NON-NLS-1$
        int dot = modulePath.lastIndexOf('.');
        if (dot < 0) return modulePath;
        String suffix = modulePath.substring(dot + 1);
        return MODULE_SUFFIXES.contains(suffix)
            ? modulePath.substring(0, dot)
            : modulePath;
    }

    // =========================================================================

    private static String lastSegment(String path)
    {
        if (path == null || path.isBlank()) return path;
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
