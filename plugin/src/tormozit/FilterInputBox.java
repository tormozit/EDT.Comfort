package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.common.ui.controls.search.ISearchHistory;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

/**
 * Единое поле smart-фильтра в окнах плагина — штатный {@link SearchBox}, как в окне «Значения» EDT.
 */
final class FilterInputBox
{
    /**
     * Единый текст подсказки для полей ввода, где фильтр умеет секции по точке и точную фразу
     * в кавычках (см. {@link SmartMatcher#parseSections}) — «Открыть объект метаданных»,
     * «Редактирование типа данных», поле «Тип» в «Новый реквизит», панель «Навигатор».
     */
    static final String HIERARCHICAL_FILTER_TOOLTIP =
        "Иерархический фильтр:\n" //$NON-NLS-1$
        + "• пробел между словами — И (все слова должны совпасть)\n" //$NON-NLS-1$
        + "• точка — переход на уровень иерархии (родитель.элемент), сравнение с конца полного имени\n" //$NON-NLS-1$
        + "• \"текст в кавычках\" — точная фраза целиком; точки и пробелы внутри неё не разделяют"; //$NON-NLS-1$

    /**
     * Текст подсказки для полей, где фильтр — простой плоский AND по словам, БЕЗ иерархии
     * (точка — такой же разделитель слов, как пробел, не переход по уровню): панель
     * «Индексирование Git» ({@link GitStagingFilterHook}).
     */
    static final String FLAT_FILTER_TOOLTIP =
        "Многословный фильтр:\n" //$NON-NLS-1$
        + "• пробел или точка между словами — И (все слова должны совпасть)\n" //$NON-NLS-1$
        + "• \"текст в кавычках\" — точная фраза целиком; точки и пробелы внутри неё не разделяют\n" //$NON-NLS-1$
        + "• ищет по полному имени объекта метаданных и имени файла"; //$NON-NLS-1$

    /**
     * Диалоги «Установить фильтр по объектам и ролям» / «Установить фильтр по правам»:
     * плоский AND по словам, матч по видимой подписи узла.
     */
    static final String SET_FILTER_DIALOG_TOOLTIP =
        "Многословный фильтр:\n" //$NON-NLS-1$
        + "• пробел или точка между словами — И (все слова должны совпасть)\n" //$NON-NLS-1$
        + "• \"текст в кавычках\" — точная фраза целиком; точки и пробелы внутри неё не разделяют\n" //$NON-NLS-1$
        + "• ищет по имени в списке; родители совпадений не скрываются"; //$NON-NLS-1$

    /**
     * Текст подсказки для «Фильтра по подсистемам» EDT: простой плоский AND по словам
     * (точка — такой же разделитель слов, как пробел), матч по имени узла дерева подсистем.
     */
    static final String SUBSYSTEMS_FILTER_TOOLTIP =
        "Многословный фильтр по подсистемам:\n" //$NON-NLS-1$
        + "• пробел или точка между словами — И (все слова должны совпасть)\n" //$NON-NLS-1$
        + "• \"текст в кавычках\" — точная фраза целиком; точки и пробелы внутри неё не разделяют\n" //$NON-NLS-1$
        + "• ищет по имени подсистемы; родители совпадений не скрываются"; //$NON-NLS-1$

    /**
     * Панель «Синтакс-помощник», страница «Содержание»: плоский AND по словам,
     * матч по видимой подписи узла дерева.
     */
    static final String SYNTAX_CONTENTS_FILTER_TOOLTIP =
        "Многословный фильтр:\n" //$NON-NLS-1$
        + "• пробел или точка между словами — И (все слова должны совпасть)\n" //$NON-NLS-1$
        + "• \"текст в кавычках\" — точная фраза целиком; точки и пробелы внутри неё не разделяют\n" //$NON-NLS-1$
        + "• ищет по имени узла дерева; родители совпадений не скрываются"; //$NON-NLS-1$

    /**
     * Панель «Структура проекта»: иерархический фильтр, как в навигаторе;
     * русские названия папок из декоратора; суффикс {@code <объект>} в поиск не входит.
     */
    static final String PROJECT_STRUCTURE_FILTER_TOOLTIP =
        HIERARCHICAL_FILTER_TOOLTIP + "\n" //$NON-NLS-1$
        + "• ищет по имени файла/папки и русскому названию типа из декоратора\n" //$NON-NLS-1$
        + "• суффикс <объект> в фильтре не участвует\n" //$NON-NLS-1$
        + "• видны только совпадения и их родители"; //$NON-NLS-1$

