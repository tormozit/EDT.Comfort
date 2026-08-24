package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;

import com._1c.g5.v8.dt.form.ui.properties.models.EventHandlerModel;
import com._1c.g5.v8.dt.ui.aef.events.AefGlobalCommandHandler;

/**
 * Shift+F4 в панели «Свойства» (и на AEF-страницах свойств редактора объекта метаданных) — как в
 * конфигураторе 1С:
 * <ul>
 * <li>поле обработчика события — очищает имя обработчика;
 * <li>поле выбора («Авто»/значение) с не-«Авто» значением, если «Авто» есть в списке, —
 * переключает на «Авто».
 * </ul>
 * <p>
 * https://github.com/tormozit/EDT.Comfort/issues/392, см. также 1c-edt-issues#228.
 *
 * <p><b>Почему не {@code SWT.KeyDown}.</b> Первая версия слушала {@code Display.addFilter(SWT.KeyDown, …)} —
 * ни одного события не приходило (диагностика подтвердила: лог пуст даже для голого F4).
 * Причина — в EDT Shift+F4 уже зарегистрирован как акселератор штатной Eclipse-команды
 * {@code com._1c.g5.v8.dt.ui.aef.commands.dt.select.clear} («Очистить») в контексте
 * {@code com._1c.g5.v8.dt.ui.aef.contexts.dt.select} («1С Редактор с кнопками выбора»,
 * см. {@code .tmp/bundles/dt-ui/plugin.xml}, экспорт {@code org.eclipse.ui.bindings}) — Eclipse
 * перехватывает клавишу на уровне обработки команд раньше, чем она становится SWT-событием
 * KeyDown, поэтому ни один фильтр Display её не видит (тот же класс проблемы, что и с
 * {@code Ctrl+<буква>} для Copy, см. раздел про акселераторы в AGENTS.md).
 *
 * <p><b>Штатный обработчик команды.</b> {@code AefGlobalCommandHandler.execute} (декомпилировано:
 * {@code .tmp/AefGlobalCommandHandler.javap.txt}) не работает с конкретным полем напрямую — он
 * берёт {@code LwtStandardRenderer.getFocusedEventChannel()} / {@code SwtStandardRenderer.…} (канал
 * СЕЙЧАС сфокусированной AEF-сцены) и кладёт туда {@code HotkeyExecutionEvent(commandId)}; что
 * дальше делает поле с этим событием — решает сам компонент поля, если он вообще слушает канал.
 * Для обработчика события и обычного комбо (наш случай) такого слушателя нет — событие уходит
 * в никуда, отсюда и молчаливое «не работает».
 *
 * <p><b>Наш подход.</b> Регистрируем свой {@code <handler>} на ТОТ ЖЕ {@code commandId} без
 * {@code activeWhen} (полностью замещает {@code defaultHandler}, см. {@code plugin.xml}) —
 * определяем сфокусированное AEF-поле теми же средствами, что и раньше
 * ({@link PropertySheetActivePropertyHook#resolvePageFromControl},
 * {@link PropertySheetControlInterop#modelForFocusedField},
 * {@link PropertySheetNonDefaultHighlightHook#focusedCombo}), только источник контрола —
 * {@code Display.getFocusControl()}, а не {@code event.widget} (событий KeyDown всё равно нет).
 * Для полей, которые мы не обрабатываем (в т.ч. настоящие поля выбора с кнопкой, для которых
 * эта команда исходно и создавалась), явно делегируем в {@code new AefGlobalCommandHandler().execute(event)} —
 * штатное поведение EDT для остальных полей не теряется.
 *
 * <p><b>Как меняется значение.</b> Не прямая правка EMF-модели, а тот же вызов, которым
 * пользуется сам редактор поля при обычном действии пользователя:
 * <ul>
 * <li>{@code EventHandlerModel} — это {@code PojoValue<String>}: платформа сама удаляет/добавляет
 * {@code EventHandler} в {@code EventHandlerContainer.getHandlers()} и чистит тело обработчика в
 * BSL-модуле при изменении значения через штатный {@code set(String)} — тот же метод, который
 * вызывает редактор поля при обычном вводе текста;
 * <li>комбо — {@code LightCombo.selectItem(int)} / {@code LightImageCombo.selectItem(int)}
 * (декомпилировано: {@code .tmp/bundles/lwt}) сам вызывает
 * {@code notifySelectionIndexListeners()} — тот же путь, что и клик мышью по пункту списка,
 * поэтому синхронизация с {@code ComboViewModel} и дальше с моделью свойства идёт штатно.
 * </ul>
 */
public class PropertySheetShiftF4ClearHook extends AbstractHandler implements IStartup
{
    private static final String TEMP_TOPIC = "свойства-очистка-shift-f4"; //$NON-NLS-1$
    private static final String DT_SELECT_CONTEXT = "com._1c.g5.v8.dt.ui.aef.contexts.dt.select"; //$NON-NLS-1$

    public PropertySheetShiftF4ClearHook()
    {
        // Диагностика регистрации: Eclipse создаёт handler лениво, факт вызова конструктора
        // подтверждает, что расширение org.eclipse.ui.handlers реально подхвачено.
        Global.tempLog(TEMP_TOPIC, "handler создан"); //$NON-NLS-1$
    }

