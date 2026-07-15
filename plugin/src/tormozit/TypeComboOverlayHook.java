package tormozit;

// import java.io.FileWriter;   // отключено — diag() отключена для релиза
// import java.io.IOException;  // отключено — diag() отключена для релиза
// import java.io.PrintWriter;  // отключено — diag() отключена для релиза
// import java.time.LocalTime;  // отключено — diag() отключена для релиза
// import java.time.format.DateTimeFormatter;  // отключено — diag() отключена для релиза
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Заменяет поле «Тип» в мастерах создания объектов метаданных ({@code DtAefMdNewWizard} и
 * наследники — «Новый реквизит» и т.п.) на собственный оверлей: настоящий SWT {@code Text}
 * (плюс иконка слева) + свой выпадающий {@code Shell}-попап со списком, с фильтрацией/сортировкой
 * по {@link SmartMatcher} (тот же «умный фильтр», что и в Outline/остальном EDT — единообразие
 * поведения и внешнего вида подсветки, см. {@link SmartMatchHighlight}). Раньше здесь была своя
 * посекционная логика {@link SectionMatcher} (разбор по {@code .} с конца) — оставлена в проекте
 * как референс для будущей доработки {@code SmartMatcher} той же секционной семантикой, но в
 * этом поле больше не используется.
 *
 * <p>Предыдущая попытка (см. историю чата/снимки {@code .tmp/chat-snapshots}) пыталась патчить
 * приватное состояние штатного {@code com._1c.g5.lwt.controls.LightCombo}
 * (filteredItemsMap/list/popup) через рефлексию — провалилась: {@code LightPopup} вешает
 * глобальные {@code Display}-фильтры (Escape/мышь), которые штатный путь открытия (клик по
 * стрелке) продолжает независимо дёргать, и рассинхронизация ломала весь мастер. Здесь вместо
 * этого штатный {@code LightCombo} не трогается вообще — поверх него рисуется полностью наш
 * виджет на стандартных SWT API, используя тот же приём наложения настоящего SWT-контрола
 * поверх LWT-отрисовки, что и штатный {@code LightText.createOverlay}/{@code adjustOverlayBounds}
 * (позиционирование через {@code SwtLightComposite.translateRectangleFromControl}).
 *
 * <p>Список типов идёт из {@code ITypeDescriptionModel.getTypes(false)} (реальные объекты типа —
 * нужны для коммита через {@code getSingleTypeItem().set(...)}, тот же приём, что уже
 * используется в {@link NewAttributeNameIdentifierHook#applyIrType}); иконки — из параллельного
 * списка {@code ComboSelectViewModel.items} ({@code ComboItemViewModel.getIcon()}/{@code getText()}),
 * сопоставляются по индексу (оба списка строит один и тот же AEF-компонент в одном порядке).
 */
public class TypeComboOverlayHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.typeComboOverlayPatched"; //$NON-NLS-1$
    private static final String WIZARD_ANCESTOR_CLASS =
        "com._1c.g5.v8.dt.md.ui.wizards.base.aef.DtAefMdNewWizard"; //$NON-NLS-1$
    // Точное имя класса (не просто contains) — иначе под фильтр попадают соседние компоненты
    // того же поля (например, кнопка «...» рядом с ним), у которых eventChannel тоже содержит
    // "TypeDescriptionComponent" в имени внутреннего класса. Подтверждено на практике: контрол
    // 16x16 (не сам комбобокс) ложно совпал по contains().
    private static final String TYPE_COMPONENT_MARKER = "TypeDescriptionComponent$3"; //$NON-NLS-1$
    private static final String LOG_TAG = "TypeComboOverlay"; //$NON-NLS-1$
    private static final int POPUP_VISIBLE_ROWS = 10;
    private static final int ICON_COLUMN_WIDTH = 18;
    private static final int ARROW_COLUMN_WIDTH = 18;
    // Кнопка «...» (открывает диалог выбора сложного/ссылочного типа) — часть того же
    // штатного LightCombo, физически рисуется в правом краю его области. Наш container не
    // должен её перекрывать — оставляем справа нетронутую полосу нативного контрола.
    private static final int RIGHT_RESERVED_WIDTH = 20;

    /** Комбобоксы, на которые уже установлен оверлей — не переустанавливать повторно. */
    private static final Set<Object> ATTACHED = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        // Отдельного флажка для этой доработки больше нет (убран как избыточная сложность —
        // см. историю чата) — теперь она подчиняется общему «Улучшать списки»
        // (ComfortSettings.PREF_REPLACE_LIST_FILTERS), читается один раз тут, при старте EDT.
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
        Display.getDefault().asyncExec(TypeComboOverlayHook::installPropertySheet);
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (!(shell.getData() instanceof IWizardContainer))
                return;
            schedule(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void schedule(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 60;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 15)
                schedule(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object data = shell.getData();
        if (!(data instanceof IWizardContainer wizardContainer))
            return false;

        IWizardPage page = wizardContainer.getCurrentPage();
        if (page == null)
            return false;

        IWizard wizard = page.getWizard();
        if (!isAttributeWizard(wizard))
        {
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }

        shell.setData(PATCHED_KEY, Boolean.TRUE);

        diag("tryPatch: shell=" + System.identityHashCode(shell) //$NON-NLS-1$
            + " wizard=" + System.identityHashCode(wizard) + " " + wizard.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            patchTypeCombo(page, wizard, false);
        }
        catch (Exception e)
        {
            diag("patchTypeCombo: исключение " + e); //$NON-NLS-1$
            Global.logError(LOG_TAG, "patchTypeCombo", e); //$NON-NLS-1$
        }
        return true;
    }

    private static boolean isAttributeWizard(IWizard wizard)
    {
        if (wizard == null)
            return false;
        for (Class<?> c = wizard.getClass(); c != null; c = c.getSuperclass())
        {
            if (WIZARD_ANCESTOR_CLASS.equals(c.getName()))
                return true;
        }
        return false;
    }

    private static void patchTypeCombo(IWizardPage page, IWizard wizard, boolean focusAfterCreate)
    {
        Object scene = Global.getField(page, "scene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.getField(scene, "renderer") : null; //$NON-NLS-1$
        Object viewModelToViewObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(viewModelToViewObj instanceof Map<?, ?> viewModelToView))
            return;

        Object typeModel = Global.getField(wizard, "model"); //$NON-NLS-1$
        if (typeModel == null)
            return;

        for (Map.Entry<?, ?> entry : viewModelToView.entrySet())
        {
            Object viewModel = entry.getKey();
            Object eventChannel = Global.getField(viewModel, "eventChannel"); //$NON-NLS-1$
            String eventChannelClass = eventChannel != null ? eventChannel.getClass().getName() : null;

            // ВРЕМЕННО: логируем ВСЁ, где eventChannel хоть как-то упоминает TypeDescription,
            // а не только то, что проходит наш текущий фильтр — чтобы увидеть, нет ли рядом
            // ещё одного реального совпадающего контрола (визуальный артефакт на скриншотах не
            // объяснился ни просвечиванием нативного combo, ни положением попапа).
            if (eventChannelClass != null && eventChannelClass.contains("TypeDescription")) //$NON-NLS-1$
            {
                Object dbgView = entry.getValue();
                Object dbgNative = dbgView != null ? Global.invoke(dbgView, "getNativeControl") : null; //$NON-NLS-1$
                Object dbgBoundsObj = dbgNative != null ? Global.invoke(dbgNative, "getBounds") : null; //$NON-NLS-1$
                if (dbgBoundsObj == null && dbgNative != null)
                    dbgBoundsObj = Global.getField(dbgNative, "bounds"); //$NON-NLS-1$
                diag("patchTypeCombo: кандидат eventChannel=" + eventChannelClass //$NON-NLS-1$
                    + " nativeControl=" + (dbgNative != null ? dbgNative.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " bounds=" + dbgBoundsObj + " matchesFilter=" //$NON-NLS-1$ //$NON-NLS-2$
                    + eventChannelClass.endsWith(TYPE_COMPONENT_MARKER)); //$NON-NLS-1$
            }

            if (eventChannel == null || !eventChannelClass.endsWith(TYPE_COMPONENT_MARKER))
                continue;

            Object view = entry.getValue();
            Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
            if (nativeControl == null || ATTACHED.contains(nativeControl))
                continue;

            // rediscover — общий с панелью «Свойства» механизм пересоздания оверлея (см.
            // rediscoverOverlay/createOverlay): для мастера это просто повторный вызов самого
            // patchTypeCombo с теми же page/wizard.
            Runnable rediscover = () -> patchTypeCombo(page, wizard, true);
            if (createOverlay(nativeControl, typeModel, viewModel, rediscover, focusAfterCreate) != null)
                ATTACHED.add(nativeControl);
        }
    }

    // === Панель «Свойства»: то же поле «Тип», другое обнаружение/жизненный цикл ===
    //
    // Панель «Свойства» рендерится generic definition-driven компонентом (нет отдельного класса
    // вроде TypeDescriptionComponent$3, по которому его можно опознать, как в мастере) — вместо
    // этого используется уже рабочий паттерн PropertyNameIdentifierHook (строка «Имя» там же):
    // искать в renderer.viewModelToView подпись «Тип» (LabelViewModel) и брать view/viewModel
    // СЛЕДУЮЩЕЙ по порядку вставки записи (см. PropertyNameIdentifierHook.findValueViewAfterLabel).
    //
    // Ключевое отличие от мастера: панель «Свойства» не закрывается — она меняет содержимое при
    // каждом клике по дереву объектов (новый объект → новая строка «Тип» для другого объекта,
    // старая пропадает). PROPERTY_OVERLAYS хранит текущий оверлей для каждой view панели, чтобы
    // корректно его продиспозить при смене объекта, а не плодить дубликаты/утечки.
    private static final String TYPE_PROPERTY_LABEL = "Тип"; //$NON-NLS-1$
    private static final Map<IViewPart, OverlayState> PROPERTY_OVERLAYS = new HashMap<>();

    private static void installPropertySheet()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookPropertySheetWindow(window);
        wb.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookPropertySheetWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
        });
    }

    private static void hookPropertySheetWindow(IWorkbenchWindow window)
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
                if (PropertyNameIdentifierHook.isPropertySheetView(view))
                    schedulePropertyAttach(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)        { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)       { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)     { tryFromRef(ref); }
            // Главный сигнал смены объекта в дереве — панель осталась той же view, но её
            // содержимое (строка «Тип») теперь для другого объекта.
            @Override public void partInputChanged(IWorkbenchPartReference ref)  { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref)  {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)   {}
            @Override public void partHidden(IWorkbenchPartReference ref)        {}

            @Override
            public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (part instanceof IViewPart view)
                    disposePropertyOverlay(view);
            }

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (PropertyNameIdentifierHook.isPropertySheetView(part))
                    schedulePropertyAttach((IViewPart) part, 0);
            }
        });
    }

    private static void schedulePropertyAttach(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            if (tryAttachPropertySheet(view))
                return;
            // Этот цикл — только на случай, когда строку «Тип» вообще ещё не нашли
            // (findValueViewAfterLabel/typeModel вернули null — строки палитры свойств
            // подгружаются лениво при первом открытии панели, тот же приём, что и
            // PropertyNameIdentifierHook.scheduleAttach). ~10с хватает с большим запасом на этот
            // одноразовый стартовый сценарий. Если же nativeControl УЖЕ найден, но createOverlay
            // отклонил его подозрительные bounds — за повтор отвечает не этот таймер, а
            // scheduleRetryOnResize (реальный ControlListener на самом контроле, без гадания
            // интервалом).
            if (attempt < 100)
                schedulePropertyAttach(view, attempt + 1);
        });
    }

    // Контролы, на которые уже повешен одноразовый resize-listener (см. scheduleRetryOnResize) —
    // не даёт плодить по листенеру на каждую неудачную попытку (schedulePropertyAttach продолжает
    // опрашивать параллельно как редкая подстраховка) на ОДНОМ И ТОМ ЖЕ ещё не перестроенном
    // контроле. IdentityHashMap — сравниваем именно по ссылке, не по equals/hashCode.
    private static final Set<Object> PENDING_RESIZE_RETRY =
        Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Вместо того чтобы гадать интервалом (было: подряд наращиваемые таймауты, каждый раз ловившие
     * либо «слишком рано», либо «слишком поздно» — см. историю правок с buttons==null/height>40),
     * подписываемся на РЕАЛЬНОЕ событие: как только AEF действительно закончит перестраивать
     * строку «Тип» (что бы это ни было — переход simple↔reference, повторный выбор того же
     * значения), nativeControl получит настоящий SWT-Resize при релэйауте — и мы переприсоединимся
     * ровно в этот момент, без произвольной паузы.
     */
    private static void scheduleRetryOnResize(IViewPart view, Object nativeControl)
    {
        if (!PENDING_RESIZE_RETRY.add(nativeControl))
            return; // уже ждём resize именно этого контрола — второй листенер не нужен
        ControlListener[] holder = new ControlListener[1];
        holder[0] = new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                Global.invokeVoid(nativeControl, "removeControlListener", holder[0]); //$NON-NLS-1$
                PENDING_RESIZE_RETRY.remove(nativeControl);
                diag("scheduleRetryOnResize: controlResized — повторная попытка присоединения"); //$NON-NLS-1$
                tryAttachPropertySheet(view);
            }
        };
        if (!Global.invokeVoid(nativeControl, "addControlListener", holder[0])) //$NON-NLS-1$
        {
            // nativeControl не поддерживает addControlListener (не Control?) — резервируем
            // только обычный поллинг (schedulePropertyAttach), листенер тут ни при чём.
            PENDING_RESIZE_RETRY.remove(nativeControl);
        }
    }

    private static boolean tryAttachPropertySheet(IViewPart view)
    {
        Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        if (page == null)
        {
            diag("tryAttachPropertySheet: page==null для view=" + view); //$NON-NLS-1$
            return false;
        }

        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (scene == null)
        {
            diag("tryAttachPropertySheet: scene==null"); //$NON-NLS-1$
            return false;
        }

        Map.Entry<?, ?> typeEditorEntry = PropertyNameIdentifierHook.findValueViewAfterLabel(scene, TYPE_PROPERTY_LABEL);
        if (typeEditorEntry == null)
        {
            diag("tryAttachPropertySheet: findValueViewAfterLabel вернул null (строка «Тип» не найдена " //$NON-NLS-1$
                + "или следующая запись — не редактор)"); //$NON-NLS-1$
            // Лог подтвердил: после выбора типа через диалог «...»/F4 редактор «Тип» пропадает из
            // renderer.viewModelToView надолго, и ни ожидание, ни IScene.refresh() не помогают —
            // карта сама себя не перестраивает. Вместо того чтобы гадать, ЧЕМ подтолкнуть AEF,
            // ловим настоящее событие «диалог закрылся» (см. watchForDialogAndReclaimFocus) и
            // пересобираем оверлей именно в этот момент — см. её правку.
            return false;
        }

        Object viewModel = typeEditorEntry.getKey();
        Object typeView = typeEditorEntry.getValue();
        Object nativeControl = typeView != null ? Global.invoke(typeView, "getNativeControl") : null; //$NON-NLS-1$
        if (nativeControl == null)
        {
            diag("tryAttachPropertySheet: nativeControl==null (typeView=" + describeClass(typeView) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        // ВРЕМЕННАЯ диагностика: два разных heuristics (buttons==null, height>40) уже пытались
        // угадать «это ещё не готовый виджет» и оба ловили ЛЕГИТИМНОЕ, постоянное состояние —
        // сносили оверлей насовсем. Нужно фактически увидеть, ЧТО за объект возвращает
        // findValueViewAfterLabel в момент, когда realBounds получается огромным (см.
        // createOverlay: Rectangle {0, 154, 509, 150}) — сейчас логируем классы viewModel/
        // typeView/nativeControl безусловно, чтобы по следующему логу понять, тот ли это вообще
        // виджет (может, findValueViewAfterLabel в этот момент указывает совсем на другую запись
        // карты viewModelToView, не на реальный комбобокс «Тип»).
        diag("tryAttachPropertySheet: viewModel=" + describeClass(viewModel) //$NON-NLS-1$
            + " typeView=" + describeClass(typeView) //$NON-NLS-1$
            + " nativeControl=" + describeClass(nativeControl)); //$NON-NLS-1$

        // Раньше здесь была проверка "nativeControl.buttons == null -> считать виджет ещё не
        // готовым и уходить в ретрай". Задумывалась как признак промежуточного состояния AEF
        // сразу после смены объекта в дереве (см. историю правок — так исправляли редкое пустое
        // поле). На практике оказалась ложной: лог подтвердил случай, где buttons==null у
        // НАЙДЕННОГО nativeControl остаётся так ПОСТОЯННО (не временно), ретрай молотил все 100
        // попыток (~10с) и полностью сдавался — оверлей переставал появляться вообще, фокус
        // уходил в штатный контрол без каретки. Убрано — лучше редкое кратковременное пустое
        // поле (само лечится через checkExternalChange/EXTERNAL_CHANGE_GRACE_MS), чем полный отказ
        // от подключения.
        OverlayState existing = PROPERTY_OVERLAYS.get(view);
        if (existing != null)
        {
            if (existing.nativeControl == nativeControl && !existing.container.isDisposed())
                return true; // тот же объект/поле — оверлей уже стоит, ничего делать не нужно
            // Другой объект выбран в дереве (или старый nativeControl уже осиротел) — сносим
            // прежний оверлей перед установкой нового, иначе накопится утечка контейнеров/поллеров
            // при активном переключении по дереву (в отличие от мастера — тут это происходит
            // постоянно, а не один раз за диалог).
            disposePropertyOverlay(view);
        }

        if (ATTACHED.contains(nativeControl))
            return true;

        // Была попытка findTypeDescriptionModelForViewModel — искать модель именно того
        // компонента, чей getViewModels() содержит уже найденный viewModel (вместо первой
        // попавшейся ITypeDescriptionModel во всей сцене). Лог опроверг само предположение:
        // getViewModels() компонента, судя по всему, вообще не содержит тот же объект, что лежит
        // ключом в viewModelToView (иначе с чем сверяем — неизвестно) — скоуп-поиск возвращал
        // null АБСОЛЮТНО ВСЕГДА, даже когда viewModel находился верно каждый раз. Это сломало
        // 100% случаев — хуже узкого края-кейса с «Сумма», который эта правка пыталась исправить.
        // Откат на обычный поиск; проблему с «Сумма» (несколько ITypeDescriptionModel в сцене)
        // придётся решать другим способом, когда найдётся надёжный признак связи.
        Object typeModel = PropertyNameIdentifierHook.findTypeDescriptionModel(scene);
        if (typeModel == null)
            return false;

        // rediscoverOverlay() снимает текущий container ДО вызова rediscover и не смотрит на
        // возвращаемое значение (Runnable) — если tryAttachPropertySheet тут же провалится
        // (например, createOverlay отклонит контрол с подозрительными bounds — см. проверки
        // width/height там же), поле осталось бы без оверлея вообще, до следующего случайного
        // триггера. Поэтому на неудаче сами уходим в ретрай (полагаемся в первую очередь на
        // scheduleRetryOnResize ниже — она сработает намного быстрее и по факту события, а не
        // подбором таймаута; schedulePropertyAttach(view, 1) — лишь редкая подстраховка).
        Runnable rediscover = () ->
        {
            if (!tryAttachPropertySheet(view))
                schedulePropertyAttach(view, 1);
        };
        OverlayState state = createOverlay(nativeControl, typeModel, viewModel, rediscover, true);
        if (state == null)
        {
            // Не гадаем интервалом — вешаем одноразовый ControlListener прямо на найденный
            // nativeControl (см. scheduleRetryOnResize) и переприсоединяемся ровно в момент,
            // когда AEF реально закончит перекладывать эту строку (controlResized), а не через
            // произвольную паузу.
            scheduleRetryOnResize(view, nativeControl);
            return false;
        }

        ATTACHED.add(nativeControl);
        PROPERTY_OVERLAYS.put(view, state);
        diag("tryAttachPropertySheet: оверлей установлен для view=" + view); //$NON-NLS-1$
        return true;
    }

    private static void disposePropertyOverlay(IViewPart view)
    {
        OverlayState state = PROPERTY_OVERLAYS.remove(view);
        if (state == null)
            return;
        ATTACHED.remove(state.nativeControl);
        if (!state.container.isDisposed())
            state.container.dispose();
    }

    // LightText.createOverlay(Composite) помечает созданный оверлей именно этим ключом
    // (см. декомпиляцию .tmp/LightText.javap.txt:911: overlay.setData("com._1c.g5.lwt.lwtOverlay", this))
    // — надёжный маркер вместо попытки найти AEF viewmodel (тот путь дал 0 совпадений на
    // практике: поля «Имя»/«Синоним», видимо, не заведены в том же viewModelToView, что и
    // компонент «Тип», либо их nativeControl — не тот класс).
    private static final String LWT_OVERLAY_DATA_KEY = "com._1c.g5.lwt.lwtOverlay"; //$NON-NLS-1$

    /**
     * Обходной путь для известного бага: подсветка выделения в LWT-текстовом поле-соседе (см.
     * {@code focusGained} на нашем {@code text}) иногда не снимается штатным механизмом
     * ({@code LightText$OverlayFocusListener.focusLost} → {@code disposeOverlayAutomatically()}),
     * если фокус переходит через наше поле, а не напрямую между двумя штатными LWT-полями.
     * Вместо поиска через AEF-модель (не сработало — см. историю правок) ищем НАПРЯМУЮ в дереве
     * SWT-виджетов: декомпиляция подтвердила, что оверлей {@code StyledText} создаётся именно
     * как прямой ребёнок ТОГО ЖЕ {@code hostComposite.getSwtComposite()}, что и наш
     * {@code container} (см. {@code LightText.createOverlay()}: тот же путь через
     * {@code SwtLightComposite.getHostSwtLightComposite(this).getSwtComposite()}), и помечен
     * {@code setData(LWT_OVERLAY_DATA_KEY, ...)}. Просто выставляем каретку в начало (0) у
     * каждого найденного чужого оверлея — тот же эффект, что и штатный
     * {@code LightText.clearSelection()} (см. {@code .tmp/LightText.javap.txt:432}:
     * {@code overlay.setSelection(0)}), но без необходимости находить конкретный экземпляр
     * {@code LightText} через рефлексию.
     */
    private static void clearSiblingTextSelections(OverlayState state)
    {
        if (state.container.isDisposed())
            return;
        // В мастере создания объекта все поля («Имя», «Синоним», «Тип», ...) — прямые дети
        // одного и того же host-composite, поэтому раньше хватало state.container.getParent().
        // В панели «Свойства» поля разложены по нескольким вложенным группам («Основные»,
        // «Использование», ...), каждая — свой SWT Composite, так что прямые «дети» родителя
        // нашего container уже не покрывают соседние поля из ДРУГИХ групп (см. скриншот:
        // подсветка остаётся висеть в «История данных», хотя фокус давно ушёл) — поэтому
        // рекурсивно обходим весь hostShell целиком (это ровно тот же Shell, что и раньше
        // получали бы через getParent() в плоском случае мастера, только теперь ищем на всю
        // глубину, а не в одном уровне).
        Shell host = state.hostShell;
        if (host == null || host.isDisposed())
            return;

        int[] clearedHolder = { 0 };
        clearSiblingTextSelectionsRecursive(host, state.container, clearedHolder);
        int cleared = clearedHolder[0];
        diag("clearSiblingTextSelections: оверлеев StyledText обработано=" + cleared); //$NON-NLS-1$
    }

    private static void clearSiblingTextSelectionsRecursive(Composite parent, Composite ownContainer, int[] cleared)
    {
        for (Control child : parent.getChildren())
        {
            if (child == ownContainer || child.isDisposed())
                continue;
            if (child instanceof StyledText overlayText)
            {
                if (overlayText.getData(LWT_OVERLAY_DATA_KEY) != null)
                {
                    overlayText.setSelection(0);
                    cleared[0]++;
                }
            }
            else if (child instanceof Composite composite)
            {
                clearSiblingTextSelectionsRecursive(composite, ownContainer, cleared);
            }
        }
    }

    /** Пара «LWT host-composite + его SWT-хозяин» — то, что нужно и createOverlay, и retry-логике
     * (scheduleRetryOnResize), чтобы не искать это дважды разными путями. */
    private static final class HostResolution
    {
        final Object hostComposite;
        final Composite parent;

        HostResolution(Object hostComposite, Composite parent)
        {
            this.hostComposite = hostComposite;
            this.parent = parent;
        }
    }

    private static HostResolution resolveHost(Object nativeControl)
    {
        ClassLoader lwtLoader = nativeControl.getClass().getClassLoader();
        Class<?> swtLightCompositeClass;
        try
        {
            swtLightCompositeClass = Class.forName(
                "com._1c.g5.lwt.interop.SwtLightComposite", true, lwtLoader); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            diag("resolveHost: SwtLightComposite class error " + e); //$NON-NLS-1$
            Global.logError(LOG_TAG, "SwtLightComposite class", e); //$NON-NLS-1$
            return null;
        }

        Object hostComposite = Global.invoke(swtLightCompositeClass, "getHostSwtLightComposite", nativeControl); //$NON-NLS-1$
        if (hostComposite == null)
            return null;
        Object parentObj = Global.invoke(hostComposite, "getSwtComposite"); //$NON-NLS-1$
        if (!(parentObj instanceof Composite parent) || parent.isDisposed())
            return null;
        return new HostResolution(hostComposite, parent);
    }

    private static OverlayState createOverlay(Object nativeControl, Object typeModel, Object viewModel,
        Runnable rediscover, boolean focusAfterCreate)
    {
        HostResolution host = resolveHost(nativeControl);
        if (host == null)
            return null;
        Object hostComposite = host.hostComposite;
        Composite parent = host.parent;

        // getBounds() отдаёт позицию/размер контрола в системе координат хоста (см.
        // диагностику: Rectangle {150, 122, 349, 24}); а translateRectangleFromControl(this, rect)
        // ожидает rect в СОБСТВЕННЫХ координатах контрола (0,0 = его левый верхний угол) — сама
        // добавляет его абсолютную позицию (см. байткод adjustOverlayBounds: передаётся
        // new Rectangle(1,1,w-2,h-2), а не (x,y,w,h)).
        Object boundsObj = Global.invoke(nativeControl, "getBounds"); //$NON-NLS-1$
        if (boundsObj == null)
            boundsObj = Global.getField(nativeControl, "bounds"); //$NON-NLS-1$
        Rectangle ownBounds = rectangleOf(boundsObj);
        if (ownBounds == null)
            return null;
        Rectangle localRect = new Rectangle(0, 0, ownBounds.width, ownBounds.height);
        Rectangle realBounds = rectangleOf(Global.invoke(hostComposite, "translateRectangleFromControl", //$NON-NLS-1$
            nativeControl, localRect));
        if (realBounds == null)
            return null;
        if (realBounds.width < 40)
        {
            diag("createOverlay: контрол слишком узкий (" + realBounds //$NON-NLS-1$
                + "), это не поле «Тип» — пропуск"); //$NON-NLS-1$
            return null;
        }
        // Рисовать оверлей поверх такого widget'а НЕДОПУСТИМО (подтверждено пользователем) —
        // растянутый на всю эту область container визуально ломает соседние поля («Представление»
        // и т.п.). Отклоняем и уходим в ретрай (см. schedulePropertyAttach — потолок попыток
        // подняли на порядок, чтобы не сдаваться навсегда за 10с, раз этот widget иногда держится
        // куда дольше одного тика).
        if (realBounds.height > 40)
        {
            diag("createOverlay: контрол слишком высокий (" + realBounds //$NON-NLS-1$
                + "), похоже на переходное состояние AEF — пропуск"); //$NON-NLS-1$
            return null;
        }
        // Резервируем справа полосу под нативную кнопку «...» — не перекрываем её своим
        // container'ом (см. RIGHT_RESERVED_WIDTH).
        realBounds = new Rectangle(realBounds.x, realBounds.y,
            Math.max(20, realBounds.width - RIGHT_RESERVED_WIDTH), realBounds.height);

        List<TypeEntry> entries = loadTypeEntries(typeModel, viewModel);
        if (entries.isEmpty())
        {
            diag("createOverlay: entries.isEmpty() — getTypes(false) пуст"); //$NON-NLS-1$
            return null;
        }

        // ОТКАЧЕНО: пробовали nativeControl.setVisible(false), чтобы не просвечивал под нашим
        // оверлеем — не помогло с визуальной проблемой, зато скрыло встроенную в LightCombo
        // кнопку «...» (открывает диалог выбора сложного типа) — она физически часть того же
        // контрола, а не отдельный сосед. Оставляем nativeControl видимым, полагаемся на
        // z-order (moveAbove) для перекрытия его текстовой части.

        Composite container = new Composite(parent, SWT.BORDER);
        container.setBounds(realBounds);
        container.moveAbove(null); // принудительно на передний план — не даём нативному combo просвечивать
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 1;
        layout.marginHeight = 1;
        layout.horizontalSpacing = 2;
        container.setLayout(layout);

        Label iconLabel = new Label(container, SWT.NONE);
        GridData iconData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        iconData.widthHint = ICON_COLUMN_WIDTH;
        iconLabel.setLayoutData(iconData);

        Text text = new Text(container, SWT.NONE);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        text.setToolTipText(FilterInputBox.HIERARCHICAL_FILTER_TOOLTIP);

        Button arrowButton = new Button(container, SWT.ARROW | SWT.DOWN);
        GridData arrowData = new GridData(SWT.CENTER, SWT.FILL, false, true);
        arrowData.widthHint = ARROW_COLUMN_WIDTH;
        arrowButton.setLayoutData(arrowData);

        // Без этого TAB из text уходил бы сначала на arrowButton (следующий по порядку в
        // GridLayout контейнера), а не сразу к следующему полю формы после нашего «Тип» —
        // стрелка по-прежнему кликабельна мышью, просто не участвует в клавиатурном обходе.
        container.setTabList(new Control[] { text });

        // Без явного layout() дочерние Label/Text внутри GridLayout не гарантированно получают
        // позицию/размер (container сам по себе на месте — а содержимое остаётся нулевого
        // размера, отсюда визуально «пустое поле»). Частая и хорошо известная ошибка SWT.
        container.layout(true, true);
        diag("createOverlay: container.getBounds()=" + container.getBounds() //$NON-NLS-1$
            + " text.getBounds()=" + text.getBounds() //$NON-NLS-1$
            + " parent.getLayout()=" + parent.getLayout()); //$NON-NLS-1$

        // Диагностика кнопки «...» (открывает диалог выбора сложного типа): смотрим, жива ли
        // она вообще — на уровне EMF-модели (viewModel.buttons, ButtonItemViewModel с tooltip
        // «Выбрать») и на уровне самого LWT-виджета (nativeControl.buttons, LightImageButton).
        Object vmButtons = Global.getField(viewModel, "buttons"); //$NON-NLS-1$
        Object ncButtons = Global.getField(nativeControl, "buttons"); //$NON-NLS-1$
        diag("createOverlay: viewModel.buttons=" + describeList(vmButtons) //$NON-NLS-1$
            + " nativeControl.buttons=" + describeList(ncButtons)); //$NON-NLS-1$

        // Сравнение по ссылке (==) с элементами entries не годится: getSingleTypeItem().get() и
        // getTypes(false) — отдельные вызовы EMF-геттеров, не гарантирующие идентичность объекта
        // для логически того же типа (проверено на практике — поле оставалось пустым). Сравниваем
        // по имени.
        Object currentTypeItem = Global.invoke(Global.invoke(typeModel, "getSingleTypeItem"), "get"); //$NON-NLS-1$ //$NON-NLS-2$
        String initialText = currentTypeItem != null ? displayLabel(currentTypeItem) : null;
        Image initialIcon = null;
        if (initialText != null)
        {
            for (TypeEntry e : entries)
            {
                if (initialText.equals(e.label))
                {
                    initialIcon = e.icon;
                    break;
                }
            }
        }
        text.setText(initialText != null ? initialText : ""); //$NON-NLS-1$
        iconLabel.setImage(initialIcon);
        diag("createOverlay: currentTypeItem=" + currentTypeItem + " initialText=" + initialText //$NON-NLS-1$ //$NON-NLS-2$
            + " initialIcon=" + initialIcon); //$NON-NLS-1$

        Shell popup = new Shell(parent.getShell(), SWT.NO_TRIM | SWT.ON_TOP);
        popup.setLayout(new FillLayout());
        // SWT.NO_TRIM убирает рамку у самого Shell — без своего SWT.BORDER на Table попап
        // визуально сливался с фоном под собой (штатные выпадающие списки такую еле заметную
        // рамку по краю всегда рисуют). Table здесь заполняет popup целиком (FillLayout без
        // отступов), так что бордер таблицы и есть бордер попапа.
        Table table = new Table(popup, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);

        // ВРЕМЕННАЯ диагностика (клик по скроллбару списка): лог показал, что НИ hidePopup(),
        // НИ наш Display-фильтр SWT.MouseDown ни разу не сработали, хотя пользователь
        // подтвердил, что попап визуально пропадает при клике по полосе прокрутки 10 раз
        // подряд. Значит дело не в нашем коде, вызывающем setVisible(false) — вероятно, попап
        // (SWT.NO_TRIM|SWT.ON_TOP дочерний Shell) теряет z-order/активацию из-за клика по
        // нативному скроллбару Table (не client-area событие, Display-фильтр SWT.MouseDown его
        // не видит) и визуально уходит под окно мастера, оставаясь isVisible()==true. Ловим ЛЮБОЕ
        // изменение видимости/активации попапа напрямую, а также сырые MouseDown на самой
        // таблице (client-level, а не Display-фильтр — может увидеть то, что фильтр не видит).
        popup.addListener(SWT.Hide, e -> diag("popup SWT.Hide (кто-то вызвал setVisible(false) " //$NON-NLS-1$
            + "или иной путь скрытия Shell)")); //$NON-NLS-1$
        popup.addListener(SWT.Deactivate, e -> diag("popup SWT.Deactivate")); //$NON-NLS-1$
        popup.addListener(SWT.Close, e -> diag("popup SWT.Close")); //$NON-NLS-1$
        table.addListener(SWT.MouseDown, e -> diag("table native MouseDown x=" + e.x + " y=" + e.y //$NON-NLS-1$ //$NON-NLS-2$
            + " button=" + e.button)); //$NON-NLS-1$

        OverlayState state = new OverlayState();
        state.container = container;
        state.nativeControl = nativeControl;
        state.hostComposite = hostComposite;
        state.hostShell = parent.getShell();
        state.rediscover = rediscover;
        state.createdAtMs = System.currentTimeMillis();
        state.text = text;
        state.iconLabel = iconLabel;
        state.arrowButton = arrowButton;
        state.popup = popup;
        state.table = table;
        state.typeModel = typeModel;
        state.entries = entries;
        state.lastCommittedText = initialText;

        installBehaviour(state);

        // TAB из предыдущего поля мастера по-прежнему приводит фокус на ШТАТНЫЙ LightCombo
        // (мы его не трогаем/не скрываем — он остаётся законной целью traversal, только
        // визуально перекрыт нашим оверлеем; подтверждено пользователем на практике: TAB уводил
        // в нативный combo, а не в наш text). Ловим момент, когда LWT реально отдаёт этому
        // контролу фокус (событие SWT.FocusIn через общий механизм LWT-событий,
        // Global.installLightControlListener — тот же приём, что уже используется в панели
        // «Свойства», см. PropertyNameIdentifierHook), и тут же перекидываем настоящий
        // OS-фокус на наше поле. asyncExec — чтобы не переключать фокус изнутри обработки
        // самого события FocusIn (риск реентерабельности внутри LWT).
        Global.installLightControlListener(nativeControl, event ->
        {
            if (event.type != SWT.FocusIn)
                return;
            // F4 (см. openTypeDialogViaF4) намеренно ставит фокус на nativeControl, чтобы
            // отправить туда F4 — этот редирект НЕ должен тут же уводить фокус обратно, иначе
            // зацикливание (FocusIn на nativeControl → редирект на text → F4-обработчик снова
            // фокусирует nativeControl → ...). state.suppressFocusRedirect выставляется на время
            // этой операции именно для такого случая.
            if (state.suppressFocusRedirect)
                return;
            Display d = state.text.getDisplay();
            d.asyncExec(() ->
            {
                if (!state.text.isDisposed() && !state.text.isFocusControl())
                    state.text.setFocus();
            });
        });

        // Изменение размера ОКНА (растягивание) штатно порождает SWT.Resize на самом Shell —
        // на это нужно реагировать, а не только раз в POSITION_SYNC_INTERVAL_MS (жалоба
        // пользователя: поле не растягивалось вместе с окном при его расширении). Но одного
        // Shell.addControlListener недостаточно: скриншот подтвердил случай, когда сам Shell
        // (окно) не меняет размер вовсе, но строка «Тип» сдвигается ПО X — после выбора типа с
        // другим набором соседних полей («Число» с «Длина»/«Точность»/«Неотрицательное» → просто
        // «Справочник...» без них) ширина общей колонки подписей в секции меняется где-то ВНУТРИ
        // окна, сам Shell снаружи остаётся того же размера. ПЕРВАЯ попытка (ControlListener прямо
        // на nativeControl, аналогично scheduleRetryOnResize) тоже НЕ сработала (подтвердил
        // пользователь) — судя по всему, собственные getBounds() у nativeControl не меняются,
        // меняется его расположение относительно экрана из-за пересчёта layout где-то выше по
        // дереву (ширина общей колонки подписей — это состояние родителя/секции, не самого combo).
        // Вместо гадания, какой именно виджет (Shell/nativeControl/что-то ещё) получит
        // SWT.Resize/SWT.Move, ловим оба события на уровне Display (тот же приём, что и
        // outsideClickFilter/wheelFilter у попапа), отфильтровывая по принадлежности к нашему
        // hostShell — так реагируем на любой relayout где угодно в этом окне.
        //
        // ВАЖНО: при живом перетаскивании края окна (drag-resize) Windows шлёт SWT.Resize
        // ДЕСЯТКАМИ РАЗ В СЕКУНДУ (подтверждено логом — на один жест перетаскивания пришлось
        // ~90 подряд идущих repositionOverlay за секунду). Дёргать на каждое такое событие
        // setBounds+layout(true,true) — лишняя нагрузка и источник побочных эффектов при быстрых
        // последовательных relayout'ах (потеря фокуса поля/попапа). Поэтому реагируем с
        // дебаунсом — тем же приёмом, что и scheduleFilter (собираем «пачку» событий за короткий
        // интервал в один вызов), а не на каждый одиночный SWT.Resize — см. scheduleReposition.
        Listener layoutFilter = event ->
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            if (control.getShell() != state.hostShell)
                return;
            scheduleReposition(state);
        };
        Display hostDisplay = state.hostShell.getDisplay();
        hostDisplay.addFilter(SWT.Resize, layoutFilter);
        hostDisplay.addFilter(SWT.Move, layoutFilter);

        container.addDisposeListener(e ->
        {
            if (!hostDisplay.isDisposed())
            {
                hostDisplay.removeFilter(SWT.Resize, layoutFilter);
                hostDisplay.removeFilter(SWT.Move, layoutFilter);
            }
            // hidePopup() снимает Display-фильтр (outsideClickFilter — см. showPopup), если он
            // был установлен; вызывать нужно ДО popup.dispose(), иначе removeFilter внутри
            // hidePopup наткнётся на уже disposed Display-объект popup и не сработает.
            hidePopup(state);
            if (!popup.isDisposed())
                popup.dispose();
        });

        // Мастер может перекладываться и без изменения размера окна (например, после
        // Enter/выбора значения — валидационное сообщение появляется/пропадает, меняя высоту
        // формы), и nativeControl (штатный combo, мы его не трогаем — только визуально
        // перекрываем) при этом двигается штатным layout'ом AEF (SwtLightLayout — свой, о нашем
        // контейнере ничего не знает). На практике такой relayout не порождает SWT.Resize ни на
        // parent, ни на Shell (проверено — слушатель ни разу не сработал после выбора значения
        // без изменения размера окна), а какой именно сигнал использует SwtLightLayout —
        // неизвестно. Поэтому для этого случая (в дополнение к SWT.Resize выше, который уже
        // покрывает основной сценарий — растягивание окна) оставляем периодическую
        // пересинхронизацию как резерв, а не как основной механизм — отсюда редкий интервал:
        // это не должно давать заметную нагрузку, но подхватит relayout и внешнее изменение
        // значения (через диалог «...») в течение секунды.
        schedulePositionSync(state);

        // После пересборки оверлея (rediscoverOverlay — переход simple→reference или внешнее
        // изменение через «...») фокус должен остаться в НАШЕМ поле, а не уйти в нативный
        // LightCombo (старый container с фокусом только что уничтожен вместе с фокусом на нём —
        // без явного возврата фокус достаётся первому попавшемуся контролу, обычно нативному).
        // asyncExec — виджет должен быть полностью реализован (layout уже вызван выше, но
        // setFocus сразу после создания на некоторых платформах не срабатывает надёжно).
        if (focusAfterCreate)
        {
            Text focusText = text;
            focusText.getDisplay().asyncExec(() ->
            {
                if (!focusText.isDisposed())
                    focusText.setFocus();
            });
        }

        diag("createOverlay: оверлей установлен, типов=" + entries.size() //$NON-NLS-1$
            + " realBounds=" + realBounds); //$NON-NLS-1$
        return state;
    }

    // Было 1000 — расчёт на то, что растягивание окна ловится немедленно через layoutFilter
    // (см. createOverlay), а этот поллер остаётся редким резервным проходом. Но подтверждено на
    // практике: после смены типа с другим набором соседних полей («Число» → «Справочник...» —
    // пропадают «Длина»/«Точность»/«Неотрицательное», меняется ширина колонки подписей) AEF
    // завершает СВОЙ пересчёт позиции НЕ сразу — ни один SWT.Resize/SWT.Move, случившийся раньше,
    // не даёт «правильных» bounds раньше времени, их физически ещё не существует. Единственный
    // способ увидеть обновлённую позицию быстрее — опрашивать чаще; событийный подход тут не
    // помогает по сути задачи (нет события «AEF действительно закончил»).
    private static final int POSITION_SYNC_INTERVAL_MS = 200;

    private static void schedulePositionSync(OverlayState state)
    {
        Composite container = state.container;
        if (container.isDisposed())
            return;
        Display display = container.getDisplay();
        Runnable[] task = new Runnable[1];
        task[0] = () ->
        {
            if (container.isDisposed())
                return;
            try
            {
                // moveAbove вызывался один раз при создании — если relayout после выбора
                // элемента молча сбрасывает порядок отрисовки (z-order) без изменения bounds
                // (лог подтвердил: repositionOverlay ни разу не сработал после commit — значит
                // bounds не менялись, а нативный combo всё равно снова стал видимым поверх),
                // переустанавливаем передний план на каждом тике, а не только один раз.
                if (!container.isDisposed())
                    container.moveAbove(null);
                repositionOverlay(state);
                checkExternalChange(state);
            }
            catch (Exception ex)
            {
                // Не даём одному сбойному тику молча остановить весь цикл пересинхронизации —
                // без явного reschedule здесь поллер тихо умирал бы после первого же исключения
                // (например, translateRectangleFromControl бросает RuntimeException — см.
                // Global.invoke — во время промежуточного состояния relayout).
                diag("schedulePositionSync: исключение " + ex); //$NON-NLS-1$
            }
            finally
            {
                if (!container.isDisposed())
                    display.timerExec(POSITION_SYNC_INTERVAL_MS, task[0]);
            }
        };
        display.timerExec(POSITION_SYNC_INTERVAL_MS, task[0]);
    }

    /**
     * Значение поля «Тип» можно поменять и в обход нашего попапа — штатной кнопкой «...»
     * (диалог выбора сложного/ссылочного типа, которую мы намеренно не перекрываем — см.
     * {@code RIGHT_RESERVED_WIDTH}). Наш {@code state.text}/{@code iconLabel} в этом случае не
     * обновляются сами по себе (мы не патчим сам {@code LightCombo}, а только накладываем
     * оверлей — событие выбора в чужом диалоге к нам не приходит). Поэтому на каждом тике
     * поллера сверяем текущее значение модели с тем, что мы сами последний раз туда закоммитили
     * ({@code state.lastCommittedText}) — если разошлось, значит, значение сменили извне, и
     * нужно пересобрать оверлей (тем же путём, что и после выбора из своего попапа — по факту
     * это может быть и переход simple→reference, для которого пересборка и так обязательна).
     */
    // Лог подтвердил гонку: commitSelection асинхронно пересобирает оверлей, и если ровно на этот
    // момент приходится тик поллера, typeModel только что пересобранного AEF ещё может на
    // мгновение бросать «Model is offline» — мы тут же пересобирали оверлей ВТОРОЙ раз, снося
    // попап, который пользователь как раз успел открыть клавишей Down сразу после выбора
    // предыдущего значения. Даём свежесозданному оверлею короткую защитную паузу (больше
    // интервала поллера — гарантированно пропускает первый тик).
    //
    // ВАЖНО: это значение раньше было 1500 — но выяснилось (другой баг, другой лог), что эта же
    // пауза ЗАДЕРЖИВАЕТ и легитимные срабатывания checkExternalChange: после смены типа с другим
    // набором соседних полей («Число» → «Справочник...») строка «Тип» физически сдвигается, но
    // самая первая пересборка (сразу после commitSelection, см. asyncExec там же) ловит AEF ДО
    // того, как он закончил перекладывать соседние поля, и получает контрол с неправильными
    // bounds. Именно ВТОРАЯ пересборка — та, что checkExternalChange запускает по «Model is
    // offline» — подхватывает уже правильную позицию. Пока действовала пауза 1500мс, эта
    // законная пересборка искусственно откладывалась на те же 1.5+ секунды — то самое зависание
    // позиции поля, на которое пожаловался пользователь. Сокращаем защитную паузу до минимума,
    // который всё ещё гарантированно перекрывает POSITION_SYNC_INTERVAL_MS (=200) — она нужна
    // только чтобы пропустить самый первый тик поллера после создания, а не блокировать
    // проверку на полторы секунды.
    private static final int EXTERNAL_CHANGE_GRACE_MS = POSITION_SYNC_INTERVAL_MS * 2;

    private static void checkExternalChange(OverlayState state)
    {
        if (state.container.isDisposed() || state.programmaticChange)
            return;
        if (System.currentTimeMillis() - state.createdAtMs < EXTERNAL_CHANGE_GRACE_MS)
            return;
        Object currentTypeItem;
        try
        {
            currentTypeItem = Global.invoke(Global.invoke(state.typeModel, "getSingleTypeItem"), "get"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IllegalStateException ex)
        {
            // В панели «Свойства» смена выбранного объекта в дереве не всегда доходит до нас
            // через partInputChanged (см. installPropertySheet/tryAttachPropertySheet) — панель
            // может обновлять содержимое внутренним ISelectionListener без пересоздания входа
            // view. Тогда typeModel, на который завязан этот оверлей, «умирает» (AEF переводит
            // его в offline), а наш поллер продолжал бы стучаться в него вечно, раз в секунду
            // логируя одно и то же исключение и оставляя на экране битый оверлей (пустой текст,
            // disposed-иконки из старого viewModel.items). Трактуем «Model is offline» как
            // надёжный сигнал «строка «Тип» под нами исчезла» и пересобираем оверлей тем же
            // путём, что и при обычном внешнем изменении значения — rediscoverOverlay сносит
            // текущий контейнер и заново запускает state.rediscover (для панели «Свойства» это
            // повторный tryAttachPropertySheet, который либо найдёт новую актуальную строку,
            // либо корректно ничего не создаст).
            diag("checkExternalChange: typeModel offline — пересобираю оверлей: " + ex); //$NON-NLS-1$
            scheduleRediscoverOverlay(state);
            return;
        }
        String currentText = currentTypeItem != null ? displayLabel(currentTypeItem) : null;
        if (java.util.Objects.equals(currentText, state.lastCommittedText))
            return;
        diag("checkExternalChange: значение изменено извне (было=" + state.lastCommittedText //$NON-NLS-1$
            + ", стало=" + currentText + ") — пересобираю оверлей"); //$NON-NLS-1$ //$NON-NLS-2$
        scheduleRediscoverOverlay(state);
    }

    /**
     * Лог подтвердил: синхронный вызов rediscoverOverlay() прямо из тика поллера (в отличие от
     * commitSelection, который специально делает это через asyncExec — см. комментарий там же)
     * иногда попадает на промежуточное состояние AEF ПОСЛЕ смены значения через диалог «...», но
     * ДО того, как AEF успел перестроить строку «Тип» обратно в обычный однострочный комбобокс —
     * в этот момент nativeControl временно оказывается совсем другим виджетом (в логе —
     * {@code Rectangle {0, 154, 489, 150}} вместо обычных ~24px высоты), и наш оверлей на секунду
     * растягивается на всю эту область, наезжая на «Представление»/«Подсказка» ниже. asyncExec
     * даёт AEF закончить перестройку синхронно в рамках текущего события, прежде чем мы заново
     * ищем виджет — тот же приём, что и в commitSelection.
     */
    private static void scheduleRediscoverOverlay(OverlayState state)
    {
        if (state.text.isDisposed())
            return;
        Display display = state.text.getDisplay();
        display.asyncExec(() -> rediscoverOverlay(state));
    }


    // Ниже какой ширины нативного контрола не доверяем текущим bounds при пересчёте размера
    // оверлея — это переходное состояние (см. repositionOverlay), а не «поле стало узким».
    private static final int MIN_TRUSTED_NATIVE_WIDTH = 40;

    /**
     * Берём ширину/высоту оверлея из ТЕКУЩИХ {@code nativeControl.getBounds()} (а не из
     * зафиксированных при создании — так было раньше, и именно это не давало полю растягиваться
     * при изменении размера окна мастера, на что пользователь жаловался). Единственное
     * исключение — переходный момент сразу после выбора ссылочного/составного типа из списка:
     * тогда {@code nativeControl} на один кадр СЖИМАЕТСЯ до размера одной иконки (AEF
     * перестраивает поле «Тип» на несколько соседних виджетов), после чего
     * {@code commitSelection} всё равно тут же пересоздаёт весь оверлей через
     * {@code rediscoverOverlay} против нового контрола — так что здесь достаточно просто
     * проигнорировать этот один узкий тик (см. {@code MIN_TRUSTED_NATIVE_WIDTH}), а не
     * замораживать размер навсегда.
     */
    private static void repositionOverlay(OverlayState state)
    {
        Composite container = state.container;
        if (container.isDisposed())
            return;
        Object nativeControl = state.nativeControl;
        Object boundsObj = Global.invoke(nativeControl, "getBounds"); //$NON-NLS-1$
        if (boundsObj == null)
            boundsObj = Global.getField(nativeControl, "bounds"); //$NON-NLS-1$
        Rectangle ownBounds = rectangleOf(boundsObj);
        if (ownBounds == null || ownBounds.width < MIN_TRUSTED_NATIVE_WIDTH)
            return;
        Rectangle localRect = new Rectangle(0, 0, ownBounds.width, ownBounds.height);
        Rectangle realBounds = rectangleOf(Global.invoke(state.hostComposite, "translateRectangleFromControl", //$NON-NLS-1$
            nativeControl, localRect));
        if (realBounds == null)
            return;
        realBounds = new Rectangle(realBounds.x, realBounds.y,
            Math.max(20, realBounds.width - RIGHT_RESERVED_WIDTH), realBounds.height);
        if (realBounds.equals(container.getBounds()))
            return;
        container.setBounds(realBounds);
        container.layout(true, true);
        diag("repositionOverlay: новый realBounds=" + realBounds + " (nativeControl.bounds сейчас=" + ownBounds + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Пара «объект типа + отображаемое имя + иконка» — {@code label}/{@code icon} сопоставлены по индексу. */
    private static final class TypeEntry
    {
        final Object typeItem;
        final String label;
        final Image icon;

        TypeEntry(Object typeItem, String label, Image icon)
        {
            this.typeItem = typeItem;
            this.label = label;
            this.icon = icon;
        }
    }

    /** Изменяемое состояние одного оверлея — держим в одном месте вместо разрозненных полей. */
    private static final class OverlayState
    {
        Composite container;
        Object nativeControl;
        Object hostComposite;
        Shell hostShell;
        // Пересоздаёт оверлей заново (ищет nativeControl/viewModel/typeModel с нуля) — для
        // мастера это patchTypeCombo(page, wizard, true), для панели «Свойства» —
        // tryAttachPropertySheet(view). См. rediscoverOverlay().
        Runnable rediscover;
        Text text;
        Label iconLabel;
        Button arrowButton;
        Shell popup;
        Table table;
        Object typeModel;
        // Момент создания (см. checkExternalChange) — сразу после пересборки AEF ещё какое-то
        // время «дособирает» typeModel, и опрос в этом окне может поймать ложное «Model is
        // offline» на только что созданном оверлее.
        long createdAtMs;
        List<TypeEntry> entries;
        List<TypeEntry> visibleEntries = new ArrayList<>();
        // Раскраску совпадений делает SmartMatchHighlight.paintTableCellMatchOverlay из
        // PaintItem — ему нужен сам matcher (сам считает диапазоны через getHighlightRanges),
        // отдельно готовые диапазоны хранить не нужно.
        SmartMatcher currentMatcher;
        String lastCommittedText;
        Runnable pendingFilter;
        boolean repositionThrottled;
        boolean repositionPending;
        boolean programmaticChange;
        Listener outsideClickFilter;
        Listener wheelFilter;
        boolean suppressFocusRedirect;
        boolean pendingSelectAllOnClick;
        boolean recentMouseDown;
    }

    private static void installBehaviour(OverlayState state)
    {
        Text text = state.text;
        Table table = state.table;

        // Раньше popup SWT.Deactivate (см. createOverlay — ВРЕМЕННАЯ диагностика скроллбара)
        // только логировался, но не закрывал попап. Из-за этого переключение на ДРУГОЕ
        // приложение (напр. активация полноэкранного окна) деактивирует главное окно EDT вместе
        // со всеми его дочерними Shell (в т.ч. наш popup — он SWT.ON_TOP дочерний Shell
        // parent.getShell()), но попап оставался видимым поверх всего, зависая на экране.
        // hidePopup(state, ...) безопасно вызывать многократно — это no-op, если попап уже скрыт
        // (см. wasVisible в hidePopup), так что не конфликтует с explicit-закрытием при выборе
        // строки/клике по стрелке.
        state.popup.addListener(SWT.Deactivate, e ->
            hidePopup(state, "popup Deactivate (потеря активации окна, напр. переключение на другое приложение)")); //$NON-NLS-1$

        state.arrowButton.addListener(SWT.Selection, e ->
        {
            if (state.popup.isVisible())
            {
                hidePopup(state, "arrowButton toggle"); //$NON-NLS-1$
            }
            else
            {
                text.setFocus();
                refresh(state, true);
            }
        });

        text.addModifyListener(e ->
        {
            if (!state.programmaticChange)
                scheduleFilter(state);
        });

        // Различаем клик мышью и фокус с клавиатуры (TAB), не гадая по таймингу: MouseDown
        // всегда приходит раньше FocusIn для клика и не приходит вообще для TAB.
        text.addListener(SWT.MouseDown, e -> state.recentMouseDown = true);

        // ПОДТВЕРЖДЕНО ЛОГОМ: нативное позиционирование каретки по месту клика в Text (Win32)
        // завершается только к MouseUp — любой selectAll(), вызванный РАНЬШЕ (даже из
        // отложенного через asyncExec/timerExec кода), затирается этим нативным сбросом.
        // Единственное надёжное место — здесь, ПОСЛЕ того как MouseUp уже дошёл и мы точно
        // внутри его обработки. См. focusGained — там для этого же клика вместо selectAll()
        // выставляется только pendingSelectAllOnClick, а сам selectAll() выполняется только тут.
        text.addListener(SWT.MouseUp, e ->
        {
            if (state.pendingSelectAllOnClick)
            {
                state.pendingSelectAllOnClick = false;
                text.selectAll();
            }
        });

        // Фокус не должен сам по себе открывать список — только выделяем текст (как штатный
        // combo при входе в поле), список считаем молча, показываем только по вводу/стрелке.
        text.addFocusListener(new FocusAdapter()
        {
            @Override
            public void focusGained(FocusEvent e)
            {
                // ПОДТВЕРЖДЕНО ЛОГОМ: клик по ЛЮБОМУ другому (не нашему) полю формы на
                // мгновение отдаёт настоящий OS-фокус нашему text — это не визуальный
                // артефакт, getFocusControl() реально указывал на наш Text. Похоже на известное
                // поведение SWT: Composite.setFocus() без явной цели отдаёт фокус первому
                // фокусируемому ребёнку, а LWT, судя по всему, дёргает setFocus() на общем
                // холсте как промежуточный шаг перед точным наведением на нужное поле — наш
                // Text, будучи единственным настоящим фокусируемым потомком холста, ловит этот
                // промежуточный вызов, после чего LWT тут же переставляет фокус на настоящую
                // цель клика (это видно как focusGained → почти сразу focusLost в логе).
                // Сам вызов на стороне LWT мы убрать не можем (закрытый код), поэтому не
                // реагируем на такие «непрожившие» получения фокуса — переносим всю обработку в
                // asyncExec с проверкой isFocusControl(): к этому моменту либо фокус реально
                // остался у нас (настоящий клик пользователя в наше поле — обрабатываем как
                // обычно), либо уже успел уйти на истинную цель (пропускаем без побочных
                // эффектов). Раньше здесь clearSiblingTextSelections() выполнялась СИНХРОННО на
                // каждый такой промежуточный вызов — вероятная причина заметного «мигания»
                // выделения в кликнутом поле.
                //
                // ПОДТВЕРЖДЕНО ЛОГОМ (отдельно от вышеописанного): нативное позиционирование
                // каретки по месту клика в Text завершается только к MouseUp — ЛЮБОЙ selectAll(),
                // вызванный раньше (в т.ч. отложенный через asyncExec/timerExec), затирается этим
                // сбросом. Поэтому для клика мышью НЕ вызываем selectAll() тут вообще — только
                // выставляем pendingSelectAllOnClick, а сам selectAll() выполняет обработчик
                // SWT.MouseUp (см. выше), который гарантированно отработает уже ПОСЛЕ нативного
                // сброса. Для фокуса с клавиатуры (TAB, MouseDown не было) MouseUp не придёт
                // вообще — здесь и делаем selectAll() сами, гонки с кликом тут в принципе нет.
                boolean fromClick = state.recentMouseDown;
                state.recentMouseDown = false;
                if (fromClick)
                    state.pendingSelectAllOnClick = true;
                text.getDisplay().asyncExec(() ->
                {
                    if (text.isDisposed() || !text.isFocusControl())
                        return;
                    if (!fromClick)
                        text.selectAll();

                    // Известный баг: если поле-сосед (например, «Имя») держало фокус с
                    // выделенным текстом, а затем фокус переходит через НАШЕ поле (не напрямую
                    // между двумя штатными LWT-полями), подсветка выделения в том соседнем поле
                    // зависает навсегда. Декомпиляция LightText (.tmp/LightText.javap.txt,
                    // .tmp/OverlayFocusListener.javap.txt) показала: снятие подсветки — это
                    // overlay.setSelection(0), обычно вызываемое из focusLost на настоящем
                    // StyledText-оверлее поля — почему-то не срабатывает надёжно именно при
                    // переходе через наше поле. Обходной путь — сами вызываем clearSelection() у
                    // всех LWT-текстовых полей формы.
                    clearSiblingTextSelections(state);

                    refresh(state, false);
                });
            }

            @Override
            public void focusLost(FocusEvent e)
            {
                // Штатный LightCombo снимает подсветку выделения текста при потере фокуса — наш
                // обычный SWT Text сам по себе этого не делает (выделение с focusGained'а
                // остаётся видимым и после ухода фокуса). Снимаем явно и сразу же, синхронно.
                if (!text.isDisposed())
                    text.clearSelection();

                // Клик по строке попапа сам по себе на мгновение уводит фокус с text (клик =
                // смена фокуса) — если прятать попап здесь же синхронно, это происходит РАНЬШЕ,
                // чем успевает дойти SWT.Selection от того же клика по таблице, и выбор
                // никогда не коммитится (подтверждено: «клик закрывает список, но ничего не
                // вставляет»). Поэтому решаем прятать или нет только на следующем тике цикла
                // событий, когда уже видно, куда фокус переместился НА САМОМ ДЕЛЕ.
                Display display = text.getDisplay();
                display.asyncExec(() ->
                {
                    if (text.isDisposed() || state.popup.isDisposed())
                        return;
                    Control focusControl = display.getFocusControl();
                    diag("focusLost(text): focusControl=" //$NON-NLS-1$
                        + (focusControl == null ? "null" : focusControl.getClass().getName() //$NON-NLS-1$
                            + "@" + System.identityHashCode(focusControl)) //$NON-NLS-1$
                        + " table=" + System.identityHashCode(state.table) //$NON-NLS-1$
                        + " popup=" + System.identityHashCode(state.popup) //$NON-NLS-1$
                        + " hostShell=" + System.identityHashCode(state.hostShell) //$NON-NLS-1$
                        + " popup.isVisible=" + state.popup.isVisible()); //$NON-NLS-1$

                    // Клик из нашего поля в штатное LWT-поле (например, «Синоним») не выделяет
                    // там весь текст, хотя обычный переход между двумя штатными полями это делает
                    // (та же природа, что и баг с зависающей подсветкой в clearSiblingTextSelections
                    // — наше поле на мгновение перехватывает промежуточный OS-фокус, из-за чего
                    // штатная последовательность клика у ЦЕЛЕВОГО поля сбивается). «Доделываем»
                    // это сами: если фокус реально осел на чужом LWT-оверлее (маркер
                    // LWT_OVERLAY_DATA_KEY — тот же, что и в clearSiblingTextSelections),
                    // выделяем там весь текст, как и должно быть при получении фокуса.
                    if (focusControl instanceof StyledText overlayText
                        && !overlayText.isDisposed()
                        && overlayText.getData(LWT_OVERLAY_DATA_KEY) != null)
                    {
                        overlayText.selectAll();
                    }

                    // Клик по нативному скроллбару Table — это non-client-область: она вообще не
                    // передаёт клавиатурный фокус ни одному контролу, и SWT в этом случае
                    // откатывается на getFocusControl()==ближайший Shell-предок кликнутого
                    // контрола. Table — дочерний контрол ИМЕННО НАШЕГО popup (не hostShell), так
                    // что откат идёт на state.popup, а не на state.hostShell (первая попытка
                    // проверяла не тот Shell — не сработало, лог подтвердил тот же hidePopup).
                    // Отличаем это от РЕАЛЬНОГО ухода в другое место мастера (клик в «Имя» и
                    // т.п. — там фокус получает конкретный контрол того поля, не голый Shell) —
                    // и не закрываем попап в обоих Shell-случаях.
                    if (focusControl != state.table && focusControl != state.popup
                        && focusControl != state.hostShell)
                        hidePopup(state, "focusLost(text): focusControl!=table"); //$NON-NLS-1$
                });
            }
        });

        text.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                switch (e.keyCode)
                {
                    case SWT.ARROW_DOWN:
                        moveSelection(state, 1);
                        e.doit = false;
                        break;
                    case SWT.ARROW_UP:
                        moveSelection(state, -1);
                        e.doit = false;
                        break;
                    case SWT.PAGE_DOWN:
                        moveSelection(state, POPUP_VISIBLE_ROWS);
                        e.doit = false;
                        break;
                    case SWT.PAGE_UP:
                        moveSelection(state, -POPUP_VISIBLE_ROWS);
                        e.doit = false;
                        break;
                    case SWT.CR:
                    case SWT.KEYPAD_CR:
                        if (state.popup.isVisible())
                        {
                            commitSelection(state);
                            e.doit = false;
                        }
                        break;
                    case SWT.ESC:
                        if (state.popup.isVisible())
                        {
                            hidePopup(state, "Escape"); //$NON-NLS-1$
                            e.doit = false;
                        }
                        break;
                    case SWT.F4:
                        // Штатное поведение combo-полей: F4 открывает диалог выбора типа. Наш
                        // попап списка тут ни при чём — прячем его на всякий случай, если был
                        // открыт, и передаём F4 нативному контролу (см. openTypeDialogViaF4).
                        hidePopup(state, "F4"); //$NON-NLS-1$
                        openTypeDialogViaF4(state);
                        e.doit = false;
                        break;
                    default:
                        break;
                }
            }
        });

        // Enter при открытом списке не должен доходить до штатной активации кнопки диалога по
        // умолчанию (SWT.TRAVERSE_RETURN — отдельный от KeyDown механизм; одного e.doit=false
        // в KeyListener недостаточно, чтобы не закрыть диалог).
        text.addTraverseListener(e ->
        {
            if (e.detail == SWT.TRAVERSE_RETURN && state.popup.isVisible())
                e.doit = false;
        });

        // Table.select()/setSelection() (наша навигация стрелками) НЕ порождают SWT.Selection —
        // событие приходит только от реального клика мышью пользователя, поэтому безопасно
        // коммитить прямо здесь, не разбираясь отдельно с MouseDown/таймингом обновления индекса.
        table.addListener(SWT.Selection, e -> commitSelection(state));
        // Раньше здесь был SWT.FocusIn → state.text.setFocus(), чтобы клавиатурный ввод
        // оставался в text. Но это уводило фокус ОБРАТНО прямо посреди клика (mouseDown → Table
        // получает фокус → мы туда же сразу дёргаем фокус в text → клик не успевает дойти до
        // SWT.Selection) — клик по списку переставал что-либо делать. Возврат фокуса перенесён
        // в конец commitSelection — после того как клик полностью обработан.

        // Иконка и текст — штатная отрисовка TableItem (setImage/setText в refresh()). Подсветку
        // совпадений рисует общий SmartMatchHighlight.paintTableCellMatchOverlay — overlay поверх
        // уже отрисованной платформой ячейки (сам берёт диапазоны из matcher.getHighlightRanges,
        // сам определяет позицию текста через item.getTextBounds — корректно учитывает нашу
        // иконку, т.к. она теперь настоящая TableItem-иконка, а не наша ручная отрисовка).
        // Раньше здесь была своя ручная отрисовка иконки+текста в обход этого метода — оказалось
        // избыточным и дублирующим раскраску, которая уже есть в общем коде.
        table.addListener(SWT.PaintItem, e ->
        {
            if (state.currentMatcher != null && !state.currentMatcher.isEmpty)
                SmartMatchHighlight.paintTableCellMatchOverlay(e, state.table, (TableItem) e.item, state.currentMatcher);
        });
    }

    private static void scheduleFilter(OverlayState state)
    {
        Display display = state.text.getDisplay();
        if (state.pendingFilter != null)
            display.timerExec(-1, state.pendingFilter);
        state.pendingFilter = () -> refresh(state, true);
        display.timerExec(150, state.pendingFilter);
    }

    private static final int RESIZE_THROTTLE_MS = 40;

    /**
     * Троттлинг (не дебаунс!) для {@code SWT.Resize} на {@code Shell} мастера. При живом
     * перетаскивании края окна события сыплются НЕПРЕРЫВНО, промежутки между ними меньше любого
     * разумного дебаунс-интервала — если бы мы просто откладывали и перезапускали таймер на
     * каждое событие (как было сделано первой попыткой), пересчёт не срабатывал бы вообще, пока
     * пользователь не отпустит мышь: видимое обновление подхватывал бы только резервный поллер
     * раз в POSITION_SYNC_INTERVAL_MS — ровно то, на что пожаловался пользователь («с большой
     * задержкой, похоже раз в 1000мс»). Вместо этого: первое событие в «пачке» обрабатываем
     * СРАЗУ (leading edge), затем на {@code RESIZE_THROTTLE_MS} блокируем повторный запуск —
     * если за это время пришли ещё события, по истечении окна выполняем один финальный пересчёт
     * (trailing edge), чтобы не отставать от последнего фактического размера.
     */
    private static void scheduleReposition(OverlayState state)
    {
        if (state.container.isDisposed())
            return;
        if (state.repositionThrottled)
        {
            state.repositionPending = true;
            return;
        }
        state.repositionThrottled = true;
        repositionOverlay(state);
        Display display = state.container.getDisplay();
        display.timerExec(RESIZE_THROTTLE_MS, () -> onRepositionThrottleElapsed(state));
    }

    private static void onRepositionThrottleElapsed(OverlayState state)
    {
        state.repositionThrottled = false;
        if (state.container.isDisposed())
            return;
        if (state.repositionPending)
        {
            state.repositionPending = false;
            scheduleReposition(state);
        }
    }

    private static void refresh(OverlayState state, boolean allowShow)
    {
        if (state.text.isDisposed())
            return;
        String text = state.text.getText();
        boolean skipFilter = text.equals(state.lastCommittedText);
        SmartMatcher matcher = new SmartMatcher(skipFilter ? null : text);

        state.visibleEntries.clear();
        for (TypeEntry entry : state.entries)
        {
            if (entry.label == null)
                continue;
            // matchesTree сам откатывается к сравнению с последним сегментом при однословном
            // фильтре без точки — plain matches() по всей строке матчил бы и по нелистовой
            // (категорийной) части, например "спр" по "СправочникСсылка.Валюты".
            if (matcher.matchesTree(entry.label))
                state.visibleEntries.add(entry);
        }
        // Сортировка по рейтингу smart-фильтра — тот же приоритет, что и
        // SmartOutlineComparator.compare (SmartOutlineComparator.java:53-70): рейтинг имени по
        // убыванию → рейтинг параметров по убыванию → алфавит без учёта регистра. У наших меток
        // нет скобок с параметрами — computeParamPremium всегда 0, только достраивает
        // детерминированность сортировки при равном computeNamePremium. Пустой фильтр не сортируем
        // вообще — при пустом matcher все премии всегда 0 (см. SmartMatcher.computePartPremium),
        // сортировка выродилась бы в чисто алфавитную и поменяла бы порядок списка без фильтра.
        if (!matcher.isEmpty)
        {
            state.visibleEntries.sort((a, b) ->
            {
                int np = Integer.compare(matcher.computeNamePremium(b.label), matcher.computeNamePremium(a.label));
                if (np != 0)
                    return np;
                int pp = Integer.compare(matcher.computeParamPremium(b.label), matcher.computeParamPremium(a.label));
                if (pp != 0)
                    return pp;
                return a.label.compareToIgnoreCase(b.label);
            });
        }

        state.currentMatcher = matcher;

        state.table.setRedraw(false);
        try
        {
            state.table.removeAll();
            int currentIndex = -1;
            for (TypeEntry entry : state.visibleEntries)
            {
                TableItem item = new TableItem(state.table, SWT.NONE);
                item.setText(entry.label);
                item.setImage(entry.icon);
                if (skipFilter && currentIndex < 0 && entry.label.equals(text))
                    currentIndex = state.table.getItemCount() - 1;
            }
            // При открытии списка кнопкой-стрелкой (без набора фильтра — skipFilter=true)
            // подсвечиваем и прокручиваем к строке текущего типа, а не всегда к первой строке
            // списка: список отсортирован по алфавиту, и текущий тип обычно вовсе не первый.
            // Во время активной фильтрации (пользователь печатает) поведение прежнее — первая
            // строка списка (лучшее совпадение по SmartMatcher).
            if (state.table.getItemCount() > 0)
            {
                state.table.select(currentIndex >= 0 ? currentIndex : 0);
                state.table.showSelection();
            }
        }
        finally
        {
            state.table.setRedraw(true);
        }

        if (allowShow && !state.visibleEntries.isEmpty() && state.text.isFocusControl())
            showPopup(state);
        else if (state.visibleEntries.isEmpty())
            hidePopup(state, "refresh: visibleEntries пуст"); //$NON-NLS-1$
    }

    private static void showPopup(OverlayState state)
    {
        if (state.table.getItemCount() == 0)
        {
            hidePopup(state, "showPopup: table пуст"); //$NON-NLS-1$
            return;
        }
        Composite anchor = state.text.getParent();
        Point anchorSize = anchor.getSize();
        Point displayLoc = anchor.toDisplay(0, anchorSize.y);
        int rows = Math.min(POPUP_VISIBLE_ROWS, state.table.getItemCount());
        int itemHeight = state.table.getItemHeight();
        state.popup.setBounds(displayLoc.x, displayLoc.y, Math.max(anchorSize.x, 200), rows * itemHeight + 4);
        if (!state.popup.isVisible())
        {
            state.popup.setVisible(true);
            // Клик по строке попапа определяем через focusLost на text (см. installBehaviour) —
            // но клик по ПОЛОСЕ ПРОКРУТКИ таблицы фокус на table не переводит (нативный
            // скроллбар Windows не крадёт keyboard focus), поэтому та проверка ошибочно считает
            // это «кликом вовне» и закрывает попап. Ловим такие клики отдельно, на уровне
            // Display — событие SWT.MouseDown видно независимо от того, получил ли целевой
            // контрол фокус. Фильтр ставим/снимаем строго парно с show/hidePopup, поэтому
            // (в отличие от штатного LightPopup, где висящие фильтры и сломали предыдущую
            // попытку) гарантированно не протекает за пределы жизни попапа.
            if (state.outsideClickFilter == null)
            {
                state.outsideClickFilter = e -> handlePotentialOutsideClick(state, e);
                state.popup.getDisplay().addFilter(SWT.MouseDown, state.outsideClickFilter);
            }
            // Тот же приём, что и с outsideClickFilter чуть выше (и по той же причине): попап
            // никогда не активируется/не получает фокус (клавиатурный ввод должен оставаться в
            // state.text), а ОС отправляет WM_MOUSEWHEEL в АКТИВНОЕ окно, а не в то, что под
            // курсором — обычный SWT.MouseWheel-листенер прямо на table (что пробовали раньше) от
            // ОС в принципе не получает это сообщение. Ловим прокрутку на уровне Display (там она
            // приходит — доставляется активному в этот момент виджету, e.widget нам не важен) и
            // применяем её к table вручную, только если курсор физически над попапом.
            if (state.wheelFilter == null)
            {
                state.wheelFilter = e -> handlePopupWheel(state, e);
                state.popup.getDisplay().addFilter(SWT.MouseWheel, state.wheelFilter);
            }
            diag("showPopup: bounds=" + state.popup.getBounds() //$NON-NLS-1$
                + " anchor.bounds=" + anchor.getBounds() + " itemCount=" + state.table.getItemCount()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void hidePopup(OverlayState state)
    {
        hidePopup(state, "unspecified"); //$NON-NLS-1$
    }

    // ВРЕМЕННО (диагностика): чтобы понять, ЧТО именно закрывает попап при клике на скроллбар
    // (обе гипотезы — фикс через focus-check и через MouseDown-фильтр — не помогли, см. историю
    // чата), логируем причину каждого вызова hidePopup с указанием, был ли попап реально виден
    // на тот момент.
    private static void hidePopup(OverlayState state, String reason)
    {
        boolean wasVisible = !state.popup.isDisposed() && state.popup.isVisible();
        if (wasVisible)
            diag("hidePopup: reason=" + reason); //$NON-NLS-1$
        if (state.outsideClickFilter != null)
        {
            if (!state.popup.isDisposed())
                state.popup.getDisplay().removeFilter(SWT.MouseDown, state.outsideClickFilter);
            state.outsideClickFilter = null;
        }
        if (state.wheelFilter != null)
        {
            if (!state.popup.isDisposed())
                state.popup.getDisplay().removeFilter(SWT.MouseWheel, state.wheelFilter);
            state.wheelFilter = null;
        }
        if (wasVisible)
            state.popup.setVisible(false);
    }

    /**
     * Display-фильтр SWT.MouseWheel, пока попап открыт (ставится/снимается строго парно с
     * show/hidePopup — см. showPopup). Событие приходит от ОС АКТИВНОМУ виджету (обычно
     * state.text), а не тому, что под курсором, поэтому e.widget не используем — сверяемся
     * напрямую с текущей позицией курсора на экране.
     */
    private static void handlePopupWheel(OverlayState state, Event e)
    {
        if (state.popup.isDisposed() || !state.popup.isVisible())
            return;
        Point cursor = state.popup.getDisplay().getCursorLocation();
        if (!state.popup.getBounds().contains(cursor))
            return;
        int itemCount = state.table.getItemCount();
        if (itemCount > 0)
        {
            // e.count > 0 — колесо крутится «от себя» (к началу списка); знак — единственное, на
            // что можно полагаться кроссплатформенно (величина сильно разнится по ОС/драйверам).
            int step = e.count > 0 ? -3 : 3;
            int newTop = Math.max(0, Math.min(itemCount - 1, state.table.getTopIndex() + step));
            state.table.setTopIndex(newTop);
        }
        // Не даём прокрутке уйти дальше в тот виджет, которому ОС на самом деле адресовала
        // событие (например, скроллящуюся панель «Свойства» под попапом) — иначе прокрутка списка
        // визуально сопровождалась бы ещё и прокруткой фона позади него.
        e.doit = false;
    }

    /**
     * Клик мышью где-то в приложении, пока наш попап открыт. Не закрываем, если клик пришёлся
     * внутри самого попапа (таблица, её скроллбар — см. {@link #showPopup}) или внутри нашего
     * поля/стрелки (там своя обработка — arrow toggle и т.п.), в остальных случаях — это
     * настоящий клик «мимо», закрываем попап.
     */
    private static void handlePotentialOutsideClick(OverlayState state, Event e)
    {
        if (state.popup.isDisposed() || !state.popup.isVisible())
            return;
        if (!(e.widget instanceof Control control))
        {
            diag("handlePotentialOutsideClick: e.widget не Control: " //$NON-NLS-1$
                + (e.widget == null ? "null" : e.widget.getClass().getName())); //$NON-NLS-1$
            return;
        }
        boolean withinPopup = isWithin(control, state.popup);
        boolean withinContainer = !state.container.isDisposed() && isWithin(control, state.container);
        diag("handlePotentialOutsideClick: widget=" + control.getClass().getName() //$NON-NLS-1$
            + "@" + System.identityHashCode(control) + " withinPopup=" + withinPopup //$NON-NLS-1$ //$NON-NLS-2$
            + " withinContainer=" + withinContainer); //$NON-NLS-1$
        if (withinPopup || withinContainer)
            return;
        hidePopup(state, "handlePotentialOutsideClick: widget=" + control.getClass().getName()); //$NON-NLS-1$
    }

    private static boolean isWithin(Control control, Composite ancestor)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (c == ancestor)
                return true;
        }
        return false;
    }

    /**
     * F4 должен открывать тот же диалог выбора типа, что и штатное поле. Декомпиляция показала:
     * то, что мы приняли за кнопку «...» ({@code nativeControl.buttons.get(0)}), на самом деле —
     * СОБСТВЕННАЯ стрелка LightCombo ({@code LightCombo$ComboLightImageButton}, клик по ней
     * просто вызывает {@code toggleDropdown()} — не открывает диалог); настоящая кнопка «...»
     * ({@code ButtonItemViewModel}, создаётся {@code AbstractDtSelectComponent.createOpenButtonItem()})
     * рендерится отдельно от LightCombo и не имеет прямого нативного контрола, до которого легко
     * дотянуться рефлексией. Пытаться синтезировать клик по ней — тупиковый путь.
     * <p>
     * Вместо этого просто переносим реальный фокус на штатный {@code nativeControl} (он всё ещё
     * жив под нашим оверлеем — мы его не отключаем, см. {@code RIGHT_RESERVED_WIDTH}) и
     * отправляем туда F4 как НАСТОЯЩЕЕ событие ({@code Display.post}, а не {@code handleEvent})
     * — точно так же, как это делает пользователь на штатном поле. Дальше это уже забота самого
     * EDT/AEF (F4 у него давно и надёжно работает), а не наша реализация клика. После закрытия
     * диалога фокус штатно возвращается на nativeControl, и наш уже установленный
     * FocusIn-перехватчик (см. installLightControlListener у createOverlay — тот же приём, что
     * и для TAB) сам переключит его обратно на наше поле.
     */
    private static void openTypeDialogViaF4(OverlayState state)
    {
        if (state.nativeControl == null)
        {
            diag("openTypeDialogViaF4: nativeControl==null"); //$NON-NLS-1$
            return;
        }
        Object focusSourceKeyboard = null;
        try
        {
            ClassLoader lwtLoader = state.nativeControl.getClass().getClassLoader();
            Class<?> focusSourceClass = Class.forName("com._1c.g5.lwt.FocusSource", true, lwtLoader); //$NON-NLS-1$
            for (Object constant : focusSourceClass.getEnumConstants())
            {
                if ("Keyboard".equals(String.valueOf(constant))) //$NON-NLS-1$
                {
                    focusSourceKeyboard = constant;
                    break;
                }
            }
        }
        catch (Exception e)
        {
            diag("openTypeDialogViaF4: FocusSource class error " + e); //$NON-NLS-1$
        }
        // См. комментарий у installLightControlListener/FocusIn (createOverlay) — без этого
        // флага setFocus(nativeControl) ниже тут же отменяется нашим же TAB-редиректом обратно
        // на text, и F4 зацикливается.
        state.suppressFocusRedirect = true;
        Global.invoke(state.nativeControl, "setFocus", focusSourceKeyboard); //$NON-NLS-1$

        Display display = state.text.getDisplay();
        watchForDialogAndReclaimFocus(state, display);
        display.asyncExec(() ->
        {
            try
            {
                // Штатный баг EDT (воспроизведён и БЕЗ нашего оверлея, на голом штатном поле):
                // F4 срабатывает только один раз подряд на одном и том же поле — повторное
                // нажатие без ухода фокуса с поля и возврата на него F4 больше не ловит.
                // Пробовал лечить это на своей стороне (Tab/Shift+Tab — не сработало; клик по
                // nativeControl — открывал его нативный выпадающий список и мигал) — раз баг
                // штатный и неисправимый снаружи, а попытки его обойти каждый раз давали новый
                // визуальный побочный эффект хуже самого бага, оставляем как есть: F4 у нас
                // ведёт себя так же, как и на голом штатном поле EDT.
                Event down = new Event();
                down.type = SWT.KeyDown;
                down.keyCode = SWT.F4;
                display.post(down);
                Event up = new Event();
                up.type = SWT.KeyUp;
                up.keyCode = SWT.F4;
                display.post(up);
                diag("openTypeDialogViaF4: F4 отправлен нативному контролу"); //$NON-NLS-1$
            }
            finally
            {
                state.suppressFocusRedirect = false;
            }
        });
    }

    // Сколько ждать появления диалога после F4, прежде чем снять слушатель как «не сработало»
    // (например, F4 в данном контексте ничего не открыл) — не должен висеть вечно.
    private static final int DIALOG_WATCH_TIMEOUT_MS = 5000;

    /**
     * Ловит появление диалога выбора типа (открытого через F4 — см. {@code openTypeDialogViaF4})
     * и вешает на его закрытие явный возврат фокуса в наше поле. Предыдущая попытка (проверка
     * {@code nativeControl.isFocused()} на каждом тике поллера) провалилась — лог показал, что
     * этот метод возвращает {@code false} даже когда диалог реально открыт и держит фокус
     * (значит в принципе не годится как сигнал). Здесь вместо угадывания состояния ловим сам
     * факт открытия/закрытия РЕАЛЬНОГО {@code Shell} диалога через {@code Display}-фильтр —
     * фактическое, а не предполагаемое событие.
     */
    private static void watchForDialogAndReclaimFocus(OverlayState state, Display display)
    {
        Listener[] filterHolder = new Listener[1];
        Runnable[] timeoutHolder = new Runnable[1];
        filterHolder[0] = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell == state.hostShell || shell == state.popup)
                return;
            if (shell.getParent() != state.hostShell)
                return;
            diag("watchForDialogAndReclaimFocus: обнаружен диалог " + shell); //$NON-NLS-1$
            display.removeFilter(SWT.Show, filterHolder[0]);
            if (timeoutHolder[0] != null)
                display.timerExec(-1, timeoutHolder[0]);
            shell.addDisposeListener(e ->
            {
                diag("watchForDialogAndReclaimFocus: диалог закрыт — пересобираю оверлей"); //$NON-NLS-1$
                // Раньше здесь просто возвращался фокус (state.text.setFocus()) — но nativeControl,
                // на который завязан ТЕКУЩИЙ state, мог за время диалога устареть (AEF перестраивает
                // строку «Тип» под новое значение), и фокус садился в никуда/в штатное поле.
                // Закрытие диалога — самый надёжный из доступных сигналов «пора перепроверить
                // строку» (в отличие от угадывания задержек/принудительных scene.refresh(),
                // которые не сработали — см. tryAttachPropertySheet), поэтому сразу пересобираем
                // оверлей тем же путём, что и после выбора из своего попапа. asyncExec — даём
                // обработке закрытия Shell полностью завершиться, прежде чем сносить/искать виджет
                // заново (rediscoverOverlay и так делает то же самое для commitSelection).
                display.asyncExec(() -> rediscoverOverlay(state));
            });
        };
        display.addFilter(SWT.Show, filterHolder[0]);

        timeoutHolder[0] = () -> display.removeFilter(SWT.Show, filterHolder[0]);
        display.timerExec(DIALOG_WATCH_TIMEOUT_MS, timeoutHolder[0]);
    }

    private static void moveSelection(OverlayState state, int delta)
    {
        int count = state.table.getItemCount();
        if (count == 0)
            return;
        if (!state.popup.isVisible())
        {
            showPopup(state);
            return;
        }
        int idx = state.table.getSelectionIndex();
        idx = idx < 0 ? 0 : Math.max(0, Math.min(count - 1, idx + delta));
        state.table.setSelection(idx);
        state.table.showSelection();
    }

    private static void commitSelection(OverlayState state)
    {
        int idx = state.table.getSelectionIndex();
        if (idx < 0 || idx >= state.visibleEntries.size())
        {
            hidePopup(state, "commitSelection: idx вне диапазона"); //$NON-NLS-1$
            return;
        }
        TypeEntry chosen = state.visibleEntries.get(idx);

        Object singleTypeItemValue = Global.invoke(state.typeModel, "getSingleTypeItem"); //$NON-NLS-1$
        Global.invokeVoid(singleTypeItemValue, "set", chosen.typeItem); //$NON-NLS-1$

        state.lastCommittedText = chosen.label;
        state.programmaticChange = true;
        try
        {
            state.text.setText(chosen.label != null ? chosen.label : ""); //$NON-NLS-1$
            state.text.setSelection(state.text.getText().length());
        }
        finally
        {
            state.programmaticChange = false;
        }
        state.iconLabel.setImage(chosen.icon);
        hidePopup(state, "commitSelection: элемент выбран"); //$NON-NLS-1$
        if (!state.text.isDisposed())
            state.text.setFocus();
        diag("commitSelection: выбран тип " + chosen.label); //$NON-NLS-1$

        // Подтверждено скриншотом с красной заливкой: после выбора ссылочного/составного типа
        // визуальная привязка нашего оверлея ломается, хотя ни bounds, ни z-order (по логам)
        // не менялись. Похоже, AEF пересоздаёт нижележащую структуру рендеринга строки «Тип»
        // при переходе simple→reference type (нужен другой набор LWT-виджетов под «...»/
        // уточнения) — наш container остаётся привязан к «осиротевшему» parent/nativeControl.
        // Поэтому вместо попытки угнаться за старым контролом — пересоздаём оверлей заново,
        // как будто окно мастера только что открылось. asyncExec — чтобы дать AEF время
        // перестроиться синхронно в рамках того же commit, прежде чем мы заново ищем виджет.
        Display display = state.text.getDisplay();
        display.asyncExec(() ->
        {
            // ВРЕМЕННАЯ диагностика: лог показал случай, где после commitSelection не было вообще
            // ни одной дальнейшей записи в лог — ни этой, ни из rediscoverOverlay/
            // tryAttachPropertySheet. Нужно увидеть, доходит ли выполнение сюда вообще.
            diag("commitSelection: asyncExec(rediscoverOverlay) исполняется, container.isDisposed()=" //$NON-NLS-1$
                + (state.container == null ? "null" : state.container.isDisposed())); //$NON-NLS-1$
            rediscoverOverlay(state);
        });
    }

    private static void rediscoverOverlay(OverlayState state)
    {
        if (state.container == null || state.container.isDisposed())
        {
            diag("rediscoverOverlay: container null/disposed — пропуск пересборки"); //$NON-NLS-1$
            return;
        }
        ATTACHED.remove(state.nativeControl);
        state.container.dispose();
        try
        {
            state.rediscover.run();
        }
        catch (Exception e)
        {
            diag("rediscoverOverlay: исключение " + e); //$NON-NLS-1$
            Global.logError(LOG_TAG, "rediscoverOverlay", e); //$NON-NLS-1$
        }
    }

    /**
     * Зип {@code typeModel.getTypes(false)} (реальные объекты типа — нужны для коммита) с
     * {@code viewModel.items} (иконки) по индексу — оба списка строит один и тот же
     * AEF-компонент в одном порядке. При несовпадении размеров (не должно происходить в норме)
     * иконки просто не проставляются, без падения.
     */
    private static List<TypeEntry> loadTypeEntries(Object typeModel, Object viewModel)
    {
        Object typesObj = Global.invoke(typeModel, "getTypes", Boolean.FALSE); //$NON-NLS-1$
        List<?> typeList = typesObj instanceof List<?> l ? l : Collections.emptyList();

        Object itemsObj = Global.getField(viewModel, "items"); //$NON-NLS-1$
        List<?> comboItems = itemsObj instanceof List<?> l ? l : Collections.emptyList();
        boolean sameSize = typeList.size() == comboItems.size();
        if (!sameSize)
        {
            diag("loadTypeEntries: getTypes()=" + typeList.size() //$NON-NLS-1$
                + " и viewModel.items=" + comboItems.size() + " разного размера — без иконок"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<TypeEntry> result = new ArrayList<>(typeList.size());
        for (int i = 0; i < typeList.size(); i++)
        {
            Object typeItem = typeList.get(i);
            String label = displayLabel(typeItem);
            Image icon = null;
            if (sameSize)
            {
                Object iconObj = Global.invoke(comboItems.get(i), "getIcon"); //$NON-NLS-1$
                if (iconObj instanceof Image img)
                    icon = img;
            }
            result.add(new TypeEntry(typeItem, label, icon));
        }
        long withIcon = result.stream().filter(e -> e.icon != null).count();
        diag("loadTypeEntries: всего=" + result.size() + " с иконкой=" + withIcon); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static String displayLabel(Object typeItem)
    {
        Object nameRu = Global.invoke(typeItem, "getNameRu"); //$NON-NLS-1$
        return nameRu instanceof String s ? s : null;
    }

    private static Rectangle rectangleOf(Object value)
    {
        return value instanceof Rectangle r ? r : null;
    }

    /** Компактное описание списка (кнопки и т.п.) для диагностики — класс + tooltip/bounds если есть. */
    private static String describeClass(Object obj)
    {
        return obj == null ? "null" : obj.getClass().getName(); //$NON-NLS-1$
    }

    private static String describeList(Object listObj)
    {
        if (!(listObj instanceof List<?> list))
            return String.valueOf(listObj);
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < list.size(); i++)
        {
            Object item = list.get(i);
            if (i > 0)
                sb.append(", "); //$NON-NLS-1$
            if (item == null)
            {
                sb.append("null"); //$NON-NLS-1$
                continue;
            }
            sb.append(item.getClass().getSimpleName());
            Object tooltip = Global.invoke(item, "getTooltip"); //$NON-NLS-1$
            if (tooltip != null)
                sb.append(" tooltip=").append(tooltip); //$NON-NLS-1$
            Object bounds = Global.invoke(item, "getBounds"); //$NON-NLS-1$
            if (bounds == null)
                bounds = Global.getField(item, "bounds"); //$NON-NLS-1$
            if (bounds != null)
                sb.append(" bounds=").append(bounds); //$NON-NLS-1$
            Object visible = Global.invoke(item, "isVisible"); //$NON-NLS-1$
            if (visible != null)
                sb.append(" visible=").append(visible); //$NON-NLS-1$
        }
        sb.append(']');
        return sb.toString();
    }

    // Отключено для релиза — diag() стала no-op, тело метода и поля DIAG_* удалены.
    static synchronized void diag(String message)
    {
        // Отключено для релиза
    }
}
