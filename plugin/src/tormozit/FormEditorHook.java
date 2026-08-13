package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.AbstractFormDataSourceInfo;
import com._1c.g5.v8.dt.form.model.DataPathReferredObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.model.PropertyInfo.PropertyInfoType;
import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorComponent;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorPage;
import com._1c.g5.v8.dt.form.ui.editor.item.FormItemActionsGroup;
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
}
