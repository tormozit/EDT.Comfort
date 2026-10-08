package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.IPartListener2;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.impl.TablePropertiesImpl;
import com._1c.g5.v8.dt.moxel.sheet.CellsSelection;
import com._1c.g5.v8.dt.moxel.sheet.Selection;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.sheet.TableSelection;
import com._1c.g5.v8.dt.moxel.ui.editor.HeaderArea;
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
    private static final String SPREADSHEET_PAGE_ID = "editors.commontemplate.pages.spreadsheet"; //$NON-NLS-1$
    private static final String MENU_TEXT = "Редактор ИР"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.tabdocMenuHooked"; //$NON-NLS-1$

    private final Map<IWorkbenchWindow, IPartListener2> partListeners = new HashMap<>();
    private final Set<DtGranularEditor<?>> pageChangeHookedEditors =
        java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Control> menuDetectHookedControls =
        java.util.Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PasteCacheRepair.install();
            InplaceCutSupport.install(Display.getDefault());
            SelectionRepaint.install(Display.getDefault());
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
                Display.getDefault().asyncExec(() -> hookGranularEditorPart(ref.getPart(false)));
            }

            @Override public void partActivated(IWorkbenchPartReference r)
            {
                Display.getDefault().asyncExec(() -> hookGranularEditorPart(r.getPart(false)));
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
            // Не getEditors(): он создаёт все восстановленные вкладки, а проект при старте ещё не поднят.
            for (org.eclipse.ui.IEditorReference ref : window.getActivePage().getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null)
                    hookGranularEditorPart(ed);
            }
    }

    private void hookGranularEditorPart(Object part)
    {
        if (!(part instanceof DtGranularEditor<?>)) return;
        DtGranularEditor<?> editor = (DtGranularEditor<?>) part;
        applyPatchToGranularEditor(editor);
        if (pageChangeHookedEditors.add(editor))
        {
            editor.addPageChangedListener(new IPageChangedListener()
            {
                @Override
                public void pageChanged(PageChangedEvent event)
                {
                    Display.getDefault().asyncExec(() -> applyPatchToGranularEditor(editor));
                }
            });
        }
    }

    private void applyPatchToGranularEditor(DtGranularEditor<?> editor)
    {
        org.eclipse.ui.forms.editor.IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorEmbeddedEditorPage<?>)
            applyPatchToEditorPage(editor, (DtGranularEditorEmbeddedEditorPage<?>) activePage);
    }

    private void applyPatchToEditorPage(DtGranularEditor<?> granularEditor, DtGranularEditorEmbeddedEditorPage<?> page)
    {
        IEditorPart embeddedEditor = page.getEmbeddedEditor();
        if (!(embeddedEditor instanceof MoxelEditor))
            return;
        MoxelEditor moxelEditor = (MoxelEditor) embeddedEditor;
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
                ComfortSubmenuHelper.setMenuItemTooltip(item, "Открыть табличный документ в редакторе ИР"); //$NON-NLS-1$
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
    private static MoxelControl getMoxelControl(MoxelEditor moxelEditor)
    {
        if (moxelEditor == null) return null;
        MoxelViewer viewer = moxelEditor.getInternalViewer();
        return viewer != null ? viewer.getMoxelControl() : null;
    }

    /** Снимок выделения ячеек до selectAll/copy. */
    private static final class SavedCellsSelection
    {
        final SheetAccessor sheet;
        final int x, y, width, height;

        SavedCellsSelection(CellsSelection selection)
        {
            sheet = selection.getSheet();
            Rectangle r = selection.getNormalizedPosition();
            x = r.x;
            y = r.y;
            width = r.width;
            height = r.height;
        }

        CellsSelection restore()
        {
            return new CellsSelection(sheet, x, y, width, height);
        }
    }

    private static SavedCellsSelection captureCellsSelection(MoxelEditor moxelEditor)
    {
        MoxelControl control = getMoxelControl(moxelEditor);
        if (control == null) return null;
        Selection tailSelection = control.getTailSelection();
        if (!(tailSelection instanceof CellsSelection)) return null;
        return new SavedCellsSelection((CellsSelection) tailSelection);
    }

    private static void restoreCellsSelection(MoxelEditor moxelEditor, SavedCellsSelection saved)
    {
        if (saved == null) return;
        MoxelControl control = getMoxelControl(moxelEditor);
        if (control == null || control.isDisposed()) return;
        try
        {
            control.replaceAllSelection(saved.restore());
        }
        catch (Exception e)
        {
            Global.log("MoxelEditorHook.restoreCellsSelection: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String getCurrentRegionName(MoxelEditor moxelEditor)
    {
        MoxelControl control = getMoxelControl(moxelEditor);
        if (control == null) return null;
        Selection tailSelection = control.getTailSelection();
        if (!(tailSelection instanceof CellsSelection)) return null;
        Rectangle r = ((CellsSelection) tailSelection).getNormalizedPosition();
        int r1 = r.y + 1, c1 = r.x + 1;
        int r2 = r.y + r.height + 1, c2 = r.x + r.width + 1;
        return "R" + r1 + "C" + c1 + ":R" + r2 + "C" + c2; //$NON-NLS-1$
    }
    private static void openInIr(DtGranularEditor<?> granularEditor, MoxelEditor embeddedEditor)
    {
        try
        {
            Object bmModel = Global.getField(granularEditor, "bmModel"); //$NON-NLS-1$
            IDtProject dtProject = (IDtProject) Global.getField(bmModel, "project"); //$NON-NLS-1$
            IRSession irSession = IRApplication.getSession(dtProject, true);
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
                    irSession.showWindow();
//                    Функция ОткрытьТабличныйДокументЛкс(ТабличныйДокумент = Неопределено, Знач Заголовок = "", Знач ТолькоПросмотр = Ложь, Знач КлючУникальности = Неопределено, ВставитьВсеИзБуфера = Ложь,
//                        Знач Модально = Ложь, Знач ИмяТекущейОбласти = Неопределено, Знач КлючИсточника = "") Экспорт
                    ComBridge.invoke(irClient, "ОткрытьТабличныйДокументЛкс", null, fullObjectName, false, fullObjectName, true, false, currentRegionName, fullObjectName);
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

    /**
     * Возвращает {@link MoxelEditor} активной или страницы табличного макета.
     */
    public static MoxelEditor findMoxelEditor(DtGranularEditor<?> editor)
    {
        if (editor == null) return null;
        IFormPage activePage = editor.getActivePageInstance();
        MoxelEditor moxel = moxelFromPage(activePage);
        if (moxel != null) return moxel;
        IFormPage spreadsheetPage = editor.findPage(SPREADSHEET_PAGE_ID);
        return moxelFromPage(spreadsheetPage);
    }

    private static MoxelEditor moxelFromPage(IFormPage page)
    {
        if (!(page instanceof DtGranularEditorEmbeddedEditorPage<?>)) return null;
        IEditorPart embedded = ((DtGranularEditorEmbeddedEditorPage<?>) page).getEmbeddedEditor();
        return embedded instanceof MoxelEditor ? (MoxelEditor) embedded : null;
    }

    /**
     * Вставляет табличный документ из системного буфера (ИР кладёт его туда перед вызовом).
     * Использует штатные команды EDT: {@code selectAll} + {@code paste}.
     */
    public static boolean importTabularDocumentFromIrClipboard(MoxelEditor editor, Shell shell)
    {
        if (editor == null) return false;
        Display display = shell != null && !shell.isDisposed()
            ? shell.getDisplay() : Display.getDefault();
        if (display == null || display.isDisposed()) return false;

        final boolean[] ok = new boolean[] { false };
        display.syncExec(() ->
        {
            SavedCellsSelection savedSelection = captureCellsSelection(editor);
            try
            {
                if (!pasteToMoxelViaCommands(editor))
                {
                    ToastNotification.show("Редактор ИР",
                        "Не удалось вставить табличный документ из буфера обмена.", 5000); //$NON-NLS-1$
                    return;
                }
                ok[0] = true;
            }
            finally
            {
                restoreCellsSelection(editor, savedSelection);
            }
        });
        return ok[0];
    }

    private static boolean copyToClipboardViaCommands(MoxelEditor editor)
    {
        return runMoxelEditCommands(editor,
            "org.eclipse.ui.edit.selectAll", //$NON-NLS-1$
            "org.eclipse.ui.edit.copy"); //$NON-NLS-1$
    }

    private static boolean pasteToMoxelViaCommands(MoxelEditor editor)
    {
        return runMoxelEditCommands(editor,
            "org.eclipse.ui.edit.selectAll", //$NON-NLS-1$
            "org.eclipse.ui.edit.paste"); //$NON-NLS-1$
    }

    private static boolean runMoxelEditCommands(MoxelEditor editor, String... commandIds)
    {
        if (editor == null || editor.getSite() == null || commandIds.length == 0) return false;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return false;

        final boolean[] ok = new boolean[] { false };
        display.syncExec(() ->
        {
            SavedCellsSelection saved = captureCellsSelection(editor);
            try
            {
                editor.setFocus();
                IHandlerService hs = editor.getSite().getService(IHandlerService.class);
                if (hs == null) return;
                for (String commandId : commandIds)
                    hs.executeCommand(commandId, null);
                ok[0] = true;
            }
            catch (Exception ignored)
            {
                ok[0] = false;
            }
            finally
            {
                restoreCellsSelection(editor, saved);
            }
        });
        return ok[0];
    }

    /**
     * Вырезание текста редактора ячейки (issue 690). MoxelControl.startInplaceEdit создаёт
     * Text непосредственно внутри MoxelControl, а штатный CutCommandHandler активен только
     * вне inplace-редактирования (moxel.nonInplaceEdit в plugin.xml EDT).
     * Команда нужна и для Win32-акселератора, который может не дать SWT.KeyDown;
     * фильтр клавиши обслуживает Shift+Delete, когда оно дошло до виджета.
     */
    private static final class InplaceCutSupport implements Listener
    {
        private static final String CUT_COMMAND = "org.eclipse.ui.edit.cut"; //$NON-NLS-1$
        private static final String LOG_TOPIC = "moxel-cut-690"; //$NON-NLS-1$

        private final IHandlerService handlerService;
        private Text target;
        private IHandlerActivation activation;

        private InplaceCutSupport(IHandlerService handlerService)
        {
            this.handlerService = handlerService;
        }

        static void install(Display display)
        {
            IHandlerService service = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (service == null)
                return;
            InplaceCutSupport support = new InplaceCutSupport(service);
            display.addFilter(SWT.FocusIn, support);
            display.addFilter(SWT.FocusOut, support);
            display.addFilter(SWT.KeyDown, support);
            support.activate(display.getFocusControl());
        }

        private static boolean isCellEditor(Control control)
        {
            return control instanceof Text && !control.isDisposed()
                && control.getParent() instanceof MoxelControl moxel && moxel.isInplaceEdit();
        }

        @Override
        public void handleEvent(Event event)
        {
            if (event.type == SWT.FocusIn)
            {
                activate(event.widget instanceof Control control ? control : null);
            }
            else if (event.type == SWT.FocusOut && event.widget == target)
            {
                deactivate();
            }
            else if (event.type == SWT.KeyDown && event.widget == target)
            {
                Global.tempLog(LOG_TOPIC, "key code=" + event.keyCode + " mask=" + event.stateMask); //$NON-NLS-1$ //$NON-NLS-2$
                if (event.keyCode == SWT.DEL && (event.stateMask & SWT.MODIFIER_MASK) == SWT.SHIFT)
                {
                    cut("Shift+Delete"); //$NON-NLS-1$
                    event.doit = false;
                    event.type = SWT.None;
                }
            }
        }

        private void activate(Control control)
        {
            if (control == target)
                return;
            deactivate();
            if (!isCellEditor(control))
                return;
            target = (Text) control;
            Text editor = target;
            editor.addDisposeListener(e ->
            {
                if (target == editor)
                    deactivate();
            });
            activation = handlerService.activateHandler(CUT_COMMAND, new AbstractHandler()
            {
                @Override
                public boolean isEnabled()
                {
                    return isCellEditor(target) && target.isFocusControl() && target.getEditable();
                }

                @Override
                public Object execute(ExecutionEvent event)
                {
                    cut("command"); //$NON-NLS-1$
                    return null;
                }
            }, new ActiveShellExpression(editor.getShell()));
            Global.tempLog(LOG_TOPIC, "activate"); //$NON-NLS-1$
        }

        private void deactivate()
        {
            if (activation != null)
            {
                handlerService.deactivateHandler(activation);
                Global.tempLog(LOG_TOPIC, "deactivate"); //$NON-NLS-1$
            }
            activation = null;
            target = null;
        }

        private void cut(String source)
        {
            Text editor = target;
            Global.tempLog(LOG_TOPIC, "cut source=" + source + " active=" + isCellEditor(editor)); //$NON-NLS-1$ //$NON-NLS-2$
            if (!isCellEditor(editor) || !editor.isFocusControl() || !editor.getEditable())
                return;
            Global.tempLog(LOG_TOPIC, "cut selection=" + editor.getSelectionCount()); //$NON-NLS-1$
            editor.cut();
        }
    }

    /**
     * Ускорение перехода по ячейкам (только Windows), по флажку «Ускорить переход по ячейкам
     * табличного документа» ({@link ComfortSettings#isMoxelFastCellNavigationEnabled()}; по умолчанию
     * выключен — вмешательство в отрисовку EDT). При смене выделения EDT помечает к перерисовке
     * шапку столбцов и шапку строк целиком плюс прямоугольник выделения
     * ({@link MoxelControl#invalidateSelection()}). Windows отдаёт в событие отрисовки один
     * охватывающий прямоугольник — полоса сверху и полоса слева дают весь контрол, и макет рисуется
     * целиком (сотни мс), хотя сама отрисовка EDT ограничена прямоугольником события.
     *
     * <p>Здесь та же область перерисовывается частями. До обработки ввода (фильтр) просим EDT
     * пометить текущее выделение и запоминаем помеченное без шапок — это старое место рамки. После
     * обработки (слушатель контрола, добавлен позже слушателей EDT) берём помеченную область,
     * снимаем пометку целиком и синхронно рисуем по отдельности: старое место, новое место и
     * участки шапок напротив них. Геометрию выделения считает только EDT.
     *
     * <p>Не вмешиваемся (остаётся штатная перерисовка), если до ввода уже была помеченная область
     * или если после ввода помечено больше половины контрола (прокрутка, смена размеров). Флаг
     * {@code SWT.NO_MERGE_PAINTS} для той же цели не подошёл: рамка выделения перестала двигаться.
     */
    private static final class SelectionRepaint implements Listener
    {
        private static final String AFTER_HOOKED = "tormozit.moxelSelectionRepaintHooked"; //$NON-NLS-1$

        private MoxelControl pendingControl;
        private Region pendingOld;

        /** Только из UI-потока. */
        static void install(Display display)
        {
            if (!"win32".equals(SWT.getPlatform())) //$NON-NLS-1$
                return;
            SelectionRepaint repaint = new SelectionRepaint();
            display.addFilter(SWT.KeyDown, repaint);
            display.addFilter(SWT.MouseDown, repaint);
        }

        /** Фильтр: до слушателей EDT. */
        @Override
        public void handleEvent(Event event)
        {
            if (!(event.widget instanceof MoxelControl))
                return;
            try
            {
                before((MoxelControl) event.widget);
            }
            catch (RuntimeException | LinkageError e)
            {
                clearPending();
            }
        }

        private void clearPending()
        {
            if (pendingOld != null && !pendingOld.isDisposed())
                pendingOld.dispose();
            pendingOld = null;
            pendingControl = null;
        }

        private void before(MoxelControl control)
        {
            clearPending();
            if (control.isDisposed() || !ComfortSettings.isMoxelFastCellNavigationEnabled())
                return;
            if (control.getData(AFTER_HOOKED) == null)
            {
                control.setData(AFTER_HOOKED, Boolean.TRUE);
                Listener after = e -> after(control);
                control.addListener(SWT.KeyDown, after);
                control.addListener(SWT.MouseDown, after);
            }
            Region old = Win32.updateRegion(control);
            boolean alreadyDirty = !old.isEmpty();
            old.dispose();
            if (alreadyDirty)
                return;
            control.invalidateSelection();
            old = Win32.updateRegion(control);
            for (Rectangle header : headerRects(control))
                old.subtract(header);
            pendingControl = control;
            pendingOld = old;
        }

        /** Слушатель контрола: после слушателей EDT. */
        private void after(MoxelControl control)
        {
            Region old = pendingOld;
            if (pendingControl != control || old == null)
                return;
            pendingControl = null;
            pendingOld = null;
            Region all = null;
            try
            {
                if (control.isDisposed())
                    return;
                all = Win32.updateRegion(control);
                if (all.isEmpty())
                    return;
                List<Rectangle> headers = headerRects(control);
                for (Rectangle header : headers)
                    all.subtract(header);
                Rectangle oldPlace = old.getBounds();
                all.subtract(old);
                Rectangle newPlace = all.getBounds();
                Rectangle client = control.getClientArea();
                long limit = (long) client.width * client.height / 2;
                if ((long) oldPlace.width * oldPlace.height > limit || (long) newPlace.width * newPlace.height > limit)
                    return;
                List<Rectangle> pieces = new ArrayList<>();
                addPiece(pieces, oldPlace, 1);
                addPiece(pieces, newPlace, 1);
                int cells = pieces.size();
                for (int i = 0; i < cells; i++)
                {
                    Rectangle cell = pieces.get(i);
                    for (Rectangle header : headers)
                    {
                        addPiece(pieces, header.intersection(new Rectangle(cell.x, header.y, cell.width, header.height)), 0);
                        addPiece(pieces, header.intersection(new Rectangle(header.x, cell.y, header.width, cell.height)), 0);
                    }
                }
                Win32.validateAll(control);
                for (Rectangle piece : pieces)
                {
                    if (control.isDisposed())
                        return;
                    control.redraw(piece.x, piece.y, piece.width, piece.height, false);
                    control.update();
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                if (!control.isDisposed())
                    control.redraw();
            }
            finally
            {
                if (all != null)
                    all.dispose();
                old.dispose();
            }
        }

        private static void addPiece(List<Rectangle> pieces, Rectangle rect, int inflate)
        {
            if (rect == null || rect.width <= 0 || rect.height <= 0)
                return;
            pieces.add(new Rectangle(rect.x - inflate, rect.y - inflate, rect.width + 2 * inflate, rect.height + 2 * inflate));
        }

        /** Области шапок строк и столбцов в координатах контрола; пусто, если поле EDT недоступно. */
        private static List<Rectangle> headerRects(MoxelControl control)
        {
            List<Rectangle> result = new ArrayList<>();
            Object areas = Global.getField(control, "headerAreas"); //$NON-NLS-1$
            if (!(areas instanceof List<?>))
                return result;
            for (Object area : (List<?>) areas)
            {
                if (!(area instanceof HeaderArea))
                    continue;
                Rectangle r = ((HeaderArea) area).getDevicePosition();
                if (r != null && r.width > 0 && r.height > 0)
                    result.add(new Rectangle(r.x, r.y, r.width, r.height));
            }
            return result;
        }

        /** Вызовы Win32 — в отдельном классе, чтобы не грузить {@code OS} на других платформах. */
        private static final class Win32
        {
            /** Помеченная к перерисовке область контрола; вызывающий освобождает. */
            static Region updateRegion(Control control)
            {
                Region region = new Region(control.getDisplay());
                org.eclipse.swt.internal.win32.OS.GetUpdateRgn(control.handle, region.handle, false);
                return region;
            }

            static void validateAll(Control control)
            {
                org.eclipse.swt.internal.win32.OS.ValidateRect(control.handle, null);
            }
        }
    }

    /**
     * Обход ошибки EDT (issue 691). После вставки целого табличного документа {@link MoxelControl}
     * продолжает держать объекты модели, которые вставка заменила либо создала в откатанной транзакции,
     * и падает на каждой перерисовке («Object is removed» / «connected to a rolled back transaction»):
     * <ul>
     * <li>{@code ViewParameters.fixedColumnColumns} — вставка удаляет все столбцы документа и создаёт новые,
     * а в контроле остаётся удалённый объект — отрисовка падает, а сохранение записывает его в настройки
     * документа и падает с «Failed to persist reference value». Если объекта столбцов контрола больше нет
     * среди столбцов документа (сравнение по {@link IBmObject#bmGetId()}), подставляем актуальные столбцы
     * документа. Проверенные тупики: штатный {@link MoxelControl#refreshViewParameters()} читает из
     * настроек отображения документа объект-пустышку (вставка не обновляет и эту ссылку); сравнение по
     * {@code getColumnsId()} не работает — у удалённых и новых столбцов он одинаковый, а чтение удалённого
     * объекта не всегда даёт исключение;</li>
     * <li>{@code SheetAccessor.formatCache} / {@code formatsMap} — штатного сброса без удаления форматов
     * из модели нет, чистим поля напрямую; оба кэша ленивые и наполняются из модели заново.</li>
     * </ul>
     * То же делают отмена и повтор вставки (они снова подменяют столбцы и форматы), поэтому слушаем и их;
     * перед ними кэш форматов сбрасывается заранее: они читают форматы уже по ходу команды.
     * Слушаем команду, а не клавишу (см. правило про Ctrl+буква).
     */
    private static final class PasteCacheRepair implements IExecutionListener
    {
        private static final String PASTE_COMMAND = "org.eclipse.ui.edit.paste"; //$NON-NLS-1$
        private static final String UNDO_COMMAND = "org.eclipse.ui.edit.undo"; //$NON-NLS-1$
        private static final String REDO_COMMAND = "org.eclipse.ui.edit.redo"; //$NON-NLS-1$

        private MoxelEditor target;
        /** До вставки закреплённые столбцы контрола были основными столбцами документа. */
        private boolean fixedWasMain;
        /** Идентификатор закреплённых столбцов контрола до вставки (для неосновных столбцов). */
        private UUID fixedColumnsId;

        /**
         * {@code true} только для бандла moxel старой EDT (major {@code < 18}, EDT 2025.2). В EDT 2026.1
         * вставка работает правильно — там слушатель не ставится вовсе, чтобы ничего не задеть.
         */
        private static boolean isAffectedEdt()
        {
            Bundle moxel = Platform.getBundle("com._1c.g5.v8.dt.moxel"); //$NON-NLS-1$
            Version version = moxel != null ? moxel.getVersion() : null;
            return version != null && version.getMajor() < 18;
        }

        static void install()
        {
            if (!isAffectedEdt())
                return;
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null)
                return;
            commandService.addExecutionListener(new PasteCacheRepair());
        }

        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!isWatched(commandId)) return;
            target = activeMoxelEditor();
            fixedWasMain = false;
            fixedColumnsId = null;
            MoxelControl control = getMoxelControl(target);
            if (control != null)
            {
                try
                {
                    Columns fixed = control.getFixedColumnColumns();
                    fixedWasMain = sameBmObject(fixed, control.getSheet().getDocument().getColumns());
                    if (fixed != null)
                        fixedColumnsId = readInTask(control, fixed::getColumnsId);
                }
                catch (RuntimeException e)
                {
                    // Столбцы контрола не прочитаны — чинить будет нечего.
                }
            }
            // Отмена и повтор читают форматы уже по ходу команды, сразу после изменения модели:
            // кэш к этому моменту должен быть пуст, иначе сама команда падает с «Object is removed».
            if (control != null && !PASTE_COMMAND.equals(commandId))
                clearFormatCache(control.getSheet());
        }

        /** Команды, после которых нужно починить кэши контрола. */
        private static boolean isWatched(String commandId)
        {
            return PASTE_COMMAND.equals(commandId) || UNDO_COMMAND.equals(commandId)
                || REDO_COMMAND.equals(commandId);
        }

        /** Сбрасывает ленивые кэши форматов {@link SheetAccessor}. */
        private static void clearFormatCache(SheetAccessor sheet)
        {
            Object formatCache = Global.getField(sheet, "formatCache"); //$NON-NLS-1$
            if (formatCache instanceof Map<?, ?>)
                ((Map<?, ?>) formatCache).clear();
            if (Global.getField(sheet, "formatsMap") != null) //$NON-NLS-1$
                Global.setFieldForce(sheet, "formatsMap", null); //$NON-NLS-1$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            finish(commandId);
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            finish(commandId);
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            finish(commandId);
        }

        private void finish(String commandId)
        {
            if (!isWatched(commandId)) return;
            MoxelEditor editor = target;
            boolean wasMain = fixedWasMain;
            UUID columnsId = fixedColumnsId;
            target = null;
            fixedWasMain = false;
            fixedColumnsId = null;
            if (editor == null) return;
            repair(editor, wasMain, columnsId);
            // Операция вставки могла завершиться (или откатиться) позже самой команды.
            Display display = Display.getCurrent();
            if (display != null)
                display.asyncExec(() -> repair(editor, wasMain, columnsId));
        }

        /** Один и тот же объект модели BM (экземпляры Java вне и внутри транзакции различаются). */
        private static boolean sameBmObject(Object first, Object second)
        {
            return first instanceof IBmObject && second instanceof IBmObject
                && ((IBmObject) first).bmGetId() == ((IBmObject) second).bmGetId();
        }

        /**
         * Если объекта закреплённых столбцов контрола больше нет среди столбцов документа, подставляет
         * актуальные. Пишем в поле контрола, а не через {@link MoxelControl#setFixedColumnColumns}: тот
         * сразу меняет модель вне транзакции. В настройки документа живой объект запишет штатное сохранение.
         */
        private static void restoreFixedColumns(MoxelControl control, boolean wasMain, UUID columnsId)
        {
            try
            {
                Columns current = control.getFixedColumnColumns();
                if (current == null)
                    return;
                SpreadsheetDocument document = control.getSheet().getDocument();
                List<Columns> all = new ArrayList<>(document.getAllColumns());
                for (Columns each : all)
                    if (sameBmObject(current, each))
                        return;
                Columns fresh = null;
                if (!wasMain && columnsId != null)
                    for (Columns each : all)
                        if (columnsId.equals(each.getColumnsId()))
                        {
                            fresh = each;
                            break;
                        }
                if (fresh == null)
                    fresh = document.getColumns();
                if (fresh == null)
                    return;
                Object viewParameters = Global.getField(control, "viewParameters"); //$NON-NLS-1$
                if (viewParameters == null)
                    return;
                Global.setFieldForce(viewParameters, "fixedColumnColumns", fresh); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                // Столбцы документа недоступны — оставляем контрол как есть.
            }
        }

        private static <T> T readInTask(MoxelControl control, Supplier<T> reader)
        {
            IEditingContext context = control.getEditingContext();
            if (context == null) return reader.get();
            return context.executeReadonlyTask(new AbstractBmTask<T>("comfort.moxelPaste691") //$NON-NLS-1$
            {
                @Override
                public T execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    return reader.get();
                }
            }, new NullProgressMonitor());
        }

        private static MoxelEditor activeMoxelEditor()
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null) return null;
            IEditorPart editor = window.getActivePage().getActiveEditor();
            if (editor instanceof MoxelEditor) return (MoxelEditor) editor;
            if (editor instanceof DtGranularEditor<?>)
                return moxelFromPage(((DtGranularEditor<?>) editor).getActivePageInstance());
            return null;
        }

        private static void repair(MoxelEditor editor, boolean wasMain, UUID columnsId)
        {
            MoxelControl control = getMoxelControl(editor);
            if (control == null || control.isDisposed())
                return;
            restoreFixedColumns(control, wasMain, columnsId);
            clearFormatCache(control.getSheet());
            control.redraw();
        }

    }

}

