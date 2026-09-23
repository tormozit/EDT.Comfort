package tormozit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferenceFilter;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.PreferenceFilterEntry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.preferences.PreferenceTransferElement;
import org.eclipse.ui.internal.wizards.preferences.WizardPreferencesExportPage1;

/**
 * Единственная страница {@link ComfortPreferencesExportWizard}. Расширение файла
 * выгрузки по умолчанию — ".par", список категорий — без Java, с суффиксами
 * «(Общие)»/«(Проект)» ({@link ComfortPreferenceTransferFilter}), отсортирован по алфавиту,
 * из записанного файла вычищены ключи ".history.". Отметки категорий и флажок «Экспортировать
 * всё» запоминаются между открытиями окна. Поле «Проект» (запоминаемое, по умолчанию —
 * активный проект): (1) задаёт, для какого проекта реально экспортируются project-scope
 * категории «Комфорт (Проект)», «BSL: форматирование», «Проверки» — их фильтры подменяются на
 * выбранный проект (см. {@link ComfortPreferenceTransferFilter#COMFORT_PROJECT_TRANSFER_ID});
 * (2) пишет имя проекта служебным ключом {@link #SOURCE_PROJECT_KEY} для показа на странице
 * импорта («Проект из файла»). При «Экспортировать всё» (штатное ограничение Eclipse — эта
 * галочка никогда не покрывает project-scope) эти же project-scope категории для выбранного
 * проекта добавляются отдельно, напрямую ({@link #exportProjectScopeData}) — иначе «Экспортировать
 * всё» реально выгружало бы не всё.
 */
public class ComfortPreferencesExportPage extends WizardPreferencesExportPage1
{
    /** Ключ в выгруженном .par с именем исходного проекта. Читается импортёром. */
    static final String SOURCE_PROJECT_KEY = "tormozit.comfort.sourceProject"; //$NON-NLS-1$

    /**
     * Ключ в выгруженном .par с расположением исходной рабочей области
     * ({@code ResourcesPlugin.getWorkspace().getRoot().getLocation()}). Читается импортёром,
     * чтобы заблокировать «Импортировать всё» при несовпадении с текущей рабочей областью —
     * иначе туда попадёт мусор из {@link #OBJECT_NAME_REFERENCE_KEYS}, привязанный к чужой
     * конфигурации (при «Экспортировать всё» наши исключения не действуют, см. класс-javadoc).
     */
    static final String SOURCE_WORKSPACE_KEY = "tormozit.comfort.sourceWorkspace"; //$NON-NLS-1$

    private Combo projectCombo;

    private Button transferAllButtonRef;

    /** {@code parent} из {@link #createControl}, нужен внутри {@link #restoreWidgetValues}. */
    private Composite pageParentRef;

    /**
     * См. подробный javadoc {@code ComfortPreferencesImportPage.restoreWidgetValues()} — тот же
     * баг общего {@code WizardPreferencesPage}: штатный {@code restoreWidgetValues()} (вызывается
     * внутри {@code super.createControl()}, раньше нашего кода ниже) сам ставит флажок
     * «Экспортировать всё» в {@code true}, если {@code IDialogSettings} ещё не сохранял его для
     * этого мастера — а у нас он не сохранён никогда, своё хранилище в {@link ComfortSettings}.
     * Прежний код ниже только СТАВИЛ {@code true}, когда нужно, но никогда не снимал явно — этого
     * недостаточно, чтобы перебить штатный {@code true} по умолчанию.
     */
    @Override
    protected void restoreWidgetValues()
    {
        super.restoreWidgetValues();
        if (pageParentRef == null || pageParentRef.isDisposed())
            return;
        Button allButton = Global.findControl(pageParentRef, Button.class,
                b -> getAllButtonText() != null && getAllButtonText().equals(b.getText()));
        if (allButton == null)
            return;
        boolean remembered = ComfortSettings.getInstance().getPreferenceStore()
                .getBoolean(ComfortSettings.PREF_PREFERENCES_EXPORT_TRANSFER_ALL);
        allButton.setSelection(remembered);
    }

