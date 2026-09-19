package tormozit;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferenceFilter;
import org.eclipse.core.runtime.preferences.PreferenceFilterEntry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.internal.preferences.PreferenceTransferElement;

/**
 * Общая логика {@link ComfortPreferencesExportPage}/{@link ComfortPreferencesImportPage}
 * по списку категорий «Экспортировать/импортировать параметры»: скрывает категории Java
 * (не нужны в EDT), добавляет к подписи суффикс «(Общие)»/«(Проект)» по scope её
 * {@code <mapping>} в расширении {@code org.eclipse.ui.preferenceTransfer}, и в обход
 * {@link #appendProjectCategory} добавляет категорию «Комфорт (Проект)».
 */
final class ComfortPreferenceTransferFilter
{
    private static final String TAG = "ComfortPreferenceTransferFilter"; //$NON-NLS-1$

    private static final String PREFERENCE_TRANSFER_EXTENSION_POINT = "org.eclipse.ui.preferenceTransfer"; //$NON-NLS-1$

    private static final String SUFFIX_COMMON = " (Общие)"; //$NON-NLS-1$

    private static final String SUFFIX_PROJECT = " (Проект)"; //$NON-NLS-1$

    /**
     * Id категорий «... (Проект)», каждая для своего узла ProjectScope. Их
     * {@code <mapping scope="project"><entry node="..."/></mapping>} в plugin.xml —
     * заглушка на случай валидации схемы: реальный путь ProjectScope —
     * {@code /project/<ИмяПроекта>/<qualifier>} (с именем проекта посередине), декларативный
     * mapping без имени проекта такое не матчит.
     * <p>
     * Хуже того: {@code WizardPreferencesExportPage1.getTransfers()} (штатный, по байткоду)
     * дополнительно проверяет фильтр КАЖДОЙ категории против {@code rootNode.node("instance")}
     * и выбрасывает её из списка, если совпадений там нет — категория с фильтром только по
     * scope="project" туда никогда не попадёт, сколько её мэппинг ни настраивай. Поэтому такие
     * категории не проходят через {@link #filterAndLabel}/{@code super.getTransfers()} вообще —
     * их добавляет в обход {@link #appendProjectCategory}, читая {@code IConfigurationElement}
     * напрямую из реестра расширений.
     */
    static final String COMFORT_PROJECT_TRANSFER_ID = "tormozit.prefTransfer.comfortProject"; //$NON-NLS-1$

    /** Узел ProjectScope — {@code com._1c.g5.v8.dt.bsl.Bsl} (id Xtext-языка BSL, см. bsl-ui/plugin.xml). */
    static final String BSL_PROJECT_TRANSFER_ID = "tormozit.prefTransfer.bslProject"; //$NON-NLS-1$

    /**
     * Узел ProjectScope с метаданными профиля проверок (активный профиль, отключение
     * массовых проверок) — {@code com.e1c.g5.v8.dt.check}, см. декомпилированный
     * {@code JSONCheckSettingsProfileStore}. Сами настройки проверок внутри профиля —
     * НЕ preferences, а JSON-файлы {@code <проект>/.settings/<профиль>.cset}; их переносит
     * отдельно {@code ComfortCheckProfileTransfer} (вызывается из страниц по
     * {@link #projectNameForQualifier} с этим же qualifier, чтобы не завязываться на вторую
     * проверку состояния флажка категории).
     */
    static final String CHECKS_PROJECT_TRANSFER_ID = "tormozit.prefTransfer.checksProject"; //$NON-NLS-1$

    static final String CHECKS_PREFERENCE_QUALIFIER = "com.e1c.g5.v8.dt.check"; //$NON-NLS-1$

    /**
     * Id категории «Параметры для метаданных» — обычная рабочая категория (наборы объектов,
     * недавние места, позиция каретки в модулях, выбор элемента дерева формы, фильтр панели
     * проблем «по подсистемам» и его история, пресеты фильтра по подсистемам — см. plugin.xml).
     * На экспорте равноправна с остальными. На импорте недоступна, если файл — из другой
     * рабочей области (эти значения ссылаются на имена объектов конфигурации): и визуально
     * ({@link #vetoCheckingItem}), и функционально — {@code getFilters()} страницы импорта
     * вызывает {@link #stripMetadataReferences} независимо от состояния флажка.
     */
    static final String METADATA_REFERENCES_TRANSFER_ID = "tormozit.prefTransfer.metadataReferences"; //$NON-NLS-1$