    private static final int MAX_ITEMS = 20;
    /** Максимальная ширина поля фильтра. */
    static final int MAX_WIDTH = 300;
    /** Отступ справа от compact-поля до следующего элемента строки. */
    static final int COMPACT_RIGHT_MARGIN = 10;

    private static final String HISTORY_SCOPE_DATA = "tormozit.filterHistoryScope"; //$NON-NLS-1$

    private static final String HISTORY_SAVE_INSTALLED_DATA = "tormozit.filterHistorySaveInstalled"; //$NON-NLS-1$

    enum Scope
    {
        COLLECTION(
            "debug.collection.filter.history.count", //$NON-NLS-1$
            "debug.collection.filter.history."), //$NON-NLS-1$
        RECENT_PLACES(
            "comfort.recentPlaces.filter.history.count", //$NON-NLS-1$
            "comfort.recentPlaces.filter.history."), //$NON-NLS-1$
        OBJECT_SETS(
            "comfort.objectSets.filter.history.count", //$NON-NLS-1$
            "comfort.objectSets.filter.history."), //$NON-NLS-1$
        OPEN_MD_OBJECT(
            "comfort.openMdObject.filter.history.count", //$NON-NLS-1$
            "comfort.openMdObject.filter.history."), //$NON-NLS-1$
        SELECT_TYPE(
            "comfort.selectType.filter.history.count", //$NON-NLS-1$
            "comfort.selectType.filter.history."), //$NON-NLS-1$
        PICTURE_DIALOG(
            "comfort.pictureDialog.filter.history.count", //$NON-NLS-1$
            "comfort.pictureDialog.filter.history."), //$NON-NLS-1$
        GIT_HISTORY(
            "comfort.gitHistory.filter.history.count", //$NON-NLS-1$
            "comfort.gitHistory.filter.history."), //$NON-NLS-1$
        RIGHTS_DIALOG(
            "comfort.rightsDialog.filter.history.count", //$NON-NLS-1$
            "comfort.rightsDialog.filter.history."), //$NON-NLS-1$
        RIGHTS_EDITOR(
            "comfort.rightsEditor.filter.history.count", //$NON-NLS-1$
            "comfort.rightsEditor.filter.history."), //$NON-NLS-1$
        RIGHTS_EDITOR_LEAVES(
            "comfort.rightsEditor.leaves.filter.history.count", //$NON-NLS-1$
            "comfort.rightsEditor.leaves.filter.history."), //$NON-NLS-1$
        SET_FILTER_DIALOG(
            "comfort.setFilterDialog.filter.history.count", //$NON-NLS-1$
            "comfort.setFilterDialog.filter.history."), //$NON-NLS-1$
        FILTER_BY_SUBSYSTEMS(
            "comfort.filterBySubsystems.filter.history.count", //$NON-NLS-1$
            "comfort.filterBySubsystems.filter.history."), //$NON-NLS-1$
        FILTERED_LIST_DIALOG(
            "comfort.filteredListDialog.filter.history.count", //$NON-NLS-1$
            "comfort.filteredListDialog.filter.history."), //$NON-NLS-1$
        INFOBASES(
            "comfort.infobasesView.filter.history.count", //$NON-NLS-1$
            "comfort.infobasesView.filter.history."), //$NON-NLS-1$
        COMPARE_STRUCTURE(
            "comfort.compareStructure.filter.history.count", //$NON-NLS-1$
            "comfort.compareStructure.filter.history."), //$NON-NLS-1$
        VALIDATION_CHECKS(
            "comfort.validationChecks.filter.history.count", //$NON-NLS-1$
            "comfort.validationChecks.filter.history."), //$NON-NLS-1$
        STACKTRACES(
            "comfort.stacktracesList.filter.history.count", //$NON-NLS-1$
            "comfort.stacktracesList.filter.history."), //$NON-NLS-1$
        COLUMN_VALUES(
            "comfort.formTableColumnValues.filter.history.count", //$NON-NLS-1$
            "comfort.formTableColumnValues.filter.history."), //$NON-NLS-1$
        PROJECT_STRUCTURE(
            "comfort.projectStructure.filter.history.count", //$NON-NLS-1$
            "comfort.projectStructure.filter.history."), //$NON-NLS-1$
        EVENT_HANDLERS(
            "comfort.eventHandlers.filter.history.count", //$NON-NLS-1$
            "comfort.eventHandlers.filter.history."), //$NON-NLS-1$
        FORM_ITEMS(
            "comfort.formItems.filter.history.count", //$NON-NLS-1$
            "comfort.formItems.filter.history."), //$NON-NLS-1$
        SYNTAX_CONTENTS(
            "comfort.syntaxAssistContents.filter.history.count", //$NON-NLS-1$
            "comfort.syntaxAssistContents.filter.history."), //$NON-NLS-1$
        SYNTAX_SEARCH(
            "comfort.syntaxAssistSearch.filter.history.count", //$NON-NLS-1$
            "comfort.syntaxAssistSearch.filter.history."), //$NON-NLS-1$
        LIST_ITEM_SELECTION_DIALOG(
            "comfort.listItemSelectionDialog.filter.history.count", //$NON-NLS-1$
            "comfort.listItemSelectionDialog.filter.history."); //$NON-NLS-1$