    @Override
    protected String getOutputSuffix()
    {
        return "par"; //$NON-NLS-1$
    }

    @Override
    protected PreferenceTransferElement[] getTransfers()
    {
        PreferenceTransferElement[] filtered = ComfortPreferenceTransferFilter.filterAndLabel(super.getTransfers());
        java.util.function.Supplier<List<String>> project = () -> projectCombo == null || projectCombo.isDisposed()
                || projectCombo.getText().isBlank() ? List.of() : List.of(projectCombo.getText());
        filtered = ComfortPreferenceTransferFilter.appendProjectCategory(filtered,
                ComfortPreferenceTransferFilter.COMFORT_PROJECT_TRANSFER_ID, Activator.PLUGIN_ID, project);
        filtered = ComfortPreferenceTransferFilter.appendProjectCategory(filtered,
                ComfortPreferenceTransferFilter.BSL_PROJECT_TRANSFER_ID,
                ComfortPreferenceTransferFilter.BSL_PREFERENCE_QUALIFIER, project);
        filtered = ComfortPreferenceTransferFilter.appendProjectCategory(filtered,
                ComfortPreferenceTransferFilter.CHECKS_PROJECT_TRANSFER_ID,
                ComfortPreferenceTransferFilter.CHECKS_PREFERENCE_QUALIFIER, project);
        return ComfortPreferenceTransferFilter.sortByLabel(filtered);
    }

    @Override
    public void createControl(Composite parent)
    {
        pageParentRef = parent; // нужен раньше super.createControl() — см. javadoc restoreWidgetValues()
        super.createControl(parent);
        Composite pageComposite = (Composite)getControl();
        createProjectField(pageComposite);
        ComfortPreferenceTransferFilter.activateRowOnCheck(transfersTree);
        ComfortPreferenceTransferFilter.enforceDescriptionMinimumHeight(descText);

        // Запоминаемые пометки категорий и флажка "Экспортировать всё" — issue #520,
        // "Запоминать пометки категорий". Сохраняются в transfer(), восстанавливаются здесь.
        java.util.Set<String> rememberedCategories = new java.util.HashSet<>(java.util.Arrays.asList(
                ComfortSettings.getInstance().getPreferenceStore()
                        .getString(ComfortSettings.PREF_PREFERENCES_EXPORT_CHECKED_CATEGORIES).split(","))); //$NON-NLS-1$
        ComfortPreferenceTransferFilter.restoreCheckedCategories(transfersTree, rememberedCategories);

        // getAllButtonText() — protected, унаследован от WizardPreferencesExportPage1; даёт
        // точный текст флажка "Экспортировать всё" для поиска (сам transferAllButton приватный).
        transferAllButtonRef = Global.findControl(pageComposite, Button.class,
                b -> getAllButtonText() != null && getAllButtonText().equals(b.getText()));
        if (transferAllButtonRef != null && ComfortSettings.getInstance().getPreferenceStore()
                .getBoolean(ComfortSettings.PREF_PREFERENCES_EXPORT_TRANSFER_ALL))
        {
            transferAllButtonRef.setSelection(true);
            // setSelection() не поднимает SWT.Selection — штатный обработчик (серит список
            // категорий, пока отмечено "Экспортировать всё") не сработает без него.
            transferAllButtonRef.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        }

        // restoreCheckedCategories() ставит пометки программно (TreeItem.setChecked()) — это НЕ
        // поднимает SWT.Selection, поэтому штатный updateEnablement()/setPageComplete() (только из
        // СОБСТВЕННЫХ слушателей стока на реальный клик) не отрабатывает — см. подробный комментарий
        // в ComfortPreferencesImportPage.createControl() (тот же баг, тот же WizardPreferencesPage).
        setPageComplete();
    }

