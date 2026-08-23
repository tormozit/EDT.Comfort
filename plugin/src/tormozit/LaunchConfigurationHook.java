package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchDelegate;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Доработки вкладки «Аргументы» диалога конфигураций запуска ({@code ArgumentsTab}).
 *
 * <h3>1. История значений текстовых полей</h3>
 * Разделение данных, параметр запуска, файл журнала, имя PWA
 * (<a href="https://github.com/tormozit/EDT.Comfort/issues/259">issue 259</a>).
 * UI и хранилище — как у {@link FilterInputBox}: персистентная история
 * ({@link FilterHistoryStore}) + кнопка ▾ и Ctrl+↓ ({@link FilterHistoryUi}).
 * У каждого поля свой {@code scopeId}, чтобы истории не смешивались. Штатные поля
 * остаются обычным {@link Text} (связаны с атрибутами конфигурации запуска через
 * ModifyListener самой вкладки). Поля лежат в {@code GridLayout} вместе с подписями
 * и кнопкой «Выбрать…»; отдельная колонка ▾ в том же parent ломала бы раскладку,
 * поэтому Text переносится в узкий ряд {@code [поле][▾]}.
 *
 * <h3>2. Поле «Вариант интерфейса»</h3>
 * Над «Разделение данных» (<a href="https://github.com/tormozit/EDT.Comfort/issues/368">issue
 * 368</a>). Значение превращается в ключ командной строки клиента 1С: «Такси» →
 * {@code /iTaxi}, «8.5» → {@code /i85}, пусто → ключ не добавляется.
 *
 * <p>В EDT нет ни атрибута конфигурации запуска, ни поля {@code RuntimeExecutionArguments}
 * под вариант интерфейса, поэтому штатного пути «значение → командная строка» не существует.
 * Единственное место, куда произвольный ключ попадает в команду запуска клиента (и толстого,
 * и тонкого), — {@code AbstractRuntimeComponentExecutor.appendAdditionalParameters}, который
 * берёт {@code infobaseManager.findInfobaseByUuid(ref.getUuid()).orElse(ref)
 * .getAdditionalParameters()}. Поле {@code infobaseManager} используется в лаунчерах
 * <b>только</b> там — поэтому его подмена прокси безопасна и точечна. Отсюда:
 * <ul>
 *   <li><b>хранение</b> — свой атрибут конфигурации запуска пишется прямо в рабочую
 *       копию диалога (см. {@link VariantField});</li>
 *   <li><b>подстановка</b> — у экземпляров лаунчеров подменяется приватное поле
 *       {@code infobaseManager} (см. {@link ManagerHandler}).</li>
 * </ul>
 *
 * <p>В точке подстановки конфигурации запуска уже нет, поэтому вариант запоминается
 * в {@link #armPendingOption} при добавлении запуска ({@link ILaunchListener#launchAdded})
 * и расходуется первым же обращением к {@code findInfobaseByUuid}.
 */
public final class LaunchConfigurationHook implements IStartup
{
    private static final String TAG = "LaunchConfiguration"; //$NON-NLS-1$
    private static final String ARGUMENTS_TAB_SUFFIX = ".ArgumentsTab"; //$NON-NLS-1$
    private static final String DATA_SEPARATION_FIELD = "dataSeparation"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.launchConfigurationPatched"; //$NON-NLS-1$
    private static final String SCHEDULED_KEY = "tormozit.launchConfigurationScheduled"; //$NON-NLS-1$

    /** Атрибут конфигурации запуска с выбранным вариантом интерфейса. */
    private static final String ATTR_INTERFACE_VARIANT = "tormozit.comfort.launch.interfaceVariant"; //$NON-NLS-1$

    private static final String VARIANT_TAXI = "Taxi"; //$NON-NLS-1$
    private static final String VARIANT_V85 = "V85"; //$NON-NLS-1$

    /** Значения атрибута по позициям {@link Combo}. */
    private static final String[] VARIANT_KEYS = { "", VARIANT_TAXI, VARIANT_V85 }; //$NON-NLS-1$
    /** Надписи в {@link Combo} по тем же позициям. */
    private static final String[] VARIANT_LABELS = { "", "Такси", "8.5" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** Сколько живёт взведённый вариант, если до лаунчера дело так и не дошло. */
    private static final long PENDING_TTL_MS = 120_000L;

    /** Глубина обхода полей при поиске настоящего менеджера компонентов за прокси. */
    private static final int MANAGER_SCAN_DEPTH = 4;

    /** Через сколько после старта EDT подменять поле {@code infobaseManager} у лаунчеров. */
    private static final int STARTUP_PATCH_DELAY_MS = 10_000;

    /** Текстовые поля {@code ArgumentsTab} → отдельный scope истории и подсказка кнопки. */
    private static final TextField[] TEXT_FIELDS = {
        new TextField(DATA_SEPARATION_FIELD, "launchDataSeparation", //$NON-NLS-1$
            "История разделения данных (или Ctrl+↓ в поле)"), //$NON-NLS-1$
        // scope сохранён от первой версии хука — уже накопленная история не сбрасывается
        new TextField("startupOption", "launchStartupOption", //$NON-NLS-1$ //$NON-NLS-2$
            "История параметров запуска (или Ctrl+↓ в поле)"), //$NON-NLS-1$
        new TextField("logFile", "launchLogFile", //$NON-NLS-1$ //$NON-NLS-2$
            "История файлов журнала (или Ctrl+↓ в поле)"), //$NON-NLS-1$
        new TextField("pwaNameText", "launchPwaName", //$NON-NLS-1$ //$NON-NLS-2$
            "История имён PWA (или Ctrl+↓ в поле)"), //$NON-NLS-1$
    };

    /** Лаунчеры, у которых поле {@code infobaseManager} уже подменено. */
    private static final Set<Object> PATCHED_EXECUTORS =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile String pendingOption;
    private static volatile long pendingStamp;

    /** Лаунчеры уже подменены — повторный поиск менеджера при каждом запуске не нужен. */
    private static volatile boolean launchersPatched;

    /**
     * Запасной источник {@code IRuntimeComponentManager}: то же поле есть у самой вкладки
     * ({@code AbstractRuntimeClientTab.runtimeComponentManager}), если диалог уже открывали.
     */
    private static volatile Object componentManagerFromTab;

    private static final class TextField
    {
        final String fieldName;
        final String scopeId;
        final String tooltip;

        TextField(String fieldName, String scopeId, String tooltip)
        {
            this.fieldName = fieldName;
            this.scopeId = scopeId;
            this.tooltip = tooltip;
        }
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            installDialogListener(Display.getDefault());
            installLaunchListener();
            // Заранее, чтобы подмена не совпала с первым запуском: сервисы EDT к этому
            // моменту уже зарегистрированы в OSGi.
            Display.getDefault().timerExec(STARTUP_PATCH_DELAY_MS, () -> patchLaunchers(null, null));
        });
    }

    // -----------------------------------------------------------------------
    // UI вкладки «Аргументы»
    // -----------------------------------------------------------------------

    private static void installDialogListener(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            Shell shell = control.getShell();
            if (shell == null || shell.isDisposed())
                return;
            if (!(shell.getData() instanceof ILaunchConfigurationDialog))
                return;
            scheduleTryPatch(shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void scheduleTryPatch(Shell shell)
    {
        if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(SCHEDULED_KEY)))
            return;
        shell.setData(SCHEDULED_KEY, Boolean.TRUE);
        shell.getDisplay().timerExec(50, () ->
        {
            if (!shell.isDisposed())
                shell.setData(SCHEDULED_KEY, null);
            tryPatch(shell);
        });
    }

    private static void tryPatch(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (!(shell.getData() instanceof ILaunchConfigurationDialog dialog))
            return;
        ILaunchConfigurationTab[] tabs = dialog.getTabs();
        if (tabs == null)
            return;
        for (ILaunchConfigurationTab tab : tabs)
        {
            if (tab == null || !tab.getClass().getName().endsWith(ARGUMENTS_TAB_SUFFIX))
                continue;
            Control control = tab.getControl();
            if (control == null || control.isDisposed())
                return;
            if (Boolean.TRUE.equals(control.getData(PATCHED_KEY)))
                return;

            // Сразу, до мутаций — иначе повторный Show/Activate успеет вставить второй набор.
            control.setData(PATCHED_KEY, Boolean.TRUE);
            if (componentManagerFromTab == null)
                componentManagerFromTab = Global.getField(tab, "runtimeComponentManager"); //$NON-NLS-1$
            Text dataSeparation = null;
            for (TextField spec : TEXT_FIELDS)
            {
                if (!(Global.getField(tab, spec.fieldName) instanceof Text text) || text.isDisposed())
                    continue;
                if (DATA_SEPARATION_FIELD.equals(spec.fieldName))
                    dataSeparation = text;
                wireHistory(text, spec);
            }
            if (dataSeparation == null)
                return;
            createVariantCombo(dataSeparation, tab, dialog);
            return;
        }
    }

    // -----------------------------------------------------------------------
    // История значений текстовых полей
    // -----------------------------------------------------------------------

    private static void wireHistory(Text text, TextField spec)
    {
        Composite parent = text.getParent();
        if (parent == null || parent.isDisposed())
            return;

        Control before = siblingBefore(text);
        Control after = siblingAfter(text);
        Object layoutData = text.getLayoutData();

        Composite row = new Composite(parent, SWT.NONE);
        GridLayout rowLayout = new GridLayout(1, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.horizontalSpacing = 2;
        row.setLayout(rowLayout);
        if (layoutData != null)
            row.setLayoutData(layoutData);
        else
            row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        text.setParent(row);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Важно для logFile: после setParent ряд оказывается в конце, а «Выбрать…»
        // остаётся первым — возвращаем ряд на место текста (перед бывшим соседом справа).
        if (after != null && !after.isDisposed())
            row.moveAbove(after);
        else if (before != null && !before.isDisposed())
            row.moveBelow(before);

        FilterHistoryUi.wireKeyboard(text, spec.scopeId);
        Composite buttonsRow = FilterHistoryUi.createButtonsRow(row);
        FilterHistoryUi.addHistoryButton(buttonsRow, text, spec.scopeId,
            spec.tooltip + Global.pluginSignForTooltip());
        parent.layout(true, true);
    }

    private static Control siblingBefore(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control)
                return i > 0 ? children[i - 1] : null;
        }
        return null;
    }

    private static Control siblingAfter(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control)
                return i + 1 < children.length ? children[i + 1] : null;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Поле «Вариант интерфейса»
    // -----------------------------------------------------------------------

    /**
     * Вставляет подпись и {@link Combo} над строкой «Разделение данных» и связывает поле
     * с рабочей копией конфигурации запуска.
     *
     * <p>Само поле «Разделение данных» лежит в двухколоночном {@code GridLayout} рядом со
     * своей подписью, но {@link Text} к этому моменту уже перенесён в свой ряд
     * {@link #wireHistory}, поэтому подпись ищется подъёмом по родителям.
     *
     * <p>Штатная вкладка про наш атрибут не знает, поэтому значение пишется прямо в
     * рабочую копию диалога ({@code LaunchConfigurationTabGroupViewer.getWorkingCopy}) —
     * ту же, которую сохраняет «Применить». Обратное чтение вызывается по изменению
     * «Разделения данных»: это признак того, что диалог только что выполнил
     * {@code initializeFrom} для другой конфигурации.
     */
    private static void createVariantCombo(Text dataSeparation, ILaunchConfigurationTab tab,
        ILaunchConfigurationDialog dialog)
    {
        Control cell = dataSeparation;
        Composite parent = cell.getParent();
        Control anchor = null;
        while (parent != null && !parent.isDisposed())
        {
            Control before = siblingBefore(cell);
            if (before instanceof Label && parent.getLayout() instanceof GridLayout grid && grid.numColumns == 2)
            {
                anchor = before;
                break;
            }
            cell = parent;
            parent = parent.getParent();
        }
        if (anchor == null || parent == null || parent.isDisposed())
        {
            Global.logError(TAG, "не найдена строка поля «Разделение данных»", null); //$NON-NLS-1$
            return;
        }

        Label label = new Label(parent, SWT.NONE);
        label.setText("Вариант интерфейса"); //$NON-NLS-1$
        label.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

        Combo combo = new Combo(parent, SWT.READ_ONLY | SWT.DROP_DOWN);
        combo.setItems(VARIANT_LABELS);
        combo.select(0);
        combo.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        combo.setToolTipText(TooltipText.wrap(combo,
            "Ключ командной строки клиента 1С: «Такси» — /iTaxi, «8.5» — /i85. Пусто — ключ не добавляется." //$NON-NLS-1$
                + Global.pluginSignForTooltip()));

        VariantField field = new VariantField(combo, dialog, tab);
        combo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> field.store()));
        dataSeparation.addModifyListener(e -> field.load());
        field.load();

        label.moveAbove(anchor);
        combo.moveAbove(anchor);
        parent.layout(true, true);
    }

    /** Связка {@link Combo} с рабочей копией конфигурации запуска, открытой в диалоге. */
    private static final class VariantField
    {
        private final Combo combo;
        private final ILaunchConfigurationDialog dialog;
        private final ILaunchConfigurationTab tab;

        VariantField(Combo combo, ILaunchConfigurationDialog dialog, ILaunchConfigurationTab tab)
        {
            this.combo = combo;
            this.dialog = dialog;
            this.tab = tab;
        }

        /** Показывает в поле значение текущей конфигурации диалога. */
        void load()
        {
            if (combo.isDisposed())
                return;
            String value = ""; //$NON-NLS-1$
            ILaunchConfiguration config = workingCopy();
            if (config != null)
            {
                try
                {
                    value = config.getAttribute(ATTR_INTERFACE_VARIANT, ""); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    Global.logError(TAG, "не удалось прочитать вариант интерфейса", e); //$NON-NLS-1$
                }
            }
            combo.select(indexOfVariant(value));
        }

        /** Пишет выбранное значение в рабочую копию и обновляет кнопки диалога. */
        void store()
        {
            if (combo.isDisposed() || !(workingCopy() instanceof ILaunchConfigurationWorkingCopy copy))
                return;
            int index = combo.getSelectionIndex();
            copy.setAttribute(ATTR_INTERFACE_VARIANT,
                index > 0 && index < VARIANT_KEYS.length ? VARIANT_KEYS[index] : null);
            // Кнопка «Применить» включается по расхождению рабочей копии с сохранённой.
            Global.invoke(tab, "updateLaunchConfigurationDialog"); //$NON-NLS-1$
        }

        private ILaunchConfiguration workingCopy()
        {
            Object viewer = Global.invoke(dialog, "getTabViewer"); //$NON-NLS-1$
            Object copy = viewer == null ? null : Global.invoke(viewer, "getWorkingCopy"); //$NON-NLS-1$
            return copy instanceof ILaunchConfiguration config ? config : null;
        }
    }

    private static int indexOfVariant(String value)
    {
        for (int i = 0; i < VARIANT_KEYS.length; i++)
        {
            if (VARIANT_KEYS[i].equals(value))
                return i;
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Подстановка ключа в командную строку запуска
    // -----------------------------------------------------------------------

    private static void installLaunchListener()
    {
        DebugPlugin plugin = DebugPlugin.getDefault();
        if (plugin == null)
            return;
        plugin.getLaunchManager().addLaunchListener(new ILaunchListener()
        {
            @Override
            public void launchAdded(ILaunch launch)
            {
                prepareLaunch(launch);
            }

            @Override
            public void launchChanged(ILaunch launch)
            {
                // значение уже взведено при добавлении запуска
            }

            @Override
            public void launchRemoved(ILaunch launch)
            {
                // ничего
            }
        });
    }

    private static void prepareLaunch(ILaunch launch)
    {
        try
        {
            ILaunchConfiguration config = launch == null ? null : launch.getLaunchConfiguration();
            String option = config == null ? null : optionFor(config.getAttribute(ATTR_INTERFACE_VARIANT, "")); //$NON-NLS-1$
            if (option == null)
            {
                pendingOption = null;
                return;
            }
            patchLaunchers(config, launch.getLaunchMode());
            armPendingOption(option);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "подготовка запуска", e); //$NON-NLS-1$
        }
    }

    private static String optionFor(String variant)
    {
        if (VARIANT_TAXI.equals(variant))
            return "/iTaxi"; //$NON-NLS-1$
        if (VARIANT_V85.equals(variant))
            return "/i85"; //$NON-NLS-1$
        return null;
    }

    private static void armPendingOption(String option)
    {
        pendingOption = option;
        pendingStamp = System.currentTimeMillis();
    }

    /** Взведённый вариант расходуется первым же обращением лаунчера к менеджеру ИБ. */
    private static String consumePendingOption()
    {
        String option = pendingOption;
        if (option == null)
            return null;
        pendingOption = null;
        if (System.currentTimeMillis() - pendingStamp > PENDING_TTL_MS)
            return null;
        return option;
    }

    /**
     * Подменяет у лаунчеров EDT приватное поле {@code infobaseManager} на прокси.
     *
     * <p>Список лаунчеров — приватное {@code executors} менеджера компонентов (загружается
     * лениво, поэтому сначала вызывается {@code getExecutorExtensions}). Вызывается один
     * раз после старта EDT и повторно при запуске, если тогда лаунчеры ещё не нашлись.
     *
     * @param config конфигурация запуска или {@code null} при подмене после старта EDT
     */
    private static void patchLaunchers(ILaunchConfiguration config, String mode)
    {
        if (launchersPatched)
            return;
        Object manager = findComponentManager(config, mode);
        if (manager == null)
        {
            Global.logError(TAG, "не найден IRuntimeComponentManager", null); //$NON-NLS-1$
            return;
        }
        Object extensions = Global.invoke(manager, "getExecutorExtensions"); //$NON-NLS-1$
        if (!(extensions instanceof Iterable<?> list))
            return;
        for (Object extension : list)
        {
            Object executor = Global.getField(extension, "executor"); //$NON-NLS-1$
            if (executor == null || PATCHED_EXECUTORS.contains(executor))
                continue;
            Object real = Global.getField(executor, "infobaseManager"); //$NON-NLS-1$
            if (!(real instanceof IInfobaseManager infobaseManager) || Proxy.isProxyClass(real.getClass()))
                continue;
            Object proxy = Proxy.newProxyInstance(IInfobaseManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class }, new ManagerHandler(infobaseManager));
            if (Global.setFieldForce(executor, "infobaseManager", proxy)) //$NON-NLS-1$
            {
                PATCHED_EXECUTORS.add(executor);
                launchersPatched = true;
            }
        }
    }

    /**
     * Ищет настоящий {@code RuntimeComponentManager} среди доступных источников.
     *
     * <p>Инъекция в EDT подставляет не сам сервис, а ленивый прокси peaberry (класс вида
     * {@code IRuntimeComponentManager$xxxxxxx} с единственным полем {@code Import}), у которого
     * нет внутреннего списка лаунчеров. Пока сервис не «материализован», добраться до
     * настоящего объекта через прокси нельзя, поэтому надёжный источник —
     * реестр OSGi ({@link Global#getOsgiService}); остальные кандидаты идут как запасные
     * и дополнительно разворачиваются ({@link #unwrapComponentManager}).
     */
    private static Object findComponentManager(ILaunchConfiguration config, String mode)
    {
        for (Object candidate : componentManagerCandidates(config, mode))
        {
            Object manager = unwrapComponentManager(candidate, MANAGER_SCAN_DEPTH,
                Collections.newSetFromMap(new IdentityHashMap<>()));
            if (manager != null)
                return manager;
        }
        return null;
    }

    /** Источники менеджера компонентов по убыванию надёжности. */
    private static List<Object> componentManagerCandidates(ILaunchConfiguration config, String mode)
    {
        List<Object> candidates = new ArrayList<>();
        try
        {
            // config == null — подмена после старта EDT, делегата запуска ещё нет.
            ILaunchConfigurationType type = config == null ? null : config.getType();
            if (type != null)
                addDelegateCandidates(candidates, type, mode);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "делегат запуска", e); //$NON-NLS-1$
        }
        if (componentManagerFromTab != null)
            candidates.add(componentManagerFromTab);
        Object osgi = Global.getOsgiService(IRuntimeComponentManager.class);
        if (osgi != null)
            candidates.add(osgi);
        return candidates;
    }

    private static void addDelegateCandidates(List<Object> candidates, ILaunchConfigurationType type, String mode)
        throws CoreException
    {
        ILaunchDelegate[] delegates = type.getDelegates(Set.of(mode));
        for (ILaunchDelegate delegate : delegates)
        {
            Object target = delegate.getDelegate();
            Object manager = Global.getField(target, "runtimeComponentManager"); //$NON-NLS-1$
            if (manager != null)
                candidates.add(manager);
            if (target != null)
                candidates.add(target);
        }
    }

    /**
     * Ищет настоящий {@code RuntimeComponentManager} за ленивым прокси сервиса EDT.
     *
     * <p>Инъекция подставляет прокси (класс вида {@code IRuntimeComponentManager$xxxxxxx}),
     * у которого нет внутреннего списка лаунчеров. Признак настоящего объекта — приватный
     * метод {@code getExecutorExtensions}; ищется он обходом полей прокси и его
     * {@link InvocationHandler}.
     */
    private static Object unwrapComponentManager(Object value, int depth, Set<Object> seen)
    {
        if (value == null || depth < 0 || !seen.add(value))
            return null;
        if (hasExecutorExtensions(value.getClass()))
            return value;
        if (Proxy.isProxyClass(value.getClass()))
        {
            Object found = unwrapComponentManager(Proxy.getInvocationHandler(value), depth - 1, seen);
            if (found != null)
                return found;
        }
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Field field : c.getDeclaredFields())
            {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                    || field.getType() == String.class)
                    continue;
                Object nested;
                try
                {
                    field.setAccessible(true);
                    nested = field.get(value);
                }
                catch (Exception ignored)
                {
                    continue;
                }
                Object found = unwrapComponentManager(nested, depth - 1, seen);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean hasExecutorExtensions(Class<?> type)
    {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
        {
            for (Method method : c.getDeclaredMethods())
            {
                if (method.getParameterCount() == 0 && "getExecutorExtensions".equals(method.getName())) //$NON-NLS-1$
                    return true;
            }
        }
        return false;
    }

    /**
     * Прокси {@code IInfobaseManager} лаунчера: в {@code findInfobaseByUuid} возвращает
     * информационную базу с дописанным ключом варианта интерфейса.
     */
    private static final class ManagerHandler implements InvocationHandler
    {
        private final IInfobaseManager target;

        ManagerHandler(IInfobaseManager target)
        {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            Object result;
            try
            {
                result = method.invoke(target, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() != null ? e.getCause() : e;
            }
            if (!"findInfobaseByUuid".equals(method.getName()) || !(result instanceof Optional<?> found)) //$NON-NLS-1$
                return result;
            if (found.isEmpty() || !(found.get() instanceof InfobaseReference reference))
                return result;
            String option = consumePendingOption();
            return option == null ? result : Optional.of(decorate(reference, option));
        }

        private static InfobaseReference decorate(InfobaseReference reference, String option)
        {
            return (InfobaseReference)Proxy.newProxyInstance(InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class }, (proxy, method, args) ->
                {
                    if ((args == null || args.length == 0)
                        && "getAdditionalParameters".equals(method.getName())) //$NON-NLS-1$
                    {
                        String base = reference.getAdditionalParameters();
                        return base == null || base.isBlank() ? option : base + " " + option; //$NON-NLS-1$
                    }
                    try
                    {
                        return method.invoke(reference, args);
                    }
                    catch (InvocationTargetException e)
                    {
                        throw e.getCause() != null ? e.getCause() : e;
                    }
                });
        }
    }
}
