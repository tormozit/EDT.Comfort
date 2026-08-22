package tormozit;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.common.localization.EnumLiteralLocalizationProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IEditingLanguageManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.parameters.appearance.FormAppearanceParameters;
import com._1c.g5.v8.dt.dcs.settings.DcsAvailableSettingsSourceForSchema;
import com._1c.g5.v8.dt.dcs.ui.UiPlugin;
import com._1c.g5.v8.dt.dcs.ui.settings.IDcsSettingsProvider;
import com._1c.g5.v8.dt.dcs.ui.settings.conditional.ConditionalAppearance;
import com._1c.g5.v8.dt.dcs.ui.util.DcsUiUtil;
import com._1c.g5.v8.dt.form.copypaste.FormElementTransfer;
import com._1c.g5.v8.dt.form.mapping.model.Item;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Addition;
import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.AbstractFormDataSourceInfo;
import com._1c.g5.v8.dt.form.model.ContextMenu;
import com._1c.g5.v8.dt.form.model.DataPathReferredObject;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.model.RowActionsPanel;
import com._1c.g5.v8.dt.form.model.SelectedItemsActionsPanel;
import com._1c.g5.v8.dt.form.model.PropertyInfo.PropertyInfoType;
import com._1c.g5.v8.dt.form.model.Visible;
import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorComponent;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorPage;
import com._1c.g5.v8.dt.form.ui.editor.item.FormItemActionsGroup;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.StandardTabularSectionDescription;
import com._1c.g5.v8.dt.md.ui.sattribute.SAttributeFactory;
import com._1c.g5.v8.dt.md.ui.sattribute.StandardAttributeProxy;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.util.EcoreUtil;
import com._1c.g5.v8.dt.ui.commands.ShowPropertiesHandler;
import com._1c.g5.v8.dt.ui.util.ContentUtil;

/**
 * Объединяет поведения редактора форм EDT:
 *
 * <ol>
 *   <li><b>Клик по заголовку эскиза формы (WYSIWYG).</b>
 *       Верхняя полоса эскиза не принадлежит ни одному элементу, и клик по ней штатно
 *       ничего не делает; вместо этого выделяется корневой узел «Форма» —
 *       см. {@link WysiwygHeaderClick}.
 *
 *   <li><b>Правый клик в области предпросмотра (WYSIWYG).</b>
 *       На {@link SWT#MouseDown} посылает синтетический левый клик,
 *       чтобы EDT выбрал элемент формы под курсором до открытия контекстного меню.
 *       Область предпросмотра реализована классом
 *       {@code WysiwygNativeComposite} (наследник {@link Composite}).
 *
 *   <li><b>Двойной клик в области предпросмотра (WYSIWYG).</b>
 *       Активирует панель «Свойства» и выполняет «Изменить» для выбранного
 *       элемента — как двойной клик по дереву элементов формы в EDT.
 *
 *   <li><b>Сортировка подменю «События».</b>
 *       Упорядочивает пункты подменю «События» контекстного меню дерева элементов
 *       и предпросмотра (WYSIWYG) формы в соответствии с порядком конфигуратора 1С.
 *       Механизм: глобальный display-фильтр на {@link SWT#MenuDetect};
 *       на SWT-меню дерева / {@code WysiwygNativeComposite} вешается {@link MenuAdapter},
 *       после построения корневого меню находим каскад «События» и подключаем
 *       {@link IMenuListener} к его JFace {@link MenuManager}.
 *       У предпросмотра меню своё: EDT вызывает {@code contributeToMenu} на
 *       {@code wysiwygViewer.getControl()} при каждом {@code mouseDown}
 *       ({@code FormEditorPage$2}), отдельно от меню дерева элементов.
 *
 *   <li><b>«Показать в навигаторе» в меню реквизитов.</b>
 *       Для полей источника данных, порождённых метаданными,
 *       добавляет пункт контекстного меню дерева реквизитов формы.
 *       В навигаторе выделяется объект-владелец ({@link MdObject}), не поле.
 *       Пользовательские реквизиты формы ({@code FormAttribute}) — disabled.
 *
 *   <li><b>Двойной клик в дереве реквизитов — «Свойства» метаданных.</b>
 *       Для стандартных реквизитов: {@link StandardAttributeProxy} в property sheet
 *       (как в редакторе метаданных EDT), без открытия редактора объекта-владельца.
 *
 *   <li><b>Число элементов в заголовках вкладок «Реквизиты», «Команды», «Параметры».</b>
 *       Как в редакторе объекта метаданных — см. {@link TabCounts}.
 *
 *   <li><b>Колонки дерева элементов формы.</b> Обработчики, условное оформление, эффективные
 *       «Невидимость» и «ТолькоПросмотр», вид группы в подписи — см. {@link ItemsTree}.
 *
 *   <li><b>Страница «Условное оформление».</b> Штатный редактор условного оформления прямо в
 *       редакторе формы, с отбором по текущему элементу — см. {@link AppearancePage}.
 * </ol>
 */
public class FormEditorHook implements IStartup
{
    /** Команда «Показать в навигаторе» для дерева реквизитов формы. */
    public static final String SHOW_IN_NAVIGATOR_COMMAND_ID =
            "tormozit.formAttributes.showInNavigator"; //$NON-NLS-1$

    /** Контекст EDT: фокус в дереве реквизитов формы. */
    public static final String ATTRIBUTES_CONTEXT_ID =
            "com._1c.g5.v8.dt.form.ui.formEditor.attributes"; //$NON-NLS-1$
    // -----------------------------------------------------------------------
    // Константы — правый клик (RightClickSelect)
    // -----------------------------------------------------------------------

    /** Простое имя класса wysiwyg-области предпросмотра форм. */
    private static final String WYSIWYG_CLASS = "WysiwygNativeComposite"; //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // Константы — порядок событий (EventOrder)
    // -----------------------------------------------------------------------

    /** Текст подменю событий (FormItemActionsGroup_Events_group_name). */
    private static final String EVENTS_SUBMENU_TEXT = "События"; //$NON-NLS-1$

    /** Параметр команды EDT: имя события формы. */
    private static final String EDT_EVENT_NAME_PARAM =
            "com._1c.g5.v8.dt.form.ui.commandParameters.eventName"; //$NON-NLS-1$

    /**
     * Ключ которым JFace {@link MenuManager} регистрирует себя в SWT {@link Menu}.
     * Значение константы {@code MenuManager.MANAGER_KEY}.
     */
    private static final String JFACE_MANAGER_KEY =
            "org.eclipse.jface.action.MenuManager.managerKey"; //$NON-NLS-1$

    /** Маркер: SWT MenuAdapter уже навешен на меню дерева элементов. */
    private static final String HOOK_MARKER = "tormozit.formEventOrderHooked"; //$NON-NLS-1$

    /** Маркер: меню дерева реквизитов уже подключено. */
    private static final String ATTRIBUTES_HOOK_MARKER = "tormozit.formAttributesMenuHooked"; //$NON-NLS-1$

    private static final String ITEM_TEXT_SHOW_IN_NAVIGATOR = "Показать в навигаторе \tCTRL+T"; //$NON-NLS-1$

    /** Пункт EDT, перед которым вставляем «Показать в навигаторе». */
    private static final String PROPERTIES_MENU_TEXT = "Свойства"; //$NON-NLS-1$

    /** Подменю «События», на которое уже навешен listener сортировки. */
    private static final Set<IMenuManager> hookedEventsMenus =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Эталонный порядок событий формы как в конфигураторе 1С.
     * Ключ — имя события; значение — порядковый номер (меньше = выше в меню).
     */
    private static final Map<String, Integer> FORM_EVENT_ORDER = buildOrderMap(
        "ПриСозданииНаСервере",                                            //  1
        "ПриОткрытии",                                                     //  2
        "ПриПовторномОткрытии",                                            //  3
        "ПередЗакрытием",                                                  //  4
        "ПриЗакрытии",                                                     //  5
        "ОбработкаВыбора",                                                 //  6
        "ОбработкаОповещения",                                             //  7
        "ОбработкаАктивизации",                                            //  8
        "ОбработкаЗаписиНового",                                           //  9
        "ПриЧтенииНаСервере",                                              // 10
        "ПередЗаписью",                                                    // 11
        "ПередЗаписьюНаСервере",                                           // 12
        "ПриЗаписиНаСервере",                                              // 13
        "ПослеЗаписиНаСервере",                                            // 14
        "ПослеЗаписи",                                                     // 15
        "ОбработкаПроверкиЗаполненияНаСервере",                            // 16
        "ВнешнееСобытие",                                                  // 17
        "ОтключениеВнешнейКомпонентыПриОшибке",                            // 18
        "ПриСохраненииДанныхВНастройкахНаСервере",                         // 19
        "ПередЗагрузкойДанныхИзНастроекНаСервере",                         // 20
        "ПриЗагрузкеДанныхИзНастроекНаСервере",                            // 21
        "ОбработкаНавигационнойСсылки",                                    // 22
        "ОбработкаПолученияНавигационнойСсылки",                           // 23
        "ОбработкаПолученияСпискаНавигационныхСсылок",                     // 24
        "ОбработкаПерехода",                                               // 25
        "ВыборЗначения",                                                   // 26
        "ПриИзменениипараметровЭкрана",                                    // 27
        "АвтоПодборПользователейСистемыВзаимодействия",                    // 28
        "ОбработкаПолученияФормыВыбораПользователейСистемыВзаимодействия", // 29
        "ПриИзмененииДоступностиОсновногоСервера",                         // 30
        "ПередПереоткрытиемСДругогоСервера",                               // 31
        "ПриПереоткрытииСДругогоСервера",                                  // 32
        "ПриЗасыпанииКлиентскогоПриложения",                               // 33
        "ПриПробужденииКлиентскогоПриложения",                             // 34
        "ПриВставкеИзБуфераОбмена"                                         // 35
    );