        final String prefCountKey;
        final String prefItemPrefix;

        Scope(String prefCountKey, String prefItemPrefix)
        {
            this.prefCountKey = prefCountKey;
            this.prefItemPrefix = prefItemPrefix;
        }
    }

    static final class Options
    {
        Scope scope = Scope.COLLECTION;
        String message = "Фильтр..."; //$NON-NLS-1$
        String tooltip = FLAT_FILTER_TOOLTIP; //$NON-NLS-1$
        GridData layoutData;
        int searchDelay = 150;
    }

    private static ScopedPreferenceStore prefs;

    private final SearchBox searchBox;
    private final Scope scope;

    private FilterInputBox(SearchBox searchBox, Scope scope)
    {
        this.searchBox = searchBox;
        this.scope = scope;
        installDeferredHistorySave();
    }

    static FilterInputBox create(Composite parent, Options options, Runnable onSearch)
    {
        Options opts = options != null ? options : new Options();
        SearchBox box = new SearchBox(parent);
        FilterInputBoxListNavigation.ensureSearchBoxStockKeyStripped(box);
        box.setLayoutData(opts.layoutData != null ? opts.layoutData : compactLayoutData());
        if (opts.tooltip != null)
            box.setToolTipText(opts.tooltip + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
        if (opts.message != null)
            box.setMessage(opts.message);
        box.setMinimumSearchTextLength(0);
        box.setSearchDelay(opts.searchDelay);
        box.setHistory(new PrefsSearchHistory(opts.scope));
        if (onSearch != null)
        {
            box.setSearchListener((text, monitor) -> onSearch.run());
            FilterInputBoxListNavigation.installHistoryCommit(box);
        }
        return new FilterInputBox(box, opts.scope);
    }

    static FilterInputBox forCollection(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.COLLECTION;
        opts.layoutData = compactLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP; //$NON-NLS-1$
        return create(parent, opts, onSearch);
    }

    static FilterInputBox forRecentPlaces(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.RECENT_PLACES;
        opts.layoutData = recentPlacesLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP; //$NON-NLS-1$
        return create(parent, opts, onSearch);
    }

    static FilterInputBox forObjectSets(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.OBJECT_SETS;
        opts.layoutData = objectSetsLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP; //$NON-NLS-1$
        return create(parent, opts, onSearch);
    }

    /** Дерево элементов формы (редактор формы). */
    static FilterInputBox forFormItems(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.FORM_ITEMS;
        opts.layoutData = compactLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    static FilterInputBox forOpenMdObject(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.OPEN_MD_OBJECT;
        opts.layoutData = compactLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = HIERARCHICAL_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    static FilterInputBox forSelectType(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.SELECT_TYPE;
        opts.layoutData = compactLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = HIERARCHICAL_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    static FilterInputBox forPictureDialog(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.PICTURE_DIALOG;
        opts.layoutData = compactLayoutData();
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP; //$NON-NLS-1$
        return create(parent, opts, onSearch);
    }

    /** Фильтр файлов коммита в «История Git» — на всю ширину, с лупой SearchBox. */
    static FilterInputBox forGitHistory(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.GIT_HISTORY;
        opts.layoutData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    /** Фильтр дерева структуры в панели сравнения (см. {@code CompareDialogStructurePanel}) — компактный, не на всю ширину. */
    static FilterInputBox forCompareStructure(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.COMPARE_STRUCTURE;
        opts.layoutData = compactLayoutData();
        opts.message = "Фильтр (можно несколько слов)"; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    /** Фильтр списка трассировок в панели «Трассировки стеков». */
    static FilterInputBox forStacktraces(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.STACKTRACES;
        opts.layoutData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    /** Фильтр по подстроке в окне «Значения колонки» ({@code ColumnValuesDialog}) — на всю ширину. */
    static FilterInputBox forColumnValues(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.COLUMN_VALUES;
        opts.layoutData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    /** Фильтр дерева «Структура проекта» — на всю ширину, с историей. */
    static FilterInputBox forProjectStructure(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.PROJECT_STRUCTURE;
        opts.layoutData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = PROJECT_STRUCTURE_FILTER_TOOLTIP;
        opts.searchDelay = 200;
        return create(parent, opts, onSearch);
    }

    /**
     * Подключает уже созданный {@link SearchBox} (повтор патча после оборачивания дерева).
     */
    static FilterInputBox wrapExisting(SearchBox box, Scope scope, Runnable onSearch)
    {
        if (box == null || box.isDisposed() || scope == null)
            return null;
        FilterInputBoxListNavigation.ensureSearchBoxStockKeyStripped(box);
        box.setHistory(new PrefsSearchHistory(scope));
        if (onSearch != null)
        {
            box.setSearchListener((text, monitor) -> onSearch.run());
            FilterInputBoxListNavigation.installHistoryCommit(box);
        }
        return new FilterInputBox(box, scope);
    }

    /** Замена штатного поля в {@code FilteredList}-диалогах (см. {@code FilteredListDialogFilterHook}) — на всю ширину. */
    static FilterInputBox forFilteredListDialog(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.FILTERED_LIST_DIALOG;
        opts.layoutData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    /**
     * Замена штатного поля в диалоге {@code ListItemSelectionDialog} («Выбор объекта», например
     * функциональные опции) — см. {@code ListItemSelectionDialogFilterHook}. На всю ширину, как
     * было у штатного поля: {@code dialogArea} — однoколоночный {@code GridLayout}, узкое поле
     * оставляет пустую полосу в своей же строке справа (не растягивает саму строку по высоте —
     * просто теряется свободное место, визуально «дыра»).
     */
    static FilterInputBox forListItemSelectionDialog(Composite parent, Runnable onSearch)
    {
        Options opts = new Options();
        opts.scope = Scope.LIST_ITEM_SELECTION_DIALOG;
        opts.layoutData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        opts.message = "Фильтр..."; //$NON-NLS-1$
        opts.tooltip = FLAT_FILTER_TOOLTIP;
        return create(parent, opts, onSearch);
    }

    /**
     * Заменяет штатное поле паттерна в диалоге EDT на {@link SearchBox} с историей. Принимает
     * любой {@link Control} с текстом ({@link Text} или {@link StyledText}, в т.ч. потомки вроде
     * {@code SearchTextWithClearButton}) — исходный текст читается через {@link #controlText}.
     */
    static FilterInputBox replacePatternText(Control oldControl, Scope scope, Runnable onSearch)
    {
        if (oldControl == null || oldControl.isDisposed())
            return null;
        Composite parent = oldControl.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Object layoutData = oldControl.getLayoutData();
        String initial = controlText(oldControl);
        Control siblingBelow = siblingBelow(oldControl);
        oldControl.dispose();
        FilterInputBox result = createForScope(parent, scope, onSearch);
        if (result == null)
            return null;
        // Если scope сам решил тянуться на всю строку (grab=true — FILTERED_LIST_DIALOG,
        // LIST_ITEM_SELECTION_DIALOG и т.п.), не сжимать обратно в compact: старое поле в
        // однoколоночном GridLayout занимало всю ширину, узкая замена оставляла бы пустую
        // полосу в той же строке справа от поля (визуально «дыра»).
        boolean scopeWantsFill = result.widget().getLayoutData() instanceof GridData scopeGd
            && scopeGd.grabExcessHorizontalSpace;
        if (!scopeWantsFill)
        {
            if (layoutData != null)
                result.widget().setLayoutData(layoutData);
            applyCompactLayout(result.widget());
        }
        if (siblingBelow != null && !siblingBelow.isDisposed())
            result.widget().moveAbove(siblingBelow);
        result.setText(initial != null ? initial : ""); //$NON-NLS-1$
        parent.layout(true, true);
        return result;
    }

    private static String controlText(Control control)
    {
        if (control instanceof Text t)
            return t.getText();
        if (control instanceof StyledText st)
            return st.getText();
        return ""; //$NON-NLS-1$
    }

    /** Найти SearchBox в иерархии родителей control. */
    public static SearchBox resolveSearchBox(Control control)
    {
        Control current = control;
        while (current != null && !current.isDisposed())
        {
            if (current instanceof SearchBox)
                return (SearchBox) current;
            current = current.getParent();
        }
        return null;
    }

    /**
     * Подключает персистентную историю и ставит compact-ширину
     * (см. {@link #applyCompactLayout}).
     */
    static void attachHistory(SearchBox searchBox, Scope scope)
    {
        attachHistory(searchBox, scope, true);
    }

    /**
     * Как {@link #attachHistory(SearchBox, Scope)}, без compact-layout:
     * штатный {@code GridData} поля не трогаем. Для страницы «Валидация»,
     * где {@code SearchBox} штатно тянется в строке с тулбаром.
     */
    static void attachHistoryKeepLayout(SearchBox searchBox, Scope scope)
    {
        attachHistory(searchBox, scope, false);
    }

    private static void attachHistory(SearchBox searchBox, Scope scope, boolean compactLayout)
    {
        if (searchBox == null || searchBox.isDisposed() || scope == null)
            return;
        searchBox.setHistory(new PrefsSearchHistory(scope));
        installDeferredHistorySave(searchBox, scope);
        if (compactLayout)
            applyCompactLayout(searchBox);
    }

    private static FilterInputBox createForScope(Composite parent, Scope scope, Runnable onSearch)
    {
        return switch (scope)
        {
            case COLLECTION -> forCollection(parent, onSearch);
            case RECENT_PLACES -> forRecentPlaces(parent, onSearch);
            case OBJECT_SETS -> forObjectSets(parent, onSearch);
            case OPEN_MD_OBJECT -> forOpenMdObject(parent, onSearch);
            case SELECT_TYPE -> forSelectType(parent, onSearch);
            case PICTURE_DIALOG -> forPictureDialog(parent, onSearch);
            case GIT_HISTORY -> forGitHistory(parent, onSearch);
            case FILTERED_LIST_DIALOG -> forFilteredListDialog(parent, onSearch);
            case LIST_ITEM_SELECTION_DIALOG -> forListItemSelectionDialog(parent, onSearch);
            case FORM_ITEMS -> forFormItems(parent, onSearch);
            case RIGHTS_DIALOG -> throw new IllegalStateException("RIGHTS_DIALOG: use attachHistory(SearchBox, Scope.RIGHTS_DIALOG)"); //$NON-NLS-1$
            case RIGHTS_EDITOR -> throw new IllegalStateException("RIGHTS_EDITOR: use attachHistory(SearchBox, Scope.RIGHTS_EDITOR)"); //$NON-NLS-1$
            case RIGHTS_EDITOR_LEAVES -> throw new IllegalStateException("RIGHTS_EDITOR_LEAVES: use attachHistory(SearchBox, Scope.RIGHTS_EDITOR_LEAVES)"); //$NON-NLS-1$
            case SET_FILTER_DIALOG -> throw new IllegalStateException("SET_FILTER_DIALOG: use attachHistory(SearchBox, Scope.SET_FILTER_DIALOG)"); //$NON-NLS-1$
            case FILTER_BY_SUBSYSTEMS -> throw new IllegalStateException("FILTER_BY_SUBSYSTEMS: use attachHistory(SearchBox, Scope.FILTER_BY_SUBSYSTEMS)"); //$NON-NLS-1$
            case INFOBASES -> throw new IllegalStateException("INFOBASES: use attachHistory(SearchBox, Scope.INFOBASES)"); //$NON-NLS-1$
            case COMPARE_STRUCTURE -> forCompareStructure(parent, onSearch);
            case VALIDATION_CHECKS -> throw new IllegalStateException("VALIDATION_CHECKS: use attachHistory(SearchBox, Scope.VALIDATION_CHECKS)"); //$NON-NLS-1$
            case STACKTRACES -> forStacktraces(parent, onSearch);
            case COLUMN_VALUES -> forColumnValues(parent, onSearch);
            case PROJECT_STRUCTURE -> forProjectStructure(parent, onSearch);
            case EVENT_HANDLERS -> throw new IllegalStateException("EVENT_HANDLERS: use attachHistory(SearchBox, Scope.EVENT_HANDLERS)"); //$NON-NLS-1$
            case SYNTAX_CONTENTS -> throw new IllegalStateException("SYNTAX_CONTENTS: use attachHistory(SearchBox, Scope.SYNTAX_CONTENTS)"); //$NON-NLS-1$
            case SYNTAX_SEARCH -> throw new IllegalStateException("SYNTAX_SEARCH: use attachHistoryKeepLayout(SearchBox, Scope.SYNTAX_SEARCH)"); //$NON-NLS-1$
        };
    }

    static GridData compactLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = MAX_WIDTH;
        gd.minimumWidth = 80;
        return gd;
    }

    /**
     * Стандартная ширина поля фильтра: не шире {@link #MAX_WIDTH}, без растягивания
     * на всю строку. {@link #attachHistory} вызывает сам; {@link #attachHistoryKeepLayout}
     * — нет. Снаружи — если layout ставится отдельно.
     */
    static void applyCompactLayout(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        Composite parent = control.getParent();
        if (parent == null || parent.isDisposed() || !(parent.getLayout() instanceof GridLayout))
            return;
        GridData gd;
        Object existing = control.getLayoutData();
        if (existing instanceof GridData data)
            gd = data;
        else
            gd = compactLayoutData();
        gd.grabExcessHorizontalSpace = false;
        gd.horizontalAlignment = SWT.BEGINNING;
        gd.verticalAlignment = SWT.CENTER;
        if (gd.widthHint <= 0 || gd.widthHint > MAX_WIDTH)
            gd.widthHint = MAX_WIDTH;
        if (gd.minimumWidth < 80)
            gd.minimumWidth = 80;
        control.setLayoutData(gd);
    }

    static GridData recentPlacesLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = MAX_WIDTH;
        gd.minimumWidth = 80;
        return gd;
    }

    static GridData objectSetsLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = MAX_WIDTH;
        gd.minimumWidth = 80;
        return gd;
    }

    static void addTrailingSpacer(Composite parent)
    {
        Label spacer = new Label(parent, SWT.NONE);
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = COMPACT_RIGHT_MARGIN;
        spacer.setLayoutData(gd);
    }

    SearchBox widget()
    {
        return searchBox;
    }

    String getText()
    {
        if (searchBox == null || searchBox.isDisposed())
            return ""; //$NON-NLS-1$
        return searchBox.getText();
    }

    void setText(String text)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        searchBox.setText(text != null ? text : ""); //$NON-NLS-1$
    }

    void setFocus()
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        searchBox.setFocus();
    }

    /** Фокус после открытия окна/диалога (когда штатный UI уже завершил activate). */
    void scheduleFocusWhenReady()
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!isDisposed())
                setFocus();
        });
    }

