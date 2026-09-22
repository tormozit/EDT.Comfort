package tormozit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IExportedPreferences;
import org.eclipse.core.runtime.preferences.IPreferenceFilter;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.PreferenceFilterEntry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.internal.preferences.PreferenceTransferElement;
import org.eclipse.ui.internal.wizards.preferences.WizardPreferencesImportPage1;

/**
 * Единственная страница {@link ComfortPreferencesImportWizard}. Список категорий —
 * без Java, с суффиксами «(Общие)»/«(Проект)» ({@link ComfortPreferenceTransferFilter}),
 * отсортирован по алфавиту, клик по флажку активирует строку. Поле «Проект из файла» — только
 * просмотр, читает {@link ComfortPreferencesExportPage#SOURCE_PROJECT_KEY}. Список «Проекты
 * приёмники» (с запоминаемыми пометками) определяет, в какие проекты применяются project-scope
 * категории («Комфорт (Проект)», «BSL: форматирование», «Проверки»): если выбран не только (или
 * не) тот же проект, что в файле, перед импортом строится временный файл с продублированными
 * путями {@code /project/<целевой>/...} — иначе фильтр («... (Проект)») просто не найдёт данных
 * под чужим именем проекта, штатный механизм копировать между разными именами не умеет.
 * <p>
 * Флажок «Импортировать всё» сам по себе (штатный служебный фильтр Eclipse) project-scope
 * категории не покрывает — {@link #importProjectScopeData} компенсирует это отдельным
 * {@code IPreferencesService.applyPreferences(...)} для «Проекты приёмники», симметрично тому,
 * как {@link ComfortPreferencesExportPage} докладывает их при "Экспортировать всё".
 * <p>
 * Флажок «Импортировать всё», а также категория «Параметры для метаданных» (ссылается на имена
 * объектов конфигурации — {@code objectSets}, {@code recentPlaces}, ...) доступны только если
 * {@link ComfortPreferencesExportPage#SOURCE_WORKSPACE_KEY} выбранного файла совпадает с
 * текущей рабочей областью — иначе это чужие объекты. Неизвестное происхождение (ключ
 * отсутствует — файл без него или ещё не выбран) считаем небезопасным так же, как чужую рабочую
 * область. Функциональная защита — {@code getFilters()}, независимо от состояния флажков.
 */
public class ComfortPreferencesImportPage extends WizardPreferencesImportPage1
{
    private Label sourceProjectValue;

    private Button transferAllButtonRef;

    private Table targetProjectsTable;

    /** Обновляется в {@link #updateTransferAllAvailability}; читает {@link #vetoCheckingItem}. */
    private volatile boolean foreignWorkspace = true;

    /** Путь временного файла с продублированными путями проектов — см. класс-javadoc. Только на время {@link #transfer}. */
    private String tempRewrittenFilePath;

    /**
     * Отложенное восстановление флажка «Импортировать всё» ({@link ComfortSettings#PREF_PREFERENCES_IMPORT_TRANSFER_ALL}).
     * {@code createControl()} вызывает {@link #refreshSourceProject()} сразу же, пока
     * {@code destinationNameField} ещё пустой (штатное авто-заполнение последнего пути — из
     * {@code setVisible()}, позже) — немедленный {@code setSelection(true)} в этот момент тут же
     * стирался бы {@link #updateTransferAllAvailability} как «неизвестное происхождение» (см.
     * класс-javadoc). Ставим здесь, снимаем и применяем в {@link #updateTransferAllAvailability}
     * при первом вызове с непустым {@code destinationNameField} — не раньше.
     */
    private boolean pendingTransferAllRestore;

    /** {@code parent} из {@link #createControl}, нужен внутри {@link #restoreWidgetValues} — см. его javadoc. */
    private Composite pageParentRef;

