package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
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

    private static final int MAX_ITEMS = 20;
    /** Максимальная ширина compact-поля (окно «Коллекция»). */
    static final int COMPACT_MAX_WIDTH = 267;
    /** Максимальная ширина поля фильтра (панель «Последние места»). */
    static final int RECENT_PLACES_MAX_WIDTH = 300;
    /** Максимальная ширина поля фильтра (панель «Набор объектов»). */
    static final int OBJECT_SETS_MAX_WIDTH = 300;
    /** Отступ справа от compact-поля до следующего элемента строки. */
    static final int COMPACT_RIGHT_MARGIN = 10;

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
            "comfort.rightsDialog.filter.history."); //$NON-NLS-1$

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
            box.setSearchListener((text, monitor) -> onSearch.run());
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

    /**
     * Заменяет штатное {@link Text} поле паттерна в диалоге EDT на {@link SearchBox} с историей.
     */
    static FilterInputBox replacePatternText(Text oldText, Scope scope, Runnable onSearch)
    {
        if (oldText == null || oldText.isDisposed())
            return null;
        Composite parent = oldText.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Object layoutData = oldText.getLayoutData();
        String initial = oldText.getText();
        Control siblingBelow = siblingBelow(oldText);
        oldText.dispose();
        FilterInputBox result = createForScope(parent, scope, onSearch);
        if (result == null)
            return null;
        if (layoutData != null)
            result.widget().setLayoutData(layoutData);
        if (siblingBelow != null && !siblingBelow.isDisposed())
            result.widget().moveAbove(siblingBelow);
        result.setText(initial != null ? initial : ""); //$NON-NLS-1$
        parent.layout(true, true);
        return result;
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

    static void attachHistory(SearchBox searchBox, Scope scope)
    {
        if (searchBox == null || searchBox.isDisposed() || scope == null)
            return;
        searchBox.setHistory(new PrefsSearchHistory(scope));
        installDeferredHistorySave(searchBox, scope);
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
            case RIGHTS_DIALOG -> throw new IllegalStateException("RIGHTS_DIALOG: use attachHistory(SearchBox, Scope.RIGHTS_DIALOG)"); //$NON-NLS-1$
        };
    }

    static GridData compactLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = COMPACT_MAX_WIDTH;
        gd.minimumWidth = 80;
        return gd;
    }

    static GridData recentPlacesLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = RECENT_PLACES_MAX_WIDTH;
        gd.minimumWidth = 80;
        return gd;
    }

    static GridData objectSetsLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = OBJECT_SETS_MAX_WIDTH;
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
        Runnable persist = () -> {
            if (box.isDisposed())
                return;
            String text = box.getText();
            if (text != null && !text.trim().isEmpty())
                remember(historyScope, text);
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