    boolean isDisposed()
    {
        return searchBox == null || searchBox.isDisposed();
    }

    boolean isFocusControl()
    {
        if (isDisposed())
            return false;
        if (searchBox.isFocusControl())
            return true;
        Control input = inputControl();
        return input != null && !input.isDisposed() && input.isFocusControl();
    }

    /** Для {@link FilterInputBoxListNavigation} и {@code KeyListener}. */
    Control inputControl()
    {
        return findTextControl(searchBox);
    }

    private void installDeferredHistorySave()
    {
        installDeferredHistorySave(searchBox, scope);
    }

    private static void installDeferredHistorySave(SearchBox box, Scope historyScope)
    {
        if (box == null || box.isDisposed() || historyScope == null)
            return;
        box.setData(HISTORY_SCOPE_DATA, historyScope);
        if (Boolean.TRUE.equals(box.getData(HISTORY_SAVE_INSTALLED_DATA)))
            return;
        box.setData(HISTORY_SAVE_INSTALLED_DATA, Boolean.TRUE);
        Runnable persist = () -> {
            if (box.isDisposed())
                return;
            Object scopeObj = box.getData(HISTORY_SCOPE_DATA);
            if (!(scopeObj instanceof Scope activeScope))
                return;
            String text = box.getText();
            if (text != null && !text.trim().isEmpty())
                remember(activeScope, text);
        };
        Control input = findTextControl(box);
        if (input != null && !input.isDisposed())
            input.addListener(SWT.FocusOut, e -> persist.run());
        box.addDisposeListener(e -> persist.run());
    }