    /**
     * По байткоду {@code WizardPreferencesPage.createControl()} штатный {@code restoreWidgetValues()}
     * вызывается ПЕРВЫМ, ещё внутри {@code super.createControl(...)} — раньше нашего восстановления в
     * {@link #createControl}. Он читает {@code IDialogSettings} по ключу {@code EXPORT_ALL_PREFERENCES_ID}
     * (общий для экспорта/импорта) и, если ключ ещё НИ РАЗУ не сохранён в этой секции (а у нашего
     * мастера он не сохранён никогда — это чужое хранилище, мы своё через {@link ComfortSettings}
     * используем), принудительно ставит флажок «Импортировать всё» в {@code true} — вот почему он
     * взводится заново при каждом открытии диалога независимо от того, что сохранил пользователь. Наш
     * код в {@link #createControl} раньше только СТАВИЛ {@code true}, когда нужно, но никогда не снимал
     * явно — этого было недостаточно, чтобы перебить штатный {@code true} по умолчанию.
     * <p>
     * {@code super.restoreWidgetValues()} не трогаем целиком — он же восстанавливает историю поля
     * «Из файла» (список недавних путей) и последний выбранный путь, это нужно. Просто сразу следом
     * принудительно ставим флажок в значение из {@link ComfortSettings} — оно становится
     * единственным источником истины для этого флажка.
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
                .getBoolean(ComfortSettings.PREF_PREFERENCES_IMPORT_TRANSFER_ALL);
        allButton.setSelection(remembered);
    }

    @Override
    protected PreferenceTransferElement[] getTransfers()
    {
        PreferenceTransferElement[] filtered = ComfortPreferenceTransferFilter.filterAndLabel(super.getTransfers());
        Supplier<List<String>> targets = this::checkedTargetProjects;
        filtered = ComfortPreferenceTransferFilter.appendProjectCategory(filtered,
                ComfortPreferenceTransferFilter.COMFORT_PROJECT_TRANSFER_ID, Activator.PLUGIN_ID, targets);
        filtered = ComfortPreferenceTransferFilter.appendProjectCategory(filtered,
                ComfortPreferenceTransferFilter.BSL_PROJECT_TRANSFER_ID, "com._1c.g5.v8.dt.bsl.Bsl", targets); //$NON-NLS-1$
        filtered = ComfortPreferenceTransferFilter.appendProjectCategory(filtered,
                ComfortPreferenceTransferFilter.CHECKS_PROJECT_TRANSFER_ID,
                ComfortPreferenceTransferFilter.CHECKS_PREFERENCE_QUALIFIER, targets);
        return ComfortPreferenceTransferFilter.sortByLabel(filtered);
    }

    /**
     * Функциональная блокировка «Параметры для метаданных» при чужой рабочей области —
     * независимо от того, что показывает флажок дерева ({@link #vetoCheckingItem} — только
     * визуальная, кнопки «Выбрать всё»/«Отменить всё» её не видят).
     */
    @Override
    protected IPreferenceFilter[] getFilters()
    {
        IPreferenceFilter[] filters = super.getFilters();
        return foreignWorkspace ? ComfortPreferenceTransferFilter.stripMetadataReferences(filters) : filters;
    }

    /**
     * {@code getDestinationValue()} штатного {@code transfer()} (по байткоду) — единственная
     * точка, откуда берётся путь к читаемому файлу. Пока идёт {@link #transfer}, подменяем его
     * на путь временного переписанного файла ({@link #tempRewrittenFilePath}), если он нужен —
     * см. класс-javadoc.
     */
    @Override
    protected String getDestinationValue()
    {
        return tempRewrittenFilePath != null ? tempRewrittenFilePath : super.getDestinationValue();
    }

