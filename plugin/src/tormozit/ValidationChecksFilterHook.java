package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.core.databinding.observable.IObservable;
import org.eclipse.core.databinding.observable.value.IObservableValue;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;

import com._1c.g5.v8.dt.common.localization.LocalizedEnumProvider;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.ui.DtUiUtil;
import com._1c.g5.v8.dt.ui.V8UiSharedImages;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.ui.validation.ChecksViewerControl;
import com._1c.g5.v8.dt.ui.validation.ChecksViewerProvider;
import com._1c.g5.v8.dt.ui.validation.IChecksTreeNode;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.INamedElement;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

/**
 * Многословный фильтр ({@link SmartMatcher}, AND по словам) на странице «Валидация»
 * (Свойства проекта / Параметры → V8 → Валидация,
 * {@code com._1c.g5.v8.dt.internal.ui.validation.ValidationPreferencePage}).
 *
 * <p>Разведка декомпиляцией показала: поле поиска там уже штатный
 * {@link SearchBox} (не голый {@code Text}), но матчинг по тексту делает не
 * {@code ViewerFilter}, а отдельный {@code CheckFilter} (predicate,
 * {@code com._1c.g5.v8.dt.common.StringUtils.fuzzyMatch} — нечёткая подстрока)
 * прямо внутри content-провайдера дерева ({@code ChecksTreeProvider}). Пакет
 * {@code internal.ui.validation} не экспортирован — {@code CheckFilter}/
 * {@code ChecksTreeViewer}/страница недоступны для прямого импорта, только
 * рефлексия; {@code ChecksViewerControl}/{@code IChecksTreeNode}
 * (пакет {@code com._1c.g5.v8.dt.ui.validation}) экспортированы и используются
 * напрямую.
 *
 * <p>Согласовано с пользователем: поверх штатного дерева добавляется
 * собственный {@link ViewerFilter} на {@link SmartMatcher}, а штатный
 * {@code CheckFilter} не трогается вовсе — вместо него {@code SearchBox}
 * получает свою персистентную историю ({@link FilterInputBox#attachHistoryKeepLayout}
 * / {@link FilterInputBox.Scope#VALIDATION_CHECKS} — обязательно, см. правило
 * «Подключение фильтра» в AGENTS.md: голая {@code InMemorySearchHistory} теряет
 * историю при закрытии диалога; compact-ширину не ставим — штатное поле
 * тянется в строке с тулбаром), поэтому {@code CheckFilter.getActivePattern()}
 * остаётся пустым навсегда и его текстовый матчинг (fuzzy) становится
 * безусловно {@code true} ({@code testSearchWithoutId} — байткод подтверждает
 * short-circuit на пустом паттерне). Это исключает риск
 * порчи истории поиска (которую до правки хранил тот же {@code CheckFilter}
 * как {@code ISearchHistory}) — вариант с принудительным сбросом его паттерна
 * после каждого поиска отвергнут именно из-за этого риска.
 *
 * <p>Узел дерева виден, если под {@link SmartMatcher#matches(String)}
 * попадает его собственный заголовок ({@link IChecksTreeNode#getValue()}.
 * {@code getTitle()}) или заголовок любого потомка — правило одинаково для
 * категорий и отдельных проверок.
 *
 * <p>Тот же {@link ValidationSearchFilter} чинит переключатели отбора
 * (критичность, тип, включённые/выключенные, изменённые/неизменённые), которые
 * в EDT не работают вовсе — баг
 * <a href="https://github.com/1C-Company/1c-edt-issues/issues/2314">2314</a>,
 * разбор причины см. в javadoc самого фильтра.
 *
 * <p>Подсветка совпадений: у страницы уже есть штатная подсветка
 * ({@code SearchStyledLabelProviderDelegate} на единственной колонке дерева),
 * но она тоже читает паттерн из {@code ISearchHistory}, переданного в
 * конструктор {@code ChecksTreeViewer} (тот же {@code CheckFilter}) —
 * захваченного ОДИН РАЗ при создании дерева, до нашего патча. Подмена
 * {@code searchBox.setHistory(...)} на неё не влияет (другой объект), поэтому
 * своя подсветка через {@link CellLabelHighlightWrapper} на колонке 0
 * {@code checksViewer} — отдельно от {@link ValidationSearchFilter}.
 *
 * <p>Ctrl+C на дереве проверок и полях страницы (идентификатор {@code StyledText},
 * параметры проверки, …) — через {@link CopyCommandSupport}: в диалоге свойств
 * Win32-акселератор Copy съедает клавишу до SWT. Поля параметров пересоздаются
 * при смене строки — донастраиваем копирование лениво по {@code FocusIn}.
 */
public final class ValidationChecksFilterHook implements IStartup
{
    private static final String PAGE_CLASS_NAME =
        "com._1c.g5.v8.dt.internal.ui.validation.ValidationPreferencePage"; //$NON-NLS-1$
    /** Новое название страницы (issue 401): «Валидация» → «Проверки». */
    static final String PAGE_TITLE = "Проверки"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.validationChecksFilterPatched"; //$NON-NLS-1$
    private static final String PAGE_COPY_ROOT_KEY = "tormozit.validationPageCopyRoot"; //$NON-NLS-1$
    private static final String COPY_WIRED_KEY = "tormozit.validationCopyWired"; //$NON-NLS-1$
    private static final String COMPACT_ROW_KEY = "tormozit.validationSettingsRowCompact"; //$NON-NLS-1$
    private static final int MAX_ATTEMPTS = 30;
    private static final int RETRY_MS = 100;
    /** Колонки-иконки таблицы («Тип», «Критичность») идут первыми и ширины не сохраняют. */
    private static final int ICON_COLUMN_COUNT = 2;
    /** Запас ширины первой колонки под пометку строки (Windows рисует её в первой колонке). */
    private static final int CHECK_BOX_WIDTH = 22;
    /** Минимальная ширина полей правой панели — она и определяет, насколько её можно сузить. */
    private static final int NARROW_FIELD_WIDTH = 60;
    /** Предпочтительная ширина области «список проверок + панель настроек» (см. {@link #relaxBodyMinimumWidth}). */
    private static final int BODY_WIDTH_HINT = 300;
    /** Страница «Аннотации» окна «Параметры» — та же, что открывает линейка обзора. */
    private static final String ANNOTATIONS_PAGE_ID = "org.eclipse.ui.editors.preferencePages.Annotations"; //$NON-NLS-1$
    /** Критичность, показанная значком аннотации, — по ней открываются её настройки. */
    private static final String MODULE_SEVERITY_KEY = "tormozit.moduleSeverity"; //$NON-NLS-1$
    /** Служба, знающая соответствие критичности и вида аннотации (см. {@link #moduleAnnotation}). */
    private static final String CHECK_EXECUTOR_CLASS = "com.e1c.g5.v8.dt.internal.check.ICheckExecutor"; //$NON-NLS-1$

    private static final Map<IssueSeverity, ModuleAnnotation> MODULE_ANNOTATIONS =
        new EnumMap<>(IssueSeverity.class);
    private static Object checkExecutorService;
    private static boolean checkExecutorResolved;
    private static final String EXCLUDED_NAMES_FRAGMENT = "исключаемых имен объектов"; //$NON-NLS-1$
    private static final String EXCLUDED_NAMES_SHORT = "исключаемых объектов"; //$NON-NLS-1$
    /** Название типа {@code IssueType.WARNING} в местах, которые перехватывает плагин (issue 401). */
    static final String OTHER_WARNING_TYPE_TITLE = "Прочее предупреждение"; //$NON-NLS-1$

