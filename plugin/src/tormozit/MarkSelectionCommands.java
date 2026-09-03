package tormozit;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com._1c.g5.v8.dt.ui.V8UiSharedImages;

/**
 * Единое оформление команд «Установить пометки» / «Снять пометки» — везде, где плагин даёт менять
 * пометки в списке или дереве. Один источник подписей и значков: новому потребителю не нужно искать
 * их заново, а пользователь узнаёт команду в любом окне.
 *
 * <p>Значки — те же, что у кнопок «Отметить все» и «Снять отметки» в окне сравнения/объединения
 * конфигураций: {@code DtComparisonEditor.SelectAllAction} / {@code UnselectAllAction} берут их из
 * {@link V8UiSharedImages} по этим же путям. Реестр картинок владеет ими сам — освобождать не нужно.
 *
 * <p>Действие у каждого потребителя своё — класс отвечает только за оформление.
 *
 * <p><b>Где уместно:</b> только там, где команда меняет пометки у НЕСКОЛЬКИХ выделенных строк —
 * на значках нарисована именно групповая пометка. Списку с одиночным выделением (например, дерево
 * подсистем диалога отбора) эти значки не подходят: они обещают действие над группой.
 *
 * <p>Потребители: {@link RefactoringPreviewTableHook} (табличный режим страницы «Вносимые
 * изменения»), {@link CompareConfigMenuHook} (контекстное меню дерева сравнения конфигураций),
 * {@link CompareSearchResultPage} (панель результатов поиска по дереву сравнения).
 */
final class MarkSelectionCommands
{
    /** Пути повторяют {@code DtComparisonEditor.SelectAllAction} / {@code UnselectAllAction}. */
    private static final String SET_ICON_PATH = "/icons/obj16/check_all_elements.png"; //$NON-NLS-1$
    private static final String CLEAR_ICON_PATH = "/icons/obj16/uncheck_all_elements.png"; //$NON-NLS-1$

    /** Подписи для места, где команда работает по выделению и это стоит назвать явно. */
    static final String SET_SELECTED_TEXT = "Установить пометки выделенных"; //$NON-NLS-1$
    static final String CLEAR_SELECTED_TEXT = "Снять пометки выделенных"; //$NON-NLS-1$

    private MarkSelectionCommands()
    {
    }

    /** Значок установки пометок — кнопка «Отметить все» окна сравнения конфигураций. */
    static Image setIcon()
    {
        return V8UiSharedImages.getImage(SET_ICON_PATH);
    }

    /** Значок снятия пометок — кнопка «Снять отметки» окна сравнения конфигураций. */
    static Image clearIcon()
    {
        return V8UiSharedImages.getImage(CLEAR_ICON_PATH);
    }

    /** Тот же значок для {@code Action} и других потребителей дескрипторов. */
    static ImageDescriptor setIconDescriptor()
    {
        return V8UiSharedImages.getImageDescriptor(SET_ICON_PATH);
    }

    /** Тот же значок для {@code Action} и других потребителей дескрипторов. */
    static ImageDescriptor clearIconDescriptor()
    {
        return V8UiSharedImages.getImageDescriptor(CLEAR_ICON_PATH);
    }

    /** Пункт «Установить пометки выделенных» в конце меню. */
    static MenuItem addSetItem(Menu menu, Runnable action)
    {
        return addSetItem(menu, SET_SELECTED_TEXT, action);
    }

    /** Пункт установки пометок со своей подписью — там, где она уже устоялась. */
    static MenuItem addSetItem(Menu menu, String text, Runnable action)
    {
        return addItem(menu, text, setIcon(), action);
    }

    /** Пункт «Снять пометки выделенных» в конце меню. */
    static MenuItem addClearItem(Menu menu, Runnable action)
    {
        return addClearItem(menu, CLEAR_SELECTED_TEXT, action);
    }

    /** Пункт снятия пометок со своей подписью — там, где она уже устоялась. */
    static MenuItem addClearItem(Menu menu, String text, Runnable action)
    {
        return addItem(menu, text, clearIcon(), action);
    }

    private static MenuItem addItem(Menu menu, String text, Image icon, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        if (icon != null && !icon.isDisposed())
            item.setImage(icon);
        item.addListener(SWT.Selection, event -> action.run());
        return item;
    }
}
