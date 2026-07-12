package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * В панели «Свойства» (LWT/AEF-рендеринг — та же технология, что и в мастерах «Новый ...», см.
 * {@link NewAttributeNameIdentifierHook}) при уходе фокуса из строки «Имя» автоматически
 * преобразует введённое представление в валидный идентификатор — см.
 * {@link Global#identifierFromRepresentation}.
 *
 * <p>Независим от остальной инфраструктуры панели «Свойства» ({@link PropertySheetHook} и др.) —
 * та отключена автором ({@code if (true) return; // не осилил}) как чрезмерно сложная попытка
 * canvas-hit-test подсветки по клику мыши.
 *
 * <p>Редактор строки «Имя» находится через {@code renderer.viewModelToView} —
 * это {@link java.util.LinkedHashMap}, сохраняющий порядок вставки строк палитры: подпись
 * ({@code LabelViewModel} с текстом «Имя», рендерится как нередактируемый
 * {@code LightLabel} — сам по себе не годится) и её редактор (в наблюдаемых случаях —
 * {@code ActionBarViewModel}/{@code DtActionBarView}, аналогично мастеру, см.
 * {@link NewAttributeNameIdentifierHook}) идут в карте строго подряд: редактор — это запись,
 * непосредственно следующая за подписью в порядке итерации (подтверждено логом debug-сборки).
 */
public class PropertyNameIdentifierHook implements IStartup
{
    private static final String NAME_PROPERTY_LABEL = "Имя"; //$NON-NLS-1$
    private static final String LOG_TAG = "PropertyNameIdentifier"; //$NON-NLS-1$

    /** Нативные light-контролы, на которых уже висит наш слушатель — не переустанавливать. */
    private static final Set<Object> ATTACHED =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(PropertyNameIdentifierHook::install);
    }

    private static void install()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
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
                if (isPropertySheetView(view))
                    scheduleAttach(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)        { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)       { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)     { tryFromRef(ref); }
            @Override public void partInputChanged(IWorkbenchPartReference ref)  { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref)  {}
            @Override public void partClosed(IWorkbenchPartReference ref)        {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)   {}
            @Override public void partHidden(IWorkbenchPartReference ref)        {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isPropertySheetView(part))
                    scheduleAttach((IViewPart) part, 0);
            }
        });
    }

    private static boolean isPropertySheetView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.PROPERTIES_SHEET_ID.equals(id)
            || "org.eclipse.ui.views.PropertySheet".equals(id); //$NON-NLS-1$
    }

    private static void scheduleAttach(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            if (tryAttach(view))
                return;
            if (attempt < 100) // строки палитры свойств подгружаются лениво — окно ожидания ~10с
                scheduleAttach(view, attempt + 1);
        });
    }

    private static boolean tryAttach(IViewPart view)
    {
        Object page = resolvePropertySheetPage(view);
        if (page == null)
            return false;

        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (scene == null)
            return false;

        Object nameEditorView = findValueViewAfterLabel(scene, NAME_PROPERTY_LABEL);
        if (nameEditorView == null)
            return false;

        Object nativeControl = Global.invoke(nameEditorView, "getNativeControl"); //$NON-NLS-1$
        if (nativeControl == null)
            return false;

        if (ATTACHED.contains(nativeControl))
            return true;

        if (!Global.installLightControlFocusOutListener(nativeControl, () -> onNameFocusLost(nativeControl)))
            return false;

        ATTACHED.add(nativeControl);
        Global.log(LOG_TAG, "строка «Имя» подключена в панели «Свойства»"); //$NON-NLS-1$
        return true;
    }

    private static Object resolvePropertySheetPage(IViewPart view)
    {
        Object page = Global.invoke(view, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;
        Object pageBook = Global.invoke(view, "getPageBook"); //$NON-NLS-1$
        page = Global.invoke(pageBook, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;
        return null;
    }

    private static boolean isPropertySheetPage(Object page)
    {
        return page != null && page.getClass().getName().contains("PropertySheetPage"); //$NON-NLS-1$
    }

    /**
     * Ищет в {@code renderer.viewModelToView} (порядок-сохраняющая {@link java.util.LinkedHashMap})
     * {@code LabelViewModel} с текстом {@code displayName} и возвращает view **следующей** записи —
     * это и есть view редактора значения (см. javadoc класса).
     */
    private static Object findValueViewAfterLabel(Object scene, String displayName)
    {
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof java.util.Map<?, ?> map))
            return null;

        boolean foundLabel = false;
        for (java.util.Map.Entry<?, ?> entry : map.entrySet())
        {
            if (foundLabel)
                return entry.getValue();

            Object key = entry.getKey();
            if (key != null && key.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
            {
                Object text = Global.invoke(key, "getText"); //$NON-NLS-1$
                if (text == null)
                    text = Global.getField(key, "text"); //$NON-NLS-1$
                if (displayName.equals(text))
                    foundLabel = true;
            }
        }
        return null;
    }

    private static void onNameFocusLost(Object nativeControl)
    {
        try
        {
            // Как и в мастере (NewAttributeNameIdentifierHook): если нативный контрол — обёртка
            // вроде LightEditorBar, сам текст хранит getContent(); если это уже LightText —
            // getContent() не найдётся, и используется сам nativeControl.
            Object content = Global.invoke(nativeControl, "getContent"); //$NON-NLS-1$
            Object textControl = content != null ? content : nativeControl;

            Object textObj = Global.invoke(textControl, "getText"); //$NON-NLS-1$
            if (!(textObj instanceof String text) || text.isEmpty())
                return;

            String identifier = Global.identifierFromRepresentation(text, "_", "", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (identifier.equals(text))
                return;

            Global.invokeVoid(textControl, "setText", identifier); //$NON-NLS-1$
            Global.log(LOG_TAG, "«" + text + "» → «" + identifier + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (Exception e)
        {
            Global.logError(LOG_TAG, "onNameFocusLost", e); //$NON-NLS-1$
        }
    }
}
