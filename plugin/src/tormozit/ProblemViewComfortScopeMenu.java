package tormozit;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Пункты «Отобранное в проекте навигатора» / «Активный набор проекта» в выпадающем
 * меню кнопки «Настройки отбора» тулбара панели «Проблемы конфигурации» (issue 462) —
 * рядом со штатными радио «Области возникновения».
 *
 * <p>Режим хранит {@link ProblemViewComfortScope}, применяет {@link ProblemViewHook}.
 * Пункты — флажки. Под нашим режимом панель работает в штатной области
 * «Текущий элемент»/«Текущий проект», поэтому при открытии меню штатные радио
 * визуально снимаются (иначе отмеченными выглядят сразу два «альтернативных» режима).
 * Сброс нашего режима при выборе штатного радио делает {@code IExecutionListener}
 * в {@link ProblemViewHook}.
 *
 * <p>Отдельный файл: точка входа из {@code plugin.xml} ({@code <dynamic>}).
 */
public final class ProblemViewComfortScopeMenu extends ContributionItem
{
    private static final String SHOW_HOOK_KEY = "tormozit.problemViewComfortScopeMenuShowHook"; //$NON-NLS-1$

    @Override
    public boolean isDynamic()
    {
        return true;
    }

    @Override
    public void fill(Menu menu, int index)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;

        ProblemViewComfortScope.Mode mode = ProblemViewComfortScope.mode();
        MenuItem navigator = create(menu, index, ProblemViewComfortScope.NAVIGATOR_LABEL);
        MenuItem activeSets = create(menu, index < 0 ? -1 : index + 1, ProblemViewComfortScope.ACTIVE_SETS_LABEL);
        navigator.setSelection(mode == ProblemViewComfortScope.Mode.NAVIGATOR);
        activeSets.setSelection(mode == ProblemViewComfortScope.Mode.ACTIVE_SETS);

        navigator.addListener(SWT.Selection, e -> onToggle(navigator, activeSets,
            ProblemViewComfortScope.Mode.NAVIGATOR));
        activeSets.addListener(SWT.Selection, e -> onToggle(activeSets, navigator,
            ProblemViewComfortScope.Mode.ACTIVE_SETS));

        // Штатные радио «Области возникновения» заполняются раньше нас (мы — перед
        // разделителем), поэтому снимаем их отметки прямо здесь…
        hideNativeScopeMarkWhenComfort(menu);
        // …и на будущие открытия (вдруг фреймворк переотметит по состоянию команды).
        if (!Boolean.TRUE.equals(menu.getData(SHOW_HOOK_KEY)))
        {
            menu.setData(SHOW_HOOK_KEY, Boolean.TRUE);
            menu.addListener(SWT.Show, e -> hideNativeScopeMarkWhenComfort(menu));
        }
    }

    /**
     * Пока активна наша область — снять отметки штатных радио «Области возникновения»
     * (в этом пулдауне только они — {@code SWT.RADIO}; флажок быстрых исправлений —
     * {@code SWT.CHECK}).
     */
    private static void hideNativeScopeMarkWhenComfort(Menu menu)
    {
        if (menu.isDisposed() || !ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE)
            return;
        for (MenuItem item : menu.getItems())
        {
            if (!item.isDisposed() && (item.getStyle() & SWT.RADIO) != 0 && item.getSelection())
                item.setSelection(false);
        }
    }

    private static void onToggle(MenuItem self, MenuItem other, ProblemViewComfortScope.Mode mode)
    {
        boolean on = self.getSelection();
        if (on && !other.isDisposed())
            other.setSelection(false);
        ProblemViewComfortScope.setMode(on ? mode : ProblemViewComfortScope.Mode.NONE);
    }

    private static MenuItem create(Menu menu, int index, String text)
    {
        MenuItem item = index >= 0 ? new MenuItem(menu, SWT.CHECK, index) : new MenuItem(menu, SWT.CHECK);
        item.setText(text);
        return item;
    }
}