    // -----------------------------------------------------------------------
    // IStartup
    // -----------------------------------------------------------------------

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDown, FormEditorHook::handleMouseDown);
        display.addFilter(SWT.MouseDoubleClick, FormEditorHook::handleMouseDoubleClick);
        display.addFilter(SWT.MenuDetect, FormEditorHook::handleMenuDetect);
        WysiwygHeaderClick.install(display);
        TabCounts.install();
        ItemsTree.install();
        AppearancePage.install();
        ConditionalAppearanceCellStyle.install(display);
    }

    // -----------------------------------------------------------------------
    // Отслеживание редакторов формы
    // -----------------------------------------------------------------------

    /** Подписчики на появление/активацию редактора формы. */
    private static final List<Consumer<FormEditor>> FORM_EDITOR_ATTACHMENTS = new ArrayList<>();

    private static boolean formEditorTrackerInstalled;

    /** Когда после старта EDT повторно обойти уже открытые редакторы формы (мс). */
    private static final int[] STARTUP_RESCAN_DELAYS_MS = { 1000, 5000, 10000, 15000, 30000 };

    /**
     * ВРЕМЕННОЕ безусловное логирование подключения к редакторам формы
     * ({@code .tmp/temp-logs/редактор-формы.log}). Подключение к восстановленным при старте EDT
     * редакторам — повторяющийся дефект: без записей о том, что нашёл каждый обход и почему
     * доработка отложила подключение, разбирать его нечем.
     */
    static void trackerLog(String text)
    {
        Global.tempLog("редактор-формы", text); //$NON-NLS-1$
    }

    /**
     * Вызывает {@code attachment} для каждого редактора формы — уже открытого и открытого позже.
     * Слушатель окон/частей один на все доработки редактора формы ({@link TabCounts},
     * {@link ItemsTree}): точка подключения у них общая, а редактор к моменту события может быть
     * ещё не достроен — ожидание достройки каждая доработка ведёт сама.
     */
    private static void trackFormEditors(Consumer<FormEditor> attachment)
    {
        FORM_EDITOR_ATTACHMENTS.add(attachment);
        if (formEditorTrackerInstalled)
            return;
        formEditorTrackerInstalled = true;

        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow window)      { hookFormEditorWindow(window); }
            @Override public void windowActivated(IWorkbenchWindow window)   {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window)      {}
        });
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookFormEditorWindow(window);
        scheduleStartupRescan();
    }

    /**
     * Повторные обходы уже открытых редакторов после старта EDT.
     *
     * <p>Редакторы, восстановленные из прошлого сеанса, к моменту {@code earlyStartup} обычно ещё
     * не построены, а события частей для них больше не приходят — доработки появлялись только
     * после первого клика в редакторе (он активировал часть). Обходы закрывают этот разрыв;
     * повторное подключение идемпотентно (каждая доработка помечает свой виджет).
     */
    private static void scheduleStartupRescan()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        for (int delay : STARTUP_RESCAN_DELAYS_MS)
            display.timerExec(delay, FormEditorHook::rescanFormEditors);
    }

    private static void rescanFormEditors()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        int references = 0;
        int created = 0;
        int forms = 0;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                continue;
            for (IEditorReference ref : page.getEditorReferences())
            {
                references++;
                Object part = ref.getEditor(false);
                if (part != null)
                    created++;
                if (part instanceof FormEditor formEditor)
                {
                    forms++;
                    notifyFormEditor(formEditor);
                }
            }
        }
        trackerLog("обход: ссылок на редакторы=" + references + ", построено=" + created //$NON-NLS-1$ //$NON-NLS-2$
            + ", редакторов формы=" + forms); //$NON-NLS-1$
    }

    private static void hookFormEditorWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (ref.getEditor(false) instanceof FormEditor formEditor)
                    notifyFormEditor(formEditor);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)       { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)    { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)      { hookFromRef(ref); }
            @Override public void partClosed(IWorkbenchPartReference ref)       {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)  {}
            @Override public void partHidden(IWorkbenchPartReference ref)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r)   {}

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (ref instanceof IEditorReference editorRef
                        && editorRef.getPart(false) instanceof FormEditor formEditor)
                    notifyFormEditor(formEditor);
            }
        });
    }

    private static void notifyFormEditor(FormEditor editor)
    {
        for (Consumer<FormEditor> attachment : FORM_EDITOR_ATTACHMENTS)
        {
            try
            {
                attachment.accept(editor);
            }
            catch (Exception e)
            {
                Global.logError("FormEditorHook", "notifyFormEditor", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /** Ключ EDT: композит вкладки редактора формы хранит свой {@link CTabItem} ({@code FormEditorPage}). */
    private static final String DATA_TAB_ITEM = "tabItem"; //$NON-NLS-1$

    /** Контрол списка вкладки редактора формы по имени поля страницы. */
    private static Control formViewerControl(FormEditorPage page, String fieldName)
    {
        if (page == null)
            return null;
        Object viewer = Global.getField(page, fieldName);
        if (viewer == null)
            return null;
        Object control = Global.call(viewer, "getControl"); //$NON-NLS-1$
        return control instanceof Control widget && !widget.isDisposed() ? widget : null;
    }

    /**
     * Вкладка, содержащая контрол. При заданной {@code folder} подходит только вкладка
     * этой полосы: у таблицы команд формы предков-вкладок два.
     */
    private static CTabItem formOwnerTab(Control control, CTabFolder folder)
    {
        for (Composite parent = control != null ? control.getParent() : null; parent != null;
                parent = parent.getParent())
        {
            if (!(parent.getData(DATA_TAB_ITEM) instanceof CTabItem item) || item.isDisposed())
                continue;
            if (folder == null || item.getParent() == folder)
                return item;
        }
        return null;
    }

    /** Страница редактора формы (в редакторе есть и другие страницы — модуль и т.п.). */
    private static FormEditorPage findFormPage(FormEditor editor)
    {
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages))
            return null;
        for (Object page : pages)
        {
            if (page instanceof FormEditorPage formPage)
                return formPage;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Клик по заголовку эскиза формы (WYSIWYG)
    // -----------------------------------------------------------------------

    /**
     * Клик по верхней полосе эскиза формы выделяет корневой узел «Форма» в дереве элементов.
     *
     * <p><b>Почему по геометрии, а не по ответу рендера.</b> В нативном режиме hit-тест целиком
     * внутри процесса-визуализатора, и при промахе он <b>сохраняет прежнее выделение</b>,
     * возвращая его же id. Поэтому «клик мимо» и «повторный клик по уже выделенному элементу»
     * из Java неотличимы, а LWT-дерево эскиза пустое, и {@code getControlUnderPoint} всегда
     * даёт {@code null}. Единственная надёжная опора — координаты клика: полоса заголовка
     * лежит выше содержимого формы, отсчёт от её верхнего края, потому что эскиз рисуется
     * от точки (0,0) своего {@code WysiwygNativeComposite}.
     *
     * <p><b>Граница вычисляется, а не задаётся.</b> Высота заголовка динамическая: её рисует
     * сам визуализатор, зависит от масштаба, темы и состава формы, и в модели раскладки её
     * нет — у {@code HippoLayElementBase} только grid-ограничения без координат. Поэтому
     * граница берётся как наименьший {@code y} фактических прямоугольников элементов верхнего
     * уровня ({@link #contentTop}); если границы недоступны, обработчик не делает ничего.
     *
     * <p>Корень выделяется безусловно: полоса входит в область верхнего элемента, поэтому
     * «пустоты» не бывает. Чтобы в дереве не мигал элемент до переключения на корень,
     * обработчик перед {@code setSelection} вызывает {@code rebuild(false)} у эскиза —
     * перестроение без события снимает выделение элемента с эскиза и не трогает дерево
     * (у события нет id, {@code notifySelection} не вызывается).
     */
    private static final class WysiwygHeaderClick
    {
        /** Сдвиг мыши между нажатием и отпусканием, выше которого это перетаскивание. */
        private static final int DRAG_THRESHOLD_PX = 3;

        /**
         * Высота полосы заголовка от верхнего края эскиза. Значение подобрано для масштаба
         * 100%: вычислить настоящую высоту нельзя — её рисует нативный визуализатор, в модели
         * раскладки координат нет, а LWT-дерево эскиза в этом режиме пустое, поэтому
         * {@code getRelatedControl}/{@code getBoundsRelativeWysiwygRoot} возвращают
         * {@code null} для всех элементов. При крупном масштабе полоса окажется занижена —
         * клик по нижней части заголовка тогда просто ничего не сделает.
         */
        private static final int HEADER_BAND_PX = 32;

        /** Шаг опроса и предельное время ожидания асинхронного перестроения эскиза. */
        private static final int POLL_INTERVAL_MS = 25;
        private static final int POLL_TIMEOUT_MS = 3000;

        /**
         * Сколько опросов подряд {@code hippoSession} должна держаться без изменений, чтобы считать
         * перестроение завершённым. Клик по эскизу запускает два перестроения
         * (см. {@code WysiwygNativeHandler.mouseUp}); реакция на первое даёт «через раз» —
         * второе перестроение перекрывает выделение корня элементом.
         */
        private static final int STABLE_POLLS = 4;

        /** Точка нажатия ЛКМ в эскизе; {@code null} — подходящего нажатия не было. */
        private static Point downPoint;

        /** Номер последнего клика: обработка более раннего прекращается. */
        private static int clickGeneration;

        private WysiwygHeaderClick() {}

        static void install(Display display)
        {
            display.addFilter(SWT.MouseDown, WysiwygHeaderClick::onMouseDown);
            display.addFilter(SWT.MouseUp, WysiwygHeaderClick::onMouseUp);
        }

        private static void onMouseDown(Event e)
        {
            downPoint = e.button == 1 && isWysiwyg(e.widget) ? new Point(e.x, e.y) : null;
        }

        private static void onMouseUp(Event e)
        {
            Point down = downPoint;
            downPoint = null;

            if (e.button != 1 || down == null || !isWysiwyg(e.widget))
                return;
            if (Math.abs(e.x - down.x) > DRAG_THRESHOLD_PX
                    || Math.abs(e.y - down.y) > DRAG_THRESHOLD_PX)
                return; // перетаскивание, а не клик
            if (e.y >= HEADER_BAND_PX)
                return; // ниже полосы заголовка

            FormEditorPage page = FormEditor.getActiveFormEditorPage();
            Object representation = getRepresentation(page);
            Display display = e.display;
            if (representation == null || display == null || display.isDisposed())
                return;

            // Display-фильтр отрабатывает до слушателя WysiwygNativeHandler, поэтому здесь
            // ещё видно состояние «до клика». Перестроение асинхронно
            // (rebuild -> MappingController.getMappingRootAsync), поэтому результат клика
            // читается не сразу, а после смены hippoSession: её переприсваивает задача рендера.
            Object sessionBefore = Global.getField(representation, "hippoSession"); //$NON-NLS-1$
            int generation = ++clickGeneration;
            awaitRebuild(page, representation, sessionBefore, display, generation, 0,
                    sessionBefore, 0, () -> onClickProcessed(page, representation));
        }

        private static void onClickProcessed(FormEditorPage page, Object representation)
        {
            // Нативный рендер относит клик по полосе заголовка к верхнему элементу формы
            // (полоса входит в его область), поэтому «пустоты» здесь не бывает — выделяем корень
            // безусловно. Плата: верхние пиксели самого верхнего элемента тоже попадают под полосу.
            //
            // Сначала перестроение без события снимает выделение элемента с эскиза
            // (rebuild(false) не трогает дерево: у события нет id, notifySelection не вызывается),
            // чтобы в дереве не мигал элемент до переключения на корень.
            Global.invokeVoid(representation, "rebuild", false); //$NON-NLS-1$
            Form form = page.getModel();
            if (form != null)
                page.setSelection(FormEditorComponent.ITEMS, false, form);
        }

        /**
         * Ждёт, пока асинхронное перестроение эскиза завершится: {@code hippoSession} сменится и
         * продержится без изменений {@value #STABLE_POLLS} опросов подряд. Клик по эскизу вызывает
         * два перестроения (см. {@code WysiwygNativeHandler.mouseUp}), поэтому реакция на первое
         * перестроение ненадёжна — второе перекроет выделение.
         */
        private static void awaitRebuild(FormEditorPage page, Object representation,
                Object sessionBefore, Display display, int generation, int elapsedMs,
                Object prevSession, int stablePolls, Runnable onFinished)
        {
            if (generation != clickGeneration || display.isDisposed() || page.getSite() == null)
                return; // подоспел более свежий клик либо редактор уже закрыт

            Object session = Global.getField(representation, "hippoSession"); //$NON-NLS-1$
            if (session == sessionBefore)
            {
                if (elapsedMs >= POLL_TIMEOUT_MS)
                    return;
                display.timerExec(POLL_INTERVAL_MS, () -> awaitRebuild(page, representation,
                        sessionBefore, display, generation, elapsedMs + POLL_INTERVAL_MS,
                        session, 0, onFinished));
                return;
            }
            if (stablePolls >= STABLE_POLLS)
            {
                onFinished.run();
                return;
            }
            if (elapsedMs >= POLL_TIMEOUT_MS)
                return;
            int nextStable = session == prevSession ? stablePolls + 1 : 0;
            display.timerExec(POLL_INTERVAL_MS, () -> awaitRebuild(page, representation,
                    sessionBefore, display, generation, elapsedMs + POLL_INTERVAL_MS,
                    session, nextStable, onFinished));
        }

        /**
         * Верхняя граница содержимого эскиза: наименьший {@code y} среди прямоугольников
         * элементов верхнего уровня. Всё, что выше, — полоса заголовка. Высоту заголовка
         * рисует сам визуализатор, в модели раскладки её нет (у {@code HippoLayElementBase}
         * только grid-ограничения), поэтому она и вычисляется от фактических границ.
         *
         * @return граница в координатах эскиза, либо {@code -1}, если границы недоступны
         */
        private static int contentTop(FormEditorPage page, Object representation)
        {
            Form form = page.getModel();
            if (form == null)
                return -1;

            int top = Integer.MAX_VALUE;
            for (FormItem item : topLevelItems(form))
            {
                Rectangle bounds = itemBounds(representation, item);
                if (bounds != null && bounds.height > 0)
                    top = Math.min(top, bounds.y);
            }
            return top == Integer.MAX_VALUE ? -1 : top;
        }

        /** Элементы верхнего уровня формы, включая автоматическую командную панель. */
        private static List<FormItem> topLevelItems(Form form)
        {
            List<FormItem> items = new ArrayList<>(form.getItems());
            if (form.getAutoCommandBar() != null)
                items.add(form.getAutoCommandBar());
            return items;
        }

        /** Прямоугольник элемента в координатах эскиза, либо {@code null}. */
        private static Rectangle itemBounds(Object representation, FormItem item)
        {
            Object control = Global.invoke(representation, "getRelatedControl", item); //$NON-NLS-1$
            if (control == null)
                return null;
            Object bounds = Global.invoke(representation, "getBoundsRelativeWysiwygRoot", control); //$NON-NLS-1$
            return bounds instanceof Rectangle rect ? rect : null;
        }

        /** Представление WYSIWYG активной страницы редактора формы, либо {@code null}. */
        private static Object getRepresentation(FormEditorPage page)
        {
            if (page == null)
                return null;
            Object viewer = Global.getField(page, "wysiwygViewer"); //$NON-NLS-1$
            return viewer == null ? null : Global.getField(viewer, "wysiwygRepresentation"); //$NON-NLS-1$
        }

        private static boolean isWysiwyg(Widget widget)
        {
            return widget instanceof Composite
                    && WYSIWYG_CLASS.equals(widget.getClass().getSimpleName());
        }
    }

    // -----------------------------------------------------------------------
    // Правый клик — выбор элемента формы
    // -----------------------------------------------------------------------

    private static void handleMouseDown(Event e)
    {
        if (e.button != 3)
            return;
        if (!(e.widget instanceof Composite))
            return;
        if (!WYSIWYG_CLASS.equals(e.widget.getClass().getSimpleName()))
            return;

        simulateLeftClick((Composite) e.widget, e.x, e.y);
    }

    /**
     * Посылает синтетическую пару MouseDown/MouseUp левой кнопки на виджет,
     * чтобы EDT выбрал элемент формы под курсором до открытия контекстного меню.
     */
    private static void simulateLeftClick(Composite widget, int x, int y)
    {
        if (widget.isDisposed())
            return;

        Event down = new Event();
        down.type   = SWT.MouseDown;
        down.button = 1;
        down.x      = x;
        down.y      = y;
        down.count  = 1;
        widget.notifyListeners(SWT.MouseDown, down);

        Event up = new Event();
        up.type   = SWT.MouseUp;
        up.button = 1;
        up.x      = x;
        up.y      = y;
        up.count  = 1;
        widget.notifyListeners(SWT.MouseUp, up);
    }

    // -----------------------------------------------------------------------
    // Двойной клик — «Свойства» (WYSIWYG и дерево реквизитов)
    // -----------------------------------------------------------------------

    private static void handleMouseDoubleClick(Event e)
    {
        if (e.button != 1)
            return;

        if (e.widget instanceof Tree tree)
        {
            FormEditorPage page = FormEditor.getActiveFormEditorPage();
            Tree attrTree = page != null ? getAttributesTree(page) : null;
            if (page != null && tree == attrTree)
            {
                handleAttributesTreeDoubleClick(e, page, tree);
                return;
            }
        }

        if (!(e.widget instanceof Composite))
            return;
        if (!WYSIWYG_CLASS.equals(e.widget.getClass().getSimpleName()))
            return;

        Display display = e.display;
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(FormEditorHook::runWysiwygDoubleClickActions);
    }

    /**
     * Стандартные реквизиты метаданных — перехват dblclick: «Свойства» без {@code runEditAction}
     * (он подставляет {@code DbViewFieldDef} и очищает property sheet).
     */
    private static void handleAttributesTreeDoubleClick(Event e, FormEditorPage page, Tree tree)
    {
        PropertyInfo selected = getSelectedPropertyInfo(tree);
        if (selected == null || isUserFormAttribute(selected))
            return;

        Object edtSource = resolveEdtPropertySource(selected);
        if (stockEdtHandlesPropertyDoubleClick(edtSource))
            return;

        e.doit = false;
        Display display = e.display;
        if (display == null || display.isDisposed())
            return;
        FormEditorPage pageFinal = page;
        PropertyInfo selectedFinal = selected;
        display.asyncExec(() -> runMetadataPropertyDoubleClickActions(pageFinal, selectedFinal));
    }

    /**
     * «Свойства» для поля метаданных: {@link StandardAttributeProxy} в штатный
     * {@code setSelectionAndNavigateToProperties} (без {@code OpenHelper}).
     */
    private static void runMetadataPropertyDoubleClickActions(
            FormEditorPage page, PropertyInfo selected)
    {
        if (!PlatformUI.isWorkbenchRunning() || page == null || selected == null)
            return;

        EObject metadataTarget = resolveMetadataPropertyEObject(selected);
        if (metadataTarget == null)
            return;

        Object propertySheetTarget = metadataTarget;
        if (metadataTarget instanceof StandardAttribute standardAttribute)
        {
            StandardAttributeProxy proxy = createStandardAttributeProxy(standardAttribute);
            if (proxy == null)
                return;
            propertySheetTarget = proxy;
        }

        Object group = Global.getField(page, "attributeActionsGroup"); //$NON-NLS-1$
        if (group == null)
            return;

        ShowPropertiesHandler.run(page.getSite());
        Global.invokeVoid(group, "setSelectionAndNavigateToProperties", //$NON-NLS-1$
                new StructuredSelection(propertySheetTarget));
    }

    /** Повторяет {@code FormEditorPage.lambda$9}: ShowProperties + Edit. */
    private static void runWysiwygDoubleClickActions()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;

        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        if (page == null)
            return;

        Object groupObj = Global.getField(page, "itemsActionsGroup"); //$NON-NLS-1$
        if (!(groupObj instanceof FormItemActionsGroup group))
            return;

        var provider = page.getSite().getSelectionProvider();
        ISelection selection = provider != null ? provider.getSelection() : null;
        if (selection == null || selection.isEmpty())
            return;

        ShowPropertiesHandler.run(page.getSite());

        IAction edit = group.getEditAction();
        if (edit != null)
            edit.run();
    }

    // -----------------------------------------------------------------------
    // Сортировка подменю «События»
    // -----------------------------------------------------------------------

    private static void handleMenuDetect(Event e)
    {
        if (!(e.widget instanceof Control))
            return;
        Control control = (Control) e.widget;

        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        Tree attrTree = page != null ? getAttributesTree(page) : null;

        if (control instanceof Tree tree)
        {
            Menu swtMenu = tree.getMenu();
            if (swtMenu == null || swtMenu.isDisposed())
                return;
            if (page != null && tree == attrTree)
            {
                hookAttributesMenu(tree, swtMenu);
                return;
            }
            hookEventsOrderOnMenu(swtMenu);
            return;
        }

        if (!isWysiwygControl(control))
            return;

        Menu swtMenu = resolveContextMenu(control);
        if (swtMenu == null)
            return;
        hookEventsOrderOnMenu(swtMenu);
    }

    /**
     * Навешивает listener на корневое SWT-меню: после показа ищем «События»
     * и подключаем сортировку. Меню предпросмотра создаётся заново на каждый
     * {@code mouseDown}, поэтому маркер ставится на конкретный экземпляр {@link Menu}.
     */
    private static void hookEventsOrderOnMenu(Menu swtMenu)
    {
        if (Boolean.TRUE.equals(swtMenu.getData(HOOK_MARKER)))
            return;
        swtMenu.setData(HOOK_MARKER, Boolean.TRUE);

        swtMenu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent me)
            {
                onFormItemsMenuShown((Menu) me.widget);
            }
        });
    }

    /**
     * Корневое меню уже построено — ищем каскад «События» и подключаем сортировку
     * к его JFace MenuManager.
     */
    private static void onFormItemsMenuShown(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return;

        MenuManager eventsMenu = findEventsMenuManager(menu);
        if (eventsMenu != null)
            hookEventsMenuManager(eventsMenu);
    }

    /** {@code WysiwygNativeComposite} или потомок (MenuDetect может прийти с дочернего). */
    private static boolean isWysiwygControl(Control control)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (WYSIWYG_CLASS.equals(c.getClass().getSimpleName()))
                return true;
        }
        return false;
    }

    /** Меню на контроле или ближайшем предке (как ищет SWT при MenuDetect). */
    private static Menu resolveContextMenu(Control control)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            Menu menu = c.getMenu();
            if (menu != null && !menu.isDisposed())
                return menu;
        }
        return null;
    }

    /**
     * Ищет MenuManager подменю «События»: через каскад SWT (если уже создан)
     * или через items корневого JFace MenuManager (submenu часто ленивый).
     */
    private static MenuManager findEventsMenuManager(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed() || (item.getStyle() & SWT.CASCADE) == 0)
                continue;
            if (!isEventsMenuText(item.getText()))
                continue;

            Menu subMenu = item.getMenu();
            if (subMenu != null && !subMenu.isDisposed())
            {
                Object data = subMenu.getData(JFACE_MANAGER_KEY);
                if (data instanceof MenuManager)
                    return (MenuManager) data;
            }
            break;
        }

        Object rootData = menu.getData(JFACE_MANAGER_KEY);
        if (rootData instanceof MenuManager)
            return findEventsSubMenuInManager((MenuManager) rootData);
        return null;
    }

    private static MenuManager findEventsSubMenuInManager(MenuManager root)
    {
        for (IContributionItem item : root.getItems())
        {
            if (!(item instanceof MenuManager))
                continue;
            MenuManager sub = (MenuManager) item;
            if (isEventsMenuText(sub.getMenuText()))
                return sub;
        }
        return null;
    }

    private static void hookEventsMenuManager(MenuManager eventsMenu)
    {
        if (!hookedEventsMenus.add(eventsMenu))
            return;
        eventsMenu.addMenuListener(FormEditorHook::onEventsMenuAboutToShow);
    }

    /** Синхронно, до заполнения SWT-меню: разворачиваем compound и сортируем. */
    private static void onEventsMenuAboutToShow(IMenuManager eventsMenu)
    {
        sortEventItems(eventsMenu);
    }

    // -----------------------------------------------------------------------
    // «Показать в навигаторе» — дерево реквизитов формы
    // -----------------------------------------------------------------------

    private static void hookAttributesMenu(Tree tree, Menu menu)
    {
        if (Boolean.TRUE.equals(menu.getData(ATTRIBUTES_HOOK_MARKER)))
            return;
        menu.setData(ATTRIBUTES_HOOK_MARKER, Boolean.TRUE);

        MenuAdapter listener = new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent me)
            {
                Menu swtMenu = (Menu) me.widget;
                PropertyInfo selected = getSelectedPropertyInfo(tree);
                EObject target = resolveMetadataNavigatorTarget(selected);
                int insertIndex = findMenuInsertIndex(swtMenu, PROPERTIES_MENU_TEXT);

                MenuItem separator = new MenuItem(swtMenu, SWT.SEPARATOR, insertIndex);
                addedItems.add(separator);

                MenuItem showNav = new MenuItem(swtMenu, SWT.PUSH, insertIndex + 1);
                showNav.setText(ITEM_TEXT_SHOW_IN_NAVIGATOR);
                showNav.setToolTipText("Показать объект-владелец в дереве навигатора" //$NON-NLS-1$
                        + Global.pluginSignForTooltip());
                showNav.setEnabled(target != null);
                showNav.addListener(SWT.Selection, ev ->
                        showSelectedMetadataAttributeInNavigator(null));
                addedItems.add(showNav);
            }

            @Override
            public void menuHidden(MenuEvent me)
            {
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                Display display = ((Menu) me.widget).getDisplay();
                display.asyncExec(() ->
                {
                    for (MenuItem item : toDispose)
                    {
                        if (!item.isDisposed())
                            item.dispose();
                    }
                });
            }
        };

        menu.addMenuListener(listener);
        tree.addDisposeListener(ev ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static Tree getAttributesTree(FormEditorPage page)
    {
        if (page == null)
            return null;
        Object viewer = Global.getField(page, "attributesViewer"); //$NON-NLS-1$
        if (viewer == null)
            return null;
        Object treeObj = Global.call(viewer, "getTree"); //$NON-NLS-1$
        return treeObj instanceof Tree ? (Tree) treeObj : null;
    }

    /**
     * Перехват Ctrl+T в контексте реквизитов: блокирует штатный focusNavigator формы,
     * даже если выбран пользовательский реквизит (тогда execute — no-op).
     */
    public static boolean shouldConsumeAttributesShowInNavigatorKey()
    {
        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        if (page == null)
            return false;
        Tree tree = getAttributesTree(page);
        return getSelectedPropertyInfo(tree) != null;
    }

    /** Показать выбранное поле метаданных в навигаторе; {@code editor} — для возврата фокуса. */
    public static void showSelectedMetadataAttributeInNavigator(IEditorPart editor)
    {
        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        Tree tree = page != null ? getAttributesTree(page) : null;
        PropertyInfo selected = tree != null ? getSelectedPropertyInfo(tree) : null;
        EObject target = resolveMetadataNavigatorTarget(selected);
        if (target != null)
            showMetadataInNavigator(target);
    }

    private static PropertyInfo getSelectedPropertyInfo(Tree attributesTree)
    {
        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        if (page == null || attributesTree != getAttributesTree(page))
            return null;

        Object viewer = Global.getField(page, "attributesViewer"); //$NON-NLS-1$
        if (viewer == null)
            return null;

        Object selection = Global.call(viewer, "getStructuredSelection"); //$NON-NLS-1$
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;
        if (structured.size() != 1)
            return null;

        Object element = structured.getFirstElement();
        return element instanceof PropertyInfo ? (PropertyInfo) element : null;
    }

    // -----------------------------------------------------------------------
    // Резолв PropertyInfo → объект метаданных (EDT getSource, затем DataPath)
    // -----------------------------------------------------------------------

    /**
     * Как {@code AttributeActionsGroup.getSource}: первый непустой {@code getSource()}
     * по цепочке родителей.
     */
    private static Object resolveEdtPropertySource(PropertyInfo selected)
    {
        for (PropertyInfo cur = selected; cur != null; cur = parentPropertyInfo(cur))
        {
            Object source = cur.getSource();
            if (source != null)
                return source;
        }
        return null;
    }

    /** {@code MdObject}-владелец для навигатора; {@code null} — пользовательский реквизит или не найдено. */
    private static EObject resolveMetadataNavigatorTarget(PropertyInfo selected)
    {
        if (selected == null || isUserFormAttribute(selected))
            return null;

        Object edtSource = resolveEdtPropertySource(selected);
        if (edtSource instanceof DbViewFieldDef fieldDef)
        {
            EObject mdObject = fieldDef.getMdObject();
            if (mdObject != null)
                return ContentUtil.getActualObject(mdObject);
        }

        EObject metadata = resolveMetadataPropertyEObject(selected);
        if (metadata == null)
            return null;
        return resolveMetadataOwnerForNavigator(metadata);
    }

    /** Поле метаданных → {@link MdObject}-владелец; сам {@link MdObject} — без изменений. */
    private static EObject resolveMetadataOwnerForNavigator(EObject metadata)
    {
        if (metadata instanceof MdObject)
            return metadata;
        MdObject owner = findContainingMdObject(metadata);
        return owner != null ? owner : metadata;
    }

    /**
     * {@code EObject} метаданных: {@code getSource()} как {@code EObject}, затем
     * {@code DbViewFieldDef.getPresentationSource()}/{@code getMdObject()}, затем {@code DataPath}.
     */
    private static EObject resolveMetadataPropertyEObject(PropertyInfo selected)
    {
        if (selected == null || isUserFormAttribute(selected))
            return null;

        Object edtSource = resolveEdtPropertySource(selected);
        if (edtSource instanceof AbstractFormAttribute)
            return null;
        if (edtSource instanceof DbViewFieldDef fieldDef)
        {
            EObject fromDbView = resolveMetadataFromDbViewFieldDef(fieldDef);
            if (fromDbView != null)
                return fromDbView;
            return resolveMetadataFromDataPath(selected);
        }
        if (edtSource instanceof EObject eObject)
            return ContentUtil.getActualObject(eObject);

        return resolveMetadataFromDataPath(selected);
    }

    private static MdObject findContainingMdObject(EObject eObject)
    {
        for (EObject cur = eObject.eContainer(); cur != null; cur = cur.eContainer())
        {
            if (cur instanceof MdObject mdObject)
                return mdObject;
        }
        return null;
    }

    /**
     * Прокси для property sheet EDT ({@code StandardAttributeProxyDescriptor});
     * тот же тип, что редактор метаданных передаёт в «Свойства» для стандартных полей.
     */
    private static StandardAttributeProxy createStandardAttributeProxy(StandardAttribute standardAttribute)
    {
        if (standardAttribute == null)
            return null;

        EObject container = standardAttribute.eContainer();
        StandardAttributeProxy proxy = SAttributeFactory.eINSTANCE.createStandardAttributeProxy();
        proxy.setStandardObject(standardAttribute);
        proxy.setName(standardAttribute.getName());

        String nameRu = standardAttribute.getSynonym().get(ScriptVariant.RUSSIAN.getLiteral());
        if (nameRu != null && !nameRu.isEmpty())
            proxy.setNameRu(nameRu);

        if (container instanceof StandardTabularSectionDescription tabularOwner)
        {
            proxy.setOwner(tabularOwner);
            MdObject context = findContainingMdObject(tabularOwner);
            if (context != null)
                proxy.setContextObject(context);
        }
        else if (container instanceof MdObject mdOwner)
        {
            proxy.setOwner(mdOwner);
            proxy.setContextObject(mdOwner);
        }
        else
        {
            MdObject mdOwner = findContainingMdObject(standardAttribute);
            if (mdOwner == null)
                return null;
            proxy.setOwner(mdOwner);
            proxy.setContextObject(mdOwner);
        }
        return proxy;
    }

    /** {@code DbViewFieldDef} из {@code PropertyInfo.getSource()} → {@code EObject} метаданных. */
    private static EObject resolveMetadataFromDbViewFieldDef(Object edtSource)
    {
        if (!(edtSource instanceof DbViewFieldDef fieldDef))
            return null;

        EObject presentation = fieldDef.getPresentationSource();
        if (presentation != null)
            return ContentUtil.getActualObject(presentation);

        EObject mdObject = fieldDef.getMdObject();
        if (mdObject != null)
            return ContentUtil.getActualObject(mdObject);

        return null;
    }

    /** {@code DbViewFieldDef} — EMF {@code EObject}, но штатный {@code runEditAction} свойства не загружает. */
    private static boolean stockEdtHandlesPropertyDoubleClick(Object edtSource)
    {
        if (edtSource instanceof AbstractFormAttribute || edtSource instanceof DbViewFieldDef)
            return false;
        return edtSource instanceof EObject;
    }

    private static EObject resolveMetadataFromDataPath(PropertyInfo selected)
    {
        if (selected == null || isUserFormAttribute(selected))
            return null;

        PropertyInfo anchor = findNearestObjectTypeAncestor(selected);
        if (anchor == null)
            return null;

        List<PropertyInfo> chain = buildPropertyInfoChain(anchor, selected);
        if (chain.isEmpty())
            return null;

        AbstractDataPath path = selected.getDataPath(ScriptVariant.ENGLISH);
        EObject fromPath = resolveReferredMetadataObject(path, chain);
        return fromPath != null ? ContentUtil.getActualObject(fromPath) : null;
    }

    /** Ближайший родитель с типом объекта (как колонка «Тип» EDT). */
    private static PropertyInfo findNearestObjectTypeAncestor(PropertyInfo selected)
    {
        for (PropertyInfo cur = parentPropertyInfo(selected); cur != null; cur = parentPropertyInfo(cur))
        {
            if (isUserFormAttribute(cur))
                break;
            if (isMetadataObjectTypeNode(cur))
                return cur;
        }
        return null;
    }

    private static boolean isMetadataObjectTypeNode(PropertyInfo info)
    {
        PropertyInfoType type = info.getType();
        if (type == PropertyInfoType.COMMON_DUAL_TYPE_PROPERTY
                || type == PropertyInfoType.COLUMN_DUAL_TYPE_PROPERTY)
            return true;

        TypeDescription valueType = info.getValueType();
        if (valueType == null)
            return false;

        for (TypeItem typeItem : valueType.getTypes())
        {
            if (typeItem == null)
                continue;

            TypeItem resolved = typeItem;
            if (typeItem.eIsProxy() && info.getForm() != null)
                resolved = (TypeItem) EcoreUtil.resolve(typeItem, info.getForm());

            String category = McoreUtil.getTypeCategory(resolved);
            if (category == null)
                continue;
            if (category.endsWith("Object") || "ConstantsSet".equals(category)) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    /** Элемент дерева реквизитов формы. */
    public static boolean isFormAttributeTreeElement(Object element)
    {
        return element instanceof PropertyInfo;
    }

    /**
     * Узел реквизитов формы, чей тип значения подходит под {@code *Ссылка.*}
     * (и английский {@code *Ref.*}: {@code CatalogRef.Номенклатура}).
     */
    public static boolean isFormAttributeReferenceNode(Object element)
    {
        if (!(element instanceof PropertyInfo info))
            return false;
        TypeDescription valueType = info.getValueType();
        if (valueType == null)
            return false;
        for (TypeItem typeItem : valueType.getTypes())
        {
            if (typeItem == null)
                continue;
            TypeItem resolved = typeItem;
            if (typeItem.eIsProxy() && info.getForm() != null)
                resolved = (TypeItem) EcoreUtil.resolve(typeItem, info.getForm());
            String typeName = McoreUtil.getTypeName(resolved);
            if (matchesReferenceTypeMask(typeName))
                return true;
        }
        return false;
    }

    /** Маска {@code *Ссылка.*}; для англоязычных имён типов — {@code *Ref.*}. */
    private static boolean matchesReferenceTypeMask(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
            return false;
            return typeName.contains("Ссылка.") || typeName.contains("Ref."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<PropertyInfo> buildPropertyInfoChain(PropertyInfo anchor, PropertyInfo leaf)
    {
        List<PropertyInfo> fromRoot = new ArrayList<>();
        for (PropertyInfo cur = leaf; cur != null; cur = parentPropertyInfo(cur))
            fromRoot.add(cur);
        Collections.reverse(fromRoot);

        List<PropertyInfo> chain = new ArrayList<>();
        boolean inChain = false;
        for (PropertyInfo node : fromRoot)
        {
            if (node == anchor)
                inChain = true;
            if (inChain)
                chain.add(node);
        }
        if (chain.isEmpty() || chain.get(0) != anchor)
            return List.of();
        return chain;
    }

    private static EObject resolveReferredMetadataObject(AbstractDataPath path, List<PropertyInfo> chain)
    {
        if (path == null || chain == null || chain.isEmpty())
            return null;

        EObject best = null;
        int bestSegmentIdx = -1;
        for (Object ref : path.getObjects())
        {
            if (!(ref instanceof DataPathReferredObject referred))
                continue;
            if (referred.isVirtual() || referred.isIndex())
                continue;

            EObject obj = referred.getObject();
            if (obj == null)
                continue;

            int segmentIdx = referred.getSegmentIdx();
            if (segmentIdx >= bestSegmentIdx)
            {
                bestSegmentIdx = segmentIdx;
                best = obj;
            }
        }
        return best;
    }

    private static boolean isUserFormAttribute(PropertyInfo info)
    {
        return info.getSource() instanceof AbstractFormAttribute;
    }

    private static PropertyInfo parentPropertyInfo(PropertyInfo info)
    {
        AbstractFormDataSourceInfo parent = info.getParent();
        return parent instanceof PropertyInfo ? (PropertyInfo) parent : null;
    }

    private static int findMenuInsertIndex(Menu menu, String beforeText)
    {
        MenuItem[] items = menu.getItems();
        for (int i = 0; i < items.length; i++)
        {
            if (beforeText.equals(stripMnemonics(items[i].getText())))
                return i;
        }
        return items.length;
    }

    /**
     * Явная команда «Показать в навигаторе» (Ctrl+T в дереве реквизитов формы) — единое
     * поведение со всеми остальными местами плагина, см.
     * {@link NavigatorReveal#revealAndActivateIfHidden}. Возврат фокуса в редактор через
     * {@code editorToReactivate} больше не нужен: видимую панель команда и так не активирует.
     */
    private static void showMetadataInNavigator(EObject eObject)
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;

        NavigatorReveal.revealAndActivateIfHidden(eObject);
    }

    // -----------------------------------------------------------------------
    // Переупорядочивание подменю
    // -----------------------------------------------------------------------

    private static void sortEventItems(IMenuManager eventsMenu)
    {
        List<IContributionItem> items = collectExpandedItems(eventsMenu);
        if (items.size() <= 1)
            return;

        List<IContributionItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) ->
        {
            int orderA = getEventOrder(a);
            int orderB = getEventOrder(b);
            if (orderA != orderB)
                return Integer.compare(orderA, orderB);
            return getItemLabel(a).compareToIgnoreCase(getItemLabel(b));
        });

        if (items.equals(sorted))
            return;

        eventsMenu.removeAll();
        for (IContributionItem item : sorted)
            eventsMenu.add(item);
    }

    /** Разворачивает {@link CompoundContributionItem} EDT в плоский список пунктов. */
    private static List<IContributionItem> collectExpandedItems(IMenuManager menu)
    {
        List<IContributionItem> result = new ArrayList<>();
        for (IContributionItem item : menu.getItems())
        {
            if (item == null || !item.isVisible())
                continue;
            if (item instanceof CompoundContributionItem)
            {
                IContributionItem[] sub = invokeGetContributionItems((CompoundContributionItem) item);
                if (sub == null)
                    continue;
                for (IContributionItem child : sub)
                {
                    if (child != null && child.isVisible())
                        result.add(child);
                }
            }
            else
            {
                result.add(item);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    /** {@link CompoundContributionItem#getContributionItems()} — protected. */
    private static IContributionItem[] invokeGetContributionItems(CompoundContributionItem item)
    {
        try
        {
            Method m = CompoundContributionItem.class.getDeclaredMethod("getContributionItems"); //$NON-NLS-1$
            m.setAccessible(true);
            return (IContributionItem[]) m.invoke(item);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static boolean isEventsMenuText(String text)
    {
        return EVENTS_SUBMENU_TEXT.equals(stripMnemonics(text));
    }

    private static String stripMnemonics(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int getEventOrder(IContributionItem item)
    {
        String label = getItemLabel(item);
        if (label.isEmpty())
            return Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : FORM_EVENT_ORDER.entrySet())
            if (label.contains(entry.getKey()))
                return entry.getValue();
        return Integer.MAX_VALUE;
    }

    private static String getItemLabel(IContributionItem item)
    {
        if (item == null)
            return ""; //$NON-NLS-1$

        String eventName = getCommandParameter(item, EDT_EVENT_NAME_PARAM);
        if (!eventName.isEmpty())
            return eventName;

        try
        {
            Class<?> cls = item.getClass();
            while (cls != null && cls != Object.class)
            {
                for (java.lang.reflect.Field f : cls.getDeclaredFields())
                {
                    String name = f.getName();
                    if (!"label".equals(name) && !"text".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    f.setAccessible(true);
                    Object v = f.get(item);
                    if (v instanceof String && !((String) v).isEmpty())
                        return (String) v;
                }
                cls = cls.getSuperclass();
            }
        }
        catch (Exception ignored) {}

        String id = item.getId();
        return id != null ? id : ""; //$NON-NLS-1$
    }

    private static String getCommandParameter(IContributionItem item, String key)
    {
        try
        {
            Class<?> cls = item.getClass();
            while (cls != null && cls != Object.class)
            {
                for (java.lang.reflect.Field f : cls.getDeclaredFields())
                {
                    if (!"parameters".equals(f.getName()) //$NON-NLS-1$
                            && !"commandParameterMap".equals(f.getName())) //$NON-NLS-1$
                        continue;
                    f.setAccessible(true);
                    Object v = f.get(item);
                    if (!(v instanceof Map))
                        continue;
                    Object value = ((Map<?, ?>) v).get(key);
                    if (value instanceof String && !((String) value).isEmpty())
                        return (String) value;
                }
                cls = cls.getSuperclass();
            }
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    private static Map<String, Integer> buildOrderMap(String... names)
    {
        Map<String, Integer> map = new HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++)
            map.put(names[i], i);
        return map;
    }

    // -----------------------------------------------------------------------
    // Число элементов в заголовках вкладок формы (TabCounts)
    // -----------------------------------------------------------------------

    /**
     * Дописывает в заголовки вкладок правой части редактора формы число строк списка —
     * «Реквизиты 7», «Команды 3», «Параметры 1» — как {@link MdEditorListTabCountHook}
     * делает это в редакторе объекта метаданных.
     *
     * <p>Числа берутся из модели формы ({@code attributes}, {@code formCommands},
     * {@code parameters}), а не из виджетов: заголовок верен и до первого открытия вкладки,
     * когда её список ещё не создан. Для «Команд» это команды формы (первая вложенная
     * вкладка); стандартные и глобальные команды принадлежат не форме, а конфигурации,
     * и в число не входят.
     *
     * <p>Вкладки ищутся не по заголовкам (они локализуются), а от контролов виджетов
     * {@code attributesViewer} / {@code formCommandsViewer} / {@code parametersViewer}:
     * композит вкладки хранит свой {@link CTabItem} в {@code setData("tabItem")}
     * ({@code FormEditorPage.createTab}). У таблицы команд формы таких предков два —
     * вложенная вкладка «Команды формы» и внешняя «Команды», — поэтому нужный отбирается
     * по полосе вкладок реквизитов.
     *
     * <p>Пересчёт — на {@link SWT#Paint} полосы вкладок (переключение вкладки, перерисовка
     * редактора) и самих списков (добавление/удаление строки в видимом списке).
     * {@code setText} вызывается только при изменении текста, поэтому цикла перерисовки нет.
     */
    private static final class TabCounts
    {
        private static final String KEY_BASE_TITLE = "tormozit.formTabCount.base"; //$NON-NLS-1$

        private static final String KEY_HOOKED = "tormozit.formTabCount.hooked"; //$NON-NLS-1$

        /** Суффикс « N», дописанный нами ранее. */
        private static final Pattern COUNT_SUFFIX = Pattern.compile(" \\d+$"); //$NON-NLS-1$

        private static final int RETRY_DELAY_MS = 200;

        private static final int MAX_ATTEMPTS = 100;

        static void install()
        {
            trackFormEditors(editor -> attach(editor, 0));
        }

        private static void attach(FormEditor editor, int attempt)
        {
            try
            {
                FormEditorPage page = findFormPage(editor);
                Control attributes = formViewerControl(page, "attributesViewer"); //$NON-NLS-1$
                Control commands = formViewerControl(page, "formCommandsViewer"); //$NON-NLS-1$
                Control parameters = formViewerControl(page, "parametersViewer"); //$NON-NLS-1$
                CTabItem attributesTab = formOwnerTab(attributes, null);
                CTabFolder folder = attributesTab != null ? attributesTab.getParent() : null;
                CTabItem commandsTab = formOwnerTab(commands, folder);
                CTabItem parametersTab = formOwnerTab(parameters, folder);

                if (folder == null || folder.isDisposed() || commandsTab == null || parametersTab == null)
                {
                    scheduleRetry(editor, attempt);
                    return;
                }
                if (folder.getData(KEY_HOOKED) != null)
                    return;
                folder.setData(KEY_HOOKED, Boolean.TRUE);

                Runnable refresh =
                        () -> refresh(page, attributesTab, commandsTab, parametersTab);
                folder.addListener(SWT.Paint, event -> refresh.run());
                for (Control list : new Control[] { attributes, commands, parameters })
                    list.addListener(SWT.Paint, event -> refresh.run());
                refresh.run();
            }
            catch (Exception e)
            {
                Global.logError("FormEditorHook.TabCounts", "attach", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static void scheduleRetry(FormEditor editor, int attempt)
        {
            if (attempt >= MAX_ATTEMPTS || editor.getSite() == null)
                return;
            Display.getDefault().timerExec(RETRY_DELAY_MS, () -> attach(editor, attempt + 1));
        }

        private static void refresh(FormEditorPage page, CTabItem attributesTab, CTabItem commandsTab,
                CTabItem parametersTab)
        {
            Form form;
            try
            {
                form = page != null ? page.getModel() : null;
            }
            catch (RuntimeException e)
            {
                return;
            }
            if (form == null || form.eIsProxy())
                return;
            try
            {
                applyCount(attributesTab, form.getAttributes().size());
                applyCount(commandsTab, form.getFormCommands().size());
                applyCount(parametersTab, form.getParameters().size());
            }
            catch (RuntimeException ignored)
            {
                // модель может быть недоступна во время перестроения редактора
            }
        }

        private static void applyCount(CTabItem item, int count)
        {
            if (item == null || item.isDisposed())
                return;
            String base = item.getData(KEY_BASE_TITLE) instanceof String stored ? stored
                    : stripCount(item.getText());
            if (base == null || base.isBlank())
                return;
            item.setData(KEY_BASE_TITLE, base);
            String text = base + " " + count; //$NON-NLS-1$
            if (!text.equals(item.getText()))
                item.setText(text);
        }

        private static String stripCount(String title)
        {
            if (title == null)
                return null;
            Matcher matcher = COUNT_SUFFIX.matcher(title);
            return matcher.find() ? title.substring(0, matcher.start()) : title;
        }

        private TabCounts()
        {
        }
    }

    // -----------------------------------------------------------------------
    // Колонки дерева элементов формы (ItemsTree)
    // -----------------------------------------------------------------------

    /**
     * Дополняет дерево элементов формы тем, чего в нём нет штатно:
     *
     * <ol>
     *   <li><b>Вид группы в подписи.</b> Для групп, вид которых не очевиден по значку,
     *       к имени дописывается вид в скобках — «Страницы», «Страница», «Командная панель»,
     *       «Всплывающее меню» и т.п. Нейтральные виды («Обычная», «Группа кнопок»,
     *       «Группа колонок») не дописываются.
     *
     *   <li><b>Колонка «Обработчики»</b> — число непустых обработчиков событий элемента.
     *       Двойной клик активирует поле первого обработчика в панели «Свойства».
     *
     *   <li><b>Колонка «Условное оформление»</b> — число включённых элементов условного
     *       оформления формы, ссылающихся на элемент в «Оформляемых полях».
     *
     *   <li><b>Колонки «Невидимость» и «ТолькоПросмотр»</b> — эффективные значения, с учётом
     *       родительских групп. Двойной клик выделяет элемент, от которого значение
     *       унаследовано; унаследованное значение показано серым.
     *
     *   <li><b>Линии сетки</b> в дереве.
     * </ol>
     *
     * <p>Штатно дерево создаётся без колонок, и его {@code ColumnLabelProvider} обслуживает
     * единственный столбец. Первая созданная {@link TreeViewerColumn} становится этим столбцом,
     * поэтому её провайдер — обёртка над штатным ({@link NameLabelProvider}): значок, цвета и
     * подсказка остаются штатными, меняется только текст.
     *
     * <p>Значения колонок зависят от свойств, которые правятся в панели «Свойства», а не в самом
     * дереве, и штатного события «свойство изменилось» у дерева нет. Поэтому значения видимых
     * строк сверяются на {@link SWT#Paint} (не чаще {@link #REFRESH_INTERVAL_MS}) и при
     * расхождении обновляются через {@code viewer.update} — как {@link TabCounts} обновляет
     * заголовки вкладок.
     */
    private static final class ItemsTree
    {
        private static final String KEY_HOOKED = "tormozit.formItemsTree.hooked"; //$NON-NLS-1$

        private static final String KEY_REFRESH_STAMP = "tormozit.formItemsTree.refreshed"; //$NON-NLS-1$

        private static final int RETRY_DELAY_MS = 200;

        private static final int MAX_ATTEMPTS = 100;

        /**
         * Перерисовок много — значения сверяются не чаще этого интервала. Сверка вызывает провайдеры
         * колонок для всех видимых строк, и на каждой отрисовке это заметно тормозило отклик на клик.
         */
        private static final int REFRESH_INTERVAL_MS = 1000;

        private static final int MAX_FOCUS_ATTEMPTS = 40;

        private static final int FOCUS_RETRY_DELAY_MS = 150;

        private static final int COLUMN_NAME = 0;

        private static final int COLUMN_HANDLERS = 1;

        private static final int COLUMN_APPEARANCE = 2;

        private static final int COLUMN_INVISIBLE = 3;

        private static final int COLUMN_READ_ONLY = 4;

        private static final String TITLE_NAME = "Элемент"; //$NON-NLS-1$

        private static final String TITLE_HANDLERS = "Обработчики"; //$NON-NLS-1$

        private static final String TITLE_APPEARANCE = "Условное оформление"; //$NON-NLS-1$

        private static final String TITLE_INVISIBLE = "Невидимость"; //$NON-NLS-1$

        private static final String TITLE_READ_ONLY = "ТолькоПросмотр"; //$NON-NLS-1$

        /**
         * Ширина добавленных колонок по умолчанию — только под флажок/число: место в дереве
         * дороже заголовков, а полное название колонки всегда есть в подсказке. Заданную
         * пользователем ширину запоминает {@link #saveWidths}.
         */
        private static final int WIDTH_NARROW = 28;

        private static final int MIN_WIDTH = 16;

        private static final int WIDTH_NAME_START = 200;

        private static final String SETTINGS_SECTION = "tormozit.formItemsTree"; //$NON-NLS-1$

        /** Ключи ширин колонок в {@link IDialogSettings}, по индексу колонки. */
        private static final String[] WIDTH_KEYS =
            { null, "colWidthHandlers", "colWidthAppearance", "colWidthInvisible", "colWidthReadOnly" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        /** Значки шапок добавленных колонок: {Bundle-SymbolicName, путь в бандле}. */
        private static final String[] ICON_HANDLERS =
            { "com._1c.g5.v8.dt.bsl.ui", "icons/obj16/event_handler.png" }; //$NON-NLS-1$ //$NON-NLS-2$

        private static final String[] ICON_APPEARANCE =
            { "com._1c.g5.v8.dt.dcs.ui", "icons/obj16/Appearance.png" }; //$NON-NLS-1$ //$NON-NLS-2$

        private static final String[] ICON_INVISIBLE =
            { "com._1c.g5.v8.dt.cmi.ui", "icons/obj16/hide_invisible_default.png" }; //$NON-NLS-1$ //$NON-NLS-2$

        private static final String[] ICON_READ_ONLY =
            { "com._1c.g5.v8.dt.eventhandlers.ui", "icons/ovrl/not_editable.png" }; //$NON-NLS-1$ //$NON-NLS-2$

        /** Названия видов групп — из локализации EDT (см. {@link #groupKindName}). */
        private static final EnumLiteralLocalizationProvider GROUP_KIND_NAMES =
            new EnumLiteralLocalizationProvider();

        /** Пометка в колонках «Невидимость» и «ТолькоПросмотр». */
        private static final String FLAG_MARK = "✓"; //$NON-NLS-1$

        static void install()
        {
            trackFormEditors(editor -> attach(editor, 0));
        }

        private static void attach(FormEditor editor, int attempt)
        {
            try
            {
                FormEditorPage page = findFormPage(editor);
                Object viewerObj = page != null ? Global.getField(page, "itemsViewer") : null; //$NON-NLS-1$
                if (!(viewerObj instanceof TreeViewer viewer))
                {
                    logAttach(attempt, page == null ? "страница формы ещё не создана" //$NON-NLS-1$
                        : "itemsViewer ещё не создан"); //$NON-NLS-1$
                    scheduleRetry(editor, attempt);
                    return;
                }
                Tree tree = viewer.getTree();
                if (tree == null || tree.isDisposed())
                {
                    logAttach(attempt, "дерево элементов ещё не создано"); //$NON-NLS-1$
                    scheduleRetry(editor, attempt);
                    return;
                }
                if (tree.getData(KEY_HOOKED) != null)
                    return;
                tree.setData(KEY_HOOKED, Boolean.TRUE);
                createColumns(page, viewer, tree);
                trackerLog("колонки дерева элементов созданы, попытка " + attempt); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.logError("FormEditorHook.ItemsTree", "attach", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static void scheduleRetry(FormEditor editor, int attempt)
        {
            if (attempt >= MAX_ATTEMPTS || editor.getSite() == null)
            {
                if (attempt >= MAX_ATTEMPTS)
                    trackerLog("подключение к дереву элементов брошено на попытке " + attempt); //$NON-NLS-1$
                return;
            }
            Display.getDefault().timerExec(RETRY_DELAY_MS, () -> attach(editor, attempt + 1));
        }

        /** Первая попытка и каждая десятая — иначе журнал забивается ожиданием. */
        private static void logAttach(int attempt, String reason)
        {
            if (attempt == 0 || attempt % 10 == 0)
                trackerLog("попытка " + attempt + ": " + reason); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static void createColumns(FormEditorPage page, TreeViewer viewer, Tree tree)
        {
            if (tree.getColumnCount() > 0)
                return;
            if (!(viewer.getLabelProvider() instanceof ColumnLabelProvider base))
                return;

            tree.setHeaderVisible(true);
            tree.setLinesVisible(true);

            TreeViewerColumn nameColumn = new TreeViewerColumn(viewer, SWT.LEFT);
            assert tree.getColumnCount() - 1 == COLUMN_NAME;
            nameColumn.getColumn().setText(TITLE_NAME);
            nameColumn.getColumn().setWidth(WIDTH_NAME_START);
            nameColumn.setLabelProvider(new NameLabelProvider(base));

            addColumn(viewer, TITLE_HANDLERS, ICON_HANDLERS, COLUMN_HANDLERS, SWT.RIGHT,
                TITLE_HANDLERS + ": число непустых обработчиков событий элемента" //$NON-NLS-1$
                    + " (в строке «Форма» — обработчики самой формы)." //$NON-NLS-1$
                    + " Двойной клик активирует поле первого обработчика в панели «Свойства»." //$NON-NLS-1$
                    + Global.pluginSignForTooltip())
                        .setLabelProvider(new CountLabelProvider(element -> handlerCount(page, element)));

            addColumn(viewer, TITLE_APPEARANCE, ICON_APPEARANCE, COLUMN_APPEARANCE, SWT.RIGHT,
                TITLE_APPEARANCE + ": число элементов условного оформления формы," //$NON-NLS-1$
                    + " у которых элемент указан в «Оформляемых полях»;" //$NON-NLS-1$
                    + " в строке «Форма» — все элементы оформления." //$NON-NLS-1$
                    + " Совпадает с числом в заголовке страницы «Условное оформление»," //$NON-NLS-1$
                    + " которую открывает двойной клик." //$NON-NLS-1$
                    + Global.pluginSignForTooltip())
                        .setLabelProvider(new CountLabelProvider(element -> appearanceCount(page, element)));

            addColumn(viewer, TITLE_INVISIBLE, ICON_INVISIBLE, COLUMN_INVISIBLE, SWT.CENTER,
                TITLE_INVISIBLE + ": галочка, если у самого элемента или у любой родительской" //$NON-NLS-1$
                    + " группы снят флажок «Видимость» (авто-панели не учитываются)." //$NON-NLS-1$
                    + " Унаследованное значение — серой галочкой." //$NON-NLS-1$
                    + " Двойной клик переходит к элементу, от которого значение унаследовано." //$NON-NLS-1$
                    + Global.pluginSignForTooltip())
                        .setLabelProvider(new FlagLabelProvider(ItemsTree::invisibilitySource));

            addColumn(viewer, TITLE_READ_ONLY, ICON_READ_ONLY, COLUMN_READ_ONLY, SWT.CENTER,
                TITLE_READ_ONLY + ": галочка, если «ТолькоПросмотр» установлен у самого элемента" //$NON-NLS-1$
                    + " или у любой родительской группы." //$NON-NLS-1$
                    + " Унаследованное значение — серой галочкой." //$NON-NLS-1$
                    + " Двойной клик переходит к элементу, от которого значение унаследовано." //$NON-NLS-1$
                    + Global.pluginSignForTooltip())
                        .setLabelProvider(new FlagLabelProvider(ItemsTree::readOnlySource));

            tree.addListener(SWT.Paint, event -> refreshVisibleRows(viewer, tree));
            tree.addListener(SWT.MouseDoubleClick, event -> onDoubleClick(page, viewer, tree, event));
            tree.addListener(SWT.Dispose, event -> saveWidths(tree));
            logVisibilityDiagnostics(page);
            // Ширины — общий механизм плагина: колонки занимают всю клиентскую область и
            // переживают ресайз панели (issue #273). Добавленные колонки из перераспределения
            // исключены: их ширину задаёт пользователь и она запоминается, а весь свободный
            // остаток забирает колонка «Элемент».
            ColumnAutoFit.install(tree, null, index -> index != COLUMN_NAME);
            // Клик по ячейке добавленных колонок штатное дерево не считает выбором строки:
            // оно создано без SWT.FULL_SELECTION.
            FormTreeInteraction.install(tree, viewer);
            viewer.refresh();
        }

        /**
         * Добавленная колонка: в шапке только значок — на подпись места нет, а полное название
         * начинает подсказку. Если значок не нашёлся, остаётся текст заголовка.
         */
        private static TreeViewerColumn addColumn(TreeViewer viewer, String title, String[] icon,
            int index, int style, String tooltip)
        {
            TreeViewerColumn column = new TreeViewerColumn(viewer, style);
            Image image = headerIcon(viewer.getTree().getDisplay(), icon);
            column.getColumn().setText(image != null ? "" : title); //$NON-NLS-1$
            column.getColumn().setImage(image);
            column.getColumn().setWidth(savedWidth(index));
            column.getColumn().setToolTipText(TooltipText.wrap(viewer.getTree(), tooltip));
            column.getColumn().setMoveable(false);
            return column;
        }

        /**
         * Значок шапки из бандла EDT: {@code icon[0]} — Bundle-SymbolicName, {@code icon[1]} — путь
         * внутри бандла. Один значок на {@link Display}, освобождается вместе с ним.
         */
        private static Image headerIcon(Display display, String[] icon)
        {
            String key = "tormozit.formItemsTree.icon." + icon[1]; //$NON-NLS-1$
            if (display.getData(key) instanceof Image cached && !cached.isDisposed())
                return cached;
            Bundle bundle = Platform.getBundle(icon[0]);
            URL url = bundle != null ? bundle.getEntry(icon[1]) : null;
            Image image = url != null ? ImageDescriptor.createFromURL(url).createImage(false, display) : null;
            if (image == null)
                return null;
            display.setData(key, image);
            display.disposeExec(() -> {
                if (!image.isDisposed())
                    image.dispose();
            });
            return image;
        }

        /**
         * ВРЕМЕННАЯ диагностика расчёта «Невидимости» (issue 379): по одному разу на открытый
         * редактор пишет в {@code .tmp/temp-logs/form-visible.log} все элементы формы, у которых
         * {@code visible == false}, с их видом, происхождением и владельцем. Нужна, чтобы
         * убедиться на реальных формах, каким элементам модель EDT не записывает «Видимость».
         */
        private static void logVisibilityDiagnostics(FormEditorPage page)
        {
            try
            {
                Form form = page != null ? page.getModel() : null;
                if (form == null || form.eIsProxy())
                    return;
                StringBuilder sb = new StringBuilder("форма ").append(formTitle(form)); //$NON-NLS-1$
                logInvisibleItems(form.getItems(), sb, 0);
                Global.tempLog("form-visible", sb.toString()); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Global.tempLog("form-visible", "ошибка диагностики: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /** Имя формы для диагностики: у {@code Form} своего имени нет — берём владельца. */
        private static String formTitle(Form form)
        {
            EObject owner = form.eContainer();
            Object name = owner != null ? Global.invoke(owner, "getName") : null; //$NON-NLS-1$
            return name != null ? String.valueOf(name) : String.valueOf(form.eResource());
        }

        private static void logInvisibleItems(List<FormItem> items, StringBuilder sb, int depth)
        {
            if (items == null || depth > 16)
                return;
            for (FormItem item : items)
            {
                if (item instanceof Visible visible && !visible.isVisible())
                    sb.append("\n  ").append(item.getName()) //$NON-NLS-1$
                        .append(" [").append(item.eClass().getName()).append(']') //$NON-NLS-1$
                        .append(" origin=").append(item.getOrigin()) //$NON-NLS-1$
                        .append(" userVisible=").append(describeUserVisible(visible)) //$NON-NLS-1$
                        .append(" родитель=").append(parentName(item)); //$NON-NLS-1$
                if (item instanceof FormItemContainer container)
                    logInvisibleItems(container.getItems(), sb, depth + 1);
            }
        }

        private static String describeUserVisible(Visible visible)
        {
            Object userVisible = visible.getUserVisible();
            return userVisible == null ? "null" : String.valueOf(Global.invoke(userVisible, "getCommon")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String parentName(FormItem item)
        {
            EObject parent = item.eContainer();
            return parent instanceof FormItem named ? named.getName() : "Форма"; //$NON-NLS-1$
        }

        // -------------------------------------------------------------------
        // Ширины колонок
        // -------------------------------------------------------------------

        private static IDialogSettings widthSettings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            return section != null ? section : top.addNewSection(SETTINGS_SECTION);
        }

        private static int savedWidth(int index)
        {
            String key = WIDTH_KEYS[index];
            return FormTableColumnState.readWidth(widthSettings(), key, WIDTH_NARROW, MIN_WIDTH);
        }

        /**
         * Ширины запоминаются при закрытии редактора — следующий открытый получит их же.
         * Подгонка ширин добавленные колонки не трогает, поэтому сохраняется именно то, что
         * выставил пользователь.
         */
        private static void saveWidths(Tree tree)
        {
            if (tree == null || tree.getColumnCount() <= COLUMN_READ_ONLY)
                return;
            IDialogSettings settings = widthSettings();
            for (int index = COLUMN_HANDLERS; index <= COLUMN_READ_ONLY; index++)
            {
                int width = tree.getColumn(index).getWidth();
                if (width >= MIN_WIDTH)
                    settings.put(WIDTH_KEYS[index], width);
            }
        }

        // -------------------------------------------------------------------
        // Актуальность значений
        // -------------------------------------------------------------------

        private static void refreshVisibleRows(TreeViewer viewer, Tree tree)
        {
            if (tree.isDisposed() || tree.getColumnCount() <= COLUMN_READ_ONLY)
                return;
            long now = System.currentTimeMillis();
            if (tree.getData(KEY_REFRESH_STAMP) instanceof Long last
                && now - last.longValue() < REFRESH_INTERVAL_MS)
                return;
            tree.setData(KEY_REFRESH_STAMP, Long.valueOf(now));

            List<Object> stale = new ArrayList<>();
            Rectangle area = tree.getClientArea();
            int step = Math.max(tree.getItemHeight(), 1);
            for (int y = area.y + step / 2; y < area.y + area.height; y += step)
            {
                TreeItem row = FormTreeInteraction.rowAt(tree, area.x + 2, y);
                if (row == null || row.isDisposed() || row.getData() == null)
                    continue;
                if (isStale(viewer, row))
                    stale.add(row.getData());
            }
            if (stale.isEmpty())
                return;
            tree.getDisplay().asyncExec(() -> {
                if (tree.isDisposed())
                    return;
                for (Object element : stale)
                    viewer.update(element, null);
            });
        }

        /** Текст колонки на экране разошёлся с текущим значением модели. */
        private static boolean isStale(TreeViewer viewer, TreeItem row)
        {
            Object element = row.getData();
            for (int column = COLUMN_HANDLERS; column <= COLUMN_READ_ONLY; column++)
            {
                if (!(viewer.getLabelProvider(column) instanceof ColumnLabelProvider labelProvider))
                    continue;
                String text = labelProvider.getText(element);
                if (!(text == null ? "" : text).equals(row.getText(column))) //$NON-NLS-1$
                    return true;
                // У колонок-флажков значение — не текст, а значок: сверяем и его.
                if (labelProvider.getImage(element) != row.getImage(column))
                    return true;
            }
            return false;
        }

        // -------------------------------------------------------------------
        // Значения колонок
        // -------------------------------------------------------------------

        /** Элемент формы строки дерева; узлы дерева — {@link Item} модели соответствия. */
        static FormItem domainItem(Object element)
        {
            if (element instanceof Item mappingItem)
            {
                Object domain = mappingItem.getDomain();
                if (domain instanceof FormItem formItem && !formItem.eIsProxy())
                    return formItem;
            }
            return null;
        }

        /**
         * Вид группы для подписи; {@code null} — вид нейтральный или элемент не группа.
         *
         * <p>Название вида берётся из локализации самой EDT
         * ({@code localization/FeatureNames*.properties}, ключ вида
         * {@code ManagedFormGroupType|Popup}) — тем же путём, каким его показывает поле «Вид»
         * панели «Свойства». Свои варианты названий не сочиняются: они разошлись бы с EDT.
         */
        private static String groupKindName(FormItem item)
        {
            if (!(item instanceof FormGroup group))
                return null;
            ManagedFormGroupType type = group.getType();
            if (type == null || isNeutralGroupKind(type))
                return null;
            EEnumLiteral literal =
                FormPackage.Literals.MANAGED_FORM_GROUP_TYPE.getEEnumLiteral(type.getValue());
            if (literal == null)
                return type.getLiteral();
            String name = GROUP_KIND_NAMES.getString(literal);
            return name != null && !name.isBlank() ? name : literal.getName();
        }

        /** Виды, которые в подписи не показываются: они и так очевидны по значку элемента. */
        private static boolean isNeutralGroupKind(ManagedFormGroupType type)
        {
            return type == ManagedFormGroupType.USUAL_GROUP
                || type == ManagedFormGroupType.BUTTON_GROUP
                || type == ManagedFormGroupType.COLUMN_GROUP;
        }

        /** Число непустых обработчиков строки дерева; у корня — обработчики самой формы. */
        private static int handlerCount(FormEditorPage page, Object element)
        {
            int count = 0;
            for (EventHandler handler : handlers(handlerOwner(page, element)))
                if (isFilled(handler))
                    count++;
            return count;
        }

        private static EventHandler firstHandler(FormEditorPage page, Object element)
        {
            for (EventHandler handler : handlers(handlerOwner(page, element)))
                if (isFilled(handler))
                    return handler;
            return null;
        }

        /** Владелец обработчиков строки: элемент формы, а у корня — сама форма. */
        private static EObject handlerOwner(FormEditorPage page, Object element)
        {
            FormItem item = domainItem(element);
            if (item != null)
                return item;
            try
            {
                return isFormRoot(element) && page != null ? page.getModel() : null;
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        /**
         * Обработчики объекта. Их два места: сам объект и его {@code extInfo} — у поля ввода
         * «ПриИзменении» лежит именно в {@code extInfo} ({@code FieldExtInfo}, {@code FormExtInfo}
         * и {@code TableExtInfo} тоже {@code EventHandlerContainer}), поэтому колонка, смотревшая
         * только на сам объект, всегда была пустой.
         */
        private static List<EventHandler> handlers(EObject owner)
        {
            if (owner == null)
                return Collections.emptyList();
            List<EventHandler> handlers = new ArrayList<>();
            addHandlers(owner, handlers);
            addHandlers(Global.invoke(owner, "getExtInfo"), handlers); //$NON-NLS-1$
            return handlers;
        }

        private static void addHandlers(Object owner, List<EventHandler> out)
        {
            if (owner instanceof EventHandlerContainer container)
                out.addAll(container.getHandlers());
        }

        private static boolean isFilled(EventHandler handler)
        {
            return handler != null && handler.getName() != null && !handler.getName().isBlank();
        }

        /**
         * Число элементов условного оформления в строке дерева. Считается ровно тем же правилом,
         * которым страница «Условное оформление» отбирает свой список, поэтому число в текущей
         * строке всегда совпадает с числом в заголовке вкладки: у элемента формы — элементы
         * оформления, ссылающиеся на него в «Оформляемых полях», у корня «Форма» — все элементы
         * оформления (на корне отбора нет).
         */
        private static int appearanceCount(FormEditorPage page, Object element)
        {
            FormItem item = domainItem(element);
            if (item != null)
                return appearanceCount(page, item.getName());
            return isFormRoot(element) ? appearanceCount(page, null) : 0;
        }

        /** @param name имя элемента формы; {@code null} — считать все элементы оформления */
        static int appearanceCount(FormEditorPage page, String name)
        {
            DataCompositionConditionalAppearance appearance = conditionalAppearance(page);
            if (appearance == null)
                return 0;
            try
            {
                return countReferences(appearance, name);
            }
            catch (RuntimeException e)
            {
                return 0;
            }
        }

        static int countReferences(DataCompositionConditionalAppearance appearance, String name)
        {
            if (name == null || name.isBlank())
                return appearance.getItems().size();
            int count = 0;
            for (DataCompositionConditionalAppearanceItem entry : appearance.getItems())
            {
                if (entry != null && referencesField(entry, name))
                    count++;
            }
            return count;
        }

        /** Корневой узел дерева — сама форма. */
        private static boolean isFormRoot(Object element)
        {
            return element != null && Global.invoke(element, "getDomain") instanceof Form; //$NON-NLS-1$
        }

        /**
         * Элемент оформления ссылается на элемент формы в «Оформляемых полях».
         *
         * <p>Флажки использования (у самого элемента оформления и у поля) намеренно не
         * проверяются: выключенный элемент оформления никуда из списка не девается, и в дереве
         * его тоже видно. Иначе число в дереве расходилось бы с содержимым страницы.
         */
        static boolean referencesField(DataCompositionConditionalAppearanceItem entry, String name)
        {
            DataCompositionAppearanceFields fields = entry.getSelection();
            if (fields == null)
                return false;
            for (DataCompositionAppearanceField field : fields.getItems())
            {
                if (field == null)
                    continue;
                DataCompositionField value = field.getField();
                if (value != null && name.equals(value.getValue()))
                    return true;
            }
            return false;
        }

        private static DataCompositionConditionalAppearance conditionalAppearance(FormEditorPage page)
        {
            try
            {
                Form form = page != null ? page.getModel() : null;
                return form != null && !form.eIsProxy() ? form.getConditionalAppearance() : null;
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        /**
         * Ближайший элемент (сам или родитель), из-за которого элемент невидим.
         *
         * <p>Служебные элементы в расчёте не участвуют вовсе — ни за себя, ни за потомков.
         * Их «Видимость» в модель формы не записывается (сами они лежат не в {@code items}, а в
         * отдельных свойствах — {@code autoCommandBar}, {@code additions}, {@code contextMenu}),
         * а незаписанное булево в модели EDT — это {@code false} (см. {@code Form.xcore}:
         * {@code boolean visible} без значения по умолчанию). Из-за этого «Строка поиска»,
         * «Состояние просмотра», «Управление поиском», автокомандные панели и их кнопки
         * показывались невидимыми, хотя на форме они есть; их видимостью управляют свойства
         * владельца (например «Положение строки поиска» у таблицы), а не собственный флажок.
         */
        private static FormItem invisibilitySource(FormItem item)
        {
            for (EObject current = item; current instanceof FormItem candidate;
                current = current.eContainer())
            {
                if (isServiceItem(candidate))
                    continue;
                if (candidate instanceof Visible visible && !visible.isVisible())
                    return candidate;
            }
            return null;
        }

        /**
         * Служебный элемент: его состав и видимость ведёт платформа, а не свойства самого элемента.
         * Это дополнения таблицы ({@link Addition} — строка поиска, состояние просмотра,
         * управление поиском), автокомандные панели, контекстные меню и панели действий.
         */
        private static boolean isServiceItem(FormItem item)
        {
            if (item instanceof Addition || item instanceof AutoCommandBar
                || item instanceof ContextMenu || item instanceof SelectedItemsActionsPanel
                || item instanceof RowActionsPanel)
                return true;
            if (!(item instanceof FormGroup group))
                return false;
            ManagedFormGroupType type = group.getType();
            return type == ManagedFormGroupType.AUTO_COMMAND_BAR
                || type == ManagedFormGroupType.CONTEXT_MENU
                || type == ManagedFormGroupType.NAVIGATOR
                || type == ManagedFormGroupType.SELECTED_ITEMS_ACTIONS_PANEL
                || type == ManagedFormGroupType.ROW_ACTIONS_PANEL;
        }

        /**
         * Ближайший элемент (сам или родитель) с «ТолькоПросмотр». Свойство объявлено не в общем
         * родителе, а отдельно у групп, полей и таблиц — отсюда вызов по имени.
         */
        private static FormItem readOnlySource(FormItem item)
        {
            for (EObject current = item; current instanceof FormItem candidate;
                current = current.eContainer())
            {
                if (isServiceItem(candidate))
                    continue;
                if (Boolean.TRUE.equals(Global.invoke(candidate, "isReadOnly"))) //$NON-NLS-1$
                    return candidate;
            }
            return null;
        }

        // -------------------------------------------------------------------
        // Двойной клик
        // -------------------------------------------------------------------

        private static void onDoubleClick(FormEditorPage page, TreeViewer viewer, Tree tree, Event event)
        {
            if (event.button != 1 || tree.isDisposed())
                return;
            TreeItem row = FormTreeInteraction.rowAt(tree, event.x, event.y);
            if (row == null || row.isDisposed())
                return;
            FormItem item = domainItem(row.getData());
            int column = FormTreeInteraction.columnAtX(tree, event.x);
            if (item == null && column != COLUMN_APPEARANCE && column != COLUMN_HANDLERS
                && column != COLUMN_NAME)
                return;
            switch (column)
            {
                case COLUMN_NAME -> openLikeLabelDoubleClick(tree, row, event);
                case COLUMN_HANDLERS -> focusFirstHandlerField(page, row.getData());
                case COLUMN_APPEARANCE -> AppearancePage.activate(page);
                case COLUMN_INVISIBLE -> revealSource(viewer, item, invisibilitySource(item));
                case COLUMN_READ_ONLY -> revealSource(viewer, item, readOnlySource(item));
                default -> { }
            }
        }

        /**
         * Двойной клик по колонке «Элемент» мимо подписи: дерево создано без
         * {@link SWT#FULL_SELECTION} и такой клик за двойной по элементу не считает — панель
         * «Свойства» не открывалась. Повторяем штатные действия (показать «Свойства» и «Изменить»)
         * сами; строка к этому моменту уже стала текущей (одиночный клик обработан раньше).
         * По самой подписи ничего не делаем — там отрабатывает штатный обработчик дерева.
         */
        private static void openLikeLabelDoubleClick(Tree tree, TreeItem row, Event event)
        {
            if (tree.getItem(new Point(event.x, event.y)) == row)
                return;
            Display display = tree.getDisplay();
            if (display != null && !display.isDisposed())
                display.asyncExec(FormEditorHook::runWysiwygDoubleClickActions);
        }

        /** Выделяет в дереве элемент, от которого унаследовано эффективное значение. */
        private static void revealSource(TreeViewer viewer, FormItem item, FormItem source)
        {
            if (source == null || source == item)
                return;
            Object element = findRow(viewer, viewer.getInput(), source, 0);
            if (element != null)
                viewer.setSelection(new StructuredSelection(element), true);
        }

        private static Object findRow(TreeViewer viewer, Object node, FormItem domain, int depth)
        {
            if (node == null || depth > 32
                || !(viewer.getContentProvider() instanceof ITreeContentProvider content))
                return null;
            for (Object child : content.getChildren(node))
            {
                if (domainItem(child) == domain)
                    return child;
                Object found = findRow(viewer, child, domain, depth + 1);
                if (found != null)
                    return found;
            }
            return null;
        }

        /**
         * Активирует панель «Свойства» и ставит ввод в поле первого непустого обработчика.
         * Поле ищется по подписи (имени события): у всех обработчиков признак модели один и тот
         * же ({@code handlers}), и по нему поля событий друг от друга не отличить.
         */
        private static void focusFirstHandlerField(FormEditorPage page, Object element)
        {
            EventHandler handler = firstHandler(page, element);
            if (handler == null || page == null || page.getSite() == null)
                return;
            List<String> labels = eventLabels(handler);
            if (labels.isEmpty())
                return;
            ShowPropertiesHandler.run(page.getSite());
            scheduleHandlerFocus(page.getSite().getPage(), labels, 0);
        }

        /** Имя события в обоих вариантах написания — панель подписывает поле одним из них. */
        private static List<String> eventLabels(EventHandler handler)
        {
            List<String> labels = new ArrayList<>(2);
            Object event = handler.getEvent();
            if (event == null)
                return labels;
            for (String getter : new String[] { "getNameRu", "getName" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object name = Global.invoke(event, getter);
                if (name instanceof String text && !text.isBlank() && !labels.contains(text))
                    labels.add(text);
            }
            return labels;
        }

        private static void scheduleHandlerFocus(IWorkbenchPage workbenchPage, List<String> labels,
            int attempt)
        {
            if (workbenchPage == null || attempt >= MAX_FOCUS_ATTEMPTS)
                return;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(FOCUS_RETRY_DELAY_MS, () -> {
                if (!tryFocusHandlerField(workbenchPage, labels))
                    scheduleHandlerFocus(workbenchPage, labels, attempt + 1);
            });
        }

        private static boolean tryFocusHandlerField(IWorkbenchPage workbenchPage, List<String> labels)
        {
            IViewPart view = findPropertySheetView(workbenchPage);
            Object sheetPage =
                view != null ? PropertyNameIdentifierHook.resolvePropertySheetPage(view) : null;
            Object scene = sheetPage != null ? Global.invoke(sheetPage, "getScene") : null; //$NON-NLS-1$
            if (scene == null)
                return false;
            for (String label : labels)
            {
                Map.Entry<?, ?> entry = PropertyNameIdentifierHook.findValueViewAfterLabel(scene, label);
                Object nativeControl = entry != null
                    ? Global.invoke(entry.getValue(), "getNativeControl") : null; //$NON-NLS-1$
                if (nativeControl != null && AefFieldFocus.focusNativeControl(nativeControl))
                    return true;
            }
            return false;
        }

        private static IViewPart findPropertySheetView(IWorkbenchPage workbenchPage)
        {
            if (workbenchPage == null)
                return null;
            for (IViewReference reference : workbenchPage.getViewReferences())
            {
                IViewPart view = reference.getView(false);
                if (view != null && PropertyNameIdentifierHook.isPropertySheetView(view))
                    return view;
            }
            return null;
        }

        // -------------------------------------------------------------------
        // Провайдеры колонок
        // -------------------------------------------------------------------

        /**
         * Штатная подпись элемента плюс вид группы в скобках — приглушённым цветом
         * ({@link StyledString#DECORATIONS_STYLER}), чтобы он не сливался с именем.
         *
         * <p>Свой {@link StyledCellLabelProvider} вместо
         * {@code DelegatingStyledCellLabelProvider}: нужен флаг {@code COLORS_ON_SELECTION}
         * (иначе на выделенной строке JFace выбрасывает цвета стилей) и перенос цветов штатного
         * провайдера — он красит, например, автоматически созданные элементы.
         */
        private static final class NameLabelProvider
            extends StyledCellLabelProvider
        {
            private final ColumnLabelProvider base;

            NameLabelProvider(ColumnLabelProvider base)
            {
                super(COLORS_ON_SELECTION);
                this.base = base;
            }

            @Override
            public void update(ViewerCell cell)
            {
                Object element = cell.getElement();
                String text = base.getText(element);
                StyledString styled = new StyledString(text == null ? "" : text); //$NON-NLS-1$
                String kind = groupKindName(domainItem(element));
                if (kind != null)
                    styled.append(" (" + kind + ")", StyledString.DECORATIONS_STYLER); //$NON-NLS-1$ //$NON-NLS-2$
                cell.setText(styled.toString());
                cell.setStyleRanges(styled.getStyleRanges());
                cell.setImage(base.getImage(element));
                cell.setForeground(base.getForeground(element));
                cell.setBackground(base.getBackground(element));
                super.update(cell);
            }

            @Override
            public String getToolTipText(Object element)
            {
                return base.getToolTipText(element);
            }
        }

        /** Число; ноль не показывается — колонка остаётся спокойной. */
        private static final class CountLabelProvider
            extends ColumnLabelProvider
        {
            private final ToIntFunction<Object> counter;

            CountLabelProvider(ToIntFunction<Object> counter)
            {
                this.counter = counter;
            }

            @Override
            public String getText(Object element)
            {
                int count = counter.applyAsInt(element);
                return count > 0 ? String.valueOf(count) : ""; //$NON-NLS-1$
            }
        }

        /** Эффективный признак: «Да» своё — обычным цветом, унаследованное — серым. */
        private static final class FlagLabelProvider
            extends ColumnLabelProvider
        {
            private final Function<FormItem, FormItem> sourceFinder;

            FlagLabelProvider(Function<FormItem, FormItem> sourceFinder)
            {
                this.sourceFinder = sourceFinder;
            }

            @Override
            public String getText(Object element)
            {
                return source(element) == null ? "" : FLAG_MARK; //$NON-NLS-1$
            }

            @Override
            public Color getForeground(Object element)
            {
                FormItem item = domainItem(element);
                FormItem source = source(element);
                Display display = Display.getCurrent();
                if (source == null || source == item || display == null)
                    return null;
                return display.getSystemColor(SWT.COLOR_DARK_GRAY);
            }

            private FormItem source(Object element)
            {
                FormItem item = domainItem(element);
                return item != null ? sourceFinder.apply(item) : null;
            }
        }

        private ItemsTree()
        {
        }
    }

    // -----------------------------------------------------------------------
    // Страница «Условное оформление» редактора формы (AppearancePage)
    // -----------------------------------------------------------------------

    /**
     * Добавляет в правую верхнюю группу вкладок редактора формы страницу
     * «Условное оформление» — тот же редактор, что открывается гиперссылкой «Открыть»
     * у свойства «Условное оформление», но всегда под рукой и с отбором по текущему
     * элементу дерева формы.
     *
     * <p><b>Как это устроено.</b> Штатный редактор — виджет
     * {@code com._1c.g5.v8.dt.dcs.ui.settings.conditional.ConditionalAppearance}; всё, что ему
     * нужно, отдаёт {@code FormConditionalAppearanceSettingsService}
     * (пакет не экспортирован — отсюда вызовы через {@link Global#invoke}). Сервис —
     * <b>одиночка</b>: {@code start} кладёт рабочую копию условного оформления в свой локальный
     * контекст BM под именем {@code @@@FORM.CAS@@@} и падает с {@code AssertionError}, если такой
     * объект уже есть. Поэтому страница держит сервис запущенным только пока она выбрана и
     * редактор активен: как только пользователь уходит в панель «Свойства» (а иначе до
     * гиперссылки «Открыть» не добраться) или на другую вкладку, сервис останавливается и
     * штатный диалог открывается как обычно.
     *
     * <p><b>Сохранение.</b> Виджет правит рабочую копию, а не модель формы. Изменения переносятся
     * в модель тем же способом, что и при нажатии «ОК» в штатном диалоге
     * ({@code FormConditionalAppearanceComponent}): клон рабочей копии либо присоединяется как
     * внешнее свойство формы (если условного оформления ещё не было), либо его элементы
     * замещают элементы имеющегося. Перенос идёт по любому изменению рабочей копии
     * (с задержкой {@link #COMMIT_DELAY_MS}) и при остановке сервиса.
     *
     * <p><b>Отбор.</b> Пока в дереве элементов выбран элемент, в списке видны только элементы
     * оформления, ссылающиеся на него в «Оформляемых полях»; на корне («Форма») отбора нет.
     * При добавлении элемента оформления с наложенным отбором текущий элемент формы сразу
     * попадает в его «Оформляемые поля» — иначе новая строка тут же исчезла бы из отбора.
     *
     * <p><b>Сервис-одиночка.</b> Редактор оформления в EDT один на процесс ({@code @@@FORM.CAS@@@}).
     * При открытии страницы в активном редакторе формы сервис автоматически отбирается у других
     * редакторов форм, где страница УО ещё держала его.
     */
    private static final class AppearancePage
    {
        private static final String KEY_PAGE = "tormozit.formAppearancePage"; //$NON-NLS-1$

        private static final String TAB_TITLE = "Условное оформление"; //$NON-NLS-1$

        /** Значок вкладки — тот же, что у страницы «Условное оформление» конструктора СКД. */
        private static final String TAB_IMAGE = "com._1c.g5.v8.dt.dcs.ui/obj16/Appearance.png"; //$NON-NLS-1$

        /** Пакет не экспортирован — сервис доступен только рефлексией. */
        private static final String SERVICE_CLASS =
            "com._1c.g5.v8.dt.form.ui.aef.swt.views.FormConditionalAppearanceSettingsService"; //$NON-NLS-1$

        /** Имя объекта рабочей копии в BM (константа сервиса) — по нему видно, что он занят. */
        private static final String SETTINGS_FQN = "@@@FORM.CAS@@@"; //$NON-NLS-1$

        /** Подпись свойства формы, чья гиперссылка открывает штатный редактор оформления. */
        private static final String[] APPEARANCE_PROPERTY_LABELS =
            { "Условное оформление", "Conditional appearance" }; //$NON-NLS-1$ //$NON-NLS-2$

        private static final int RETRY_DELAY_MS = 200;

        private static final int MAX_ATTEMPTS = 100;

        /** Задержка переноса правок в модель формы — правки идут пачками. */
        private static final int COMMIT_DELAY_MS = 400;

        /** Все живые страницы УО — чтобы отобрать сервис у неактивного редактора формы. */
        private static final Set<AppearancePage> INSTANCES =
            Collections.newSetFromMap(new WeakHashMap<>());

        /** Штатная команда кнопки «Добавить» в редакторе условного оформления. */
        private static final String ADD_COMMAND =
            "com._1c.g5.v8.dt.dcs.ui.settings.conditional.add"; //$NON-NLS-1$

        private final FormEditorPage page;

        private final CTabFolder folder;

        private Composite host;

        private CTabItem tab;

        private Label message;

        private Composite editor;

        private Object service;

        private Adapter changeAdapter;

        private IExecutionListener addCommandListener;

        /** Защита от двойного срабатывания (тулбар + ICommandService). */
        private long lastAddHandledAtMs;

        private boolean commitScheduled;

        /** Имя выбранного элемента дерева формы; {@code null} — корень, отбора нет. */
        private String filterName;

        /**
         * ВРЕМЕННОЕ безусловное логирование жизненного цикла страницы в
         * {@code .tmp/temp-logs/уо-страница.log}: создание/удаление виджета, причины,
         * состояние сервиса и имя контейнера рабочей копии. Пересоздание виджета — повторяющийся
         * дефект, и разбирать его без записей о том, кто и почему освободил редактор, бесполезно.
         */
        private void log(String text)
        {
            Global.tempLog("уо-страница", text //$NON-NLS-1$
                + " [вкладка=" + (tab != null && !tab.isDisposed() && !folder.isDisposed() //$NON-NLS-1$
                    && folder.getSelection() == tab)
                + ", виджет=" + (editor != null && !editor.isDisposed()) //$NON-NLS-1$
                + ", сервис=" + (service != null) //$NON-NLS-1$
                + "]"); //$NON-NLS-1$
        }

        static void install()
        {
            trackFormEditors(AppearancePage::attach);
        }

        private static void attach(FormEditor editor)
        {
            attach(editor, 0);
        }

        private static void attach(FormEditor editor, int attempt)
        {
            try
            {
                FormEditorPage page = findFormPage(editor);
                Control attributes = formViewerControl(page, "attributesViewer"); //$NON-NLS-1$
                CTabItem attributesTab = formOwnerTab(attributes, null);
                CTabFolder folder = attributesTab != null ? attributesTab.getParent() : null;
                if (folder == null || folder.isDisposed())
                {
                    scheduleRetry(editor, attempt);
                    return;
                }
                if (folder.getData(KEY_PAGE) instanceof AppearancePage existing)
                {
                    existing.refreshState("редактор формы получил событие части"); //$NON-NLS-1$
                    return;
                }
                AppearancePage instance = new AppearancePage(page, folder);
                folder.setData(KEY_PAGE, instance);
                instance.createTab();
            }
            catch (Exception e)
            {
                Global.logError("FormEditorHook.AppearancePage", "attach", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static void scheduleRetry(FormEditor editor, int attempt)
        {
            if (attempt >= MAX_ATTEMPTS || editor.getSite() == null)
                return;
            Display.getDefault().timerExec(RETRY_DELAY_MS, () -> attach(editor, attempt + 1));
        }

        private AppearancePage(FormEditorPage page, CTabFolder folder)
        {
            this.page = page;
            this.folder = folder;
            INSTANCES.add(this);
        }

        // -------------------------------------------------------------------
        // Вкладка и её жизненный цикл
        // -------------------------------------------------------------------

        private void createTab()
        {
            host = new Composite(folder, SWT.NONE);
            host.setLayout(new FillLayout());

            tab = new CTabItem(folder, SWT.NONE);
            tab.setText(TAB_TITLE);
            tab.setImage(UiPlugin.getImage(TAB_IMAGE));
            tab.setControl(host);
            // Штатный FormEditorPage$5 на выборе вкладки читает Supplier из getData()
            // и зовёт get() → StructuredViewer для repositionForSearching. Без него — NPE.
            tab.setData((Supplier<StructuredViewer>) this::viewerForStockTabSelection);
            host.setData(DATA_TAB_ITEM, tab);

            folder.addListener(SWT.Selection, event -> refreshState("выбор вкладки")); //$NON-NLS-1$
            folder.addListener(SWT.Paint, event -> refreshTabTitle());
            host.addDisposeListener(event -> {
                INSTANCES.remove(this);
                release("удаление страницы"); //$NON-NLS-1$
            });
            refreshTabTitle();
            log("создана вкладка страницы"); //$NON-NLS-1$

            hookEditorActivation();
            hookItemsSelection();
            hookStockLinkClick();
        }

        /**
         * Viewer для штатного слушателя выбора вкладки {@code FormEditorPage$5}. Пока редактор
         * оформления ещё не создан — отдаём {@code attributesViewer} той же полосы вкладок
         * (он всегда есть к моменту клика).
         */
        private StructuredViewer viewerForStockTabSelection()
        {
            Viewer own = appearanceViewer();
            if (own instanceof StructuredViewer structured)
            {
                Control control = structured.getControl();
                if (control != null && !control.isDisposed())
                    return structured;
            }
            Object attributes = Global.getField(page, "attributesViewer"); //$NON-NLS-1$
            if (attributes instanceof StructuredViewer structured)
            {
                Control control = structured.getControl();
                if (control != null && !control.isDisposed())
                    return structured;
            }
            // Не должно случаться на живой странице формы; штатный код всё равно упадёт на null.
            return null;
        }

        /**
         * Щелчок по гиперссылке свойства «Условное оформление» в панели «Свойства» освобождает
         * сервис-одиночку ДО того, как щелчок дойдёт до самой гиперссылки: иначе штатный
         * {@code start} упрётся в занятый {@code @@@FORM.CAS@@@} и диалог не откроется. Фильтр
         * {@link Display} получает событие раньше виджета.
         *
         * <p>Проверяется попадание именно в поле этого свойства, а не «щелчок мимо страницы»:
         * иначе виджет пересоздавался бы от любого клика в панели. Обратно страница
         * восстанавливается по активации окна, то есть после закрытия диалога.
         */
        private void hookStockLinkClick()
        {
            Display display = host.getDisplay();
            Listener filter = event -> {
                if (service == null || !(event.widget instanceof Control control)
                    || control.isDisposed())
                    return;
                String property = PropertySheetActivePropertyHook.valuePropertyNameAt(control,
                    control.toDisplay(event.x, event.y));
                if (property == null || !isAppearanceProperty(property))
                    return;
                release("щелчок по гиперссылке штатного редактора оформления"); //$NON-NLS-1$
            };
            display.addFilter(SWT.MouseDown, filter);
            host.addDisposeListener(event -> {
                if (!display.isDisposed())
                    display.removeFilter(SWT.MouseDown, filter);
            });
        }

        private static boolean isAppearanceProperty(String label)
        {
            for (String candidate : APPEARANCE_PROPERTY_LABELS)
            {
                if (candidate.equalsIgnoreCase(label.trim()))
                    return true;
            }
            return false;
        }

        /**
         * Сервис-одиночка отпускается, как только редактор перестаёт быть активной частью:
         * гиперссылка «Открыть» в панели «Свойства» доступна только после ухода из редактора,
         * и к моменту её нажатия сервис уже свободен.
         */
        private void hookEditorActivation()
        {
            IWorkbenchPartSite site = page.getSite();
            IWorkbenchPage workbenchPage = site != null ? site.getPage() : null;
            if (workbenchPage == null)
                return;
            IPartListener2 listener = new IPartListener2()
            {
                @Override public void partActivated(IWorkbenchPartReference ref)
                {
                    refreshState("активирована часть " + ref.getId()); //$NON-NLS-1$
                }
                @Override public void partDeactivated(IWorkbenchPartReference ref)  {}
                @Override public void partHidden(IWorkbenchPartReference ref)       { releaseOwn(ref); }
                @Override public void partClosed(IWorkbenchPartReference ref)       { releaseOwn(ref); }
                @Override public void partOpened(IWorkbenchPartReference ref)       {}
                @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
                @Override public void partVisible(IWorkbenchPartReference ref)      {}
                @Override public void partInputChanged(IWorkbenchPartReference r)   {}

                /** Отпускать редактор оформления — только когда уходит наш собственный редактор. */
                private void releaseOwn(IWorkbenchPartReference ref)
                {
                    IWorkbenchPartSite own = page.getSite();
                    if (own != null && ref != null && ref.getPart(false) == own.getPart())
                        release("редактор формы скрыт или закрыт"); //$NON-NLS-1$
                }
            };
            workbenchPage.addPartListener(listener);
            host.addDisposeListener(event -> workbenchPage.removePartListener(listener));

            // Закрытие штатного диалога возвращает активность окну редактора — по этому событию
            // страница и восстанавливает свой редактор оформления.
            Shell shell = site.getShell();
            if (shell != null && !shell.isDisposed())
            {
                Listener activated =
                    event -> shell.getDisplay().asyncExec(() -> refreshState("активировано окно")); //$NON-NLS-1$
                shell.addListener(SWT.Activate, activated);
                host.addDisposeListener(event -> {
                    if (!shell.isDisposed())
                        shell.removeListener(SWT.Activate, activated);
                });
            }

        }

        private void hookItemsSelection()
        {
            if (!(Global.getField(page, "itemsViewer") instanceof TreeViewer viewer)) //$NON-NLS-1$
                return;
            viewer.addSelectionChangedListener(event -> onElementSelected(event.getStructuredSelection()));
            // Инициализировать отбор из текущего выделения дерева — не ждать следующего щелчка.
            onElementSelected(viewer.getStructuredSelection());
        }

        private void onElementSelected(IStructuredSelection selection)
        {
            Object element = selection != null && selection.size() == 1 ? selection.getFirstElement() : null;
            FormItem item = ItemsTree.domainItem(element);
            String name = item != null ? item.getName() : null;
            if (java.util.Objects.equals(name, filterName))
                return;
            filterName = name == null || name.isBlank() ? null : name;
            refreshFilter();
            refreshTabTitle();
        }

        /**
         * Число строк списка в заголовке вкладки — как в заголовках вкладок «Реквизиты»,
         * «Команды», «Параметры» ({@link TabCounts}). С наложенным отбором это число элементов
         * оформления текущего элемента формы, на корне — все. Считается по модели, а не по
         * виджету: заголовок верен и до первого открытия страницы.
         */
        private void refreshTabTitle()
        {
            if (tab == null || tab.isDisposed())
                return;
            int count = 0;
            try
            {
                // Пока страница открыта, правки живут в рабочей копии — считаем по ней,
                // иначе заголовок отставал бы от списка на задержку переноса правок.
                DataCompositionConditionalAppearance appearance = workingCopy();
                if (appearance == null)
                {
                    Form form = page.getModel();
                    appearance = form != null && !form.eIsProxy() ? form.getConditionalAppearance() : null;
                }
                if (appearance != null && !appearance.eIsProxy())
                    count = ItemsTree.countReferences(appearance, filterName);
            }
            catch (RuntimeException e)
            {
                return;
            }
            String text = TAB_TITLE + " " + count; //$NON-NLS-1$
            if (!text.equals(tab.getText()))
                tab.setText(text);
        }

        /** Страница видна и редактор активен — редактор оформления нужен; иначе он отпускается. */
        private void refreshState(String reason)
        {
            if (host == null || host.isDisposed())
                return;
            boolean active = isActive();
            boolean alive = editor != null && !editor.isDisposed();
            if (active && alive)
            {
                // Провайдер настроек СКД глобальный: штатный диалог по гиперссылке «Открыть»
                // переставляет его на себя, а после закрытия он остаётся указывать на удалённый
                // виджет — тогда у списков в диалогах нашей страницы пропадают доступные поля.
                // Возвращаем указатель на свой виджет при каждой активации; сам виджет живёт.
                if (editor instanceof ConditionalAppearance appearance)
                    DcsUiUtil.setSettingsProvider(appearance);
                return;
            }
            if (!active && !alive)
                return; // состояние уже нужное — ни создавать, ни удалять нечего
            log("refreshState: " + reason + ", нужно показывать=" + active); //$NON-NLS-1$ //$NON-NLS-2$
            if (active)
                ensureEditor();
            else
                release(reason);
        }

        /**
         * Страница должна показывать редактор оформления: её вкладка выбрана <b>и</b> этот
         * редактор формы — активный редактор workbench.
         *
         * <p>Без проверки активного редактора две формы с выбранной вкладкой УО обе считали бы
         * себя активными: при переключении «туда-обратно» слушатели частей гонялись бы за
         * сервисом-одиночкой, и в итоге он оставался у фоновой формы.
         *
         * <p>Смотрим именно {@link IWorkbenchPage#getActiveEditor()}, а не активную часть:
         * фокус в панели «Свойства» не должен отпускать сервис (иначе ломается сценарий со
         * штатной гиперссылкой «Открыть»). Отпуск по гиперссылке — {@link #hookStockLinkClick}.
         */
        private boolean isActive()
        {
            if (folder.isDisposed() || folder.getSelection() != tab
                || host == null || host.isDisposed())
                return false;
            IWorkbenchPartSite site = page.getSite();
            if (site == null)
                return false;
            IWorkbenchPage workbenchPage = site.getPage();
            return workbenchPage != null && workbenchPage.getActiveEditor() == site.getPart();
        }

        // -------------------------------------------------------------------
        // Редактор оформления
        // -------------------------------------------------------------------

        private void ensureEditor()
        {
            if (editor != null && !editor.isDisposed())
                return;
            log("ensureEditor: начало"); //$NON-NLS-1$
            Class<?> serviceClass = serviceClass();
            if (serviceClass == null)
            {
                log("ensureEditor: класс сервиса не найден"); //$NON-NLS-1$
                return;
            }
            // Сервис один на процесс: забираем его у других редакторов форм, где страница УО
            // ещё держала виджет. Штатный диалог по гиперссылке «Открыть» — не наша страница,
            // его не трогаем (тогда isServiceBusy останется true).
            releaseOtherAppearancePages();
            if (isServiceBusy())
            {
                log("ensureEditor: контейнер " + SETTINGS_FQN + " занят"); //$NON-NLS-1$ //$NON-NLS-2$
                showMessage("Редактор условного оформления сейчас открыт в другом окне." //$NON-NLS-1$
                    + " Закройте его, чтобы работать здесь."); //$NON-NLS-1$
                return;
            }
            if (!startService(serviceClass))
            {
                log("ensureEditor: сервис не запустился"); //$NON-NLS-1$
                showMessage("Не удалось открыть редактор условного оформления."); //$NON-NLS-1$
                return;
            }
            clearMessage();
            editor = createAppearanceControl();
            if (editor == null)
            {
                release("виджет не создался"); //$NON-NLS-1$
                showMessage("Не удалось открыть редактор условного оформления."); //$NON-NLS-1$
                return;
            }
            host.layout();
            log("ensureEditor: виджет создан"); //$NON-NLS-1$
            ConditionalAppearanceCellStyle.attach(editor);
            refreshFilter();
            hookDrop();
            hookWorkingCopyChanges();
            hookAddCommand();
            host.getDisplay().asyncExec(this::verifyContent);
        }

        /**
         * Отпускает сервис УО во всех остальных редакторах форм. Правки той страницы
         * успевают уйти в модель ({@link #release} → {@code commitNow}).
         */
        private void releaseOtherAppearancePages()
        {
            for (AppearancePage other : new ArrayList<>(INSTANCES))
            {
                if (other == this || other.service == null)
                    continue;
                log("ensureEditor: отпускаем УО в другом редакторе формы"); //$NON-NLS-1$
                other.release("УО открыт в другом редакторе формы"); //$NON-NLS-1$
                other.showMessage("Редактор условного оформления перенесён в другую форму." //$NON-NLS-1$
                    + " Выберите эту вкладку снова, чтобы вернуть его сюда."); //$NON-NLS-1$
            }
        }

        /** Запись в лог, наполнился ли список: разбирать пустую страницу без этого нечем. */
        private void verifyContent()
        {
            if (editor == null || editor.isDisposed() || service == null)
                return;
            log("проверка содержимого: строк=" + viewerRowCount() //$NON-NLS-1$
                + ", элементов рабочей копии=" + workingCopyItemCount()); //$NON-NLS-1$
        }

        /** Число строк списка условного оформления в виджете. */
        private int viewerRowCount()
        {
            Object viewer = editor != null && !editor.isDisposed()
                ? Global.invoke(editor, "getViewer") : null; //$NON-NLS-1$
            Object table = viewer != null ? Global.invoke(viewer, "getTable") : null; //$NON-NLS-1$
            Object count = table != null ? Global.invoke(table, "getItemCount") : null; //$NON-NLS-1$
            return count instanceof Integer value ? value.intValue() : -1;
        }

        private int workingCopyItemCount()
        {
            DataCompositionConditionalAppearance appearance = workingCopy();
            try
            {
                return appearance != null ? appearance.getItems().size() : -1;
            }
            catch (RuntimeException e)
            {
                return -1;
            }
        }

        private void release(String reason)
        {
            if (service == null)
                return;
            log("release: " + reason); //$NON-NLS-1$
            commitNow();
            unhookAddCommand();
            unhookWorkingCopyChanges();
            disposeEditorWidget();
            editor = null;
            // discard() выполняет «Detach Form DataCompositionSettings» и закрывает контекст BM
            // — без него объект
            // рабочей копии остаётся в модели, и штатный диалог по гиперссылке «Открыть» падает на
            // проверке «Model shouldn't contain object registered with @@@FORM.CAS@@@ fqn».
            // stop() же только обнуляет ссылку на сервис-одиночку и ничего не освобождает.
            Global.invoke(service, "discard"); //$NON-NLS-1$
            Class<?> serviceClass = serviceClass();
            if (serviceClass != null)
                Global.invoke(serviceClass, "stop"); //$NON-NLS-1$
            service = null;
        }

        /**
         * Удаление виджета редактора оформления.
         *
         * <p>{@code TableEx.dispose} освобождает свой {@link DropTarget} уже из слушателя
         * {@code Dispose} контрола: {@code DropTarget.onDispose} вызывает
         * {@code control.removeListener}, а контрол к этому моменту disposed —
         * {@code SWTException: Widget is disposed}. SWT через {@code ExceptionStash} сразу отдаёт
         * её в handler Display («Unhandled event loop exception»), поэтому обычный
         * {@code try/catch} вокруг {@code editor.dispose()} её не глушит. Снимаем
         * {@code DropTarget} сами, пока контролы ещё живы.
         */
        private void disposeEditorWidget()
        {
            if (editor == null || editor.isDisposed())
                return;
            disposeDropTargets(editor);
            try
            {
                editor.dispose();
            }
            catch (SWTException | SWTError e)
            {
                Global.logError("FormEditorHook.AppearancePage", "dispose", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /**
         * Рекурсивно освобождает {@link DropTarget} у контрола и потомков, пока виджеты живы.
         * Иначе штатный {@code TableEx.dispose} падает на {@code removeListener} у disposed-контрола.
         */
        private static void disposeDropTargets(Control root)
        {
            if (root == null || root.isDisposed())
                return;
            if (root instanceof Composite composite)
            {
                Control[] children = composite.getChildren();
                for (int i = children.length - 1; i >= 0; i--)
                    disposeDropTargets(children[i]);
            }
            Object data = root.getData(DND.DROP_TARGET_KEY);
            if (!(data instanceof DropTarget target) || target.isDisposed())
                return;
            try
            {
                target.dispose();
            }
            catch (SWTException | SWTError ignored)
            {
                // контрол уже уходит — дальше ловить нечего
            }
        }

        private Class<?> serviceClass()
        {
            try
            {
                return FormEditorPage.class.getClassLoader().loadClass(SERVICE_CLASS);
            }
            catch (ClassNotFoundException | LinkageError e)
            {
                Global.logError("FormEditorHook.AppearancePage", "serviceClass", e); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }

        /** Рабочая копия уже в модели — сервис занят штатным диалогом (или другой формой). */
        private boolean isServiceBusy()
        {
            IBmModel model = page.getBmModel();
            Object engine = model != null ? Global.invoke(model, "getEngine") : null; //$NON-NLS-1$
            return engine != null && Global.invoke(engine, "getTopObjectByFqn", SETTINGS_FQN) != null; //$NON-NLS-1$
        }

        private boolean startService(Class<?> serviceClass)
        {
            Form form = page.getModel();
            IV8Project v8project = v8project(form);
            if (form == null || v8project == null)
                return false;
            Object mdTypeIndex = edtService("com._1c.g5.v8.dt.md.typeinfo.IMdTypeIndex"); //$NON-NLS-1$
            Object indexManager = edtService("com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager"); //$NON-NLS-1$
            Object presentation = edtService("com._1c.g5.v8.dt.md.ui.presentation.IPresentationService"); //$NON-NLS-1$
            if (mdTypeIndex == null || indexManager == null || presentation == null)
                return false;
            try
            {
                Global.invoke(serviceClass, "start", v8project, form, form.getConditionalAppearance(), //$NON-NLS-1$
                    version(form), languageCode(v8project), page.getMappingController(),
                    mdTypeIndex, indexManager, presentation);
            }
            catch (RuntimeException | AssertionError e)
            {
                Global.logError("FormEditorHook.AppearancePage", "start", e); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            service = Global.invoke(serviceClass, "getActive"); //$NON-NLS-1$
            return service != null;
        }

        private Composite createAppearanceControl()
        {
            if (!(service instanceof IDcsSettingsProvider provider))
                return null;
            Object current = Global.invoke(service, "getCurrentObject"); //$NON-NLS-1$
            if (!(current instanceof EObject modelObject))
                return null;
            ConditionalAppearance appearance = new ConditionalAppearance(host, SWT.NONE, false, provider,
                modelObject, false, DcsAvailableSettingsSourceForSchema.FieldUse.eFormField,
                FormAppearanceParameters.class, !page.isReadOnly());
            // Провайдер настроек СКД — глобальный и должен указывать на САМ виджет, а не на сервис
            // (так делает штатный SwtConditionalAppearanceView). С сервисом в этой роли у списка
            // отваливается редактирование ячеек и не открывается диалог «Оформление».
            DcsUiUtil.setSettingsProvider(appearance);
            return appearance;
        }

        private IV8Project v8project(Form form)
        {
            Object manager = Global.getField(page, "v8projectManager"); //$NON-NLS-1$
            return manager instanceof IV8ProjectManager projectManager && form != null
                ? projectManager.getProject(form) : null;
        }

        private Version version(Form form)
        {
            Object support = Global.getField(page, "versionSupport"); //$NON-NLS-1$
            return support instanceof IRuntimeVersionSupport versionSupport
                ? versionSupport.getRuntimeVersion(form) : null;
        }

        private String languageCode(IV8Project v8project)
        {
            Object manager = Global.getField(page, "languageManager"); //$NON-NLS-1$
            return manager instanceof IEditingLanguageManager languageManager
                ? languageManager.getEditingLanguageCode(v8project.getDtProject()) : null;
        }

        private static Object edtService(String className)
        {
            try
            {
                Class<?> serviceInterface = FormEditorPage.class.getClassLoader().loadClass(className);
                return ServiceAccess.get(serviceInterface);
            }
            catch (ClassNotFoundException | LinkageError e)
            {
                return null;
            }
        }

        // -------------------------------------------------------------------
        // Отбор по текущему элементу формы
        // -------------------------------------------------------------------

        private void refreshFilter()
        {
            Viewer viewer = appearanceViewer();
            if (!(viewer instanceof StructuredViewer structured)
                || structured.getControl() == null || structured.getControl().isDisposed())
                return;
            for (ViewerFilter existing : structured.getFilters())
            {
                if (existing instanceof ElementFilter)
                    structured.removeFilter(existing);
            }
            if (filterName != null)
                structured.addFilter(new ElementFilter(filterName));
            structured.refresh();
        }

        private Viewer appearanceViewer()
        {
            return editor instanceof ConditionalAppearance appearance && !appearance.isDisposed()
                ? appearance.getViewer() : null;
        }

        /** Показывает только элементы оформления, ссылающиеся на выбранный элемент формы. */
        private static final class ElementFilter
            extends ViewerFilter
        {
            private final String name;

            ElementFilter(String name)
            {
                this.name = name;
            }

            @Override
            public boolean select(Viewer viewer, Object parent, Object element)
            {
                if (!(element instanceof DataCompositionConditionalAppearanceItem entry))
                    return true;
                return ItemsTree.referencesField(entry, name);
            }
        }

        // -------------------------------------------------------------------
        // Перенос правок в модель формы
        // -------------------------------------------------------------------

        /**
         * Слушатель рабочей копии: перенос правок в модель формы.
         * Подстановку поля при «Добавить» делает {@link #hookAddCommand} — BM-команда не шлёт
         * EMF-уведомления на адаптеры рабочей копии (проверено логом «уо-добавить»).
         */
        private void hookWorkingCopyChanges()
        {
            DataCompositionConditionalAppearance appearance = workingCopy();
            if (appearance == null)
                return;
            changeAdapter = new EContentAdapter()
            {
                @Override
                public void notifyChanged(Notification notification)
                {
                    super.notifyChanged(notification);
                    if (notification.isTouch())
                        return;
                    // ВРЕМЕННОЕ: какие EMF-события вообще доходят при правках списка.
                    Global.tempLog("уо-добавить", "emf type=" + notification.getEventType() //$NON-NLS-1$ //$NON-NLS-2$
                        + " feature=" + notification.getFeature() //$NON-NLS-1$
                        + " new=" + (notification.getNewValue() != null
                            ? notification.getNewValue().getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
                    scheduleCommit();
                }
            };
            appearance.eAdapters().add(changeAdapter);
        }

        private void unhookWorkingCopyChanges()
        {
            DataCompositionConditionalAppearance appearance = workingCopy();
            if (appearance != null && changeAdapter != null)
                appearance.eAdapters().remove(changeAdapter);
            changeAdapter = null;
        }

        /**
         * Кнопка «Добавить» на тулбаре: {@code CommandAction} зовёт handler напрямую, минуя
         * {@link ICommandService} — поэтому слушатель команд не срабатывает. Вешаем
         * {@link SWT#Selection} на {@link ToolItem} после штатного слушателя: сначала
         * отрабатывает добавление, затем подставляем поле.
         */
        private void hookAddCommand()
        {
            if (!(editor instanceof ConditionalAppearance appearance))
                return;
            if (!(Global.getField(appearance, "manager") instanceof ToolBarManager manager)) //$NON-NLS-1$
                return;
            ToolBar toolBar = manager.getControl();
            if (toolBar == null || toolBar.isDisposed())
                return;
            ToolItem addItem = findAddToolItem(manager);
            if (addItem == null)
            {
                Global.tempLog("уо-добавить", "кнопка add на тулбаре не найдена"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Listener listener = event -> {
                Global.tempLog("уо-добавить", "клик add на тулбаре, filter=" + filterName); //$NON-NLS-1$ //$NON-NLS-2$
                onAddCommandSucceeded();
            };
            addItem.addListener(SWT.Selection, listener);
            addItem.addDisposeListener(event -> {
                if (!addItem.isDisposed())
                    addItem.removeListener(SWT.Selection, listener);
            });
            // Контекстное меню / акселератор — через ICommandService (если команда идёт этим путём).
            if (addCommandListener == null)
            {
                ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
                if (commands != null)
                {
                    addCommandListener = new IExecutionListener()
                    {
                        @Override
                        public void preExecute(String commandId, ExecutionEvent event) {}

                        @Override
                        public void postExecuteSuccess(String commandId, Object returnValue)
                        {
                            if (ADD_COMMAND.equals(commandId))
                                onAddCommandSucceeded();
                        }

                        @Override
                        public void postExecuteFailure(String commandId, ExecutionException exception) {}

                        @Override
                        public void notHandled(String commandId, NotHandledException exception) {}
                    };
                    commands.addExecutionListener(addCommandListener);
                }
            }
            Global.tempLog("уо-добавить", "кнопка add перехвачена, filter=" + filterName); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static ToolItem findAddToolItem(IToolBarManager manager)
        {
            if (!(manager instanceof ToolBarManager toolBarManager))
                return null;
            ToolBar toolBar = toolBarManager.getControl();
            if (toolBar == null || toolBar.isDisposed())
                return null;
            for (ToolItem item : toolBar.getItems())
            {
                if (item.isDisposed())
                    continue;
                Object data = item.getData();
                if (!(data instanceof ActionContributionItem contribution))
                    continue;
                IAction action = contribution.getAction();
                if (action == null)
                    continue;
                Object commandId = Global.getField(action, "commandIdIn"); //$NON-NLS-1$
                if (ADD_COMMAND.equals(commandId))
                    return item;
            }
            // Запасной путь: перебор contribution items по индексу ToolItem
            int index = 0;
            for (IContributionItem contribution : manager.getItems())
            {
                if (contribution instanceof ActionContributionItem actionItem)
                {
                    IAction action = actionItem.getAction();
                    Object commandId = action != null ? Global.getField(action, "commandIdIn") : null; //$NON-NLS-1$
                    if (ADD_COMMAND.equals(commandId) && index < toolBar.getItemCount())
                        return toolBar.getItem(index);
                }
                if (contribution.isVisible())
                    index++;
            }
            return null;
        }

        private void unhookAddCommand()
        {
            if (addCommandListener == null)
                return;
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
                commands.removeExecutionListener(addCommandListener);
            addCommandListener = null;
        }

        private void onAddCommandSucceeded()
        {
            long now = System.currentTimeMillis();
            if (now - lastAddHandledAtMs < 200)
                return;
            lastAddHandledAtMs = now;
            Global.tempLog("уо-добавить", "после add: active=" + isActive() //$NON-NLS-1$ //$NON-NLS-2$
                + ", filter=" + filterName //$NON-NLS-1$
                + ", service=" + (service != null)); //$NON-NLS-1$
            if (!isActive() || filterName == null || service == null)
                return;
            // После штатного Selection: дать BM-команде закончить добавление в список.
            Display.getDefault().asyncExec(this::applyFilterFieldAfterAdd);
        }

        /**
         * Новый элемент оформления при наложенном отборе сразу получает текущий элемент формы
         * в «Оформляемые поля»: иначе он не подходит под отбор и пропадает из списка.
         */
        private void applyFilterFieldAfterAdd()
        {
            try
            {
                String name = filterName;
                if (name == null || service == null || !isActive())
                    return;
                DataCompositionConditionalAppearanceItem target = findNewItemWithoutField(name);
                Global.tempLog("уо-добавить", "подстановка: filter=" + name //$NON-NLS-1$ //$NON-NLS-2$
                    + ", target=" + (target != null) //$NON-NLS-1$
                    + ", wc=" + workingCopyItemCount() //$NON-NLS-1$
                    + ", rows=" + viewerRowCount()); //$NON-NLS-1$
                if (target == null)
                {
                    Global.tempLog("уо-добавить", "нет нового элемента без поля"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                int index = indexOfAppearanceItem(target);
                if (!addAppearanceFieldInService(target, name))
                {
                    Global.tempLog("уо-добавить", "не удалось добавить поле"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                // После BM объект в списке может быть другим экземпляром; штатный BM-refresh
                // редактора ещё и сбрасывает выделение на первую строку — ставим свою строку
                // сразу и ещё раз после асинхронного обновления вьюера.
                DataCompositionConditionalAppearanceItem live = appearanceItemAt(index, name);
                selectAppearanceItem(live);
                // Штатный BM-async слушатель ConditionalAppearance обновляет вьюер чуть позже
                // нашего asyncExec и сбрасывает выделение — повторяем после него.
                Display display = Display.getDefault();
                display.asyncExec(() -> selectAppearanceItem(appearanceItemAt(index, name)));
                display.timerExec(100, () -> selectAppearanceItem(appearanceItemAt(index, name)));
                scheduleCommit();
                Global.tempLog("уо-добавить", "готово, строк=" + viewerRowCount() //$NON-NLS-1$ //$NON-NLS-2$
                    + ", index=" + index //$NON-NLS-1$
                    + ", refs=" + (live != null && ItemsTree.referencesField(live, name))); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Global.tempLogException("уо-добавить", "applyFilterFieldAfterAdd", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private void selectAppearanceItem(DataCompositionConditionalAppearanceItem item)
        {
            Viewer viewer = appearanceViewer();
            if (viewer == null || item == null || viewer.getControl() == null
                || viewer.getControl().isDisposed())
                return;
            viewer.refresh();
            viewer.setSelection(new StructuredSelection(item), true);
        }

        private int indexOfAppearanceItem(DataCompositionConditionalAppearanceItem item)
        {
            DataCompositionConditionalAppearance appearance = workingCopy();
            if (appearance == null || item == null)
                return -1;
            return appearance.getItems().indexOf(item);
        }

        /**
         * Актуальный элемент рабочей копии после BM: сначала по индексу, иначе последний
         * с только что подставленным полем отбора.
         */
        private DataCompositionConditionalAppearanceItem appearanceItemAt(int index, String name)
        {
            DataCompositionConditionalAppearance appearance = workingCopy();
            if (appearance == null)
                return null;
            if (index >= 0 && index < appearance.getItems().size())
            {
                DataCompositionConditionalAppearanceItem at = appearance.getItems().get(index);
                if (at != null && ItemsTree.referencesField(at, name))
                    return at;
            }
            DataCompositionConditionalAppearanceItem last = null;
            for (DataCompositionConditionalAppearanceItem item : appearance.getItems())
            {
                if (item != null && ItemsTree.referencesField(item, name))
                    last = item;
            }
            return last;
        }

        /**
         * Только что добавленный элемент оформления: с конца списка, без текущего поля в
         * «Оформляемых полях», предпочтительно с ещё пустым списком полей.
         */
        private DataCompositionConditionalAppearanceItem findNewItemWithoutField(String name)
        {
            DataCompositionConditionalAppearance appearance = workingCopy();
            if (appearance == null)
                return null;
            DataCompositionConditionalAppearanceItem blank = null;
            DataCompositionConditionalAppearanceItem any = null;
            for (int i = appearance.getItems().size() - 1; i >= 0; i--)
            {
                DataCompositionConditionalAppearanceItem item = appearance.getItems().get(i);
                if (item == null || ItemsTree.referencesField(item, name))
                    continue;
                if (any == null)
                    any = item;
                if (isBlankAppearanceFields(item))
                {
                    blank = item;
                    break;
                }
            }
            return blank != null ? blank : any;
        }

        private static boolean isBlankAppearanceFields(DataCompositionConditionalAppearanceItem item)
        {
            DataCompositionAppearanceFields fields = item.getSelection();
            return fields == null || fields.getItems().isEmpty();
        }

        // -------------------------------------------------------------------
        // Перетаскивание элемента формы на строку оформления
        // -------------------------------------------------------------------

        /**
         * Приём перетаскивания элементов дерева формы на строку списка: элементы попадают в её
         * «Оформляемые поля». У списка уже есть свой {@link DropTarget} (перетаскивание доступных
         * полей), поэтому создавать второй нельзя — SWT это запрещает: к существующему
         * добавляется тип переноса дерева элементов ({@link FormElementTransfer}) и свой
         * слушатель. Имена перетаскиваемых элементов берутся из выделения дерева — оно и есть
         * источник перетаскивания.
         */
        private void hookDrop()
        {
            Viewer viewer = appearanceViewer();
            Control control = viewer != null ? viewer.getControl() : null;
            if (control == null || control.isDisposed())
                return;
            Object existing = control.getData(DND.DROP_TARGET_KEY);
            DropTarget target = existing instanceof DropTarget dropTarget ? dropTarget
                : new DropTarget(control, DND.DROP_COPY | DND.DROP_MOVE);
            target.setTransfer(withFormElementTransfer(target.getTransfer()));
            target.addDropListener(new DropTargetAdapter()
            {
                @Override public void dragEnter(DropTargetEvent event)             { allow(event); }
                @Override public void dragOver(DropTargetEvent event)              { allow(event); }
                @Override public void dragOperationChanged(DropTargetEvent event)  { allow(event); }
                @Override public void dropAccept(DropTargetEvent event)            { allow(event); }
                @Override public void drop(DropTargetEvent event)                  { onDrop(event); }

                private void allow(DropTargetEvent event)
                {
                    if (!isFormElementDrag(event) || draggedElementNames().isEmpty())
                        return;
                    event.detail = DND.DROP_COPY;
                    event.feedback = DND.FEEDBACK_SELECT | DND.FEEDBACK_SCROLL;
                }
            });
        }

        private static Transfer[] withFormElementTransfer(Transfer[] transfers)
        {
            Transfer[] source = transfers != null ? transfers : new Transfer[0];
            for (Transfer transfer : source)
            {
                if (transfer instanceof FormElementTransfer)
                    return source;
            }
            Transfer[] extended = java.util.Arrays.copyOf(source, source.length + 1);
            extended[source.length] = FormElementTransfer.getInstance();
            return extended;
        }

        private static boolean isFormElementDrag(DropTargetEvent event)
        {
            return event.currentDataType != null
                && FormElementTransfer.getInstance().isSupportedType(event.currentDataType);
        }

        private void onDrop(DropTargetEvent event)
        {
            if (!isFormElementDrag(event) || !(appearanceViewer() instanceof ColumnViewer viewer))
                return;
            Control control = viewer.getControl();
            if (control == null || control.isDisposed())
                return;
            ViewerCell cell = viewer.getCell(control.toControl(event.x, event.y));
            Object element = cell != null ? cell.getElement() : null;
            if (!(element instanceof DataCompositionConditionalAppearanceItem entry))
                return;
            boolean added = false;
            for (String name : draggedElementNames())
                added |= addAppearanceField(entry, name);
            if (!added)
                return;
            viewer.refresh();
            scheduleCommit();
        }

        /** Имена выбранных элементов дерева формы — источника перетаскивания. */
        private List<String> draggedElementNames()
        {
            List<String> names = new ArrayList<>();
            if (!(Global.getField(page, "itemsViewer") instanceof TreeViewer viewer)) //$NON-NLS-1$
                return names;
            for (Object element : viewer.getStructuredSelection().toList())
            {
                FormItem item = ItemsTree.domainItem(element);
                String name = item != null ? item.getName() : null;
                if (name != null && !name.isBlank() && !names.contains(name))
                    names.add(name);
            }
            return names;
        }

        /** Добавляет элемент формы в «Оформляемые поля»; {@code false} — он уже там был. */
        private static boolean addAppearanceField(DataCompositionConditionalAppearanceItem entry,
            String name)
        {
            if (entry == null || name == null || ItemsTree.referencesField(entry, name))
                return false;
            DataCompositionAppearanceFields fields = entry.getSelection();
            if (fields == null)
            {
                fields = DcsFactory.eINSTANCE.createDataCompositionAppearanceFields();
                entry.setSelection(fields);
            }
            DataCompositionField field =
                com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
            field.setValue(name);
            DataCompositionAppearanceField appearanceField =
                DcsFactory.eINSTANCE.createDataCompositionAppearanceField();
            appearanceField.setField(field);
            appearanceField.setUse(true);
            fields.getItems().add(appearanceField);
            return true;
        }

        /**
         * То же, что {@link #addAppearanceField}, но внутри editing-контекста сервиса УО:
         * рабочая копия — BM-объект, прямая мутация вне задачи падает или откатывается.
         */
        private boolean addAppearanceFieldInService(DataCompositionConditionalAppearanceItem entry,
            String name)
        {
            if (entry == null || name == null || ItemsTree.referencesField(entry, name))
                return false;
            Object context = Global.invoke(service, "getEditingContext"); //$NON-NLS-1$
            if (!(context instanceof IBmEditingContext editingContext))
            {
                Global.tempLog("уо-добавить", "нет editingContext — прямая запись"); //$NON-NLS-1$ //$NON-NLS-2$
                return addAppearanceField(entry, name);
            }
            try
            {
                Boolean added = editingContext.execute(
                    new AbstractBmTask<Boolean>("Comfort: оформляемое поле УО") //$NON-NLS-1$
                    {
                        @Override
                        public Boolean execute(IBmTransaction transaction, IProgressMonitor monitor)
                        {
                            DataCompositionConditionalAppearanceItem target = entry;
                            try
                            {
                                EObject tx = transaction.toTransactionObject(entry);
                                if (tx instanceof DataCompositionConditionalAppearanceItem item)
                                    target = item;
                            }
                            catch (RuntimeException ignored)
                            {
                                // объект уже из локального контекста
                            }
                            return Boolean.valueOf(addAppearanceField(target, name));
                        }
                    });
                Global.tempLog("уо-добавить", "BM execute result=" + added); //$NON-NLS-1$ //$NON-NLS-2$
                return Boolean.TRUE.equals(added);
            }
            catch (RuntimeException e)
            {
                Global.tempLogException("уо-добавить", "BM addAppearanceField", e); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }

        private void scheduleCommit()
        {
            if (commitScheduled)
                return;
            commitScheduled = true;
            Display.getDefault().timerExec(COMMIT_DELAY_MS, () -> {
                commitScheduled = false;
                commitNow();
                refreshTabTitle();
            });
        }

        private void commitNow()
        {
            try
            {
                DataCompositionConditionalAppearance changed = workingCopy();
                Form form = page.getModel();
                Object context = Global.invoke(page, "getEditingContext"); //$NON-NLS-1$
                if (changed == null || form == null || !(context instanceof IBmEditingContext editingContext))
                    return;
                DataCompositionConditionalAppearance original = form.getConditionalAppearance();
                boolean attach = original == null || original.eIsProxy();
                editingContext.execute(new AbstractBmTask<Void>("Comfort: условное оформление формы") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        DataCompositionConditionalAppearance copy =
                            (DataCompositionConditionalAppearance)EcoreUtil2.cloneWithProxies(changed);
                        if (attach)
                            attachAppearance(transaction, form, copy);
                        else
                            mergeAppearance(transaction, original, copy);
                        return null;
                    }
                });
            }
            catch (RuntimeException e)
            {
                Global.logError("FormEditorHook.AppearancePage", "commit", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /** Условного оформления у формы ещё не было — присоединяем как внешнее свойство. */
        private static void attachAppearance(IBmTransaction transaction, Form form,
            DataCompositionConditionalAppearance copy)
        {
            ITopObjectFqnGenerator generator = ServiceAccess.get(ITopObjectFqnGenerator.class);
            if (generator == null)
                return;
            Form target = (Form)transaction.toTransactionObject(form);
            String fqn = generator.generateExternalPropertyFqn(target,
                FormPackage.Literals.FORM__CONDITIONAL_APPEARANCE);
            transaction.attachTopObject((IBmObject)copy, fqn);
            target.setConditionalAppearance(copy);
        }

        private static void mergeAppearance(IBmTransaction transaction,
            DataCompositionConditionalAppearance original, DataCompositionConditionalAppearance copy)
        {
            DataCompositionConditionalAppearance target =
                (DataCompositionConditionalAppearance)transaction.toTransactionObject(original);
            target.setUserSettingID(copy.getUserSettingID());
            target.setUserSettingPresentation(copy.getUserSettingPresentation());
            target.getItems().clear();
            target.getItems().addAll(copy.getItems());
        }

        private DataCompositionConditionalAppearance workingCopy()
        {
            Object settings = service != null ? Global.invoke(service, "getSettings") : null; //$NON-NLS-1$
            Object appearance = settings != null ? Global.invoke(settings, "getConditionalAppearance") : null; //$NON-NLS-1$
            return appearance instanceof DataCompositionConditionalAppearance value ? value : null;
        }

        // -------------------------------------------------------------------
        // Сообщение вместо редактора
        // -------------------------------------------------------------------

        private void showMessage(String text)
        {
            if (host == null || host.isDisposed())
                return;
            if (message == null || message.isDisposed())
                message = new Label(host, SWT.WRAP);
            message.setText(text);
            host.layout();
        }

        private void clearMessage()
        {
            if (message != null && !message.isDisposed())
                message.dispose();
            message = null;
        }

        /** Выбирает страницу «Условное оформление» (двойной клик по колонке дерева элементов). */
        static void activate(FormEditorPage page)
        {
            Control attributes = formViewerControl(page, "attributesViewer"); //$NON-NLS-1$
            CTabItem attributesTab = formOwnerTab(attributes, null);
            CTabFolder folder = attributesTab != null ? attributesTab.getParent() : null;
            if (folder == null || folder.isDisposed()
                || !(folder.getData(KEY_PAGE) instanceof AppearancePage instance)
                || instance.tab == null || instance.tab.isDisposed())
                return;
            folder.setSelection(instance.tab);
            instance.refreshState("переход из дерева элементов"); //$NON-NLS-1$
        }
    }
}
