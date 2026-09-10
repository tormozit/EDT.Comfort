package tormozit;

import java.lang.invoke.MethodHandles;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import java.util.concurrent.ConcurrentHashMap;

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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
 * <h3>3. Флажок «Проверять модальные вызовы»</h3>
 * В группе «Общие настройки» (<a href="https://github.com/tormozit/EDT.Comfort/issues/501">issue
 * 501</a>). Включён по умолчанию. Лаунчеры EDT сами всегда вызывают
 * {@code enableCheckModal()} ({@code /EnableCheckModal}). При снятом флажке ключ
 * вырезается из командной строки в {@code appendAdditionalParameters} (подмена
 * тонкого и толстого лаунчера на подклассы, созданные в runtime).
 *
 * <p>В EDT нет ни атрибута конфигурации запуска, ни поля {@code RuntimeExecutionArguments}
 * под эти ключи, поэтому штатного пути «значение → командная строка» не существует.
 * Единственное место, куда произвольный ключ попадает в команду запуска клиента (и толстого,
 * и тонкого), — {@code AbstractRuntimeComponentExecutor.appendAdditionalParameters}, который
 * берёт {@code infobaseManager.findInfobaseByUuid(ref.getUuid()).orElse(ref)
 * .getAdditionalParameters()}. Поле {@code infobaseManager} используется в лаунчерах
 * <b>только</b> там — поэтому его подмена прокси безопасна и точечна. Отсюда:
 * <ul>
 *   <li><b>хранение</b> — свои атрибуты конфигурации запуска пишутся прямо в рабочую
 *       копию диалога (см. {@link VariantField}, {@link CheckModalField});</li>
 *   <li><b>подстановка</b> — у экземпляров лаунчеров подменяется приватное поле
 *       {@code infobaseManager} (см. {@link ManagerHandler}).</li>
 * </ul>
 *
 * <p>В точке подстановки конфигурации запуска уже нет, поэтому ключи запоминаются
 * в {@link #armPendingOption} при добавлении запуска ({@link ILaunchListener#launchAdded})
 * и расходуются первым же обращением к {@code findInfobaseByUuid}.
 */
public final class LaunchConfigurationHook implements IStartup
{
    private static final String TAG = "LaunchConfiguration"; //$NON-NLS-1$
    private static final String ARGUMENTS_TAB_SUFFIX = ".ArgumentsTab"; //$NON-NLS-1$
    private static final String DATA_SEPARATION_FIELD = "dataSeparation"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.launchConfigurationPatched"; //$NON-NLS-1$
    private static final String SCHEDULED_KEY = "tormozit.launchConfigurationScheduled"; //$NON-NLS-1$
    private static final String RUNTIME_CLIENT_TYPE = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    /** Атрибут конфигурации запуска с выбранным вариантом интерфейса. */
    private static final String ATTR_INTERFACE_VARIANT = "tormozit.comfort.launch.interfaceVariant"; //$NON-NLS-1$
    /** Атрибут конфигурации запуска: проверять модальные вызовы (по умолчанию включено). */
    private static final String ATTR_CHECK_MODAL = "tormozit.comfort.launch.checkModal"; //$NON-NLS-1$
    private static final String OPTION_CHECK_MODAL = "EnableCheckModal"; //$NON-NLS-1$
    private static final String LAUNCHER_IMPL_PACKAGE =
        "com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl."; //$NON-NLS-1$
    private static final String BUILDER_INTERNAL =
        "com/_1c/g5/v8/dt/platform/services/core/runtimes/execution/impl/AbstractExecutionCommandBuilder"; //$NON-NLS-1$
    private static final String INFOBASE_INTERNAL =
        "com/_1c/g5/v8/dt/platform/services/model/InfobaseReference"; //$NON-NLS-1$

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
    private static volatile boolean pendingSkipCheckModal;
    private static volatile long pendingSkipStamp;

    /** Оригинальный лаунчер → обёртка с вырезом {@code /EnableCheckModal}. */
    private static final Map<Object, Object> EXECUTOR_WRAPPERS =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Class<?>, Class<?>> LAUNCHER_SUBCLASSES = new ConcurrentHashMap<>();

    /** Лаунчеры уже хотя бы раз подменены — ошибку «менеджер не найден» не повторять. */
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
            if (Global.getField(tab, "technicalSpecialistMode") instanceof Button specialist //$NON-NLS-1$
                && !specialist.isDisposed())
            {
                createCheckModalButton(specialist, dataSeparation, tab, dialog);
            }
            else
                Global.logError(TAG, "не найдена группа «Общие настройки»", null); //$NON-NLS-1$
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
     * {@link #wireHistory}, поэтому подпись ищется подъёмом по родителям. После вставки
     * релейаут идёт по всей вкладке — иначе заголовок остаётся высотой на две строки
     * и сжимает «Разделение данных».
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
        // Сам заголовок уже получил высоту под две строки; layout только его
        // сожмёт новую строку внутри старых границ — релейаут вкладки.
        relayoutTab(tab);
    }

    /**
     * Добавляет флажок в группу «Общие настройки» сразу после штатного
     * «Режим технического специалиста» и связывает его с рабочей копией.
     */
    private static void createCheckModalButton(Button specialist, Text dataSeparation,
        ILaunchConfigurationTab tab, ILaunchConfigurationDialog dialog)
    {
        Composite parent = specialist.getParent();
        if (parent == null || parent.isDisposed())
        {
            Global.logError(TAG, "не найдена группа «Общие настройки»", null); //$NON-NLS-1$
            return;
        }

        Button button = new Button(parent, SWT.CHECK);
        button.setText("Проверять модальные вызовы"); //$NON-NLS-1$
        button.setSelection(true);
        Object layoutData = specialist.getLayoutData();
        if (layoutData instanceof GridData grid)
        {
            GridData copy = new GridData(grid.horizontalAlignment, grid.verticalAlignment,
                grid.grabExcessHorizontalSpace, grid.grabExcessVerticalSpace);
            copy.horizontalSpan = grid.horizontalSpan;
            button.setLayoutData(copy);
        }
        else
            button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.setToolTipText(TooltipText.wrap(button,
            "Проверка модальных вызовов в соответствии с настройками конфигурации" //$NON-NLS-1$
                + Global.pluginSignForTooltip()));
        button.moveBelow(specialist);

        CheckModalField field = new CheckModalField(button, dialog, tab);
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> field.store()));
        dataSeparation.addModifyListener(e -> field.load());
        field.load();
        relayoutTab(tab);
    }

    /**
     * Пересчитывает раскладку вкладки после вставки строки, чтобы заголовок
     * получил свою новую высоту. Если содержимое больше видимой области —
     * диалог чуть увеличивается, иначе «Разделение данных» снова сжимается.
     */
    private static void relayoutTab(ILaunchConfigurationTab tab)
    {
        Control control = tab.getControl();
        if (!(control instanceof Composite tabComposite) || tabComposite.isDisposed())
            return;
        tabComposite.layout(true, true);
        Point have = tabComposite.getSize();
        if (have.x <= 0 || have.y <= 0)
            return;
        int extra = tabComposite.computeSize(have.x, SWT.DEFAULT, true).y - have.y;
        if (extra <= 0)
            return;
        Shell shell = tabComposite.getShell();
        if (shell == null || shell.isDisposed() || shell.getMaximized())
            return;
        Point shellSize = shell.getSize();
        shell.setSize(shellSize.x, shellSize.y + extra);
        shell.layout(true, true);
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
            return workingCopyOf(dialog);
        }
    }

    /** Связка флажка «Проверять модальные вызовы» с рабочей копией конфигурации запуска. */
    private static final class CheckModalField
    {
        private final Button button;
        private final ILaunchConfigurationDialog dialog;
        private final ILaunchConfigurationTab tab;

        CheckModalField(Button button, ILaunchConfigurationDialog dialog, ILaunchConfigurationTab tab)
        {
            this.button = button;
            this.dialog = dialog;
            this.tab = tab;
        }

        void load()
        {
            if (button.isDisposed())
                return;
            boolean value = true;
            ILaunchConfiguration config = workingCopyOf(dialog);
            if (config != null)
            {
                try
                {
                    value = config.getAttribute(ATTR_CHECK_MODAL, true);
                }
                catch (Exception e)
                {
                    Global.logError(TAG, "не удалось прочитать флажок проверки модальных вызовов", e); //$NON-NLS-1$
                }
            }
            button.setSelection(value);
        }

        void store()
        {
            if (button.isDisposed() || !(workingCopyOf(dialog) instanceof ILaunchConfigurationWorkingCopy copy))
                return;
            copy.setAttribute(ATTR_CHECK_MODAL, button.getSelection());
            Global.invoke(tab, "updateLaunchConfigurationDialog"); //$NON-NLS-1$
        }
    }

    private static ILaunchConfiguration workingCopyOf(ILaunchConfigurationDialog dialog)
    {
        Object viewer = Global.invoke(dialog, "getTabViewer"); //$NON-NLS-1$
        Object copy = viewer == null ? null : Global.invoke(viewer, "getWorkingCopy"); //$NON-NLS-1$
        return copy instanceof ILaunchConfiguration config ? config : null;
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
            if (!isRuntimeClient(config))
                return;
            prepareCheckModal(config);
            String option = extraArgsFor(config);
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

    private static String extraArgsFor(ILaunchConfiguration config) throws CoreException
    {
        if (config == null)
            return null;
        StringBuilder sb = new StringBuilder();
        String variant = optionFor(config.getAttribute(ATTR_INTERFACE_VARIANT, "")); //$NON-NLS-1$
        if (variant != null)
            appendArg(sb, variant);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendArg(StringBuilder sb, String arg)
    {
        if (sb.length() > 0)
            sb.append(' ');
        sb.append(arg);
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

    /**
     * Взводит пропуск штатного {@code /EnableCheckModal} по конфигурации запуска клиента.
     * Вызывать до сборки командной строки: из {@code launch()} делегата и из {@code launchAdded}.
     */
    static void prepareCheckModal(ILaunchConfiguration config)
    {
        try
        {
            if (!isRuntimeClient(config))
                return;
            boolean checkModal = config.getAttribute(ATTR_CHECK_MODAL, true);
            armPendingSkipCheckModal(!checkModal);
            patchLaunchers(config, null);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "подготовка флажка проверки модальных вызовов", e); //$NON-NLS-1$
        }
    }

    private static boolean isRuntimeClient(ILaunchConfiguration config)
    {
        try
        {
            return config != null && RUNTIME_CLIENT_TYPE.equals(config.getType().getIdentifier());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static void armPendingSkipCheckModal(boolean skip)
    {
        pendingSkipCheckModal = skip;
        pendingSkipStamp = System.currentTimeMillis();
    }

    /**
     * {@code true} — вырезать штатный {@code /EnableCheckModal} в
     * {@code appendAdditionalParameters}. Флаг не сбрасывается после первого вызова:
     * за один запуск клиента метод может вызваться несколько раз.
     */
    private static boolean shouldSkipCheckModal()
    {
        boolean skip = pendingSkipCheckModal
            && System.currentTimeMillis() - pendingSkipStamp <= PENDING_TTL_MS;
        if (pendingSkipCheckModal && !skip)
            pendingSkipCheckModal = false;
        return skip;
    }

    static void stripCheckModalIfNeeded(Object builder)
    {
        if (!shouldSkipCheckModal() || builder == null)
            return;
        Object raw = Global.getField(builder, "commands"); //$NON-NLS-1$
        if (!(raw instanceof List<?> list))
        {
            Global.logError(TAG, "не найден список команд лаунчера", null); //$NON-NLS-1$
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> commands = (List<Object>)list;
        commands.removeIf(LaunchConfigurationHook::isCheckModalOption);
    }

    private static boolean isCheckModalOption(Object item)
    {
        if (item == null)
            return false;
        String text = item.toString();
        if (!text.isEmpty() && (text.charAt(0) == '/' || text.charAt(0) == '-'))
            text = text.substring(1);
        return OPTION_CHECK_MODAL.equals(text);
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
     * Подменяет лаунчеры EDT: тонкий/толстый клиент — на подклассы с вырезом
     * {@code /EnableCheckModal}; поле {@code infobaseManager} — на прокси варианта интерфейса.
     *
     * <p>Список лаунчеров — приватное {@code executors} менеджера компонентов (загружается
     * лениво, поэтому сначала вызывается {@code getExecutorExtensions}). Вызывается после
     * старта EDT и при каждом запуске клиента: обёртка идемпотентна.
     *
     * @param config конфигурация запуска или {@code null} при подмене после старта EDT
     */
    private static void patchLaunchers(ILaunchConfiguration config, String mode)
    {
        Object manager = findComponentManager(config, mode);
        if (manager == null)
        {
            if (!launchersPatched)
                Global.logError(TAG, "не найден IRuntimeComponentManager", null); //$NON-NLS-1$
            return;
        }
        Object extensions = Global.invoke(manager, "getExecutorExtensions"); //$NON-NLS-1$
        if (!(extensions instanceof Iterable<?> list))
            return;
        int wrapped = 0;
        int proxied = 0;
        for (Object extension : list)
        {
            Object executor = Global.getField(extension, "executor"); //$NON-NLS-1$
            if (executor == null)
                continue;
            Object replacement = wrapExecutor(executor);
            if (replacement != executor && Global.setFieldForce(extension, "executor", replacement)) //$NON-NLS-1$
                wrapped++;
            if (patchInfobaseManager(replacement != null ? replacement : executor))
                proxied++;
        }
        if (wrapped > 0 || proxied > 0)
            launchersPatched = true;
    }

    /**
     * Подменяет тонкий/толстый лаунчер на runtime-подкласс с вырезом
     * {@code /EnableCheckModal}, копируя поля живого экземпляра (Guice-инъекция).
     * Лаунчер сессии конфигуратора сам не наследник толстого: оборачивается
     * только его поле {@code thickClientLauncher}.
     */
    private static Object wrapExecutor(Object executor)
    {
        if (executor == null)
            return null;
        if (isComfortLauncher(executor))
            return executor;
        Object cached = EXECUTOR_WRAPPERS.get(executor);
        if (cached != null)
            return cached;
        Object wrapper;
        if (isLauncher(executor, "ThinClientLauncher")) //$NON-NLS-1$
            wrapper = newComfortLauncher("ThinClientLauncher", "ComfortThinClientLauncher"); //$NON-NLS-1$ //$NON-NLS-2$
        else if (isLauncher(executor, "ThickClientLauncher")) //$NON-NLS-1$
            wrapper = newComfortLauncher("ThickClientLauncher", "ComfortThickClientLauncher"); //$NON-NLS-1$ //$NON-NLS-2$
        else if (isLauncher(executor, "DesignerSessionThickClientLauncher")) //$NON-NLS-1$
        {
            Object inner = Global.getField(executor, "thickClientLauncher"); //$NON-NLS-1$
            Object wrappedInner = wrapExecutor(inner);
            if (wrappedInner != null && wrappedInner != inner)
                Global.setFieldForce(executor, "thickClientLauncher", wrappedInner); //$NON-NLS-1$
            EXECUTOR_WRAPPERS.put(executor, executor);
            return executor;
        }
        else
            return executor;
        if (wrapper == null)
            return executor;
        copyInstanceFields(executor, wrapper);
        EXECUTOR_WRAPPERS.put(executor, wrapper);
        EXECUTOR_WRAPPERS.put(wrapper, wrapper);
        patchInfobaseManager(wrapper);
        return wrapper;
    }

    private static boolean isComfortLauncher(Object executor)
    {
        return executor.getClass().getName().startsWith("tormozit.Comfort"); //$NON-NLS-1$
    }

    private static boolean isLauncher(Object executor, String simpleName)
    {
        Class<?> type = launcherClass(simpleName);
        return type != null && type.isInstance(executor);
    }

    private static Class<?> launcherClass(String simpleName)
    {
        try
        {
            return Class.forName(LAUNCHER_IMPL_PACKAGE + simpleName, false,
                LaunchConfigurationHook.class.getClassLoader());
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    private static Object newComfortLauncher(String launcherSimpleName, String wrapperSimpleName)
    {
        Class<?> launcherType = launcherClass(launcherSimpleName);
        if (launcherType == null)
            return null;
        Class<?> wrapperType = subclassOf(launcherType, wrapperSimpleName);
        if (wrapperType == null)
            return null;
        try
        {
            return wrapperType.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            Global.logError(TAG, "создание обёртки лаунчера " + wrapperSimpleName, e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Runtime-подкласс лаунчера EDT: в исходниках нельзя {@code extends ThickClientLauncher} —
     * JDT тянет {@code IDbUpdateConfirm} из бандла, которого нет в целевой платформе плагина.
     */
    private static Class<?> subclassOf(Class<?> launcherType, String simpleName)
    {
        Class<?> cached = LAUNCHER_SUBCLASSES.get(launcherType);
        if (cached != null)
            return cached;
        synchronized (LAUNCHER_SUBCLASSES)
        {
            cached = LAUNCHER_SUBCLASSES.get(launcherType);
            if (cached != null)
                return cached;
            String internal = "tormozit/" + simpleName; //$NON-NLS-1$
            String superInternal = launcherType.getName().replace('.', '/');
            String desc = "(L" + BUILDER_INTERNAL + ";L" + INFOBASE_INTERNAL + ";)V"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internal, null, superInternal, null);
            MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); //$NON-NLS-1$ //$NON-NLS-2$
            ctor.visitCode();
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false); //$NON-NLS-1$ //$NON-NLS-2$
            ctor.visitInsn(Opcodes.RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PROTECTED, "appendAdditionalParameters", //$NON-NLS-1$
                desc, null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "tormozit/LaunchConfigurationHook", //$NON-NLS-1$
                "stripCheckModalIfNeeded", "(Ljava/lang/Object;)V", false); //$NON-NLS-1$ //$NON-NLS-2$
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "appendAdditionalParameters", //$NON-NLS-1$
                desc, false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
            writer.visitEnd();
            try
            {
                Class<?> defined = MethodHandles.lookup().defineClass(writer.toByteArray());
                LAUNCHER_SUBCLASSES.put(launcherType, defined);
                return defined;
            }
            catch (Throwable t)
            {
                Global.logError(TAG, "не удалось создать подкласс лаунчера " + simpleName, t); //$NON-NLS-1$
                return null;
            }
        }
    }

    private static boolean patchInfobaseManager(Object executor)
    {
        if (executor == null || PATCHED_EXECUTORS.contains(executor))
            return false;
        Object real = Global.getField(executor, "infobaseManager"); //$NON-NLS-1$
        if (!(real instanceof IInfobaseManager infobaseManager) || Proxy.isProxyClass(real.getClass()))
            return false;
        Object proxy = Proxy.newProxyInstance(IInfobaseManager.class.getClassLoader(),
            new Class<?>[] { IInfobaseManager.class }, new ManagerHandler(infobaseManager));
        if (!Global.setFieldForce(executor, "infobaseManager", proxy)) //$NON-NLS-1$
            return false;
        PATCHED_EXECUTORS.add(executor);
        return true;
    }

    private static void copyInstanceFields(Object from, Object to)
    {
        for (Class<?> type = from.getClass(); type != null && type != Object.class; type = type.getSuperclass())
        {
            for (Field field : type.getDeclaredFields())
            {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic())
                    continue;
                try
                {
                    field.setAccessible(true);
                    field.set(to, field.get(from));
                }
                catch (Exception e)
                {
                    Global.logError(TAG, "копирование поля лаунчера " + field.getName(), e); //$NON-NLS-1$
                }
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
