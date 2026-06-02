package tormozit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.moxel.content.impl.TablePropertiesImpl;
import com._1c.g5.v8.dt.moxel.sheet.CellsSelection;
import com._1c.g5.v8.dt.moxel.sheet.Selection;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.sheet.TableSelection;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelControl;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditor;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelViewer;
import com._1c.g5.v8.dt.ui.IMultiSelection;
import com._1c.g5.v8.dt.ui.MultiSelection;


/**
 * Добавляет кнопку «Редактор ИР» в редактор табличного документа (макет .xmxl/.mxl).
 * Логика намеренно сделана «по месту»: берём файл из embedded-редактора и открываем его в ИР.
 */
public class MoxelEditorHook implements IStartup
{
    private static final String EDITOR_ID = "com._1c.g5.v8.dt.md.ui.editor.commonTemplate"; //$NON-NLS-1$
    private static final String MENU_TEXT = "Редактор ИР"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.tabdocMenuHooked"; //$NON-NLS-1$

    private final Map<IWorkbenchWindow, IPartListener2> partListeners = new HashMap<>();
    private final Set<Control> menuDetectHookedControls =
        java.util.Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

//            // Даём EDT время завершить BuildMarkersJob перед обходом уже открытых редакторов.
//            Display.getDefault().timerExec(3000, () ->
//            {
                for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                    hookWindow(w);
//            });
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || partListeners.containsKey(window))
            return;

        IPartListener2 listener = new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!EDITOR_ID.equals(ref.getId())) return;
                Display.getDefault().asyncExec(() ->
                {
                    IEditorPart part = (IEditorPart) ref.getPart(false);
                    if (part instanceof DtGranularEditor<?>)
                        applyPatchToGranularEditor((DtGranularEditor<?>) part);
                });
            }

            @Override public void partActivated(IWorkbenchPartReference r)
            {
                if (!EDITOR_ID.equals(r.getId())) return;
                Display.getDefault().asyncExec(() ->
                {
                    Object p = r.getPart(false);
                    if (p instanceof DtGranularEditor<?>)
                        applyPatchToGranularEditor((DtGranularEditor<?>) p);
                });
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        };

        partListeners.put(window, listener);
        window.getPartService().addPartListener(listener);

        if (window.getActivePage() != null)
            for (IEditorPart ed : window.getActivePage().getEditors())
                if (ed instanceof DtGranularEditor<?> && EDITOR_ID.equals(ed.getSite().getId()))
                    applyPatchToGranularEditor((DtGranularEditor<?>) ed);
    }

    private void applyPatchToGranularEditor(DtGranularEditor<?> editor)
    {
        org.eclipse.ui.forms.editor.IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorEmbeddedEditorPage)
            applyPatchToEditorPage(editor, (DtGranularEditorEmbeddedEditorPage<?>) activePage);
    }

    private void applyPatchToEditorPage(DtGranularEditor<?> granularEditor, DtGranularEditorEmbeddedEditorPage<?> page)
    {
        MoxelEditor moxelEditor = (MoxelEditor) page.getEmbeddedEditor();
        if (moxelEditor==null)
            return;
        Control partControl = page.getPartControl();
        if (!(partControl instanceof Composite))
            return;
        Display.getDefault().asyncExec(() -> attachMenuByMenuDetect((Composite) partControl, granularEditor, moxelEditor));
    }

    /**
     * Устраняет гонки: вместо поиска "контрола с меню" заранее, подписываемся на SWT.MenuDetect.
     * Тогда мы всегда получаем фактический Control, по которому открывают контекстное меню,
     * и можем навесить MenuAdapter на его Menu в момент, когда Menu уже готово.
     */
    private void attachMenuByMenuDetect(Composite container, DtGranularEditor<?> granularEditor, MoxelEditor moxelEditor)
    {
        if (container == null || container.isDisposed())
            return;

        attachMenuDetectRecursively(container, granularEditor, moxelEditor);
    }

    private void attachMenuDetectRecursively(Composite container, DtGranularEditor<?> granularEditor, MoxelEditor moxelEditor)
    {
        for (Control child : container.getChildren())
        {
            hookMenuDetectOnControl(child, granularEditor, moxelEditor);
            if (child instanceof Composite)
                attachMenuDetectRecursively((Composite) child, granularEditor, moxelEditor);
        }
        hookMenuDetectOnControl(container, granularEditor, moxelEditor);
    }

    private void hookMenuDetectOnControl(Control control, DtGranularEditor<?> granularEditor, MoxelEditor moxelEditor)
    {
        if (control == null || control.isDisposed())
            return;
        if (!menuDetectHookedControls.add(control))
            return;

        Listener l = new Listener()
        {
            @Override
            public void handleEvent(Event event)
            {
                if (!(event.widget instanceof Control))
                    return;
                Control w = (Control) event.widget;
                Menu menu = w.getMenu();
                if (menu == null || menu.isDisposed())
                    return;

                if (Boolean.TRUE.equals(menu.getData(HOOK_MARKER)))
                    return;
                menu.setData(HOOK_MARKER, Boolean.TRUE);

                MenuAdapter adapter = buildMenuListener(granularEditor, moxelEditor);
                menu.addMenuListener(adapter);
                w.addDisposeListener(e ->
                {
                    if (!menu.isDisposed())
                        menu.removeMenuListener(adapter);
                });
            }
        };

        control.addListener(SWT.MenuDetect, l);
        control.addDisposeListener(e ->
        {
            try { control.removeListener(SWT.MenuDetect, l); }
            catch (Exception ignored) {}
        });
    }

    private static MenuAdapter buildMenuListener(DtGranularEditor<?> granularEditor, MoxelEditor moxelEditor)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                Menu menu = (Menu) e.widget;
                if (menu == null || menu.isDisposed())
                    return;

                MenuItem item = new MenuItem(menu, SWT.PUSH);
                item.setText(MENU_TEXT);
                item.setToolTipText("Открыть табличный документ в редакторе ИР (Tormozit)"); //$NON-NLS-1$
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        openInIr(granularEditor, moxelEditor);
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() ->
                {
                    for (MenuItem mi : toDispose)
                        if (!mi.isDisposed()) mi.dispose();
                });
            }
        };
    }
    private static String getCurrentRegionName(MoxelEditor moxelEditor)
    {
        MoxelViewer viewer = moxelEditor.getInternalViewer();
        if (viewer == null)
            return null;
        MoxelControl control = viewer.getMoxelControl();
        Selection tailSelection = control.getTailSelection();
        if (!(tailSelection instanceof CellsSelection))
        {
            return null;
        }
        Rectangle r = ((CellsSelection)tailSelection).getNormalizedPosition();
        int r1 = r.y + 1, c1 = r.x + 1;
        int r2 = r.y + r.height + 1, c2 = r.x + r.width + 1;
//        if (r1 == r2 && c1 == c2)
//            return "R" + r1 + "C" + c1; //$NON-NLS-1$
        return "R" + r1 + "C" + c1 + ":R" + r2 + "C" + c2; //$NON-NLS-1$
    }
    private static void openInIr(DtGranularEditor<?> granularEditor, MoxelEditor embeddedEditor)
    {
        try
        {
            Object bmModel = Global.getField(granularEditor, "bmModel"); //$NON-NLS-1$
            IDtProject dtProject = (IDtProject) Global.getField(bmModel, "project"); //$NON-NLS-1$
            IRSession irSession = IRApplication.getSession(dtProject);
            if (irSession == null || irSession.executor == null)
                return;
            String currentRegionName = getCurrentRegionName(embeddedEditor);
            if (!copyToClipboardViaCommands(embeddedEditor))
            {
                ToastNotification.show("Редактор ИР", "Не удалось скопировать табличный документ в буфер обмена."); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            String fullObjectName = GetRef.getRefFromEditor(granularEditor);
            irSession.executor.submit(() ->
            {
                try
                {
                    Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                    ComBridge.setProperty(irSession.root, "Visible", true); //$NON-NLS-1$
//                    Функция ОткрытьТабличныйДокументЛкс(ТабличныйДокумент = Неопределено, Знач Заголовок = "", Знач ТолькоПросмотр = Ложь, Знач КлючУникальности = Неопределено, ВставитьВсеИзБуфера = Ложь,
//                        Знач Модально = Ложь, Знач ИмяТекущейОбласти = Неопределено, Знач КлючИсточника = "") Экспорт
                    ComBridge.invoke(irClient, "ОткрытьТабличныйДокументЛкс", null, fullObjectName, false, fullObjectName, true, false, currentRegionName, fullObjectName);
                    ToastNotification.show("Редактор ИР", "Табличный документ открыт в ИР."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Exception e)
                {
                    Global.log("Ошибка вызова ИР: " + e.getMessage()); //$NON-NLS-1$
                }
            });
        }
        catch (Exception e)
        {
            Global.log("MoxelEditorHook: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static boolean copyToClipboardViaCommands(IEditorPart editor)
    {
        if (editor == null || editor.getSite() == null) return false;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return false;

        final boolean[] ok = new boolean[] { false };
        display.syncExec(() ->
        {
            try
            {
                editor.setFocus();
                IHandlerService hs = editor.getSite().getService(IHandlerService.class);
                if (hs == null) return;
                hs.executeCommand("org.eclipse.ui.edit.selectAll", null); //$NON-NLS-1$
                hs.executeCommand("org.eclipse.ui.edit.copy", null); //$NON-NLS-1$
                ok[0] = true;
            }
            catch (Exception ignored)
            {
                ok[0] = false;
            }
        });
        return ok[0];
    }

}