    /**
     * Файловая часть категории «Проверки» ({@code .settings/*.cset}) — штатный
     * {@code IPreferencesService} копировать файлы не умеет, поэтому после обычного
     * preferences-переноса восстанавливаем их отдельно для каждого целевого проекта
     * ({@link ComfortCheckProfileTransfer}), если категория отмечена.
     * <p>
     * Если отмеченные «Проекты приёмники» не совпадают ровно с проектом из файла — строим
     * временный файл с продублированными путями {@code /project/<целевой>/...} и на время
     * {@code super.transfer(...)} подменяем им {@link #getDestinationValue()}.
     */
    @Override
    protected boolean transfer(IPreferenceFilter[] filters)
    {
        // Запоминаем независимо от успеха transfer() — пользователь настроил именно это.
        ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_IMPORT_CHECKED_CATEGORIES,
                String.join(",", ComfortPreferenceTransferFilter.getCheckedCategoryIds(transfersTree))); //$NON-NLS-1$
        boolean transferAllToSave = transferAllButtonRef != null && transferAllButtonRef.getSelection();
        ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_IMPORT_TRANSFER_ALL, transferAllToSave);

        boolean importAll = ComfortPreferenceTransferFilter.isTransferAllFilter(filters);
        List<String> targets = checkedTargetProjects();
        if (!targets.isEmpty())
            saveRememberedTargetProjects(targets);

        String sourceProject = sourceProjectValue == null || sourceProjectValue.isDisposed()
                ? null : sourceProjectValue.getText();
        boolean sourceKnown = sourceProject != null && !sourceProject.isBlank() && !"-".equals(sourceProject); //$NON-NLS-1$
        boolean needsRewrite = sourceKnown && !targets.isEmpty()
                && !(targets.size() == 1 && targets.get(0).equals(sourceProject));

        if (needsRewrite)
            tempRewrittenFilePath = buildRewrittenFile(super.getDestinationValue(), sourceProject, targets);

        try
        {
            boolean ok = super.transfer(filters);
            if (ok)
            {
                // При "Импортировать всё" filters — служебный фильтр-«без ограничений»
                // (см. ComfortPreferenceTransferFilter.isTransferAllFilter), в его mapping нет
                // project-scope категорий — projectNamesForQualifier() тут всегда вернёт пусто,
                // поэтому в этом случае берём "Проекты приёмники" напрямую.
                List<String> checksTargets = importAll ? targets
                        : ComfortPreferenceTransferFilter.projectNamesForQualifier(filters,
                                ComfortPreferenceTransferFilter.CHECKS_PREFERENCE_QUALIFIER);
                if (!checksTargets.isEmpty())
                {
                    // .cset-файлы не привязаны к пути проекта внутри .par (см. ComfortCheckProfileTransfer) —
                    // читаем из ОРИГИНАЛЬНОГО файла, не из переписанного, и восстанавливаем в каждый целевой.
                    Properties props = loadProperties(super.getDestinationValue());
                    if (props != null)
                        for (String target : checksTargets)
                            ComfortCheckProfileTransfer.extract(props, target);
                }
                if (importAll && !targets.isEmpty())
                    importProjectScopeData(targets);
            }
            return ok;
        }
        finally
        {
            if (tempRewrittenFilePath != null)
            {
                new File(tempRewrittenFilePath).delete();
                tempRewrittenFilePath = null;
            }
        }
    }

    /**
     * Компенсация штатного ограничения Eclipse: служебный фильтр-«без ограничений» флажка
     * «Импортировать всё» ({@code getScopes()={"instance","configuration"}}, см.
     * {@link ComfortPreferenceTransferFilter#isTransferAllFilter}) никогда не покрывает
     * project-scope — «Комфорт (Проект)», «BSL: форматирование», «Проверки» (сами preferences,
     * не {@code .cset}-файлы — те переносит {@link ComfortCheckProfileTransfer} отдельно, см.
     * вызывающий {@link #transfer}) для {@code targets} остались бы не импортированы, хотя
     * {@link ComfortPreferencesExportPage} при "Экспортировать всё" их в файл кладёт (см.
     * {@code ComfortPreferencesExportPage.postProcessExportedFile}). Читаем тот же файл, что
     * использовал {@code super.transfer(...)} ({@link #getDestinationValue()} — переписанный, с
     * продублированными путями под все {@code targets}, если рабочая область файла требовала
     * переименования проекта, см. {@link #buildRewrittenFile}), и импортируем project-scope узлы
     * этих трёх qualifier'ов для всех {@code targets} напрямую через {@link IPreferencesService}.
     */
    private void importProjectScopeData(List<String> targets)
    {
        File file = new File(getDestinationValue());
        if (!file.isFile())
            return;
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
                for (String target : targets)
                {
                    mapping.put(target + '/' + Activator.PLUGIN_ID, null);
                    mapping.put(target + "/com._1c.g5.v8.dt.bsl.Bsl", null); //$NON-NLS-1$
                    mapping.put(target + '/' + ComfortPreferenceTransferFilter.CHECKS_PREFERENCE_QUALIFIER, null);
                }
                return mapping;
            }
        };
        try (FileInputStream in = new FileInputStream(file))
        {
            IPreferencesService service = Platform.getPreferencesService();
            IExportedPreferences exported = service.readPreferences(in);
            service.applyPreferences(exported, new IPreferenceFilter[] {filter});
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Копия {@code originalPath} с добавленными ключами: для каждого ключа
     * {@code /project/<sourceProject>/...} добавляется по копии на каждый {@code target} из
     * {@code targets} (кроме самого {@code sourceProject}, он уже есть как есть). Так фильтр
     * project-scope категории (матчит {@code <targetProject>/<qualifier>}) находит данные и под
     * именем целевого проекта, которого в исходном файле не было.
     */
    private String buildRewrittenFile(String originalPath, String sourceProject, List<String> targets)
    {
        Properties original = loadProperties(originalPath);
        if (original == null)
            return null;
        Properties rewritten = new Properties();
        String sourcePrefix = "/project/" + sourceProject + '/'; //$NON-NLS-1$
        for (String key : original.stringPropertyNames())
        {
            String value = original.getProperty(key);
            rewritten.setProperty(key, value);
            if (!key.startsWith(sourcePrefix))
                continue;
            String suffix = key.substring(sourcePrefix.length());
            for (String target : targets)
            {
                if (target.equals(sourceProject))
                    continue;
                rewritten.setProperty("/project/" + target + '/' + suffix, value); //$NON-NLS-1$
            }
        }
        try
        {
            File temp = File.createTempFile("comfort-import-", ".par"); //$NON-NLS-1$ //$NON-NLS-2$
            temp.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(temp))
            {
                rewritten.store(out, "Eclipse Preferences (rewritten for target projects)"); //$NON-NLS-1$
            }
            return temp.getAbsolutePath();
        }
        catch (IOException ignored)
        {
            return null;
        }
    }

    private static Properties loadProperties(String path)
    {
        if (path == null || path.isBlank())
            return null;
        File file = new File(path);
        if (!file.isFile())
            return null;
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file))
        {
            props.load(in);
        }
        catch (IOException | IllegalArgumentException ignored)
        {
            return null;
        }
        return props;
    }

    @Override
    public void createControl(Composite parent)
    {
        pageParentRef = parent; // нужен раньше super.createControl() — см. javadoc restoreWidgetValues()
        super.createControl(parent);
        Composite pageComposite = (Composite)getControl();
        createSourceProjectField(pageComposite);
        sourceProjectRow.moveAbove(null); // "самом верху" — буквально первая строка страницы
        restructureWithSash(pageComposite);
        ComfortPreferenceTransferFilter.enforceDescriptionMinimumHeight(descText);
        ComfortPreferenceTransferFilter.vetoCheckingItem(transfersTree,
                ComfortPreferenceTransferFilter.METADATA_REFERENCES_TRANSFER_ID, () -> foreignWorkspace);
        ComfortPreferenceTransferFilter.activateRowOnCheck(transfersTree);

        // Запоминаемые пометки категорий и флажка "Импортировать всё" — issue #520,
        // "Запоминать пометки категорий". Сохраняются в transfer(), восстанавливаются здесь;
        // refreshSourceProject() ниже может их же сразу снять, если файл — из чужой рабочей
        // области (это не должно перебивать restore, порядок вызовов здесь важен).
        java.util.Set<String> rememberedCategories = new java.util.HashSet<>(java.util.Arrays.asList(
                ComfortSettings.getInstance().getPreferenceStore()
                        .getString(ComfortSettings.PREF_PREFERENCES_IMPORT_CHECKED_CATEGORIES).split(","))); //$NON-NLS-1$
        ComfortPreferenceTransferFilter.restoreCheckedCategories(transfersTree, rememberedCategories);

        // transferAllButton — private в WizardPreferencesPage, недоступен напрямую; getAllButtonText()
        // (protected, унаследован от WizardPreferencesImportPage1) даёт точный текст для поиска.
        transferAllButtonRef = Global.findControl(pageComposite, Button.class,
                b -> getAllButtonText() != null && getAllButtonText().equals(b.getText()));
        pendingTransferAllRestore = transferAllButtonRef != null && ComfortSettings.getInstance()
                .getPreferenceStore().getBoolean(ComfortSettings.PREF_PREFERENCES_IMPORT_TRANSFER_ALL);

        // По байткоду WizardPreferencesPage.createTransfersList() кнопка "Импортировать всё" —
        // прямой потомок pageComposite, создаётся РАНЬШЕ группы с деревом категорий (та же
        // страница) — то есть штатно она висит отдельной строкой над всем остальным. Переносим
        // её ВНУТРЬ строки со списком "Проекты приёмники" (createTargetProjectsField), сразу под
        // таблицей — а не просто ниже всего sashForm целиком (там ещё и дерево категорий, это
        // увело бы флажок далеко вниз, вообще не рядом со списком, о котором речь).
        if (transferAllButtonRef != null && !transferAllButtonRef.isDisposed()
                && targetProjectsRowRef != null && !targetProjectsRowRef.isDisposed())
        {
            transferAllButtonRef.setParent(targetProjectsRowRef);
            GridData transferAllData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            transferAllButtonRef.setLayoutData(transferAllData);
            transferAllButtonRef.moveBelow(null); // последним в row — сразу под таблицей проектов
        }

        // destinationNameField — protected-поле WizardPreferencesPage, доступное
        // подклассу напрямую; штатный код обновляет его и по вводу текста,
        // и по выбору файла через "Обзор...".
        destinationNameField.addListener(SWT.Modify, event -> refreshSourceProject());
        refreshSourceProject();

        // restoreCheckedCategories()/restoreWidgetValues() ставят пометки/флажок программно
        // (TreeItem.setChecked(), Button.setSelection()) — это НЕ поднимает SWT.Selection, поэтому
        // штатный updateEnablement()/setPageComplete() (который вызывается только из СОБСТВЕННЫХ
        // слушателей стока на реальный клик) ни разу не отрабатывает — "Готово" остаётся в
        // начальном состоянии (недоступна) несмотря на видимые пометки. Пересчитываем явно —
        // setPageComplete() здесь public no-arg метод WizardPreferencesPage (не JFace-перегрузка
        // с boolean), сам вызывает determinePageCompletion().
        setPageComplete();

        // Финальный layout — один раз, после того как вся страница построена (эталон:
        // ConfigSearchResultsHook.installMatchTableSplitPane делает pageContainer.layout(true,true)
        // самым последним действием, а не сразу после setWeights внутри restructureWithSash).
        pageComposite.layout(true, true);
    }

    private Composite sourceProjectRow;

    private void createSourceProjectField(Composite pageComposite)
    {
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
        label.setText("Проект из файла:"); //$NON-NLS-1$

        sourceProjectValue = new Label(row, SWT.NONE);
        sourceProjectValue.setText("-"); //$NON-NLS-1$
        sourceProjectValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        sourceProjectRow = row;
    }

    /**
     * Оборачивает список «Проекты приёмники» и дерево категорий (найденное как прямой потомок
     * {@code pageComposite}, содержащий {@link #transfersTree} — та же группа, где «Выбрать
     * всё»/«Отменить всё») в {@link SashForm} с перетаскиваемым разделителем; положение
     * запоминается в {@link ComfortSettings#PREF_PREFERENCES_IMPORT_SASH_WEIGHT}. Панель
     * описания (штатное {@code descText}) в разделитель не входит — её минимальную высоту
     * дополнительно закрепляет {@link #enforceDescriptionMinimumHeight}.
     */
    private void restructureWithSash(Composite pageComposite)
    {
        Control categoriesSection = findDirectChild(pageComposite, transfersTree);
        if (categoriesSection == null)
        {
            createTargetProjectsField(pageComposite);
            return;
        }
        // Не проверено эмпирически, что этот контейнер включает и кнопки "Выбрать всё"/
        // "Отменить всё" (не только дерево) — если нет, они останутся вне разделителя.

        SashForm sashForm = new SashForm(pageComposite, SWT.VERTICAL);
        GridData sashData = new GridData(SWT.FILL, SWT.FILL, true, true);
        if (pageComposite.getLayout() instanceof GridLayout pageLayout)
            sashData.horizontalSpan = pageLayout.numColumns;
        // По байткоду WizardDialog.calculatePageSizeDelta/createDialogArea: размер диалога
        // считается ОДИН РАЗ через pageComposite.computeSize(DEFAULT, DEFAULT) — а это
        // "естественная" (несжатая) высота содержимого SashForm, БЕЗ учёта веса разделителя
        // (веса работают только когда высота уже задана, не при вычислении preferred-размера).
        // Без явного heightHint здесь computeSize суммирует полные высоты обеих панелей
        // (таблица проектов + дерево категорий целиком), диалог получает завышенный pageHeight —
        // а после того как SashForm реально сжимает панели по весу, остаётся пустое место снизу
        // ровно на эту разницу. Фиксируем разумную высоту сразу, чтобы computeSize не завышал.
        sashData.heightHint = 300;
        sashForm.setLayoutData(sashData);
        sashForm.moveAbove(categoriesSection);

        createTargetProjectsField(sashForm);
        categoriesSection.setParent(sashForm);
        categoriesSection.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // SashForm.setWeights(int...) по декомпиляции требует ТОЧНОГО совпадения длины массива с
        // числом РЕАЛЬНО видимых не-Sash потомков (getControls(false)) — иначе SWT.error
        // (ERROR_INVALID_ARGUMENT), и это валится прямо в createControl(), диалог падает ещё до
        // показа (runtime-лог: "Unable... SashForm.setWeights" на каждом открытии). Раньше здесь
        // было жёстко "2" — считаем реально, вместо повторной догадки.
        int visibleNonSash = 0;
        for (Control c : sashForm.getChildren())
            if (c.getVisible())
                visibleNonSash++;

        // ВАЖНО: SashForm.getWeights()/setWeights() — НЕ проценты 0-100, а внутренняя шкала SWT
        // (по декомпиляции SashFormLayout.computeWeights: вес контрола без явного SashFormData —
        // 200 по умолчанию, у остальных — (childSize*1000L)>>16, т.е. произвольные положительные
        // числа, необязательно суммой 100). Раньше здесь сохранялось getWeights()[0] НАПРЯМУЮ как
        // "процент" — первый же реальный вызов DisposeListener (закрытие окна) сохранил вес в этой
        // внутренней шкале (обычно намного больше 100), и следующее открытие считало его %,
        // получало 100-weight ОТРИЦАТЕЛЬНЫМ — SWT.error(ERROR_INVALID_ARGUMENT) в setWeights,
        // диалог падал ещё в createControl(). Храним/подаём именно проценты, с чтением через
        // нормализацию и защитным clamp на входе (на случай уже испорченного старого значения).
        int weight = ComfortSettings.getInstance().getPreferenceStore()
                .getInt(ComfortSettings.PREF_PREFERENCES_IMPORT_SASH_WEIGHT);
        if (weight < 1 || weight > 99)
            weight = ComfortSettings.DEFAULT_PREFERENCES_IMPORT_SASH_WEIGHT;
        if (visibleNonSash == 2)
            sashForm.setWeights(weight, 100 - weight);

        // Искать внутренний Sash-контрол и вешать на него слушатель — ненадёжно (создаётся лениво
        // во время layout(), см. предыдущие попытки). Рабочий паттерн уже есть в этом же плагине —
        // ConfigSearchResultsHook.installMatchTableSplitPane: вес читается через getWeights() в
        // DisposeListener самого SashForm, без поиска Sash вообще (эталон).
        sashForm.addDisposeListener(e ->
        {
            int[] weights = sashForm.getWeights();
            if (weights.length == 2 && weights[0] + weights[1] > 0)
            {
                // Нормализация во внутреннюю-шкалу-независимый процент — см. комментарий выше.
                int pct = Math.max(1, Math.min(99, weights[0] * 100 / (weights[0] + weights[1])));
                ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_IMPORT_SASH_WEIGHT, pct);
            }
        });
        this.sashFormRef = sashForm;
        this.categoriesSectionRef = categoriesSection;
    }

    /** См. {@link #restructureWithSash} — итоговый {@code layout()} делается один раз, в конце {@link #createControl}. */
    private SashForm sashFormRef;

    private Control categoriesSectionRef;

    /**
     * Строка со списком «Проекты приёмники» — устанавливается в {@link #createTargetProjectsField}.
     * {@link #createControl} переносит в неё (через {@code setParent}) штатную кнопку
     * «Импортировать всё», чтобы та оказалась сразу под таблицей проектов, а не отдельной строкой
     * над всем остальным (штатное место — прямой потомок {@code pageComposite} перед деревом
     * категорий, см. байткод {@code WizardPreferencesPage.createTransfersList()}).
     */
    private Composite targetProjectsRowRef;

    /** Прямой потомок {@code root}, в поддереве которого лежит {@code descendant}. */
    private static Control findDirectChild(Composite root, Control descendant)
    {
        Control c = descendant;
        while (c != null && c.getParent() != root)
            c = c.getParent();
        return c;
    }

    /**
     * @param pageComposite куда класть строку с подписью и таблицу — либо страница целиком
     *     (без разделителя, см. {@link #restructureWithSash}), либо верхняя часть {@link SashForm}
     */
    private void createTargetProjectsField(Composite pageComposite)
    {
        Composite row = new Composite(pageComposite, SWT.NONE);
        GridLayout rowLayout = new GridLayout(1, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        row.setLayout(rowLayout);
        if (pageComposite.getLayout() instanceof GridLayout pageLayout)
        {
            GridData rowData = new GridData(SWT.FILL, SWT.FILL, true, true);
            rowData.horizontalSpan = pageLayout.numColumns;
            row.setLayoutData(rowData);
        }

        Label label = new Label(row, SWT.NONE);
        label.setText("Проекты приёмники:"); //$NON-NLS-1$

        targetProjectsTable = new Table(row, SWT.CHECK | SWT.BORDER);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = targetProjectsTable.getItemHeight() * 3;
        targetProjectsTable.setLayoutData(tableData);

        Set<String> remembered = loadRememberedTargetProjects();
        for (String name : openProjectNames())
        {
            TableItem item = new TableItem(targetProjectsTable, SWT.NONE);
            item.setText(name);
            item.setChecked(remembered.contains(name));
        }

        targetProjectsRowRef = row;
    }

    private static List<String> openProjectNames()
    {
        List<String> names = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen())
                names.add(project.getName());
        return names;
    }

    private List<String> checkedTargetProjects()
    {
        if (targetProjectsTable == null || targetProjectsTable.isDisposed())
            return List.of();
        List<String> result = new ArrayList<>();
        for (TableItem item : targetProjectsTable.getItems())
            if (item.getChecked())
                result.add(item.getText());
        return result;
    }

    private static Set<String> loadRememberedTargetProjects()
    {
        String raw = ComfortSettings.getInstance().getPreferenceStore()
                .getString(ComfortSettings.PREF_PREFERENCES_IMPORT_TARGET_PROJECTS);
        if (raw == null || raw.isBlank())
            return Set.of();
        return new HashSet<>(Arrays.asList(raw.split(","))); //$NON-NLS-1$
    }

    private static void saveRememberedTargetProjects(List<String> targets)
    {
        ComfortSettings.setAndSave(ComfortSettings.PREF_PREFERENCES_IMPORT_TARGET_PROJECTS, String.join(",", targets)); //$NON-NLS-1$
    }

    /** Если ни один проект ещё не отмечен, а проект из файла есть среди открытых — отмечает его по умолчанию. */
    private void autoCheckSourceProjectIfNoneChecked(String sourceProject)
    {
        if (targetProjectsTable == null || targetProjectsTable.isDisposed() || sourceProject == null)
            return;
        for (TableItem item : targetProjectsTable.getItems())
            if (item.getChecked())
                return;
        for (TableItem item : targetProjectsTable.getItems())
            if (sourceProject.equals(item.getText()))
            {
                item.setChecked(true);
                return;
            }
    }

    private void refreshSourceProject()
    {
        Properties props = loadProperties(destinationNameField.getText());
        String sourceProject = props == null ? null : props.getProperty(ComfortPreferencesExportPage.SOURCE_PROJECT_KEY);
        if (sourceProjectValue != null && !sourceProjectValue.isDisposed())
            sourceProjectValue.setText(sourceProject == null ? "-" : sourceProject); //$NON-NLS-1$
        autoCheckSourceProjectIfNoneChecked(sourceProject);
        updateTransferAllAvailability(props);
    }

    /**
     * Пересчитывает {@link #foreignWorkspace} по {@link ComfortPreferencesExportPage#SOURCE_WORKSPACE_KEY}
     * файла; разрешает «Импортировать всё» только при совпадении с текущей рабочей областью
     * (иначе выключает флажок и снимает пометку, если она уже стояла — могли поставить до смены
     * пути к файлу). То же для «Параметры для метаданных»: функционально её и так блокирует
     * {@link #getFilters()}, но если пометка уже стояла (поставили до смены файла на «чужой»),
     * снимаем и её — {@link ComfortPreferenceTransferFilter#uncheckItem}.
     */
    private void updateTransferAllAvailability(Properties props)
    {
        String sourceWorkspace = props == null ? null : props.getProperty(ComfortPreferencesExportPage.SOURCE_WORKSPACE_KEY);
        String currentWorkspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toString();
        boolean sameWorkspace = currentWorkspace.equals(sourceWorkspace);
        foreignWorkspace = !sameWorkspace;
        if (transferAllButtonRef != null && !transferAllButtonRef.isDisposed())
        {
            transferAllButtonRef.setEnabled(sameWorkspace);
            if (!sameWorkspace && transferAllButtonRef.getSelection())
                transferAllButtonRef.setSelection(false);
        }
        if (!sameWorkspace)
            ComfortPreferenceTransferFilter.uncheckItem(transfersTree,
                    ComfortPreferenceTransferFilter.METADATA_REFERENCES_TRANSFER_ID);

        // Путь ещё не заполнен (это самый первый вызов из createControl(), до штатного
        // авто-восстановления последнего файла в setVisible()) — ничего не решено, откладываем
        // потребление pendingTransferAllRestore до следующего вызова с реальным путём.
        boolean destinationKnown = destinationNameField != null
                && destinationNameField.getText() != null && !destinationNameField.getText().isBlank();
        if (pendingTransferAllRestore && destinationKnown)
        {
            pendingTransferAllRestore = false;
            if (sameWorkspace && transferAllButtonRef != null && !transferAllButtonRef.isDisposed())
            {
                transferAllButtonRef.setSelection(true);
                // setSelection() не поднимает SWT.Selection — штатный обработчик не сработает без него.
                transferAllButtonRef.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            }
        }
    }
}