    private static final WeakHashMap<Shell, Boolean> pendingWiring = new WeakHashMap<>();
    private static final Map<Display, Image> OTHER_WARNING_ICONS = new WeakHashMap<>();
    private static final Map<Display, Image> TABLE_MODE_ICONS = new WeakHashMap<>();
    private static boolean lazyCopyFilterInstalled;
    /** Сколько страниц проверок сейчас открыто — ограничивает патч меню отбора (см. {@link #patchTypeMenu}). */
    private static int openPages;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (event.widget instanceof Menu menu)
            {
                patchTypeMenu(menu);
                return;
            }
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    /**
     * Подменю «Тип» в меню отбора над списком проверок: пункт «Предупреждение»
     * показываем как «Прочее предупреждение» с нейтральной иконкой (issue 401).
     *
     * <p>Меню строится заново на каждый показ (штатный {@code CheckFilterMenuProvider}
     * пересоздаёт {@code MenuManager} в {@code Action.run}), поэтому ловим показ,
     * а не создание. Чтобы не задеть чужие меню EDT, требуем два признака сразу:
     * страница проверок сейчас открыта и в этом же меню есть пункты других типов
     * проблем. Названия сравниваем с {@code LocalizedEnumProvider}, а не с
     * жёсткой строкой — на другом языке интерфейса тоже сработает.
     */
    private static void patchTypeMenu(Menu menu)
    {
        if (openPages <= 0 || menu.isDisposed())
            return;

        String warningTitle = LocalizedEnumProvider.getLocalizedString(IssueType.WARNING);
        String spellingTitle = LocalizedEnumProvider.getLocalizedString(IssueType.SPELLING);
        String codeStyleTitle = LocalizedEnumProvider.getLocalizedString(IssueType.CODE_STYLE);
        MenuItem warningItem = null;
        boolean otherTypesPresent = false;
        for (MenuItem item : menu.getItems())
        {
            String text = item.getText();
            if (text == null)
                continue;
            if (text.equals(warningTitle))
                warningItem = item;
            else if (text.equals(spellingTitle) || text.equals(codeStyleTitle))
                otherTypesPresent = true;
        }
        if (warningItem == null || !otherTypesPresent)
            return;

        warningItem.setText(OTHER_WARNING_TYPE_TITLE);
        Image icon = typeImage(menu.getDisplay(), IssueType.WARNING);
        if (icon != null)
            warningItem.setImage(icon);
        Debug.temp("patchTypeMenu: renamed"); //$NON-NLS-1$
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            if (current.getData() instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
    {
        synchronized (pendingWiring)
        {
            if (Boolean.TRUE.equals(pendingWiring.get(shell)))
                return;
            pendingWiring.put(shell, Boolean.TRUE);
        }

        IPageChangedListener pageListener = event -> tryPatchSelected(event.getSelectedPage(), dialog);
        dialog.addPageChangedListener(pageListener);
        scheduleRetry(display, shell, dialog, 0);
    }

    private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (tryPatchSelected(dialog.getSelectedPage(), dialog) || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
    }

    /** @return {@code true}, если для текущей выбранной страницы больше нечего делать (не наша страница или уже пропатчена). */
    private static boolean tryPatchSelected(Object selected, PreferenceDialog dialog)
    {
        if (!(selected instanceof IPreferencePage page) || !PAGE_CLASS_NAME.equals(page.getClass().getName()))
            return true;
        return tryPatch(page, dialog);
    }

    private static boolean tryPatch(IPreferencePage page, PreferenceDialog dialog)
    {
        try
        {
            Object controlObj = Global.getField(page, "checksViewerControl"); //$NON-NLS-1$
            if (!(controlObj instanceof ChecksViewerControl control))
            {
                Debug.log("tryPatch WAIT: checksViewerControl=" //$NON-NLS-1$
                    + (controlObj == null ? "null" : controlObj.getClass().getName())); //$NON-NLS-1$
                return false;
            }

            Object treeViewerObj = Global.getField(control, "checksViewer"); //$NON-NLS-1$
            if (!(treeViewerObj instanceof TreeViewer treeViewer) || treeViewer.getControl().isDisposed())
            {
                Debug.log("tryPatch WAIT: checksViewer not ready"); //$NON-NLS-1$
                return false;
            }
            if (Boolean.TRUE.equals(treeViewer.getControl().getData(PATCHED_KEY)))
                return true;

            Control pageControl = page.getControl();
            SearchBox searchBox = pageControl != null ? findSearchBox(pageControl) : null;
            if (searchBox == null)
            {
                Debug.log("tryPatch WAIT: searchBox not found"); //$NON-NLS-1$
                return false;
            }

            ValidationSearchFilter filter = new ValidationSearchFilter(resolveCheckFilter(control));
            filter.captureInitialExpanded(treeViewer);
            treeViewer.addFilter(filter);
            CellLabelHighlightWrapper highlight = installHighlight(treeViewer);

            // Персистентная история (см. правило «Подключение фильтра» в AGENTS.md) —
            // штатный CheckFilter (см. javadoc класса) больше не получает savePattern()
            // и его activePattern остаётся пустым.
            // Compact 300 px здесь слишком узкий: штатно SearchBox тянется
            // в строке с тулбаром (fillDefaults + minSize 100 + grab).
            relaxBodyMinimumWidth(treeViewer);
            ChecksTablePane.install(control, treeViewer, filter, pageControl);
            TreeExpander.installWhitelisted(TreeExpander.Target.VALIDATION_CHECKS, treeViewer);
            installAutoExpandOnReset(treeViewer, filter);

            FilterInputBox.attachHistoryKeepLayout(searchBox, FilterInputBox.Scope.VALIDATION_CHECKS);
            searchBox.setSearchListener((text, monitor) -> applySearch(treeViewer, filter, highlight, text));
            searchBox.setToolTipText(FilterInputBox.FLAT_FILTER_TOOLTIP);

            if (pageControl != null)
            {
                pageControl.setData(PAGE_COPY_ROOT_KEY, Boolean.TRUE);
                wirePageCopy(pageControl);
                installLazyCopyFilter(treeViewer.getControl().getDisplay());
            }

            applyPageTitle(page, dialog);
            compactSettingsRow(control);


            openPages++;
            treeViewer.getControl().addDisposeListener(event -> openPages--);
            treeViewer.getControl().setData(PATCHED_KEY, Boolean.TRUE);
            Debug.temp("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            // ВРЕМЕННО безусловно (issue 401): при исключении патч обрывается
            // на середине, и это видно только по последствиям в интерфейсе.
            StringBuilder sb = new StringBuilder("tryPatch EXCEPTION: ").append(e).append('\n'); //$NON-NLS-1$
            for (StackTraceElement frame : e.getStackTrace())
                sb.append("        at ").append(frame).append('\n'); //$NON-NLS-1$
            Debug.temp(sb.toString());
            return false;
        }
    }

    /**
     * Заголовок страницы «Валидация» → «Проверки» (issue 401). Имя узла в дереве
     * окна «Параметры» меняет {@link PreferenceSearchFilterAugmenter}; здесь —
     * надпись над самой страницей. {@code PreferenceDialog.updateTitle()} —
     * protected, поэтому через {@link Global#invoke}.
     */
    private static void applyPageTitle(IPreferencePage page, PreferenceDialog dialog)
    {
        if (PAGE_TITLE.equals(page.getTitle()))
            return;
        page.setTitle(PAGE_TITLE);
        if (dialog != null)
            Global.invoke(dialog, "updateTitle"); //$NON-NLS-1$
    }

    /**
     * Панель параметров текущей проверки: критичность — на отдельную строку
     * (issue 401).
     *
     * <p>Штатно {@code ChecksViewerPreferencesControl.createCheckSettingsArea}
     * кладёт в одну строку из пяти колонок «иконка типа | иконка критичности |
     * код | выпадающий список критичности | сброс». Список критичности задаёт
     * большую минимальную ширину, из-за чего правую часть {@code SashForm} не
     * сузить. Переносим список на вторую строку — панель становится уже,
     * состав и поведение контролов не меняются (правится только раскладка).
     *
     * <p>Контролы ищем по типам, а не по индексам: строка содержит ровно один
     * {@code Combo} и ровно один {@code Button}. Если вёрстка EDT изменится
     * (другое число колонок или контролов) — тихо ничего не делаем.
     */
    private static void compactSettingsRow(ChecksViewerControl control)
    {
        Object idTxtObj = Global.getField(control, "idTxt"); //$NON-NLS-1$
        if (!(idTxtObj instanceof StyledText idTxt) || idTxt.isDisposed())
        {
            Debug.temp("compactSettingsRow: idTxt not found"); //$NON-NLS-1$
            return;
        }

        Composite row = idTxt.getParent();
        if (row == null || row.isDisposed() || Boolean.TRUE.equals(row.getData(COMPACT_ROW_KEY)))
            return;
        if (!(row.getLayout() instanceof GridLayout layout) || layout.numColumns != 5)
        {
            Debug.temp("compactSettingsRow: unexpected layout"); //$NON-NLS-1$
            return;
        }

        Combo severityCombo = null;
        Control resetButton = null;
        Label typeLabel = null;
        Label severityLabel = null;
        int comboCount = 0;
        int buttonCount = 0;
        for (Control child : row.getChildren())
        {
            if (child instanceof Combo combo)
            {
                severityCombo = combo;
                comboCount++;
            }
            else if (child instanceof Button button)
            {
                resetButton = button;
                buttonCount++;
            }
            else if (child instanceof Label label)
            {
                // Первый Label строки — иконка типа, второй — критичности.
                if (typeLabel == null)
                    typeLabel = label;
                else if (severityLabel == null)
                    severityLabel = label;
            }
        }
        if (severityCombo == null || resetButton == null || severityLabel == null || comboCount != 1
            || buttonCount != 1)
        {
            Debug.temp("compactSettingsRow: unexpected controls combo=" + comboCount + " button=" + buttonCount); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        // Строка 1: значок типа | код | сброс. Строка 2: значок критичности |
        // значок этой же критичности в редакторе модуля | список критичности.
        layout.numColumns = 4;
        idTxt.moveBelow(typeLabel);
        resetButton.moveBelow(idTxt);
        severityLabel.moveBelow(resetButton);
        Label moduleSeverityLabel = new Label(row, SWT.NONE);
        moduleSeverityLabel.moveBelow(severityLabel);
        severityCombo.moveBelow(moduleSeverityLabel);

        if (idTxt.getLayoutData() instanceof GridData idData)
            idData.horizontalSpan = 2;
        moduleSeverityLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        GridData comboData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        comboData.horizontalSpan = 2;
        comboData.widthHint = NARROW_FIELD_WIDTH;
        severityCombo.setLayoutData(comboData);

        // Поле кода без подсказки ширины требует место под весь идентификатор
        // («xdto-package-extension-package-namespace-features-state» и подобные) —
        // это и держало правую панель широкой. Растягиваться оно по-прежнему
        // растягивается, но больше не диктует минимальную ширину.
        applyNarrowWidth(idTxt);

        installModuleSeverityIcon(moduleSeverityLabel, severityCombo);

        row.setData(COMPACT_ROW_KEY, Boolean.TRUE);
        row.layout(true, true);
        // Приватный метод самого ChecksViewerPreferencesControl — пересчитывает
        // размеры внешнего ScrolledForm после смены раскладки.
        Global.invoke(control, "reflowParent", idTxt); //$NON-NLS-1$
        installTypeLabelPatch(control, typeLabel, row.getParent());
        Debug.temp("compactSettingsRow: applied"); //$NON-NLS-1$
    }

    /**
     * Параметры проверки (они пересоздаются при каждой смене выбранной строки):
     * поля ввода не должны требовать место под всё своё содержимое, а подпись
     * «Шаблон исключаемых имён объектов» укорачиваем — слово «имён» в ней лишнее,
     * а ширину панели она задаёт целиком (issue 401).
     */
    private static void compactOptions(Composite optionsGroup)
    {
        if (optionsGroup == null || optionsGroup.isDisposed())
            return;
        for (Control child : optionsGroup.getChildren())
        {
            if (child instanceof Label label)
            {
                String text = label.getText();
                if (text != null && text.contains(EXCLUDED_NAMES_FRAGMENT))
                    label.setText(text.replace(EXCLUDED_NAMES_FRAGMENT, EXCLUDED_NAMES_SHORT));
            }
            else if (child instanceof Text || child instanceof StyledText || child instanceof Combo)
            {
                applyNarrowWidth(child);
            }
            if (child instanceof Composite composite)
                compactOptions(composite);
        }
        optionsGroup.layout(true, true);
    }

    /**
     * Значок «как эта критичность выглядит в редакторе модуля» рядом со списком
     * критичности (issue 423).
     *
     * <p>В модуле проблема показывается не значком критичности, а значком
     * серьёзности: «Значительная» и выше — ошибкой, «Незначительная» —
     * предупреждением, «Тривиальная» — информацией (см.
     * {@link #moduleSeverityImage}). По списку критичности этого не видно, и
     * связь между настройкой и тем, что потом появится в модуле, приходится
     * держать в голове.
     *
     * <p>Значок следует и за выбором другой проверки, и за сменой значения в
     * самом списке: критичность читаем из списка, а не из настроек проверки, —
     * тогда не важно, успел ли штатный обработчик записать новое значение.
     */
    private static void installModuleSeverityIcon(Label moduleSeverityLabel, Combo severityCombo)
    {
        Listener update = event -> applyModuleSeverity(moduleSeverityLabel, severityCombo);
        severityCombo.addListener(SWT.Selection, update);
        severityCombo.addListener(SWT.Modify, update);
        wireModuleAnnotationSettings(moduleSeverityLabel);
        applyModuleSeverity(moduleSeverityLabel, severityCombo);
    }

    private static void applyModuleSeverity(Label moduleSeverityLabel, Combo severityCombo)
    {
        if (moduleSeverityLabel.isDisposed() || severityCombo.isDisposed())
            return;
        setModuleSeverityIcon(moduleSeverityLabel, severityByTitle(severityCombo.getText()));
    }

    /**
     * Значок аннотации: картинка, подсказка и критичность, по которой открываются
     * настройки аннотации ({@link #wireModuleAnnotationSettings}).
     */
    static void setModuleSeverityIcon(Label icon, IssueSeverity severity)
    {
        icon.setData(MODULE_SEVERITY_KEY, severity);
        icon.setImage(severity != null ? moduleSeverityImage(severity) : null);
        icon.setToolTipText(severity == null ? null : TooltipText.wrap(icon, moduleSeverityTooltip(severity)));
    }

    /**
     * Щелчок по значку открывает настройку этой аннотации — так же, как команда
     * «Параметры...» контекстного меню линейки обзора в редакторе модуля.
     */
    static void wireModuleAnnotationSettings(Label icon)
    {
        icon.setCursor(icon.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        icon.addListener(SWT.MouseUp, event ->
        {
            if (event.button != 1 || icon.isDisposed())
                return;
            if (icon.getData(MODULE_SEVERITY_KEY) instanceof IssueSeverity severity)
                openModuleAnnotationSettings(icon.getShell(), severity);
        });
    }

    /**
     * Открывает страницу «Аннотации» на строке этой аннотации.
     *
     * <p>Так же, как {@code AbstractDecoratedTextEditor.overviewRulerContextMenu‑
     * AboutToShow}: страница получает параметром метку аннотации
     * ({@code AnnotationPreference.getPreferenceLabel()}), и штатный
     * {@code applyData} выбирает по ней строку списка.
     *
     * <p>Тип аннотации — штатный: маркеры проблем BSL наследуют
     * {@code org.eclipse.core.resources.problemmarker}, а тому в
     * {@code org.eclipse.ui.editors} сопоставлены
     * {@code org.eclipse.ui.workbench.texteditor.error/warning/info}.
     */
    private static void openModuleAnnotationSettings(Shell shell, IssueSeverity severity)
    {
        String annotationType = switch (moduleAnnotation(severity))
        {
            case ERROR -> "org.eclipse.ui.workbench.texteditor.error"; //$NON-NLS-1$
            case WARNING -> "org.eclipse.ui.workbench.texteditor.warning"; //$NON-NLS-1$
            case INFO -> "org.eclipse.ui.workbench.texteditor.info"; //$NON-NLS-1$
        };
        AnnotationPreference preference =
            EditorsUI.getAnnotationPreferenceLookup().getAnnotationPreference(annotationType);
        if (preference == null)
        {
            Debug.log("openModuleAnnotationSettings: no preference for " + annotationType); //$NON-NLS-1$
            return;
        }
        PreferencesUtil
            .createPreferenceDialogOn(shell, ANNOTATIONS_PAGE_ID, new String[] { ANNOTATIONS_PAGE_ID },
                preference.getPreferenceLabel())
            .open();
    }

    private static IssueSeverity severityByTitle(String title)
    {
        if (title == null || title.isBlank())
            return null;
        for (IssueSeverity severity : IssueSeverity.values())
        {
            if (title.equals(localizedSeverity(severity)))
                return severity;
        }
        return null;
    }

    /** Вид проблемы в редакторе модуля — то, во что превращается критичность проверки. */
    enum ModuleAnnotation
    {
        ERROR, WARNING, INFO
    }

    /**
     * Соответствие критичности проверки и вида аннотации в редакторе модуля.
     *
     * <p>Спрашиваем сам EDT — {@code CheckExecutor.toDiagnosticSeverity}, тот же
     * метод, по которому проблема и попадает в модуль. Если в EDT соответствие
     * изменят, здесь оно изменится вместе с ним. Метод приватный, поэтому
     * вызывается рефлексией на службе {@code ICheckExecutor}; результат
     * кэшируется — за сеанс он не меняется.
     *
     * <p>{@link #fallbackAnnotation} — на случай, когда службу получить не
     * удалось: значок должен остаться осмысленным, а не пропасть.
     */
    static ModuleAnnotation moduleAnnotation(IssueSeverity severity)
    {
        ModuleAnnotation cached = MODULE_ANNOTATIONS.get(severity);
        if (cached != null)
            return cached;
        Object executor = checkExecutor();
        Object diagnostic = executor != null ? Global.invoke(executor, "toDiagnosticSeverity", severity) : null; //$NON-NLS-1$
        ModuleAnnotation annotation;
        if (diagnostic instanceof Integer value)
        {
            annotation = value.intValue() >= Diagnostic.ERROR ? ModuleAnnotation.ERROR
                : value.intValue() >= Diagnostic.WARNING ? ModuleAnnotation.WARNING : ModuleAnnotation.INFO;
        }
        else
        {
            annotation = fallbackAnnotation(severity);
            Debug.log("moduleAnnotation: fallback for " + severity); //$NON-NLS-1$
        }
        MODULE_ANNOTATIONS.put(severity, annotation);
        return annotation;
    }

    /** Соответствие на момент EDT 2025.2 — если штатный метод недоступен. */
    private static ModuleAnnotation fallbackAnnotation(IssueSeverity severity)
    {
        return switch (severity)
        {
            case BLOCKER, CRITICAL, MAJOR -> ModuleAnnotation.ERROR;
            case MINOR -> ModuleAnnotation.WARNING;
            case TRIVIAL -> ModuleAnnotation.INFO;
        };
    }

    private static Object checkExecutor()
    {
        if (checkExecutorResolved)
            return checkExecutorService;
        checkExecutorResolved = true;
        try
        {
            // Пакет службы наружу не экспортирован, поэтому класс грузим
            // загрузчиком её же бандла — через доступный из него ICheckRepository.
            Class<?> serviceType = ICheckRepository.class.getClassLoader().loadClass(CHECK_EXECUTOR_CLASS);
            checkExecutorService = Global.getOsgiService(serviceType);
        }
        catch (Exception e)
        {
            Debug.log("checkExecutor: " + e); //$NON-NLS-1$
        }
        return checkExecutorService;
    }

    /** Значок проблемы в редакторе модуля — см. {@link #moduleAnnotation}. */
    static Image moduleSeverityImage(IssueSeverity severity)
    {
        String path = switch (moduleAnnotation(severity))
        {
            case ERROR -> "/icons/markers16/error.png"; //$NON-NLS-1$
            case WARNING -> "/icons/markers16/warning.gif"; //$NON-NLS-1$
            case INFO -> "/icons/markers16/info.gif"; //$NON-NLS-1$
        };
        return V8UiSharedImages.getImage(path);
    }

    /**
     * @return критичность проверки, соответствующая критичности маркера, или
     * {@code null} для {@code ERRORS}/{@code NONE} — у них нет своей проверки, а
     * значит и вида в редакторе модуля
     */
    static IssueSeverity issueSeverityOf(MarkerSeverity marker)
    {
        if (marker == null)
            return null;
        for (IssueSeverity severity : IssueSeverity.values())
        {
            if (toMarkerSeverity(severity) == marker)
                return severity;
        }
        return null;
    }

    /** Подсказка значка: чем эта критичность обернётся в редакторе модуля. */
    static String moduleSeverityTooltip(IssueSeverity severity)
    {
        String annotation = switch (moduleAnnotation(severity))
        {
            case ERROR -> "Ошибка"; //$NON-NLS-1$
            case WARNING -> "Предупреждение"; //$NON-NLS-1$
            case INFO -> "Информация"; //$NON-NLS-1$
        };
        return "Аннотация в редакторе модуля: " + annotation //$NON-NLS-1$
            + ".\nЩелчок открывает её настройку в окне «Параметры»."; //$NON-NLS-1$
    }

    /** Минимальная ширина поля: растягиваться не мешает, но и не требует места под весь текст. */
    private static void applyNarrowWidth(Control control)
    {
        if (control == null || control.isDisposed() || !(control.getLayoutData() instanceof GridData data))
            return;
        data.widthHint = NARROW_FIELD_WIDTH;
        data.minimumWidth = NARROW_FIELD_WIDTH;
    }

    /**
     * Иконка и подсказка типа в панели параметров проверки: для
     * {@link IssueType#WARNING} — «Прочее предупреждение» и своя нейтральная
     * иконка (см. {@link #typeImage}).
     *
     * <p>Штатный обработчик выбора проверки перезаписывает и то, и другое,
     * поэтому подписываемся на тот же источник ({@code getSelectedCheckObjects()})
     * и правим уже после него — наш слушатель добавлен позже штатного.
     */
    private static void installTypeLabelPatch(ChecksViewerControl control, Label typeLabel, Composite optionsGroup)
    {
        Object selectedCheck = Global.invoke(control, "getSelectedCheckObjects"); //$NON-NLS-1$
        if (!(selectedCheck instanceof IObservableValue<?> observable))
        {
            Debug.temp("installTypeLabelPatch: observable not found"); //$NON-NLS-1$
            return;
        }
        observable.addChangeListener(event ->
        {
            applyTypeLabel(typeLabel, observable.getValue());
            // Параметры пересозданы под новую проверку — сужаем их заново.
            compactOptions(optionsGroup);
        });
        applyTypeLabel(typeLabel, observable.getValue());
        compactOptions(optionsGroup);
    }

    private static void applyTypeLabel(Label typeLabel, Object selected)
    {
        if (typeLabel == null || typeLabel.isDisposed() || !(selected instanceof IChecksTreeNode node))
            return;
        IssueType single = null;
        for (ICheckSettings settings : node.getVisibleChecks())
        {
            IssueType type = settings.getType();
            if (single != null && single != type)
                return;
            single = type;
        }
        if (single != IssueType.WARNING)
            return;
        typeLabel.setImage(typeImage(typeLabel.getDisplay(), IssueType.WARNING));
        typeLabel.setToolTipText(TooltipText.wrap(typeLabel, "Тип: " + OTHER_WARNING_TYPE_TITLE)); //$NON-NLS-1$
    }

    /**
     * Возврат дерева в начальное (полностью свёрнутое) состояние — кнопка
     * «Свернуть все» и «Восстановить значения по умолчанию» — должен заново
     * применять авторазворачивание {@link TreeExpander} (цепочки единственных
     * потомков и единственный корень), иначе пользователь остаётся с голым
     * списком категорий.
     *
     * <p>Ловим это по общему признаку, а не по конкретным кнопкам: обе команды
     * пересчитывают дерево, а после них не остаётся ни одного развёрнутого узла.
     * Фильтрация по тексту (там дерево разворачивается целиком) под условие не
     * попадает. Сам разворот — асинхронно: сигнал приходит изнутри пересчёта.
     */
    private static void installAutoExpandOnReset(TreeViewer treeViewer, ValidationSearchFilter filter)
    {
        filter.addTreeRefreshListener(() ->
        {
            Tree tree = treeViewer.getTree();
            if (tree == null || tree.isDisposed())
                return;
            tree.getDisplay().asyncExec(() ->
            {
                if (tree.isDisposed())
                    return;
                Object[] expanded = treeViewer.getExpandedElements();
                if (expanded != null && expanded.length > 0)
                    return;
                TreeExpander.notifyContentLoaded(treeViewer);
                Debug.temp("autoExpandOnReset: applied"); //$NON-NLS-1$
            });
        });
    }

    /**
     * Штатный {@code CheckFilter} страницы — состояние переключателей отбора
     * (критичность, тип, включённые/выключенные, изменённые/неизменённые).
     * Тип {@code CheckFilter} лежит в неэкспортированном пакете
     * {@code internal.ui.validation}, поэтому и сам геттер зовём рефлексией, и
     * дальше работаем с {@code Object} (см. {@link CheckFilterState}).
     */
    private static Object resolveCheckFilter(ChecksViewerControl control)
    {
        ChecksViewerProvider provider = control.getChecksViewerProvider();
        if (provider == null)
            return null;
        Object checkFilter = Global.invoke(provider, "getCheckFilter"); //$NON-NLS-1$
        Debug.temp("resolveCheckFilter: " //$NON-NLS-1$
            + (checkFilter == null ? "null" : checkFilter.getClass().getName())); //$NON-NLS-1$
        return checkFilter;
    }

    /** {@code performSearch} у {@link SearchBox} может прийти не с UI-потока — как и в штатном коде, оборачиваем в {@code BusyIndicator.showWhile}. */
    /** Табличный режим обновляется сам — по пересчёту дерева ({@link ValidationSearchFilter#setTreeRefreshListener}). */
    private static void applySearch(TreeViewer treeViewer, ValidationSearchFilter filter,
        CellLabelHighlightWrapper highlight, String text)
    {
        Display display = treeViewer.getControl().getDisplay();
        if (display == null || display.isDisposed())
            return;
        BusyIndicator.showWhile(display, () ->
        {
            if (treeViewer.getControl().isDisposed())
                return;
            boolean wasFiltering = filter.isFiltering();
            // При очистке фильтра refresh + restoreInitialExpanded теряют/прячут
            // текущую строку — сохраняем выделение до refresh (как InfobasesViewHook /
            // SmartOutlineHook) и восстанавливаем с reveal после свёртки.
            IStructuredSelection savedSelection = null;
            if (wasFiltering && treeViewer.getSelection() instanceof IStructuredSelection ss
                && !ss.isEmpty())
                savedSelection = ss;
            filter.setPattern(text);
            if (highlight != null)
                highlight.setHighlightPattern(text);
            treeViewer.getControl().setRedraw(false);
            try
            {
                treeViewer.refresh();
                if (filter.isFiltering())
                    treeViewer.expandAll();
                else if (wasFiltering)
                {
                    filter.restoreInitialExpanded(treeViewer);
                    if (savedSelection != null)
                    {
                        // После свёртки виджеты потомков уничтожены — без
                        // раскрытия предков setSelection не найдёт строку.
                        expandAncestors(treeViewer, savedSelection.getFirstElement());
                        treeViewer.setSelection(savedSelection, true);
                    }
                }
            }
            finally
            {
                treeViewer.getControl().setRedraw(true);
            }
        });
    }

    /** Раскрывает цепочку родителей элемента через {@link ITreeContentProvider#getParent}. */
    private static void expandAncestors(TreeViewer viewer, Object element)
    {
        if (element == null
            || !(viewer.getContentProvider() instanceof ITreeContentProvider tcp))
            return;
        Object parent = tcp.getParent(element);
        while (parent != null)
        {
            viewer.setExpandedState(parent, true);
            parent = tcp.getParent(parent);
        }
    }

    /**
     * Список проверок и панель настроек текущей проверки не должны задавать
     * минимальную ширину всей страницы (issue 401).
     *
     * <p>Штатно {@code SashForm} с этими двумя частями объявлен без ограничения
     * ширины, поэтому его предпочтительная ширина складывается из содержимого
     * панели настроек — а она зависит от выбранной проверки: код проверки,
     * список критичности, описание, поля параметров. Стоит выбрать проверку
     * (например открыть страницу двойным щелчком по коду в панели проблем), и
     * страница начинает требовать больше ширины, чем есть в окне: содержимое
     * обрезается, часть строки с полем фильтра и тулбаром уходит за правый край.
     *
     * <p>Обе части и так растягиваются по доступной ширине, поэтому задаём
     * скромную предпочтительную ширину — {@link #BODY_WIDTH_HINT}. Что именно
     * лежит в панели настроек, на минимальную ширину страницы больше не влияет,
     * причём для любой проверки.
     */
    private static void relaxBodyMinimumWidth(TreeViewer treeViewer)
    {
        Control tree = treeViewer.getControl();
        Composite body = tree != null && !tree.isDisposed() ? tree.getParent() : null;
        if (!(body instanceof SashForm) || !(body.getLayoutData() instanceof GridData data))
        {
            Debug.temp("relaxBodyMinimumWidth: unexpected layout"); //$NON-NLS-1$
            return;
        }
        data.widthHint = BODY_WIDTH_HINT;
        data.minimumWidth = BODY_WIDTH_HINT;
        Debug.temp("relaxBodyMinimumWidth: applied"); //$NON-NLS-1$
    }

    private static String describe(Control control)
    {
        if (control == null)
            return "null"; //$NON-NLS-1$
        return control.getClass().getSimpleName() + "@" //$NON-NLS-1$
            + Integer.toHexString(System.identityHashCode(control)) + control.getBounds();
    }

    private static SearchBox findSearchBox(Control control)
    {
        if (control instanceof SearchBox searchBox)
            return searchBox;
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren())
            {
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        return null;
    }

    /**
     * Ctrl+C на дереве и текстовых полях страницы (см. javadoc класса).
     * Обход {@code page.getControl()} покрывает дерево и постоянные поля
     * ({@code idTxt} и т.п.); поля параметров проверки пересоздаются при смене
     * строки — их донастраивает {@link #installLazyCopyFilter}.
     */
    private static void wirePageCopy(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        if (isCopyableControl(control) && !Boolean.TRUE.equals(control.getData(COPY_WIRED_KEY)))
        {
            control.setData(COPY_WIRED_KEY, Boolean.TRUE);
            CopyCommandSupport.wireCopyOverride(control);
        }
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren())
                wirePageCopy(child);
    }

    private static void installLazyCopyFilter(Display display)
    {
        if (lazyCopyFilterInstalled || display == null || display.isDisposed())
            return;
        display.addFilter(SWT.FocusIn, event ->
        {
            if (!(event.widget instanceof Control focus) || focus.isDisposed())
                return;
            if (!isCopyableControl(focus) || Boolean.TRUE.equals(focus.getData(COPY_WIRED_KEY)))
                return;
            if (!isUnderValidationCopyRoot(focus))
                return;
            focus.setData(COPY_WIRED_KEY, Boolean.TRUE);
            CopyCommandSupport.wireCopyOverride(focus);
        });
        lazyCopyFilterInstalled = true;
    }

    private static boolean isCopyableControl(Control control)
    {
        return control instanceof Tree
            || control instanceof Text
            || control instanceof StyledText
            || control instanceof Combo;
    }

    private static boolean isUnderValidationCopyRoot(Control control)
    {
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (Boolean.TRUE.equals(c.getData(PAGE_COPY_ROOT_KEY)))
                return true;
        }
        return false;
    }

    /** Оборачивает label provider колонки 0 {@code checksViewer} в {@link CellLabelHighlightWrapper} (если ещё не обёрнут). */
    private static CellLabelHighlightWrapper installHighlight(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getColumnCount() == 0)
            return null;

        Object columnObj = Global.invoke(viewer, "getViewerColumn", Integer.valueOf(0)); //$NON-NLS-1$
        if (!(columnObj instanceof TreeViewerColumn column))
            return null;

        Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
        if (lpObj instanceof CellLabelHighlightWrapper existing)
            return existing;
        if (!(lpObj instanceof CellLabelProvider lp))
            return null;

        CellLabelHighlightWrapper wrapper = new CellLabelHighlightWrapper(lp);
        column.setLabelProvider(wrapper);
        return wrapper;
    }

    /**
     * Отбирает узлы дерева проверок по двум независимым критериям.
     *
     * <p><b>Текст.</b> Узел виден, если под {@link SmartMatcher#matches(String)}
     * попадает его заголовок или заголовок любого потомка (правило одинаково для
     * категорий и отдельных проверок — {@link IChecksTreeNode#getValue()} есть
     * у обоих).
     *
     * <p><b>Переключатели отбора</b> (критичность, тип, включённые/выключенные,
     * изменённые/неизменённые) — обход бага EDT
     * <a href="https://github.com/1C-Company/1c-edt-issues/issues/2314">2314</a>:
     * штатно они не работают вообще. Причина (декомпиляция): состав дерева даёт
     * {@code ChecksTreeProvider}, фильтруя узлы по {@code IChecksTreeNode.isVisible()}
     * {@code == isMatched() || hasMatchedParent() || hasMathedChildren()}; для
     * узла-категории {@code isMatched()} сводится к {@code CheckFilter.testCategory()}
     * → {@code testSearchWithoutId()}, который при пустой строке поиска всегда
     * {@code true} (критичность/тип/включённость для категорий не проверяются
     * вовсе). Значит каждая корневая категория «совпала», у любой проверки-листа
     * {@code hasMatchedParent() == true}, и переключатели не влияют ни на что.
     *
     * <p>Чиним, не трогая EDT: состояние переключателей читаем у самого
     * {@code CheckFilter} ({@link CheckFilterState}) и применяем его здесь с
     * правильной семантикой — лист проходит по своим настройкам, категория видна,
     * только если под ней остался хотя бы один прошедший потомок. Штатный
     * {@code applyFilter} после смены переключателя делает
     * {@code checksViewer.refresh(true)}, поэтому пересчёт запускается сам.
     */
    private static final class ValidationSearchFilter extends ViewerFilter
    {
        private final Object checkFilter;
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        /** Результат {@link #isVisible} (текст + отбор). */
        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();
        /** Результат {@link #acceptsAnyCheck} (только отбор, без текста). */
        private final Map<Object, Boolean> acceptsMemo = new IdentityHashMap<>();
        private CheckFilterState state = CheckFilterState.PASS_ALL;
        private Object[] initialExpanded = new Object[0];
        private final List<Runnable> treeRefreshListeners = new ArrayList<>();

        ValidationSearchFilter(Object checkFilter)
        {
            this.checkFilter = checkFilter;
        }

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        boolean isFiltering()
        {
            return !matcher.isEmpty;
        }

        SmartMatcher currentMatcher()
        {
            return matcher;
        }

        /**
         * Тот же критерий видимости, что и у дерева — чтобы табличный режим
         * ({@link ChecksTablePane}) показывал ровно тот же набор проверок.
         * {@code viewer} нужен только для обхода потомков, поэтому передаётся
         * дерево даже при отборе строк таблицы.
         */
        boolean accepts(Viewer viewer, Object element)
        {
            return isVisible(viewer, element);
        }

        void captureInitialExpanded(TreeViewer viewer)
        {
            Object[] current = viewer.getExpandedElements();
            initialExpanded = current != null ? current : new Object[0];
        }

        void restoreInitialExpanded(TreeViewer viewer)
        {
            viewer.setExpandedElements(initialExpanded);
        }

        /**
         * Начало нового пересчёта дерева: корневой уровень дерево строит первым,
         * а штатный {@code applyFilter} после смены переключателя отбора делает
         * {@code refresh(true)}. Здесь же перечитываем состояние переключателей —
         * другого события о нём EDT не даёт.
         */
        @Override
        public Object[] filter(Viewer viewer, Object parent, Object[] elements)
        {
            if (viewer instanceof TreeViewer treeViewer && parent != null && parent == treeViewer.getInput())
            {
                invalidate();
                // Любой пересчёт дерева (кнопки «Отметить все»/«Снять все»,
                // «Свернуть все», «Восстановить значения по умолчанию», смена
                // профиля настроек) означает, что данные или состояние дерева
                // изменились. Другого события EDT не даёт.
                for (Runnable listener : treeRefreshListeners)
                    listener.run();
            }
            return super.filter(viewer, parent, elements);
        }

        void addTreeRefreshListener(Runnable listener)
        {
            treeRefreshListeners.add(listener);
        }

        /**
         * Сбрасывает кэш видимости и перечитывает переключатели отбора. Кэш живёт
         * ровно один проход по дереву: он зависит не только от переключателей, но
         * и от самих настроек проверок (пометка «включена», отличие от значений по
         * умолчанию), которые пользователь меняет прямо на странице.
         */
        void invalidate()
        {
            subtreeMemo.clear();
            acceptsMemo.clear();
            CheckFilterState fresh = CheckFilterState.read(checkFilter);
            if (!fresh.equals(state))
            {
                state = fresh;
                Debug.temp("check filter state: " + fresh); //$NON-NLS-1$
            }
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            return isVisible(viewer, element);
        }

        /**
         * Лист виден, когда проходит отбор и (при непустом фильтре) совпадает по
         * тексту. Категория видна, когда виден хотя бы один потомок — либо когда
         * по тексту совпала она сама и под ней есть хотя бы одна прошедшая отбор
         * проверка (штатное поведение поиска: совпавшая категория показывается
         * целиком).
         */
        private boolean isVisible(Viewer viewer, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            boolean result;
            ICheckSettings settings = settingsOf(element);
            if (settings != null)
            {
                String text = titleOf(element);
                result = state.accepts(settings) && (matcher.isEmpty || (text != null && matcher.matches(text)));
            }
            else
            {
                result = anyChild(viewer, element, child -> isVisible(viewer, child));
                if (!result && !matcher.isEmpty)
                {
                    String text = titleOf(element);
                    result = text != null && matcher.matches(text) && acceptsAnyCheck(viewer, element);
                }
            }
            subtreeMemo.put(element, result);
            return result;
        }

        /** Есть ли под узлом хотя бы одна проверка, проходящая отбор (без учёта текста). */
        private boolean acceptsAnyCheck(Viewer viewer, Object element)
        {
            Boolean memo = acceptsMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            ICheckSettings settings = settingsOf(element);
            boolean result = settings != null
                ? state.accepts(settings)
                : anyChild(viewer, element, child -> acceptsAnyCheck(viewer, child));
            acceptsMemo.put(element, result);
            return result;
        }

        private static boolean anyChild(Viewer viewer, Object element, Predicate<Object> predicate)
        {
            if (!(viewer instanceof TreeViewer treeViewer)
                || !(treeViewer.getContentProvider() instanceof ITreeContentProvider tcp))
                return false;
            for (Object child : tcp.getChildren(element))
            {
                if (predicate.test(child))
                    return true;
            }
            return false;
        }

        private static ICheckSettings settingsOf(Object element)
        {
            return element instanceof IChecksTreeNode node && node.getValue() instanceof ICheckSettings settings
                ? settings
                : null;
        }

        private static String titleOf(Object element)
        {
            if (!(element instanceof IChecksTreeNode node))
                return null;
            INamedElement value = node.getValue();
            String title = value != null ? value.getTitle() : null;
            return title != null && !title.isEmpty() ? title : null;
        }
    }

    /**
     * Название типа проблемы. Для {@link IssueType#WARNING} — «Прочее
     * предупреждение» вместо штатного «Предупреждение» (issue 401): само слово
     * «предупреждение» описывает не характер проблемы, а её значимость, и в
     * штатном виде путается с критичностью. Остальные типы — как в EDT.
     */
    static String localizedType(IssueType type)
    {
        if (type == null)
            return ""; //$NON-NLS-1$
        if (type == IssueType.WARNING)
            return OTHER_WARNING_TYPE_TITLE;
        return LocalizedEnumProvider.getLocalizedString(type);
    }

    /** Название критичности. В UI EDT оно берётся от {@link MarkerSeverity}, а не от {@link IssueSeverity}. */
    static String localizedSeverity(IssueSeverity severity)
    {
        MarkerSeverity marker = toMarkerSeverity(severity);
        return marker != null ? LocalizedEnumProvider.getLocalizedString(marker) : ""; //$NON-NLS-1$
    }

    /**
     * Иконка типа проблемы. Пути повторяют внутренний {@code UIHelper.getImageId(IssueType)}
     * (пакет не экспортирован), сами картинки берём штатным {@link V8UiSharedImages}.
     *
     * <p>Для {@link IssueType#WARNING} — собственная нейтральная иконка: штатная
     * {@code warning.gif} совпадает с маркером критичности «Незначительная» в
     * модуле, из-за чего тип и критичность выглядят одинаково (issue 401).
     */
    static Image typeImage(Display display, IssueType type)
    {
        if (type == null)
            return null;
        if (type == IssueType.WARNING)
            return otherWarningIcon(display);
        String path = switch (type)
        {
            case ERROR -> "/icons/markers16/error.png"; //$NON-NLS-1$
            case SECURITY -> "/icons/markers16/security.png"; //$NON-NLS-1$
            case PERFORMANCE -> "/icons/markers16/perfomance.png"; //$NON-NLS-1$
            case PORTABILITY -> "/icons/markers16/cross-platform.png"; //$NON-NLS-1$
            case LIBRARY_DEVELOPMENT_AND_USAGE -> "/icons/markers16/libraries.png"; //$NON-NLS-1$
            case CODE_STYLE -> "/icons/markers16/naming-standards.png"; //$NON-NLS-1$
            case UI_STYLE -> "/icons/markers16/interface.png"; //$NON-NLS-1$
            case SPELLING -> "/icons/markers16/spell-check.png"; //$NON-NLS-1$
            case CRITICAL_DATA_INTEGRITY -> "/icons/markers16/blocker.png"; //$NON-NLS-1$
            default -> null;
        };
        return path != null ? V8UiSharedImages.getImage(path) : null;
    }

    /** Иконка критичности — та же, что рисует EDT в дереве проверок и в панели проблем. */
    static Image severityImage(IssueSeverity severity)
    {
        MarkerSeverity marker = toMarkerSeverity(severity);
        return marker != null ? DtUiUtil.getImageByMarkerSeverity(marker) : null;
    }

    /** Повторяет внутренний {@code UIHelper.convert(IssueSeverity)}. */
    private static MarkerSeverity toMarkerSeverity(IssueSeverity severity)
    {
        if (severity == null)
            return null;
        return switch (severity)
        {
            case BLOCKER -> MarkerSeverity.BLOCKER;
            case CRITICAL -> MarkerSeverity.CRITICAL;
            case MAJOR -> MarkerSeverity.MAJOR;
            case MINOR -> MarkerSeverity.MINOR;
            case TRIVIAL -> MarkerSeverity.TRIVIAL;
            default -> null;
        };
    }

    /**
     * Иконка типа «Прочее предупреждение»: нейтральный кружок с восклицательным
     * знаком — намеренно не треугольник и не восьмиугольник, чтобы не совпадать
     * ни с одним маркером критичности. Рисуем системными цветами (сама подходит
     * и к светлой, и к тёмной теме), кэш — на {@link Display}, как у
     * {@code ColumnFilterMenuBuilder.filterGlyph}.
     */
    private static Image otherWarningIcon(Display display)
    {
        if (display == null || display.isDisposed())
            return null;
        Image cached = OTHER_WARNING_ICONS.get(display);
        if (cached != null && !cached.isDisposed())
            return cached;

        int size = 16;
        Image image = new Image(display, size, size);
        GC gc = new GC(image);
        try
        {
            gc.setAdvanced(true);
            gc.setAntialias(SWT.ON);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, 0, size, size);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.fillOval(2, 2, size - 5, size - 5);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(size / 2 - 1, 4, 2, 5);
            gc.fillRectangle(size / 2 - 1, 10, 2, 2);
        }
        finally
        {
            gc.dispose();
        }
        OTHER_WARNING_ICONS.put(display, image);
        display.disposeExec(() ->
        {
            OTHER_WARNING_ICONS.remove(display);
            if (!image.isDisposed())
                image.dispose();
        });
        return image;
    }

    /** Иконка кнопки табличного режима: контур таблицы с разлиновкой, системными цветами. */
    private static Image tableModeIcon(Display display)
    {
        if (display == null || display.isDisposed())
            return null;
        Image cached = TABLE_MODE_ICONS.get(display);
        if (cached != null && !cached.isDisposed())
            return cached;

        int size = 16;
        Image image = new Image(display, size, size);
        GC gc = new GC(image);
        try
        {
            gc.setAdvanced(true);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, 0, size, size);
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawRectangle(2, 3, size - 6, size - 7);
            gc.drawLine(2, 6, size - 4, 6);
            gc.drawLine(2, 9, size - 4, 9);
            gc.drawLine(6, 3, 6, size - 4);
        }
        finally
        {
            gc.dispose();
        }
        TABLE_MODE_ICONS.put(display, image);
        display.disposeExec(() ->
        {
            TABLE_MODE_ICONS.remove(display);
            if (!image.isDisposed())
                image.dispose();
        });
        return image;
    }