    /** Узел и ключ-маркер из мэппинга категории — по ним узнаём её среди {@code IPreferenceFilter[]}. */
    private static final String METADATA_REFERENCES_MARKER_NODE = "tormozit"; //$NON-NLS-1$

    private static final String METADATA_REFERENCES_MARKER_KEY = "objectSets.data"; //$NON-NLS-1$

    private ComfortPreferenceTransferFilter()
    {
    }

    static PreferenceTransferElement[] filterAndLabel(PreferenceTransferElement[] source)
    {
        if (source == null)
            return new PreferenceTransferElement[0];
        List<PreferenceTransferElement> result = new ArrayList<>(source.length);
        StringBuilder hidden = new StringBuilder();
        for (PreferenceTransferElement element : source)
        {
            if (isJavaCategory(element))
            {
                hidden.append(hidden.isEmpty() ? "" : ", ").append(element.getID()); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            result.add(wrap(element, null, null));
        }
        StringBuilder shown = new StringBuilder();
        for (PreferenceTransferElement element : result)
            shown.append(shown.isEmpty() ? "" : "; ").append(element.getLabel(null)); //$NON-NLS-1$ //$NON-NLS-2$
        Global.tempLog(TAG, "filterAndLabel: source=" + source.length + " shown=" + result.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " [" + shown + "] hiddenJdt=[" + hidden + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return result.toArray(new PreferenceTransferElement[0]);
    }

    /**
     * Добавляет категорию «... (Проект)» {@code transferId} к {@code elements} (уже прошедшим
     * {@link #filterAndLabel}) в обход штатной фильтрации по instance-scope — см. javadoc
     * {@link #COMFORT_PROJECT_TRANSFER_ID}. {@code preferenceNodeQualifier} — узел ProjectScope
     * (например {@code "tormozit"} или {@code "com._1c.g5.v8.dt.bsl.Bsl"}). {@code projectNamesSupplier}
     * читается заново при каждом {@code getFilter()} (то есть на момент нажатия «Готово», а не на
     * момент построения списка) и может вернуть несколько имён — на экспорте один (текущий
     * проект), на импорте столько, сколько отмечено в «Проекты приёмники»
     * ({@link ComfortPreferencesImportPage}).
     */
    static PreferenceTransferElement[] appendProjectCategory(PreferenceTransferElement[] elements,
            String transferId, String preferenceNodeQualifier, Supplier<List<String>> projectNamesSupplier)
    {
        for (PreferenceTransferElement element : elements)
            if (transferId.equals(element.getID()))
                return elements; // уже есть (штатная фильтрация когда-нибудь перестанет её выбрасывать)

        PreferenceTransferElement raw = findRawElement(transferId);
        if (raw == null)
        {
            Global.tempLog(TAG, "appendProjectCategory: id не найден в реестре расширений: " + transferId); //$NON-NLS-1$
            return elements;
        }
        PreferenceTransferElement[] result = Arrays.copyOf(elements, elements.length + 1);
        result[elements.length] = wrap(raw, projectNamesSupplier, preferenceNodeQualifier);
        return result;
    }

    private static final Collator RU_COLLATOR = Collator.getInstance(new Locale("ru")); //$NON-NLS-1$

    /** Сортирует категории по подписи (с суффиксом «(Общие)»/«(Проект)») — вызывать последним. */
    static PreferenceTransferElement[] sortByLabel(PreferenceTransferElement[] elements)
    {
        PreferenceTransferElement[] sorted = Arrays.copyOf(elements, elements.length);
        Arrays.sort(sorted, (a, b) -> RU_COLLATOR.compare(a.getLabel(null), b.getLabel(null)));
        return sorted;
    }

    /**
     * Ставит/снимает пометку по клику на флажок дерева — делает саму строку активной (выделенной),
     * чтобы её описание сразу показывалось в панели снизу, как при обычном клике по строке.
     */
    static void activateRowOnCheck(org.eclipse.ui.dialogs.FilteredTree filteredTree)
    {
        if (filteredTree == null || filteredTree.getViewer() == null)
            return;
        org.eclipse.swt.widgets.Tree tree = filteredTree.getViewer().getTree();
        if (tree == null || tree.isDisposed())
            return;
        tree.addListener(org.eclipse.swt.SWT.Selection, event ->
        {
            if (event.detail != org.eclipse.swt.SWT.CHECK)
                return;
            if (!(event.item instanceof org.eclipse.swt.widgets.TreeItem item))
                return;
            filteredTree.getViewer().setSelection(
                    new org.eclipse.jface.viewers.StructuredSelection(item.getData()), true);
        });
    }

    /**
     * Штатное {@code descText} (панель описания категории под деревом) не должно сжиматься
     * меньше одной строки — {@code heightHint} под давлением сжимается, а
     * {@code GridData.minimumHeight} — жёсткий пол для {@link org.eclipse.swt.layout.GridLayout}.
     * Общий метод для {@link ComfortPreferencesExportPage}/{@link ComfortPreferencesImportPage}.
     */
    static void enforceDescriptionMinimumHeight(Text descText)
    {
        if (descText == null || descText.isDisposed())
            return;
        int oneLine = descText.getLineHeight() + descText.getBorderWidth() * 2;
        if (descText.getLayoutData() instanceof GridData gd)
            gd.minimumHeight = oneLine;
        else
        {
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
            gd.minimumHeight = oneLine;
            descText.setLayoutData(gd);
        }
    }

    /**
     * Если среди отмеченных пользователем фильтров есть наш project-scope фильтр для
     * {@code qualifier} (см. {@link #appendProjectCategory}) — возвращает имена проектов, на
     * которые он настроен (пустой список, если такого фильтра нет). Так
     * {@link ComfortPreferencesExportPage}/{@link ComfortPreferencesImportPage} узнают, нужно
     * ли (и для каких проектов) переносить файловую часть категории «Проверки»
     * ({@code .settings/*.cset}), не полагаясь на состояние флажка дерева напрямую.
     */
    static List<String> projectNamesForQualifier(IPreferenceFilter[] filters, String qualifier)
    {
        if (filters == null)
            return List.of();
        String suffix = '/' + qualifier;
        List<String> names = new ArrayList<>();
        for (IPreferenceFilter filter : filters)
        {
            if (filter == null)
                continue;
            Map<String, PreferenceFilterEntry[]> mapping = filter.getMapping(MultiProjectPreferenceFilter.SCOPE_PROJECT);
            if (mapping == null)
                continue;
            for (String key : mapping.keySet())
                if (key.endsWith(suffix) && key.length() > suffix.length())
                    names.add(key.substring(0, key.length() - suffix.length()));
        }
        return names;
    }

    /**
     * {@code true}, если среди {@code filters} (то, что реально будет выгружено/загружено —
     * из {@code transfer(IPreferenceFilter[])}) есть категория «Параметры для метаданных»:
     * ищем её собственный узел+ключ из мэппинга ({@link #METADATA_REFERENCES_MARKER_NODE}/
     * {@link #METADATA_REFERENCES_MARKER_KEY}) — у «Комфорт (Общие)» (весь узел {@code tormozit}
     * без ограничения по ключам) в мэппинге для этого узла {@code entries == null}, так что
     * спутать их нельзя.
     */
    static boolean isMetadataReferencesChecked(IPreferenceFilter[] filters)
    {
        if (filters == null)
            return false;
        for (IPreferenceFilter filter : filters)
        {
            if (filter == null)
                continue;
            Map<String, PreferenceFilterEntry[]> mapping = filter.getMapping("instance"); //$NON-NLS-1$
            if (mapping == null)
                continue;
            PreferenceFilterEntry[] entries = mapping.get(METADATA_REFERENCES_MARKER_NODE);
            if (entries == null)
                continue;
            for (PreferenceFilterEntry entry : entries)
                if (entry != null && METADATA_REFERENCES_MARKER_KEY.equals(entry.getKey()))
                    return true;
        }
        return false;
    }

    /**
     * Убирает из {@code filters} фильтр категории «Параметры для метаданных» (см.
     * {@link #isMetadataReferencesChecked}) — вызывается на импорте в чужую рабочую область,
     * независимо от того, что показывает флажок дерева (функциональная защита; визуальная —
     * {@link #vetoCheckingItem}).
     */
    static IPreferenceFilter[] stripMetadataReferences(IPreferenceFilter[] filters)
    {
        if (filters == null)
            return filters;
        List<IPreferenceFilter> kept = new ArrayList<>(filters.length);
        int removed = 0;
        for (IPreferenceFilter filter : filters)
        {
            Map<String, PreferenceFilterEntry[]> mapping = filter == null ? null : filter.getMapping("instance"); //$NON-NLS-1$
            PreferenceFilterEntry[] entries = mapping == null ? null : mapping.get(METADATA_REFERENCES_MARKER_NODE);
            boolean isMetadataReferences = false;
            if (entries != null)
                for (PreferenceFilterEntry entry : entries)
                    if (entry != null && METADATA_REFERENCES_MARKER_KEY.equals(entry.getKey()))
                        isMetadataReferences = true;
            if (isMetadataReferences)
                removed++;
            else
                kept.add(filter);
        }
        if (removed > 0)
            Global.tempLog(TAG, "stripMetadataReferences: убрано " + removed //$NON-NLS-1$
                    + " фильтр(ов) — импорт в чужую рабочую область"); //$NON-NLS-1$
        return kept.toArray(new IPreferenceFilter[0]);
    }

    /**
     * Сбрасывает пометку у категории {@code transferId} сразу при попытке поставить её вручную
     * (клик по флажку дерева), если {@code blocked} возвращает {@code true} на момент клика.
     * Кнопки «Выбрать всё»/«Отменить всё» это не перехватывает (штатный
     * {@code CheckboxTreeViewer.setAllChecked} не проходит через {@code SWT.Selection}) — это
     * только визуальная защита, функциональная — {@link #stripMetadataReferences} в
     * {@code getFilters()} страницы импорта, независимо от состояния флажка.
     */
    static void vetoCheckingItem(org.eclipse.ui.dialogs.FilteredTree filteredTree, String transferId,
            java.util.function.BooleanSupplier blocked)
    {
        if (filteredTree == null || filteredTree.getViewer() == null)
            return;
        org.eclipse.swt.widgets.Tree tree = filteredTree.getViewer().getTree();
        if (tree == null || tree.isDisposed())
            return;
        tree.addListener(org.eclipse.swt.SWT.Selection, event ->
        {
            if (event.detail != org.eclipse.swt.SWT.CHECK)
                return;
            if (!(event.item instanceof org.eclipse.swt.widgets.TreeItem item) || !item.getChecked())
                return;
            if (item.getData() instanceof PreferenceTransferElement element && transferId.equals(element.getID())
                    && blocked.getAsBoolean())
            {
                item.setChecked(false);
                Global.tempLog(TAG, "vetoCheckingItem: сброшена попытка включить " + transferId //$NON-NLS-1$
                        + " (чужая рабочая область)"); //$NON-NLS-1$
            }
        });
    }

    /**
     * Программно снимает пометку с категории {@code transferId} в дереве, если она стояла —
     * для случая, когда рабочая область файла стала «чужой» уже после того, как пользователь
     * поставил пометку (сменил путь к файлу). В отличие от {@link #vetoCheckingItem} это не
     * обработчик клика, а разовое действие по вызову.
     */
    static void uncheckItem(org.eclipse.ui.dialogs.FilteredTree filteredTree, String transferId)
    {
        if (filteredTree == null || filteredTree.getViewer() == null)
            return;
        org.eclipse.swt.widgets.Tree tree = filteredTree.getViewer().getTree();
        if (tree == null || tree.isDisposed())
            return;
        for (org.eclipse.swt.widgets.TreeItem item : tree.getItems())
            uncheckItemRecursive(item, transferId);
    }

    private static void uncheckItemRecursive(org.eclipse.swt.widgets.TreeItem item, String transferId)
    {
        if (item.getChecked() && item.getData() instanceof PreferenceTransferElement element
                && transferId.equals(element.getID()))
        {
            item.setChecked(false);
            Global.tempLog(TAG, "uncheckItem: снята пометка с " + transferId + " (чужая рабочая область)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (org.eclipse.swt.widgets.TreeItem child : item.getItems())
            uncheckItemRecursive(child, transferId);
    }

    /** Id (по {@code PreferenceTransferElement.getID()}) всех отмеченных сейчас категорий дерева. */
    static List<String> getCheckedCategoryIds(org.eclipse.ui.dialogs.FilteredTree filteredTree)
    {
        List<String> ids = new ArrayList<>();
        if (filteredTree == null || filteredTree.getViewer() == null)
            return ids;
        org.eclipse.swt.widgets.Tree tree = filteredTree.getViewer().getTree();
        if (tree == null || tree.isDisposed())
            return ids;
        for (org.eclipse.swt.widgets.TreeItem item : tree.getItems())
            collectCheckedIds(item, ids);
        return ids;
    }

    private static void collectCheckedIds(org.eclipse.swt.widgets.TreeItem item, List<String> ids)
    {
        if (item.getChecked() && item.getData() instanceof PreferenceTransferElement element)
            ids.add(element.getID());
        for (org.eclipse.swt.widgets.TreeItem child : item.getItems())
            collectCheckedIds(child, ids);
    }

    /** Ставит пометку на категории дерева, чей id есть в {@code ids} (запомненные с прошлого раза). */
    static void restoreCheckedCategories(org.eclipse.ui.dialogs.FilteredTree filteredTree, java.util.Set<String> ids)
    {
        if (ids.isEmpty() || filteredTree == null || filteredTree.getViewer() == null)
            return;
        org.eclipse.swt.widgets.Tree tree = filteredTree.getViewer().getTree();
        if (tree == null || tree.isDisposed())
            return;
        for (org.eclipse.swt.widgets.TreeItem item : tree.getItems())
            restoreCheckedRecursive(item, ids);
    }

    private static void restoreCheckedRecursive(org.eclipse.swt.widgets.TreeItem item, java.util.Set<String> ids)
    {
        if (item.getData() instanceof PreferenceTransferElement element && ids.contains(element.getID()))
            item.setChecked(true);
        for (org.eclipse.swt.widgets.TreeItem child : item.getItems())
            restoreCheckedRecursive(child, ids);
    }

    private static PreferenceTransferElement findRawElement(String id)
    {
        for (IConfigurationElement el : Platform.getExtensionRegistry()
                .getConfigurationElementsFor(PREFERENCE_TRANSFER_EXTENSION_POINT))
        {
            if ("transfer".equals(el.getName()) && id.equals(el.getAttribute("id"))) //$NON-NLS-1$ //$NON-NLS-2$
                return new PreferenceTransferElement(el);
        }
        return null;
    }

    private static boolean isJavaCategory(PreferenceTransferElement element)
    {
        String pluginId = element.getPluginId();
        return pluginId != null && pluginId.startsWith("org.eclipse.jdt"); //$NON-NLS-1$
    }

    /** Суффикс «(Общие)»/«(Проект)» к подписи, плюс (если задан) подмена фильтра проектом. */
    private static PreferenceTransferElement wrap(PreferenceTransferElement element,
            Supplier<List<String>> projectNamesSupplier, String preferenceNodeQualifier)
    {
        String suffix = isProjectScope(element) ? SUFFIX_PROJECT : SUFFIX_COMMON;
        IConfigurationElement config = element.getConfigurationElement();
        return new PreferenceTransferElement(config)
        {
            @Override
            public String getLabel(Object o)
            {
                return super.getLabel(o) + suffix;
            }

            @Override
            public IPreferenceFilter getFilter()
            {
                if (projectNamesSupplier != null)
                    return new MultiProjectPreferenceFilter(projectNamesSupplier.get(), preferenceNodeQualifier);
                try
                {
                    return super.getFilter();
                }
                catch (org.eclipse.core.runtime.CoreException e)
                {
                    Global.tempLogException(TAG, "getFilter " + getID(), e); //$NON-NLS-1$
                    return null;
                }
            }
        };
    }

    private static boolean isProjectScope(PreferenceTransferElement element)
    {
        IConfigurationElement config = element.getConfigurationElement();
        for (IConfigurationElement mapping : config.getChildren("mapping")) //$NON-NLS-1$
        {
            if ("project".equals(mapping.getAttribute("scope"))) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    /**
     * Фильтр, матчащий ровно {@code /project/<projectName>/<pluginId>} целиком для каждого
     * имени в {@code projectNames} (без списка ключей — {@code null} в значении
     * {@link #getMapping} по семантике {@link IPreferenceFilter#getMapping}). {@code getScopes()}
     * ограничен одним "project" — {@code PreferencesService.internalMatches} вызывает
     * {@code getMapping} только для перечисленных здесь scope, так что другие scope этот
     * фильтр не затронет. На экспорте {@code projectNames} — список из одного текущего
     * проекта; на импорте — все отмеченные «Проекты приёмники» (тогда данные под другими
     * именами должны уже быть в самом файле — см. {@code ComfortPreferencesImportPage}
     * построение временного файла с переименованным путём проекта).
     */
    private static final class MultiProjectPreferenceFilter implements IPreferenceFilter
    {
        private static final String SCOPE_PROJECT = "project"; //$NON-NLS-1$

        private static final String[] SCOPES = {SCOPE_PROJECT};

        private final List<String> projectNames;

        private final String pluginId;

        MultiProjectPreferenceFilter(List<String> projectNames, String pluginId)
        {
            this.projectNames = projectNames == null ? List.of() : projectNames;
            this.pluginId = pluginId;
        }

        @Override
        public String[] getScopes()
        {
            return SCOPES;
        }

        @Override
        public Map<String, PreferenceFilterEntry[]> getMapping(String scope)
        {
            if (!SCOPE_PROJECT.equals(scope) || projectNames.isEmpty())
                return Map.of();
            Map<String, PreferenceFilterEntry[]> mapping = new HashMap<>();
            for (String name : projectNames)
                if (name != null && !name.isBlank())
                    mapping.put(name + '/' + pluginId, null);
            return mapping;
        }
    }
}