    /**
     * Временная диагностика, не связанная с самим handler'ом: логирует активные контексты
     * Eclipse при каждом фокусе внутри AEF-палитры — проверяем, входит ли туда
     * {@value #DT_SELECT_CONTEXT} (от него зависит, дойдёт ли акселератор Shift+F4 до команды).
     */
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display.getDefault().addFilter(SWT.FocusIn, event ->
            {
                if (!(event.widget instanceof Control control) || control.isDisposed())
                    return;
                Object page = PropertySheetActivePropertyHook.resolvePageFromControl(control);
                if (page == null)
                    return;
                IContextService svc = PlatformUI.getWorkbench().getService(IContextService.class);
                java.util.Collection<?> active = svc != null ? svc.getActiveContextIds() : null;
                boolean hasDtSelect = active != null && active.contains(DT_SELECT_CONTEXT);
                Global.tempLog(TEMP_TOPIC, "FocusIn " + control.getClass().getName() //$NON-NLS-1$
                    + ": dt.select активен=" + hasDtSelect + ", всего контекстов=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (active == null ? "<null>" : active.size())); //$NON-NLS-1$
            });
            Global.tempLog(TEMP_TOPIC, "диагностика контекстов установлена"); //$NON-NLS-1$
        });
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Control control = focusControl();
        Object page = control != null ? PropertySheetActivePropertyHook.resolvePageFromControl(control) : null;
        Global.tempLog(TEMP_TOPIC, "execute: focusControl=" //$NON-NLS-1$
            + (control == null ? "<null>" : control.getClass().getName()) //$NON-NLS-1$
            + ", page=" + (page == null ? "<null>" : page.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
        if (page != null)
        {
            if (clearFocusedEventHandler(page))
                return null;
            if (switchFocusedComboToAuto(page))
                return null;
        }
        // Не наш случай — штатное поведение команды «Очистить» (например, поле выбора с кнопкой).
        return new AefGlobalCommandHandler().execute(event);
    }

    private static String itemTextDiag(Object item)
    {
        if (item == null)
            return "<null>"; //$NON-NLS-1$
        Object text = Global.invoke(item, "getText"); //$NON-NLS-1$
        return text instanceof String s ? s : String.valueOf(item);
    }

    private static Control focusControl()
    {
        Display display = Display.getDefault();
        return display != null && !display.isDisposed() ? display.getFocusControl() : null;
    }

    /** @return {@code true}, если фокус был в поле обработчика события (команда обработана). */
    private static boolean clearFocusedEventHandler(Object page)
    {
        Object model = PropertySheetControlInterop.modelForFocusedField(page);
        if (!(model instanceof EventHandlerModel handlerModel))
            return false;
        String current = handlerModel.get();
        if (current != null && !current.isEmpty())
        {
            handlerModel.set(""); //$NON-NLS-1$
            // set() сам по себе только выставляет uncommittedValue — реальное применение
            // (в т.ч. запрос на удаление процедуры из модуля, если она больше нигде не
            // используется) идёт через getChange().apply() внутри commit(). При обычном вводе
            // это делает штатный обработчик потери фокуса поля; у нас такого события нет —
            // коммитим сами.
            handlerModel.commit();
            Global.tempLog(TEMP_TOPIC, "очищено имя обработчика события «" + current + "»"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return true;
    }

    /** @return {@code true}, если фокус был в комбо со значением, переключаемым на «Авто». */
    private static boolean switchFocusedComboToAuto(Object page)
    {
        Object[] combo = PropertySheetNonDefaultHighlightHook.focusedCombo(page);
        if (combo == null)
            return false;
        Object viewModel = combo[0];
        Object light = combo[1];
        Integer autoIndex = PropertySheetNonDefaultHighlightHook.autoItemIndexToSwitchTo(viewModel);
        if (autoIndex == null)
        {
            Global.tempLog(TEMP_TOPIC, "комбо в фокусе, но переключать не на что " //$NON-NLS-1$
                + "(значение уже «Авто», «Авто» нет в списке, или есть ошибка статуса)"); //$NON-NLS-1$
            return false;
        }
        Object beforeItem = Global.invoke(viewModel, "getSelectedItem"); //$NON-NLS-1$
        Global.invokeVoid(light, "selectItem", autoIndex); //$NON-NLS-1$
        // Временная диагностика: непонятно, где рвётся синхронизация — на уровне нативного
        // контрола, view-модели или дальше (реальная запись в свойство).
        Object afterIndex = Global.invoke(light, "getSelectionIndex"); //$NON-NLS-1$
        Object afterItem = Global.invoke(viewModel, "getSelectedItem"); //$NON-NLS-1$
        Global.tempLog(TEMP_TOPIC, "комбо переключено на «Авто» (индекс " + autoIndex + "): " //$NON-NLS-1$ //$NON-NLS-2$
            + "light.class=" + light.getClass().getName() //$NON-NLS-1$
            + ", light.selectionIndex после=" + afterIndex //$NON-NLS-1$
            + ", viewModel.selectedItem до=" + itemTextDiag(beforeItem) //$NON-NLS-1$
            + ", после=" + itemTextDiag(afterItem)); //$NON-NLS-1$
        return true;
    }
}
