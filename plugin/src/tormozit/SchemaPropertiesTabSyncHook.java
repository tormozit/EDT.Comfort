package tormozit;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.EPartService.PartState;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
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

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Issue 2202: если панели «Свойства» и «Схема» (Outline) стоят в одной группе вкладок,
 * активная вкладка группы переключается вслед за редактором — вручную дёргать панели не
 * нужно.
 *
 * <p>При активации редактора кода (standalone {@link BslXtextEditor} либо страница
 * «Модуль» внутри {@link DtGranularEditor}, см. {@link GetRef#getActiveBslEditor}) группа
 * переключается на «Схема». При активации страницы «Форма» редактора формы
 * ({@link FormEditorPage}) — на «Свойства». Переключение — только если обе панели уже
 * открыты и лежат в одной группе вкладок; иначе трогать нечего.
 *
 * <p>Показ панели — {@code EPartService.showPart(MPart, PartState.VISIBLE)}: делает вкладку
 * видимой в её группе, не забирая ввод у редактора (в отличие от {@code activate}). Сервис
 * получен через {@code getSite().getService(...)}, поэтому метод вызывается через рефлексию
 * по классу самого возвращённого объекта — как и {@link NavigatorReveal#reactivateEditorPart}.
 */
public final class SchemaPropertiesTabSyncHook implements IStartup
{
    private static final String PROPERTY_SHEET_VIEW_ID = "org.eclipse.ui.views.PropertySheet"; //$NON-NLS-1$
    /**
     * {@code IPageLayout.ID_OUTLINE} — id ПРЕДСТАВЛЕНИЯ «Схема». Не путать с FQN самого
     * Java-класса {@code org.eclipse.ui.views.contentoutline.ContentOutline} (см.
     * {@link BslOutlineEventsSupport}, где это именно класс, а не id) — этой строкой
     * {@link IWorkbenchPage#findViewReference} панель не находил (issue 2202, «не работает»).
     */
    private static final String OUTLINE_VIEW_ID = "org.eclipse.ui.views.ContentOutline"; //$NON-NLS-1$

    /** Многостраничные редакторы, на которых уже висит слушатель смены страницы. */
    private static final Set<DtGranularEditor<?>> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(SchemaPropertiesTabSyncHook::install);
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
            @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
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
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookGranularEditor(granular);
            }
            IEditorPart active = page.getActiveEditor();
            if (active != null)
                syncFromActiveEditor(active);
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partActivated(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (part instanceof IEditorPart editor)
                {
                    if (editor instanceof DtGranularEditor<?> granular)
                        hookGranularEditor(granular);
                    syncFromActiveEditor(editor);
                }
            }

            @Override public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (part instanceof DtGranularEditor<?> granular)
                    hookGranularEditor(granular);
            }

            @Override public void partVisible(IWorkbenchPartReference ref)      {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref)       {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)  {}
            @Override public void partHidden(IWorkbenchPartReference ref)       {}
        });
    }

    private static void hookGranularEditor(DtGranularEditor<?> editor)
    {
        if (editor == null || !HOOKED_EDITORS.add(editor))
            return;
        IPageChangedListener listener = event -> syncFromActiveEditor(editor);
        editor.addPageChangedListener(listener);
    }

    /** Определяет режим по активному редактору/странице и, если нужно, переключает группу. */
    private static void syncFromActiveEditor(IEditorPart editor)
    {
        boolean codeActive = GetRef.getActiveBslEditor(editor) != null;
        boolean formDesignActive = !codeActive && editor instanceof FormEditor formEditor
            && formEditor.getActivePageInstance() instanceof FormEditorPage;
        if (!codeActive && !formDesignActive)
            return;
        if (editor.getSite() == null)
            return;
        syncTabGroup(editor.getSite().getPage(), codeActive);
    }

    /**
     * @param preferOutline {@code true} — на верх группы должна выйти «Схема», {@code false} —
     *        «Свойства»
     */
    private static void syncTabGroup(IWorkbenchPage page, boolean preferOutline)
    {
        if (page == null)
            return;
        IViewReference propRef = page.findViewReference(PROPERTY_SHEET_VIEW_ID);
        IViewReference outlineRef = page.findViewReference(OUTLINE_VIEW_ID);
        // getView(true): панель может быть открытой фоновой вкладкой группы, ещё не
        // материализованной — getView(false) в этом случае молча даёт null (issue 2202).
        IViewPart propView = propRef != null ? propRef.getView(true) : null;
        IViewPart outlineView = outlineRef != null ? outlineRef.getView(true) : null;
        if (propView == null || outlineView == null)
            return; // одна из панелей не открыта — синхронизировать нечего

        MPart propPart = mpartOf(propView);
        MPart outlinePart = mpartOf(outlineView);
        if (propPart == null || outlinePart == null)
            return;

        // MPart.getParent() у этих панелей не заполнен (compatibility-обёртка e3-view над
        // CompatibilityView) — группу определяем по общему CTabFolder-предку виджетов,
        // а не по модели.
        CTabFolder propFolder = folderOf(propPart);
        CTabFolder outlineFolder = folderOf(outlinePart);
        if (propFolder == null || propFolder != outlineFolder)
            return; // не в одной группе вкладок

        MPart desired = preferOutline ? outlinePart : propPart;
        showPartVisible(preferOutline ? outlineView : propView, desired);
    }

    /** Ближайший предок-{@link CTabFolder} виджета панели; {@code null} — не найден. */
    private static CTabFolder folderOf(MPart part)
    {
        Object widget = part.getWidget();
        if (!(widget instanceof Control control) || control.isDisposed())
            return null;
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c instanceof CTabFolder folder)
                return folder;
        }
        return null;
    }

    private static MPart mpartOf(IWorkbenchPart part)
    {
        try
        {
            if (part == null || part.getSite() == null)
                return null;
            Object raw = part.getSite().getService(MPart.class);
            return raw instanceof MPart mpart ? mpart : null;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    /**
     * Делает {@code desired} видимой вкладкой её группы без передачи ей фокуса
     * ({@code PartState.VISIBLE}) — активный редактор ввод не теряет.
     */
    private static void showPartVisible(IWorkbenchPart anyPartInWindow, MPart desired)
    {
        try
        {
            Object partService = anyPartInWindow.getSite().getService(EPartService.class);
            if (partService == null)
                return;
            Method m = partService.getClass().getMethod("showPart", MPart.class, PartState.class); //$NON-NLS-1$
            m.invoke(partService, desired, PartState.VISIBLE);
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            // Переключение вкладки — необязательное улучшение UX.
        }
    }
}