    private void createProjectField(Composite pageComposite)
    {
        List<String> projectNames = openProjectNames();

        Composite row = new Composite(pageComposite, SWT.NONE);
        GridLayout rowLayout = new GridLayout(2, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        row.setLayout(rowLayout);
        GridData rowData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        if (pageComposite.getLayout() instanceof GridLayout pageLayout)
            rowData.horizontalSpan = pageLayout.numColumns;
        row.setLayoutData(rowData);

        Label label = new Label(row, SWT.NONE);
        label.setText("Проект:"); //$NON-NLS-1$

        projectCombo = new Combo(row, SWT.READ_ONLY | SWT.DROP_DOWN);
        projectCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectCombo.setItems(projectNames.toArray(new String[0]));
        selectDefaultProject(projectNames);

        Control[] siblings = pageComposite.getChildren();
        if (siblings.length > 1)
            row.moveAbove(siblings[0]);
        pageComposite.layout(true, true);
    }

    private void selectDefaultProject(List<String> projectNames)
    {
        if (projectNames.isEmpty())
            return;
        String remembered = ComfortSettings.getInstance().getPreferenceStore()
                .getString(ComfortSettings.PREF_PREFERENCES_EXPORT_LAST_PROJECT);
        String toSelect = projectNames.contains(remembered) ? remembered : activeProjectName(projectNames);
        if (toSelect != null)
            projectCombo.setText(toSelect);
        else
            projectCombo.select(0);
    }

    private static String activeProjectName(List<String> projectNames)
    {
        IWorkbenchPage page = activePage();
        IProject active = page == null ? null : ActiveProjectTracker.peek(page);
        if (active != null && projectNames.contains(active.getName()))
            return active.getName();
        return null;
    }

    private static IWorkbenchPage activePage()
    {
        if (PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null)
            return null;
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    private static List<String> openProjectNames()
    {
        List<String> names = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen())
                names.add(project.getName());
        return names;
    }

    @Override
    protected boolean transfer(IPreferenceFilter[] filters)
    {
        // Запоминаем независимо от успеха transfer() — пользователь настроил именно это.
        ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_EXPORT_CHECKED_CATEGORIES,
                String.join(",", ComfortPreferenceTransferFilter.getCheckedCategoryIds(transfersTree))); //$NON-NLS-1$
        ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_EXPORT_TRANSFER_ALL,
                transferAllButtonRef != null && transferAllButtonRef.getSelection());