    private static Control findTextControl(Object searchBox)
    {
        if (searchBox == null)
            return null;
        StyledText styled = styledTextFromSearchBox(searchBox);
        if (styled != null && !styled.isDisposed())
            return styled;
        if (searchBox instanceof StyledText)
            return (Control) searchBox;
        if (searchBox instanceof Text)
            return (Control) searchBox;
        for (String field : new String[] { "text", "searchText", "styledText", "searchTextWidget" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object text = Global.getField(searchBox, field);
            if (text instanceof Control && !((Control) text).isDisposed())
                return (Control) text;
        }
        for (String method : new String[] { "getText", "getSearchText", "getStyledText", "getInputControl" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object text = Global.invoke(searchBox, method);
            if (text instanceof Control && !((Control) text).isDisposed())
                return (Control) text;
        }
        if (searchBox instanceof Composite)
            return findTextControlInComposite((Composite) searchBox);
        return searchBox instanceof Control ? (Control) searchBox : null;
    }

    private static StyledText styledTextFromSearchBox(Object searchBox)
    {
        if (searchBox == null)
            return null;
        for (String field : new String[] { "text", "searchText", "styledText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object text = Global.getField(searchBox, field);
            if (text instanceof StyledText)
                return (StyledText) text;
        }
        return null;
    }

    private static Control findTextControlInComposite(Composite parent)
    {
        if (parent == null || parent.isDisposed())
            return null;
        for (Control child : parent.getChildren())
        {
            if (child instanceof StyledText || child instanceof Text)
                return child;
            if (child instanceof Composite)
            {
                Control found = findTextControlInComposite((Composite) child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    public static Control siblingBelow(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null)
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control && i + 1 < children.length)
                return children[i + 1];
        }
        return null;
    }

    static void remember(Scope scope, String pattern)
    {
        if (pattern == null)
            return;
        final String trimmed = pattern.trim();
        if (trimmed.isEmpty())
            return;

        List<String> items = new ArrayList<>(load(scope));
        items.removeIf(existing -> trimmed.equalsIgnoreCase(existing));
        items.add(0, trimmed);
        while (items.size() > MAX_ITEMS)
            items.remove(items.size() - 1);
        save(scope, items);
    }

    static List<String> load(Scope scope)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return new ArrayList<>();
        int count = store.getInt(scope.prefCountKey);
        if (count <= 0)
            return new ArrayList<>();
        List<String> items = new ArrayList<>(Math.min(count, MAX_ITEMS));
        for (int i = 0; i < count && i < MAX_ITEMS; i++)
        {
            String value = store.getString(scope.prefItemPrefix + i);
            if (value != null && !value.isBlank())
                items.add(value.trim());
        }
        return items;
    }

    static void save(Scope scope, List<String> items)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || items == null)
            return;
        int count = Math.min(items.size(), MAX_ITEMS);
        store.setValue(scope.prefCountKey, count);
        for (int i = 0; i < count; i++)
            store.setValue(scope.prefItemPrefix + i, items.get(i));
        for (int i = count; i < MAX_ITEMS; i++)
            store.setToDefault(scope.prefItemPrefix + i);
        try
        {
            store.save();
        }
        catch (Exception ignored)
        {
            // prefs optional
        }
    }

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(FilterInputBox.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }

    private static final class PrefsSearchHistory implements ISearchHistory
    {
        private final Scope historyScope;

        PrefsSearchHistory(Scope historyScope)
        {
            this.historyScope = historyScope;
        }

        @Override
        public void savePattern(String pattern)
        {
            // отложено — см. installDeferredHistorySave()
        }

        @Override
        public void replacePattern(String pattern)
        {
            // отложено — см. installDeferredHistorySave()
        }

        @Override
        public String getActivePattern()
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public List<String> getRecentPatterns(int max)
        {
            List<String> items = load(historyScope);
            if (max <= 0 || items.size() <= max)
                return items;
            return new ArrayList<>(items.subList(0, max));
        }
    }

    void remember(String pattern)
    {
        remember(scope, pattern);
    }
}