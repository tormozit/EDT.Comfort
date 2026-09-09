package tormozit;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Отслеживает «активное место» для {@link RecentPlaces}.
 *
 * <p>BSL: метод фиксируется, когда каретка <b>3 секунды находится в одном методе</b>
 * сфокусированного модуля. Движение каретки внутри того же метода таймер не сбрасывает.
 * Смена метода или потеря фокуса модулем — отмена / новый отсчёт.
 * Каретка вне метода 3 секунды при фокусе модуля — владелец модуля.
 *
 * <p>Не-BSL (объект МД): по-прежнему 3 секунды после FocusIn/Activate,
 * пока активный редактор не BSL.
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

    private static final String INSTALLED_KEY = "tormozit.recentPlacesTrackerInstalled"; //$NON-NLS-1$
    private static final String CARET_WIRED_KEY = "tormozit.recentPlacesCaretWired"; //$NON-NLS-1$
    private static final int MAX_WIRE_ATTEMPTS = 50;

    private static Display display;
    private static Runnable bslPending;
    private static String bslPendingKey;
    private static BslXtextEditor bslPendingEditor;
    private static Runnable mdPending;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display d)
    {
        if (d == null || d.isDisposed()) return;
        if (Boolean.TRUE.equals(d.getData(INSTALLED_KEY)))
            return;
        d.setData(INSTALLED_KEY, Boolean.TRUE);
        display = d;

        Listener uiListener = new Listener()
        {
            @Override
            public void handleEvent(Event event)
            {
                onUiFocusOrActivate();
            }
        };
        d.addFilter(SWT.FocusIn, uiListener);
        d.addFilter(SWT.Activate, uiListener);

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);

        onUiFocusOrActivate();
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            hookEditorIfNeeded(page.getActiveEditor());
            for (IEditorReference ref : page.getEditorReferences())
                hookEditorIfNeeded(ref.getEditor(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (ref instanceof IEditorReference edRef)
                    hookEditorIfNeeded(edRef.getEditor(false));
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                if (ref instanceof IEditorReference edRef)
                    hookEditorIfNeeded(edRef.getEditor(false));
                onUiFocusOrActivate();
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor == null)
            return;
        BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
        if (bsl != null)
            ensureCaretWired(bsl, 0);
    }

    private static void onUiFocusOrActivate()
    {
        BslXtextEditor bsl = getActiveBslEditorFromWorkbench();
        if (isBslFocused(bsl))
        {
            cancelMd();
            ensureCaretWired(bsl, 0);
            armBslDwell(bsl);
            return;
        }
        cancelBsl();
        armMd();
    }

    private static void ensureCaretWired(BslXtextEditor editor, int attempt)
    {
        if (display == null || display.isDisposed() || editor == null)
            return;
        StyledText st = textWidget(editor);
        if (st == null || st.isDisposed())
        {
            if (attempt >= MAX_WIRE_ATTEMPTS)
                return;
            display.asyncExec(() -> ensureCaretWired(editor, attempt + 1));
            return;
        }
        if (Boolean.TRUE.equals(st.getData(CARET_WIRED_KEY)))
            return;
        st.setData(CARET_WIRED_KEY, Boolean.TRUE);
        st.addListener(ST.CaretMoved, e -> armBslDwell(editor));
        st.addListener(SWT.FocusIn, e -> armBslDwell(editor));
        st.addListener(SWT.FocusOut, e ->
        {
            if (bslPendingEditor == editor)
                cancelBsl();
        });
        if (st.isFocusControl())
            armBslDwell(editor);
    }

    private static void armBslDwell(BslXtextEditor editor)
    {
        if (display == null || display.isDisposed())
            return;
        if (!isBslFocused(editor))
        {
            if (bslPendingEditor == editor)
                cancelBsl();
            return;
        }
        String key = bslPlaceKey(editor);
        if (key == null)
        {
            if (bslPendingEditor == editor)
                cancelBsl();
            return;
        }
        if (key.equals(bslPendingKey) && bslPendingEditor == editor)
            return;
        cancelBsl();
        cancelMd();
        bslPendingKey = key;
        bslPendingEditor = editor;
        bslPending = () ->
        {
            bslPending = null;
            fireBsl(editor, key);
        };
        display.timerExec(DWELL_MS, bslPending);
    }

    private static void fireBsl(BslXtextEditor editor, String expectedKey)
    {
        if (!isBslFocused(editor))
        {
            bslPendingKey = null;
            bslPendingEditor = null;
            return;
        }
        String now = bslPlaceKey(editor);
        if (!expectedKey.equals(now))
        {
            bslPendingKey = null;
            bslPendingEditor = null;
            return;
        }
        recordBslPlace(editor);
    }

    private static void cancelBsl()
    {
        if (bslPending != null && display != null && !display.isDisposed())
            display.timerExec(-1, bslPending);
        bslPending = null;
        bslPendingKey = null;
        bslPendingEditor = null;
    }

    private static void armMd()
    {
        if (display == null || display.isDisposed())
            return;
        if (mdPending != null)
        {
            display.timerExec(-1, mdPending);
            mdPending = null;
        }
        mdPending = () ->
        {
            mdPending = null;
            recordMdPlace();
        };
        display.timerExec(DWELL_MS, mdPending);
    }

    private static void cancelMd()
    {
        if (mdPending == null)
            return;
        if (display != null && !display.isDisposed())
            display.timerExec(-1, mdPending);
        mdPending = null;
    }

    private static boolean isBslFocused(BslXtextEditor editor)
    {
        if (editor == null)
            return false;
        StyledText st = textWidget(editor);
        return st != null && !st.isDisposed() && st.isFocusControl();
    }

    private static StyledText textWidget(BslXtextEditor editor)
    {
        if (editor == null)
            return null;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return null;
        return viewer.getTextWidget();
    }

    /** Ключ текущего места BSL: {@code "Модуль: Метод"} или владелец модуля. */
    private static String bslPlaceKey(BslXtextEditor editor)
    {
        org.eclipse.ui.IEditorInput input = editor.getEditorInput();
        if (input == null)
            return null;
        org.eclipse.core.resources.IFile file =
            input.getAdapter(org.eclipse.core.resources.IFile.class);
        if (file == null)
            return null;
        GetRef.ModuleRef moduleRef =
            GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null)
            return null;
        String methodName = resolveMethodNameAtCaret(editor);
        if (methodName != null)
            return moduleRef.modulePath + ": " + methodName; //$NON-NLS-1$
        return stripModuleTypeSuffix(moduleRef.modulePath);
    }

    // =========================================================================
    // Определение текущего места
    // =========================================================================

    private static void recordMdPlace()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        IEditorPart editor = page.getActiveEditor();
        if (editor != null && GetRef.getActiveBslEditor(editor) != null)
            return;
        String ref = GetRef.getRefFromPart(page.getActivePart());
        if (ref == null || ref.isBlank())
            return;
        String ownName = lastSegment(ref);
        String projectName = resolveProjectName(page, null);
        if (RecentPlaces.getInstance().add(ref, ref, ref, ownName, projectName))
            Global.log("RecentPlaces add (MD): " + ref); //$NON-NLS-1$
    }

    // =========================================================================
    // Случай 1: BSL-редактор
    // =========================================================================

    /**
     * Схема модуля (Quick Outline / Content Outline): добавить метод в «Последние места»
     * при подтверждении выбора (Enter или двойной клик), не при смене текущей строки.
     * Группирующие узлы дерева пропускаются.
     */
    public static void recordOutlineSelection(TreeViewer viewer, Object element, ILabelProvider labels)
    {
        if (viewer == null || element == null)
            return;
        Object cp = viewer.getContentProvider();
        if (cp instanceof ITreeContentProvider
                && ((ITreeContentProvider) cp).hasChildren(element))
            return;

        String methodName = resolveOutlineMethodName(element, labels);
        if (methodName == null || methodName.isEmpty())
            return;

        BslXtextEditor editor = getActiveBslEditorFromWorkbench();
        if (editor == null)
            return;

        recordOutlineMethod(editor, methodName);
    }

    private static BslXtextEditor getActiveBslEditorFromWorkbench()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        IEditorPart editor = page.getActiveEditor();
        return editor != null ? GetRef.getActiveBslEditor(editor) : null;
    }

    private static String resolveOutlineMethodName(Object element, ILabelProvider labels)
    {
        for (String method : new String[] { "getName", "getMethodName", "getSimpleName", "getLabel" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object value = Global.invoke(element, method);
            if (value instanceof String && isMethodIdentifier((String) value))
                return (String) value;
        }
        String label = labels != null
            ? SmartTreeElementLabels.resolve(element, labels instanceof org.eclipse.jface.viewers.IBaseLabelProvider
                ? (org.eclipse.jface.viewers.IBaseLabelProvider) labels : null)
            : SmartTreeElementLabels.resolve(element, null);
        return extractMethodNameFromLabel(label);
    }

    private static boolean isMethodIdentifier(String name)
    {
        if (name == null || name.isEmpty())
            return false;
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
            return false;
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
                return false;
        }
        return true;
    }

    private static String extractMethodNameFromLabel(String label)
    {
        if (label == null || label.isEmpty())
            return null;
        String text = label.trim();
        int paren = text.indexOf('(');
        if (paren > 0)
            text = text.substring(0, paren).trim();
        int space = text.lastIndexOf(' ');
        if (space >= 0 && space + 1 < text.length())
            text = text.substring(space + 1).trim();
        return isMethodIdentifier(text) ? text : null;
    }

    private static void recordOutlineMethod(BslXtextEditor bslEditor, String methodName)
    {
        org.eclipse.ui.IEditorInput input = bslEditor.getEditorInput();
        if (input == null)
            return;
        org.eclipse.core.resources.IFile file =
            input.getAdapter(org.eclipse.core.resources.IFile.class);
        if (file == null)
            return;

        GetRef.ModuleRef moduleRef =
            GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null)
            return;

        String modulePath = moduleRef.modulePath;
        String navRef = GetRef.buildExtendedRefForMethod(bslEditor, methodName);
        String key = modulePath + ": " + methodName; //$NON-NLS-1$
        String projectName = resolveProjectName(null, file);
        if (RecentPlaces.getInstance().add(key, navRef != null ? navRef : key, key, methodName, projectName))
            Global.log("RecentPlaces add (Outline): " + key); //$NON-NLS-1$
    }

    private static void recordBslPlace(BslXtextEditor bslEditor)
    {
        org.eclipse.ui.IEditorInput input = bslEditor.getEditorInput();
        if (input == null)
            return;
        org.eclipse.core.resources.IFile file =
            input.getAdapter(org.eclipse.core.resources.IFile.class);
        if (file == null)
            return;

        GetRef.ModuleRef moduleRef =
            GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null)
            return;

        // modulePath, например "Справочник.Валюты.МодульОбъекта"
        String modulePath = moduleRef.modulePath;

        // navRef — расширенная ссылка с позицией строки (для навигации)
        String navRef = GetRef.buildExtendedRef(bslEditor, false);

        // Имя метода — по документу (не разбором navRef: тот ломался на "_" и на лишнем ':').
        String methodName = resolveMethodNameAtCaret(bslEditor);
        if (methodName == null)
            methodName = extractMethodName(navRef);

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

        if (RecentPlaces.getInstance().add(key, navRef != null ? navRef : key,
                                        displayName, ownName, resolveProjectName(null, file)))
            Global.log("RecentPlaces add (BSL): " + displayName); //$NON-NLS-1$
    }

    /**
     * Имя объемлющего метода по модельной каретке документа (не виджетный offset).
     */
    private static String resolveMethodNameAtCaret(BslXtextEditor bslEditor)
    {
        ISourceViewer viewer = bslEditor.getInternalSourceViewer();
        if (viewer == null)
            return null;
        IDocument doc = viewer.getDocument();
        if (doc == null || viewer.getSelectionProvider() == null)
            return null;
        Object selObj = viewer.getSelectionProvider().getSelection();
        if (!(selObj instanceof ITextSelection textSel))
            return null;
        return GetRef.findEnclosingMethodName(doc, textSel.getStartLine() + 1);
    }

    private static String resolveProjectName(IWorkbenchPage page,
            org.eclipse.core.resources.IFile file)
    {
        if (file != null)
        {
            org.eclipse.core.resources.IProject p = file.getProject();
            if (p != null)
                return p.getName();
        }
        org.eclipse.core.resources.IProject p = Global.getActiveProject(page, false);
        return p != null ? p.getName() : ""; //$NON-NLS-1$
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
        return isMethodIdentifier(name) ? name : null;
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