    /**
     * Табличный режим списка проверок (issue 401): та же выборка, что в дереве,
     * но плоским списком с колонками «Тип», «Критичность», «Проверка» и «Раздел»
     * — их видно сразу, по ним можно сортировать и отбирать.
     *
     * <p>Переключает режим кнопка в штатном тулбаре над списком (не вкладки —
     * они съедали бы строку по высоте). Состояние кнопки запоминается между
     * запусками EDT ({@link ComfortSettings#isValidationChecksTableMode}).
     *
     * <p>Дерево и таблица живут в одном {@code Composite} с {@link TopControlStack}
     * на месте штатного дерева, поэтому поле поиска и меню отбора (они выше по
     * вёрстке) остаются общими для обоих режимов.
     *
     * <p>Состав строк определяет тот же {@link ValidationSearchFilter}, что
     * фильтрует дерево, — режимы всегда показывают одно и то же. Пометки
     * включения проверок переключаются штатным {@code ChecksTreeViewer.setItemChecked},
     * то есть через тот же код EDT, что и клик в дереве (он же ставит
     * {@code dirty} и {@code ICheckSettings.setEnabled}).
     */
    /**
     * То же, что {@link StackLayout}, но предпочтительный размер берётся только
     * у текущего верхнего контрола.
     *
     * <p>Штатный {@code StackLayout} в {@code computeSize} возвращает максимум по
     * ВСЕМ детям — в том числе по скрытой таблице. Таблица через
     * {@code TableColumnLayout} требует сумму фиксированных ширин своих колонок,
     * и эта ширина становилась предпочтительной шириной всей страницы «Проверки»,
     * хотя таблица выключена и не видна. Страница окна «Параметры» лежит в
     * {@code ScrolledComposite}: её раскладывали по этой завышенной ширине, затем
     * содержимое сжимали до видимой области, а строку «поле фильтра + тулбар»
     * заново не пересчитывали. Строка оставалась шире родителя, поле поиска
     * занимало всю видимую ширину, а тулбар со всеми кнопками оказывался за
     * правой границей — в модели он есть и {@code isVisible() == true}, на экране
     * его нет.
     */
    private static final class TopControlStack extends Layout
    {
        Control topControl;

