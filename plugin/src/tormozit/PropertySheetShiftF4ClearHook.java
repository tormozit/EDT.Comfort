package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.form.ui.properties.models.EventHandlerModel;
import com._1c.g5.v8.dt.ui.aef.events.AefGlobalCommandHandler;

/**
 * Shift+F4 в панели «Свойства» (и на AEF-страницах свойств редактора объекта метаданных) — как в
 * конфигураторе 1С:
 * <ul>
 * <li>поле обработчика события — очищает имя обработчика (платформа сама предложит удалить
 * процедуру из модуля, если она больше нигде не используется — см. {@code commit()} ниже);
 * <li>поле выбора («Авто»/значение) с не-«Авто» значением, если «Авто» есть в списке, —
 * переключает на «Авто».
 * </ul>
 * <p>
 * https://github.com/tormozit/EDT.Comfort/issues/392, см. также 1c-edt-issues#228.
 *
 * <p><b>Почему не {@code SWT.KeyDown}.</b> В EDT Shift+F4 уже зарегистрирован как акселератор
 * штатной Eclipse-команды {@code com._1c.g5.v8.dt.ui.aef.commands.dt.select.clear} («Очистить»)
 * в контексте {@code com._1c.g5.v8.dt.ui.aef.contexts.dt.select} («1С Редактор с кнопками
 * выбора», см. {@code .tmp/bundles/dt-ui/plugin.xml}, экспорт {@code org.eclipse.ui.bindings}) —
 * Eclipse перехватывает клавишу на уровне обработки команд раньше, чем она становится
 * SWT-событием KeyDown, поэтому ни один фильтр Display её не видит (тот же класс проблемы, что
 * и с {@code Ctrl+<буква>} для Copy, см. раздел про акселераторы в AGENTS.md).
 *
 * <p><b>Штатный обработчик команды.</b> {@code AefGlobalCommandHandler.execute} (декомпилировано:
 * {@code .tmp/AefGlobalCommandHandler.javap.txt}) не работает с конкретным полем напрямую — он
 * берёт канал СЕЙЧАС сфокусированной AEF-сцены ({@code LwtStandardRenderer.getFocusedEventChannel()} /
 * {@code SwtStandardRenderer.…}) и кладёт туда {@code HotkeyExecutionEvent(commandId)}; что
 * дальше делает поле с этим событием — решает сам компонент поля, если он вообще слушает канал.
 * Для обработчика события и обычного комбо (наш случай) такого слушателя нет — событие уходит
 * в никуда, отсюда и молчаливое «не работает».
 *
 * <p><b>Наш подход.</b> Регистрируем свой {@code <handler>} на ТОТ ЖЕ {@code commandId} с
 * {@code activeWhen}, проверяющим тот же контекст {@code dt.select} (без него наш handler не
 * выигрывает у {@code defaultHandler}, см. {@code plugin.xml}) — определяем сфокусированное
 * AEF-поле через {@link PropertySheetActivePropertyHook#resolvePageFromControl},
 * {@link PropertySheetControlInterop#modelForFocusedField},
 * {@link PropertySheetNonDefaultHighlightHook#focusedCombo}, источник контрола —
 * {@code Display.getFocusControl()} (событий KeyDown всё равно нет). Для полей, которые мы не
 * обрабатываем (в т.ч. настоящие поля выбора с кнопкой, для которых эта команда исходно и
 * создавалась), делегируем в {@code new AefGlobalCommandHandler().execute(event)} — штатное
 * поведение EDT для остальных полей не теряется.
 *
 * <p><b>Как меняется значение.</b> Не прямая правка EMF-модели, а тот же вызов, которым
 * пользуется сам редактор поля при обычном действии пользователя:
 * <ul>
 * <li>{@code EventHandlerModel} — это {@code PojoValue<String>}: {@code set(String)} только
 * выставляет {@code uncommittedValue}, реальное применение (удаление {@code EventHandler} из
 * {@code EventHandlerContainer.getHandlers()} и — через
 * {@code IBslModuleContentManagementService.removeEventHandlerMethod}, декомпилировано:
 * {@code .tmp/BslModuleContentManagementService.javap.txt} — проверка остальных использований
 * процедуры и штатный диалог удаления из модуля) идёт только через {@code getChange().apply()}
 * внутри {@code commit()}. При обычном вводе это делает штатный обработчик потери фокуса поля;
 * у нас такого события нет — коммитим сами;
 * <li>комбо — {@code LightCombo.selectItem(int)} / {@code LightImageCombo.selectItem(int)}
 * (декомпилировано: {@code .tmp/bundles/lwt}) сам вызывает
 * {@code notifySelectionIndexListeners()} — тот же путь, что и клик мышью по пункту списка,
 * поэтому синхронизация с {@code ComboViewModel} и дальше с моделью свойства идёт штатно.
 * </ul>
 */
public class PropertySheetShiftF4ClearHook extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Control control = focusControl();
        Object page = control != null ? PropertySheetActivePropertyHook.resolvePageFromControl(control) : null;
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
            handlerModel.commit();
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
            return false;
        Global.invokeVoid(light, "selectItem", autoIndex); //$NON-NLS-1$
        return true;
    }
}