        boolean ok = super.transfer(filters);
        if (ok)
        {
            String projectName = projectCombo == null ? null : projectCombo.getText();
            if (projectName != null && !projectName.isBlank())
                ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_EXPORT_LAST_PROJECT, projectName);
            List<String> checksProjects = ComfortPreferenceTransferFilter.projectNamesForQualifier(filters,
                    ComfortPreferenceTransferFilter.CHECKS_PREFERENCE_QUALIFIER);
            String checksProject = checksProjects.isEmpty() ? null : checksProjects.get(0);
            boolean exportAll = ComfortPreferenceTransferFilter.isTransferAllFilter(filters);
            boolean metadataReferencesChecked = ComfortPreferenceTransferFilter.isMetadataReferencesChecked(filters);
            postProcessExportedFile(new File(getDestinationValue()), projectName, checksProject,
                    exportAll, metadataReferencesChecked);
        }
        return ok;
    }

    /**
     * Ключи категории «Параметры для метаданных» ({@code tormozit.prefTransfer.metadataReferences}
     * в plugin.xml) — ссылки на имена объектов КОНКРЕТНОЙ конфигурации (полные имена метаданных,
     * пути модулей, подсистемы). Сама категория — обычная, равноправная с остальными (её и
     * выгружает штатный {@code super.transfer(filters)}, если отмечена); эта же проверка нужна,
     * чтобы вычистить их, когда категория НЕ отмечена, а «Комфорт (Общие)» (весь узел
     * {@code tormozit} без ограничения по ключам) её всё равно затрагивает — иначе выключенный
     * флажок не выключал бы ничего.
     * <p>
     * Свои: {@link ObjectSets}, {@link ObjectSetsAddTargetState}, {@link RecentPlaces},
     * {@link EditorMemoryStore} — позиция каретки в модуле у {@link BslModulePositionMemoryHook},
     * выбор элемента дерева формы у {@link FormEditorHook} — каждый хранит записи одной
     * строкой по своему ключу.
     * <p>
     * Штатный EDT: {@code ProblemView.filters} (декомпилированный
     * {@code com._1c.g5.v8.dt.internal.ui.validation.ProblemFilters}) — помимо severity/типов
     * проверок хранит {@code subsystemsFilterData} (фильтр «по подсистемам» — метаданные
     * конкретной конфигурации) и {@code searchPatterns} (история поиска панели, может содержать
     * введённые имена объектов); хирургически вычистить только эти два поля из чужого
     * XML-memento не стали — весь ключ целиком.
     * <p>
     * {@code comfort.filterBySubsystems.presets.*.xml} ({@link FilterBySubsystemsDialogHook}) —
     * пресеты фильтра «по подсистемам»: подсистемы — объекты конфигурации.
     */
    private static final String[] OBJECT_NAME_REFERENCE_KEYS = {
        "objectSets.data", //$NON-NLS-1$
        "objectSets.addTargetByProject", //$NON-NLS-1$
        "recentPlaces.entries", //$NON-NLS-1$
        "bslModulePosition.entries", //$NON-NLS-1$
        "formItemsSelection.entries", //$NON-NLS-1$
        "com._1c.g5.v8.dt.internal.ui.validation.ProblemView.filters", //$NON-NLS-1$
        "comfort.filterBySubsystems.presets", //$NON-NLS-1$
    };

    /**
     * Ключи, которые чистим не из-за ссылок на объекты конфигурации, а потому что это
     * пересчитываемый кэш — экспортировать его смысла нет никогда, независимо от категорий.
     * {@code comfort.preferenceSearch.index.cache} — {@link PreferenceSearchIndexCache}, кэш
     * индекса поиска по дереву параметров; именно он даёт основной объём файла при
     * "Экспортировать всё" (эта галочка полностью обходит категории и фильтры —
     * штатный {@code WizardPreferencesPage.finish()} по байткоду, при {@code getTransferAll()},
     * зовёт {@code transfer()} с фильтром-«без ограничений» на весь instance-scope, а не с
     * {@code getFilters()} по отмеченным категориям).
     */
    private static final String[] CACHE_KEYS = {
        "comfort.preferenceSearch.index.cache", //$NON-NLS-1$
    };

    /**
     * {@code IPreferencesService.exportPreferences(...)} (вызывается штатным
     * {@code super.transfer(...)}) пишет обычный {@link Properties}-файл с ключами вида
     * {@code /scope/plugin.id/key=value}. Перезаписываем его через тот же формат:
     * <ul>
     * <li>{@code exportAll} — при «Экспортировать всё» это уже не выборочный экспорт по
     * категориям, а осознанное «выгрузить буквально всё» (сама эта галочка, по байткоду,
     * обходит весь наш механизм категорий) — поэтому ничего из перечисленного ниже не
     * применяется, файл не трогаем вообще (построчное вычёркивание рискованно из-за переноса
     * длинных значений в {@link Properties}).</li>
     * <li>{@code metadataReferencesChecked} — если отмечена сама категория «Параметры для
     * метаданных», {@link #OBJECT_NAME_REFERENCE_KEYS} не вычищаем (иначе включённый флажок
     * ничего бы не выгружал); если НЕ отмечена — вычищаем, даже если её ключи попали в файл
     * через другую категорию (например «Комфорт (Общие)», весь узел {@code tormozit}).</li>
     * <li>".history." и {@link #CACHE_KEYS} — вычищаются всегда (вне зависимости от категорий).</li>
     * </ul>
     * {@link #SOURCE_PROJECT_KEY} и {@link #SOURCE_WORKSPACE_KEY} пишутся всегда — вторая
     * нужна на импорте, чтобы отличить «свою» рабочую область от чужой. Файлы {@code .cset}
     * категории «Проверки» — если она отмечена ({@code checksProject != null}).
     */
    private static void postProcessExportedFile(File file, String projectName, String checksProject,
            boolean exportAll, boolean metadataReferencesChecked)
    {
        if (!file.isFile())
            return;
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file))
        {
            props.load(in);
        }
        catch (IOException ignored)
        {
            return;
        }
        List<String> toRemove = new ArrayList<>();
        if (!exportAll)
        {
            for (String key : props.stringPropertyNames())
                if (key.contains(".history.") || isCacheKey(key) //$NON-NLS-1$
                        || (!metadataReferencesChecked && isObjectNameReferenceKey(key)))
                    toRemove.add(key);
            for (String key : toRemove)
                props.remove(key);
        }
        // "Экспортировать всё" по байткоду штатного finish() — единственный элемент filters с
        // getScopes()={"instance","configuration"} — НИКОГДА не покрывает project-scope (Комфорт/
        // BSL/Проверки для конкретного проекта), это ограничение самого Eclipse, не наше. Раз
        // штатный флажок этого не может — добавляем project-scope данные выбранного проекта сами.
        if (exportAll && projectName != null && !projectName.isBlank())
        {
            Properties projectProps = exportProjectScopeData(projectName);
            for (String key : projectProps.stringPropertyNames())
                props.setProperty(key, projectProps.getProperty(key));
            ComfortCheckProfileTransfer.embed(props, projectName);
        }
        if (projectName != null && !projectName.isBlank())
            props.setProperty(SOURCE_PROJECT_KEY, projectName);
        props.setProperty(SOURCE_WORKSPACE_KEY, workspaceLocation());
        if (checksProject != null && !checksProject.isBlank())
            ComfortCheckProfileTransfer.embed(props, checksProject);
        try (FileOutputStream out = new FileOutputStream(file))
        {
            props.store(out, "Eclipse Preferences"); //$NON-NLS-1$
        }
        catch (IOException ignored)
        {
        }
    }

    private static String workspaceLocation()
    {
        return ResourcesPlugin.getWorkspace().getRoot().getLocation().toString();
    }

    /**
     * Выгружает project-scope узлы Комфорта, BSL-форматирования и Проверок для одного
     * {@code projectName} напрямую через {@link IPreferencesService}, в обход механизма
     * категорий — используется только когда штатное «Экспортировать всё» само этого не делает
     * (см. {@link #postProcessExportedFile}).
     */
    private static Properties exportProjectScopeData(String projectName)
    {
        IPreferenceFilter filter = new IPreferenceFilter()
        {
            @Override
            public String[] getScopes()
            {
                return new String[] {"project"}; //$NON-NLS-1$
            }

            @Override
            public Map<String, PreferenceFilterEntry[]> getMapping(String scope)
            {
                if (!"project".equals(scope)) //$NON-NLS-1$
                    return Map.of();
                Map<String, PreferenceFilterEntry[]> mapping = new HashMap<>();
                mapping.put(projectName + '/' + Activator.PLUGIN_ID, null);
                mapping.put(projectName + '/' + ComfortPreferenceTransferFilter.BSL_PREFERENCE_QUALIFIER, null);
                mapping.put(projectName + '/' + ComfortPreferenceTransferFilter.CHECKS_PREFERENCE_QUALIFIER, null);
                return mapping;
            }
        };
        Properties props = new Properties();
        try
        {
            IPreferencesService service = Platform.getPreferencesService();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            service.exportPreferences(service.getRootNode(), new IPreferenceFilter[] {filter}, buffer);
            props.load(new ByteArrayInputStream(buffer.toByteArray()));
        }
        catch (Exception ignored)
        {
        }
        return props;
    }

    private static boolean isObjectNameReferenceKey(String key)
    {
        for (String marker : OBJECT_NAME_REFERENCE_KEYS)
            if (key.contains(marker))
                return true;
        return false;
    }

    private static boolean isCacheKey(String key)
    {
        for (String marker : CACHE_KEYS)
            if (key.contains(marker))
                return true;
        return false;
    }
}