        @Override
        protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache)
        {
            Point size = topControl == null || topControl.isDisposed() ? new Point(0, 0)
                : topControl.computeSize(wHint, hHint, flushCache);
            if (wHint != SWT.DEFAULT)
                size.x = wHint;
            if (hHint != SWT.DEFAULT)
                size.y = hHint;
            return size;
        }

        @Override
        protected void layout(Composite composite, boolean flushCache)
        {
            Rectangle client = composite.getClientArea();
            for (Control child : composite.getChildren())
            {
                child.setVisible(child == topControl);
                if (child == topControl)
                    child.setBounds(client);
            }
        }
    }

    private static final class ChecksTablePane
    {
        private static final String SETTINGS_SECTION = "tormozit.validationChecksTable"; //$NON-NLS-1$
        private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
        /** Положение разделителя «список проверок | настройки текущей проверки». */
        private static final String KEY_SASH_WEIGHTS = "sashWeights"; //$NON-NLS-1$

        private static final String KEY_COL_TITLE_WIDTH = "titleWidth"; //$NON-NLS-1$
        private static final String KEY_COL_CATEGORY_WIDTH = "categoryWidth"; //$NON-NLS-1$

        private static final int DEFAULT_TITLE_WIDTH = 420;
        private static final int DEFAULT_CATEGORY_WIDTH = 220;
        private static final int MIN_COLUMN_WIDTH = 40;

        private final ChecksViewerControl control;
        private final TreeViewer treeViewer;
        private final ValidationSearchFilter filter;
        private final Composite stack;
        private final TopControlStack stackLayout;
        private final Composite tableHost;
        private final Table table;
        private final CheckboxTableViewer viewer;
        private final List<CheckRow> rows = new ArrayList<>();

        private final CellLabels labels = new CellLabels();
        private FormTableInteraction interaction;
        private ToolItem toggleItem;
        private boolean tableMode;
        private boolean syncing;
        private boolean reloadScheduled;

        private ChecksTablePane(ChecksViewerControl control, TreeViewer treeViewer, ValidationSearchFilter filter,
            Composite stack, TopControlStack stackLayout, Composite tableHost, Table table, CheckboxTableViewer viewer)
        {
            this.control = control;
            this.treeViewer = treeViewer;
            this.filter = filter;
            this.stack = stack;
            this.stackLayout = stackLayout;
            this.tableHost = tableHost;
            this.table = table;
            this.viewer = viewer;
        }

        /**
         * @return {@code null}, если вёрстка страницы не та, которую мы знаем —
         * тогда страница просто остаётся штатной (только с деревом).
         */
        static ChecksTablePane install(ChecksViewerControl control, TreeViewer treeViewer,
            ValidationSearchFilter filter, Control pageControl)
        {
            Tree tree = treeViewer.getTree();
            if (tree == null || tree.isDisposed() || !(tree.getParent() instanceof SashForm body))
            {
                Debug.temp("ChecksTablePane: tree parent is not SashForm"); //$NON-NLS-1$
                return null;
            }

            Control rightPane = null;
            for (Control child : body.getChildren())
            {
                if (child != tree && !(child instanceof Sash))
                {
                    rightPane = child;
                    break;
                }
            }

            int[] weights = savedSashWeights();
            if (weights == null)
                weights = body.getWeights();
            Composite stack = new Composite(body, SWT.NONE);
            TopControlStack stackLayout = new TopControlStack();
            stack.setLayout(stackLayout);
            if (!tree.setParent(stack))
            {
                stack.dispose();
                Debug.temp("ChecksTablePane: setParent(tree) failed"); //$NON-NLS-1$
                return null;
            }
            if (rightPane != null)
                stack.moveAbove(rightPane);

            // tableHost с layout(null) + columnHost с TableColumnLayout — раскладка,
            // которую ожидает FormTableInteraction для overlay заголовка колонок.
            Composite tableHost = new Composite(stack, SWT.NONE);
            tableHost.setLayout(null);
            Composite columnHost = new Composite(tableHost, SWT.NONE);
            TableColumnLayout columnLayout = new TableColumnLayout();
            columnHost.setLayout(columnLayout);

            // CheckboxTableViewer, а не TableViewer: пометки строк должен восстанавливать
            // сам JFace по ICheckStateProvider. Иначе любое обновление содержимого
            // (отбор по значению ячейки, сортировка, refresh) пересоздаёт TableItem'ы,
            // и проставленные вручную пометки пропадают, хотя проверки остаются включёнными.
            CheckboxTableViewer viewer = new CheckboxTableViewer(new Table(columnHost, SWT.CHECK
                | SWT.FULL_SELECTION));
            Table table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            ChecksTablePane pane =
                new ChecksTablePane(control, treeViewer, filter, stack, stackLayout, tableHost, table, viewer);
            pane.createColumns(columnLayout);

            stackLayout.topControl = tree;
            stack.layout();
            if (weights != null && weights.length == body.getWeights().length)
                body.setWeights(weights);
            body.layout(true, true);
            body.addDisposeListener(event -> saveSashWeights(body));

            pane.wireListeners();
            pane.installToggle(pageControl);
            pane.setTableMode(ComfortSettings.isValidationChecksTableMode());
            Debug.temp("ChecksTablePane: installed"); //$NON-NLS-1$
            return pane;
        }

        private void createColumns(TableColumnLayout columnLayout)
        {
            IDialogSettings settings = dialogSettings();

            TableViewerColumn typeColumn = new TableViewerColumn(viewer, SWT.NONE);
            typeColumn.getColumn().setToolTipText("Тип проблемы"); //$NON-NLS-1$
            typeColumn.setLabelProvider(labels.forType());
            // В первой колонке система рисует ещё и пометку строки (SWT.CHECK) —
            // значку типа нужен запас по ширине, иначе он обрезается.
            FormTableInteraction.applyIconColumn(typeColumn.getColumn(), columnLayout, CHECK_BOX_WIDTH);

            TableViewerColumn severityColumn = new TableViewerColumn(viewer, SWT.NONE);
            severityColumn.getColumn().setToolTipText("Критичность"); //$NON-NLS-1$
            severityColumn.setLabelProvider(labels.forSeverity());
            FormTableInteraction.applyIconColumn(severityColumn.getColumn(), columnLayout);

            TableColumn titleColumn = addTextColumn(columnLayout, settings, "Проверка", labels.forTitle(), //$NON-NLS-1$
                KEY_COL_TITLE_WIDTH, DEFAULT_TITLE_WIDTH);
            TableColumn categoryColumn = addTextColumn(columnLayout, settings, "Раздел", //$NON-NLS-1$
                labels.forCategory(), KEY_COL_CATEGORY_WIDTH, DEFAULT_CATEGORY_WIDTH);

            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setCheckStateProvider(new ICheckStateProvider()
            {
                @Override
                public boolean isChecked(Object element)
                {
                    return element instanceof CheckRow row && row.settings.isEnabled();
                }

                @Override
                public boolean isGrayed(Object element)
                {
                    return false;
                }
            });
            viewer.setInput(rows);

            interaction = new FormTableInteraction(table, viewer,
                (item, col) -> cellText(item != null ? item.getData() : null, col));
            interaction.setFilterTextResolver(ChecksTablePane::cellText);
            interaction.setOwnerDrawColumns(titleColumn, categoryColumn);
            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
            boolean hasSavedWidths = settings.get(KEY_COL_TITLE_WIDTH) != null
                || settings.get(KEY_COL_CATEGORY_WIDTH) != null;
            interaction.install(hasSavedWidths);
            interaction.enableHeaderSort();
        }

        private TableColumn addTextColumn(TableColumnLayout columnLayout, IDialogSettings settings, String title,
            IStyledLabelProvider labelProvider, String widthKey, int defaultWidth)
        {
            TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
            column.getColumn().setText(title);
            // Только SelectionAwareStyledCellLabelProvider: у штатного
            // DelegatingStyledCellLabelProvider нет способа передать COLORS_ON_SELECTION,
            // и подсветка вхождений фильтра пропадала бы на выделенной строке.
            column.setLabelProvider(new SelectionAwareStyledCellLabelProvider(labelProvider));
            columnLayout.setColumnData(column.getColumn(), new ColumnPixelData(
                FormTableColumnState.readWidth(settings, widthKey, defaultWidth, MIN_COLUMN_WIDTH), true, true));
            return column.getColumn();
        }

        private void wireListeners()
        {
            table.addListener(SWT.Selection, event ->
            {
                if (event.detail == SWT.CHECK)
                    toggleCheck(event.item);
                else
                    syncSelectionToTree();
            });
            treeViewer.addSelectionChangedListener(event -> syncSelectionFromTree());

            // Смена критичности выпадающим списком справа меняет иконку строки.
            Object selectedCheck = Global.invoke(control, "getSelectedCheckObjects"); //$NON-NLS-1$
            if (selectedCheck instanceof IObservable observable)
                observable.addChangeListener(event -> refreshRows());

            filter.addTreeRefreshListener(this::scheduleReload);

            table.addDisposeListener(event -> saveColumnLayout());
        }

        /**
         * Пометка проверки переключается штатным {@code ChecksTreeViewer.setItemChecked}
         * — тем же кодом, что и клик в дереве: он ставит {@code ICheckSettings.setEnabled}
         * всем проверкам узла и помечает страницу изменённой.
         */
        private void toggleCheck(Widget item)
        {
            if (!(item instanceof TableItem tableItem) || !(tableItem.getData() instanceof CheckRow row))
                return;
            Global.invoke(treeViewer, "setItemChecked", row.node, Boolean.valueOf(tableItem.getChecked())); //$NON-NLS-1$
            // При отборе «только включённые»/«только выключенные» строка после
            // переключения перестаёт проходить отбор — тогда пересобираем состав,
            // иначе достаточно перерисовать (пометка уже стоит).
            filter.invalidate();
            if (!filter.accepts(treeViewer, row.node))
                reload();
        }

        private void syncSelectionToTree()
        {
            if (syncing || !tableMode)
                return;
            if (!(viewer.getStructuredSelection().getFirstElement() instanceof CheckRow row))
                return;
            syncing = true;
            try
            {
                // Без раскрытия категории у проверки нет TreeItem, setSelection
                // не находит элемент и выделение остаётся пустым — тогда панель
                // параметров справа (она следует за выбором дерева) не обновляется.
                expandAncestors(treeViewer, row.node);
                treeViewer.setSelection(new StructuredSelection(row.node), true);
            }
            finally
            {
                syncing = false;
            }
        }

        private void syncSelectionFromTree()
        {
            // В режиме дерева искать строку незачем: при включении табличного
            // режима состав пересобирается и выделение проставляется в reload().
            if (syncing || !tableMode || table.isDisposed())
                return;
            Object selected = treeViewer.getStructuredSelection().getFirstElement();
            CheckRow target = null;
            for (CheckRow row : rows)
            {
                if (row.node == selected)
                {
                    target = row;
                    break;
                }
            }
            if (target == null)
                return;
            syncing = true;
            try
            {
                viewer.setSelection(new StructuredSelection(target), true);
                if (interaction != null)
                    interaction.revealSelection();
            }
            finally
            {
                syncing = false;
            }
        }

        /** Кнопка табличного режима — в штатном тулбаре над списком (справа от поля поиска). */
        private void installToggle(Control pageControl)
        {
            ToolBar toolBar = pageControl != null ? findToolBar(pageControl) : null;
            if (toolBar == null || toolBar.isDisposed())
            {
                Debug.temp("ChecksTablePane: toolbar not found"); //$NON-NLS-1$
                return;
            }
            int itemsBefore = toolBar.getItemCount();
            ToolItem item = new ToolItem(toolBar, SWT.CHECK);
            item.setImage(tableModeIcon(toolBar.getDisplay()));
            item.setToolTipText(TooltipText.wrap(toolBar,
                "Табличный режим: проверки плоским списком с колонками «Тип», «Критичность» и «Раздел»" //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            item.addListener(SWT.Selection, event ->
            {
                applyMode(item.getSelection());
                ComfortSettings.setValidationChecksTableMode(item.getSelection());
            });
            requestToolBarLayout(toolBar);
            toggleItem = item;
            Debug.temp("installToggle: toolBar=" + describe(toolBar) //$NON-NLS-1$
                + " parent=" + describe(toolBar.getParent()) //$NON-NLS-1$
                + " items " + itemsBefore + "->" + toolBar.getItemCount() //$NON-NLS-1$ //$NON-NLS-2$
                + " itemBounds=" + item.getBounds() //$NON-NLS-1$
                + " image=" + (item.getImage() != null)); //$NON-NLS-1$
        }

        /**
         * Тулбар и поле фильтра лежат в одной строке, и на момент патча страница
         * ещё не разложена — в логе все её родители нулевого размера. Раскладку
         * поэтому только ЗАПРАШИВАЕМ: SWT выполнит её сам в общем проходе, когда
         * размеры уже настоящие.
         *
         * <p>Ручной {@code layout(true, true)} вверх по родителям (и тем более
         * {@code pack()}) здесь вредит: выполненный при нулевых размерах, он
         * ломал раскладку строки — тулбар со всеми кнопками временами пропадал
         * целиком.
         */
        private static void requestToolBarLayout(ToolBar toolBar)
        {
            toolBar.requestLayout();
        }

        private static ToolBar findToolBar(Control control)
        {
            if (control instanceof ToolBar toolBar)
                return toolBar;
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    ToolBar found = findToolBar(child);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        void setTableMode(boolean value)
        {
            applyMode(value);
            if (toggleItem != null && !toggleItem.isDisposed())
                toggleItem.setSelection(value);
        }

        private void applyMode(boolean value)
        {
            if (stack.isDisposed())
                return;
            Debug.temp("ChecksTablePane.applyMode: table=" + value); //$NON-NLS-1$
            tableMode = value;
            if (value)
                reload();
            stackLayout.topControl = value ? tableHost : treeViewer.getTree();
            stack.layout();
        }

        /**
         * Пересборка после того, как дерево закончит свой пересчёт: сигнал приходит
         * из {@code ViewerFilter.filter()}, то есть в середине {@code refresh}
         * дерева — обновлять таблицу прямо там нельзя. Флаг гасит пачку сигналов
         * от одного {@code refresh} (фильтр вызывается на каждом уровне).
         */
        private void scheduleReload()
        {
            if (reloadScheduled || table.isDisposed() || !tableMode)
                return;
            reloadScheduled = true;
            table.getDisplay().asyncExec(() ->
            {
                reloadScheduled = false;
                if (!table.isDisposed() && tableMode)
                    reload();
            });
        }

        /** Пересобирает строки по тем же правилам, по которым отбирается дерево. */
        void reload()
        {
            if (table.isDisposed() || !(treeViewer.getContentProvider() instanceof ITreeContentProvider tcp))
                return;
            filter.invalidate();
            rows.clear();
            for (Object root : tcp.getElements(treeViewer.getInput()))
                collect(tcp, root, null);
            labels.setMatcher(filter.currentMatcher());
            viewer.refresh();
            syncSelectionFromTree();
            Debug.temp("ChecksTablePane.reload: rows=" + rows.size()); //$NON-NLS-1$
        }

        /** Обновляет уже собранные строки (значки, пометки), не пересобирая состав. */
        private void refreshRows()
        {
            if (table.isDisposed() || !tableMode)
                return;
            viewer.refresh();
        }

        private void collect(ITreeContentProvider tcp, Object element, String categoryTitle)
        {
            ICheckSettings settings = ValidationSearchFilter.settingsOf(element);
            if (settings != null)
            {
                if (element instanceof IChecksTreeNode node && filter.accepts(treeViewer, element))
                    rows.add(new CheckRow(node, settings, categoryTitle));
                return;
            }
            String title = ValidationSearchFilter.titleOf(element);
            for (Object child : tcp.getChildren(element))
                collect(tcp, child, title != null ? title : categoryTitle);
        }


        private void saveColumnLayout()
        {
            if (table.isDisposed())
                return;
            IDialogSettings settings = dialogSettings();
            FormTableColumnState.saveOrder(settings, KEY_COL_ORDER, table);
            String[] widthKeys = {KEY_COL_TITLE_WIDTH, KEY_COL_CATEGORY_WIDTH};
            TableColumn[] columns = table.getColumns();
            for (int i = 0; i < widthKeys.length; i++)
            {
                int index = ICON_COLUMN_COUNT + i;
                if (index < columns.length)
                    settings.put(widthKeys[i], columns[index].getWidth());
            }
        }

        static String cellText(Object element, int column)
        {
            if (!(element instanceof CheckRow row))
                return ""; //$NON-NLS-1$
            return switch (column)
            {
                case 0 -> localizedType(row.settings.getType());
                case 1 -> localizedSeverity(row.settings.getSeverity());
                case 2 -> row.title();
                case 3 -> row.category != null ? row.category : ""; //$NON-NLS-1$
                default -> ""; //$NON-NLS-1$
            };
        }

        /**
         * Положение разделителя между списком проверок и панелью настроек
         * текущей проверки — между открытиями окна «Параметры» и между запусками
         * EDT (issue 401). Штатно оно каждый раз возвращается к исходному, хотя
         * удобная ширина панели настроек у каждого своя.
         *
         * @return {@code null}, если сохранённого положения нет или оно испорчено —
         * тогда остаётся штатное
         */
        private static int[] savedSashWeights()
        {
            String value = dialogSettings().get(KEY_SASH_WEIGHTS);
            if (value == null || value.isBlank())
                return null;
            String[] parts = value.split(","); //$NON-NLS-1$
            int[] weights = new int[parts.length];
            for (int i = 0; i < parts.length; i++)
            {
                try
                {
                    weights[i] = Integer.parseInt(parts[i].trim());
                }
                catch (NumberFormatException e)
                {
                    return null;
                }
                if (weights[i] <= 0)
                    return null;
            }
            return weights;
        }

        private static void saveSashWeights(SashForm body)
        {
            if (body.isDisposed())
                return;
            int[] weights = body.getWeights();
            if (weights == null || weights.length == 0)
                return;
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < weights.length; i++)
            {
                if (i > 0)
                    value.append(',');
                value.append(weights[i]);
            }
            dialogSettings().put(KEY_SASH_WEIGHTS, value.toString());
        }

        private static IDialogSettings dialogSettings()
        {
            IDialogSettings root = Activator.getDefault().getDialogSettings();
            IDialogSettings section = root.getSection(SETTINGS_SECTION);
            return section != null ? section : root.addNewSection(SETTINGS_SECTION);
        }

        /** Строка таблицы: узел дерева и его данные, вычисленные один раз при сборке. */
        private static final class CheckRow
        {
            final IChecksTreeNode node;
            final ICheckSettings settings;
            final String category;

            CheckRow(IChecksTreeNode node, ICheckSettings settings, String category)
            {
                this.node = node;
                this.settings = settings;
                this.category = category;
            }

            String title()
            {
                String title = settings.getTitle();
                return title != null ? title : ""; //$NON-NLS-1$
            }
        }

        /** Провайдеры ячеек: иконки типа/критичности и текст с подсветкой вхождений фильтра. */
        private final class CellLabels
        {
            private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

            void setMatcher(SmartMatcher value)
            {
                matcher = value;
            }

            CellLabelProvider forType()
            {
                return new ColumnLabelProvider()
                {
                    @Override
                    public String getText(Object element)
                    {
                        return ""; //$NON-NLS-1$
                    }

                    @Override
                    public Image getImage(Object element)
                    {
                        return element instanceof CheckRow row ? typeImage(table.getDisplay(), row.settings.getType())
                            : null;
                    }

                    @Override
                    public String getToolTipText(Object element)
                    {
                        return element instanceof CheckRow row ? localizedType(row.settings.getType()) : null;
                    }
                };
            }

            CellLabelProvider forSeverity()
            {
                return new ColumnLabelProvider()
                {
                    @Override
                    public String getText(Object element)
                    {
                        return ""; //$NON-NLS-1$
                    }

                    @Override
                    public Image getImage(Object element)
                    {
                        return element instanceof CheckRow row ? severityImage(row.settings.getSeverity()) : null;
                    }

                    @Override
                    public String getToolTipText(Object element)
                    {
                        return element instanceof CheckRow row ? localizedSeverity(row.settings.getSeverity()) : null;
                    }
                };
            }


            IStyledLabelProvider forTitle()
            {
                return styled(CheckRow::title);
            }

            IStyledLabelProvider forCategory()
            {
                return styled(row -> row.category != null ? row.category : ""); //$NON-NLS-1$
            }

            private IStyledLabelProvider styled(Function<CheckRow, String> text)
            {
                return new StyledTextLabelProvider(text);
            }

            private final class StyledTextLabelProvider extends LabelProvider implements IStyledLabelProvider
            {
                private final Function<CheckRow, String> text;

                StyledTextLabelProvider(Function<CheckRow, String> text)
                {
                    this.text = text;
                }

                @Override
                public StyledString getStyledText(Object element)
                {
                    if (!(element instanceof CheckRow row))
                        return new StyledString();
                    String value = text.apply(row);
                    StyledString styled = new StyledString(value != null ? value : ""); //$NON-NLS-1$
                    if (!matcher.isEmpty && value != null)
                        SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(value), table);
                    return styled;
                }
            }
        }
    }

    /**
     * Снимок состояния переключателей отбора штатного {@code CheckFilter}
     * (неэкспортированный {@code internal.ui.validation}, поэтому только через
     * {@link Global#invoke}; сами геттеры публичны и принимают экспортированные
     * {@link IssueSeverity}/{@link IssueType}).
     *
     * <p>Снимается один раз на пересчёт дерева, дальше {@link #accepts} работает
     * на массивах — рефлексии на каждый узел нет. При неудаче рефлексии флаг
     * считается разрешающим: сломанная разведка не должна прятать проверки.
     */
    private static final class CheckFilterState
    {
        static final CheckFilterState PASS_ALL = new CheckFilterState(null, null, true, true, true, true);

        private final boolean[] severity;
        private final boolean[] type;
        private final boolean showDefault;
        private final boolean showChanged;
        private final boolean showEnabled;
        private final boolean showDisabled;

        private CheckFilterState(boolean[] severity, boolean[] type, boolean showDefault, boolean showChanged,
            boolean showEnabled, boolean showDisabled)
        {
            this.severity = severity;
            this.type = type;
            this.showDefault = showDefault;
            this.showChanged = showChanged;
            this.showEnabled = showEnabled;
            this.showDisabled = showDisabled;
        }

        static CheckFilterState read(Object checkFilter)
        {
            if (checkFilter == null)
                return PASS_ALL;

            boolean[] severity = new boolean[IssueSeverity.values().length];
            for (IssueSeverity value : IssueSeverity.values())
                severity[value.ordinal()] = flag(checkFilter, "isSeverityEnabled", value); //$NON-NLS-1$

            boolean[] type = new boolean[IssueType.values().length];
            for (IssueType value : IssueType.values())
                type[value.ordinal()] = flag(checkFilter, "isTypeEnabled", value); //$NON-NLS-1$

            return new CheckFilterState(severity, type,
                flag(checkFilter, "isShowDefault"), //$NON-NLS-1$
                flag(checkFilter, "isShowChanged"), //$NON-NLS-1$
                flag(checkFilter, "isShowEnabled"), //$NON-NLS-1$
                flag(checkFilter, "isShowDisabled")); //$NON-NLS-1$
        }

        private static boolean flag(Object target, String method, Object... args)
        {
            Object result = Global.invoke(target, method, args);
            return !(result instanceof Boolean value) || value.booleanValue();
        }

        boolean accepts(ICheckSettings settings)
        {
            IssueSeverity checkSeverity = settings.getSeverity();
            if (severity != null && checkSeverity != null && !severity[checkSeverity.ordinal()])
                return false;
            IssueType checkType = settings.getType();
            if (type != null && checkType != null && !type[checkType.ordinal()])
                return false;
            if (!(settings.isDefault() ? showDefault : showChanged))
                return false;
            return settings.isEnabled() ? showEnabled : showDisabled;
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof CheckFilterState other
                && Arrays.equals(severity, other.severity)
                && Arrays.equals(type, other.type)
                && showDefault == other.showDefault
                && showChanged == other.showChanged
                && showEnabled == other.showEnabled
                && showDisabled == other.showDisabled;
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(severity) * 31 + Arrays.hashCode(type);
        }

        @Override
        public String toString()
        {
            return "severity=" + Arrays.toString(severity) + ", type=" + Arrays.toString(type) //$NON-NLS-1$ //$NON-NLS-2$
                + ", default=" + showDefault + ", changed=" + showChanged //$NON-NLS-1$ //$NON-NLS-2$
                + ", enabled=" + showEnabled + ", disabled=" + showDisabled; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static final class Debug
    {
        private static final String TAG = "ValidationChecksFilter"; //$NON-NLS-1$
        /** Тема временного лога {@code .tmp/temp-logs/validation-checks.log} (см. {@link #temp}). */
        private static final String TEMP_TOPIC = "validation-checks"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }

        /**
         * ВРЕМЕННАЯ диагностика переработки страницы «Проверки» (issue 401):
         * безусловная запись в отдельный файл, независимо от «Общего логирования»
         * — иначе при первой же неудаче лог оказался бы пустым. Снять после
         * подтверждения работоспособности пользователем.
         */
        static void temp(String msg)
        {
            Global.tempLog(TEMP_TOPIC, msg);
        }
    }
}
