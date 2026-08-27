package tormozit;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.IWorkbenchPropertyPage;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Страница настроек плагина Comfort:
 * «Параметры → Комфорт» (workspace) и «Свойства проекта → Комфорт» (словарь проекта).
 *
 * <p>Поле «Символы» автооткрытия подсказки скрыто — его значение захардкожено в
 * {@link ContentAssistSettings#CHARSET_VALUE}.
 */
public class ComfortPreferencePage
        extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage, IWorkbenchPropertyPage
{
    private IAdaptable element;

    private Text installedVersionText;
    private Text installedDateText;
    private Text latestVersionText;
    private Text latestDateText;
    private Link installedChangesLink;
    private Link latestChangesLink;
    private Link updateLink;

    private static final String REPLACE_LIST_FILTERS_DOC_URL =
            "https://tormozit.github.io/EDT.Comfort/help#/uluchshenie-spiskov"; //$NON-NLS-1$

    private static final String REPLACE_LIST_FILTERS_TOOLTIP =
            "Текст фильтра будет дробиться на фрагменты пробелами и будет требоваться и подсвечиваться вхождение каждого фрагмента с мягким учетом порядка.\n"
            + "Влияет на навигатор, список баз, быструю схему модуля, диалоги выбора типа и открытия объекта метаданных, поле «Тип» в мастере и диалогах, список автодополнения.\n"
            + "Также включает доработки панели глобального поиска: фильтр с подсветкой над правой таблицей, счётчик вхождений в дереве, "
            + "объединённый показ вхождений всех потомков при выборе ветки-группы (с колонкой «Путь»), открытие первого вхождения по двойному клику на узле.\n"
            + "Для поиска по файлам справа от дерева добавляется таблица с колонками «Путь», «Файл», «Номер строки», «Текст», "
            + "показывающая все совпадения выбранного узла (включая дочерние).\n"
            + "\n"
            + "Изменение настройки применяется сразу для большинства механизмов; доработка поля «Тип» применяется только при следующем старте EDT."; //$NON-NLS-1$

    private static final String MD_EDITOR_VERTICAL_TABS_TOOLTIP =
            "Если вкладок больше 10, их список показывается слева.\n"
            + "При выключенном флажке вкладки остаются снизу."; //$NON-NLS-1$

    private static final String THEME_AWARE_COLOR_TOOLTIP =
            "В поле — цвет текущей темы. В хранилище — всегда вариант для светлой темы.\n"
            + "При сохранении в тёмной теме цвет из поля обратно пересчитывается в вариант для светлой."; //$NON-NLS-1$

    private static final String FILTER_MATCH_COLOR_TOOLTIP =
            "Цвет подсветки найденных фрагментов в списках с улучшенным фильтром.\n"
            + THEME_AWARE_COLOR_TOOLTIP;

    private static final String SERVER_CALL_COLOR_TOOLTIP =
            "Цвет подсветки серверных вызовов в клиентском коде.\n"
            + THEME_AWARE_COLOR_TOOLTIP;

    private static final String SERVER_CALL_CONTEXT_COLOR_TOOLTIP =
            "Цвет подсветки серверных вызовов с контекстом (&НаСервере).\n"
            + THEME_AWARE_COLOR_TOOLTIP;

    private static final String IMPLICIT_VARIABLE_COLOR_TOOLTIP =
            "Цвет имени переменной в месте создания: первое присваивание и переменная цикла Для / Для Каждого.\n"
            + THEME_AWARE_COLOR_TOOLTIP;

    private static final String WORKSPACE_DESCRIPTION =
            "Настройки плагина Комфорт (Tormozit)."; //$NON-NLS-1$

    public ComfortPreferencePage()
    {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench)
    {
        ensurePreferenceStore();
        if (isProjectPreferencePage())
            setDescription("Настройки Комфорт для проекта."); //$NON-NLS-1$
    }

    @Override
    public void setElement(IAdaptable element)
    {
        this.element = element;
        ensurePreferenceStore();
        if (isProjectPreferencePage())
            setDescription("Настройки Комфорт для проекта."); //$NON-NLS-1$
    }

    @Override
    public IAdaptable getElement()
    {
        return element;
    }

    private void ensurePreferenceStore()
    {
        if (getPreferenceStore() != null)
            return;
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        if (settings != null)
            setPreferenceStore(settings.getPreferenceStore());
    }

    private boolean isProjectPreferencePage()
    {
        return getProject() != null;
    }

    private IProject getProject()
    {
        if (element == null)
            return null;
        IProject project = element.getAdapter(IProject.class);
        if (project != null)
            return project;
        IResource resource = element.getAdapter(IResource.class);
        return resource != null ? resource.getProject() : null;
    }

    @Override
    public void createControl(Composite parent)
    {
        ensurePreferenceStore();
        super.createControl(parent);
        if (!isProjectPreferencePage())
        {
            refreshVersionSection();
            ComfortUpdateChecker.checkAsync(true, this::refreshVersionSection);
        }
    }

    /**
     * Для свойств проекта не используем FieldEditorPreferencePage: без {@code addField}
     * родитель с {@code horizontalSpan=2} даёт пустую страницу.
     */
    @Override
    protected Control createContents(Composite parent)
    {
        ensurePreferenceStore();
        if (isProjectPreferencePage())
        {
            if (getDescription() == null || getDescription().isBlank())
                setDescription("Настройки Комфорт для проекта."); //$NON-NLS-1$
            Composite area = new Composite(parent, SWT.NONE);
            area.setLayout(new GridLayout(1, false));
            area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            createProjectDictionarySection(area);
            return area;
        }
        return super.createContents(parent);
    }

    @Override
    protected void createFieldEditors()
    {
        ensurePreferenceStore();
        if (isProjectPreferencePage())
            return;

        createHeader();
        createVersionSection();
        createKeysLink();

        createReplaceListFiltersField();
        createFilterMatchColorField();

        BooleanFieldEditor improveDebuggerField = new BooleanFieldEditor(
            ComfortSettings.PREF_IMPROVE_DEBUGGER_WINDOWS,
            "Улучшать окна отладчика", //$NON-NLS-1$
            getFieldEditorParent());
        addField(improveDebuggerField);
        setFieldTooltip(improveDebuggerField, IMPROVE_DEBUGGER_WINDOWS_TOOLTIP);

        createGroupCommonModulesFields();

        BooleanFieldEditor verticalTabsField = new BooleanFieldEditor(
            ComfortSettings.PREF_MD_EDITOR_VERTICAL_TABS,
            "Вертикальные вкладки в редакторе объекта", //$NON-NLS-1$
            getFieldEditorParent());
        addField(verticalTabsField);
        setFieldTooltip(verticalTabsField, MD_EDITOR_VERTICAL_TABS_TOOLTIP);

        // === Группа «Редактор кода» ===
        Group codeEditorGroup = new Group(getFieldEditorParent(), SWT.NONE);
        codeEditorGroup.setText("Редактор кода");
        GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
        groupData.horizontalSpan = 2;
        groupData.verticalIndent = 8;        // отступ сверху от предыдущего поля
        codeEditorGroup.setLayoutData(groupData);

        GridLayout groupLayout = new GridLayout(2, false);
        groupLayout.marginWidth = 10;       // внутренние отступы по горизонтали
        groupLayout.marginHeight = 8;       // внутренние отступы по вертикали
        groupLayout.marginTop = 6;          // чтобы заголовок группы не сливался с первым элементом
        groupLayout.horizontalSpacing = 8;  // расстояние между колонками
        groupLayout.verticalSpacing = 4;    // расстояние между строками
        codeEditorGroup.setLayout(groupLayout);

        BooleanFieldEditor autoOpenField = new BooleanFieldEditor(
            ContentAssistSettings.PREF_ENABLED,
            "Автооткрытие подсказок при вводе",
            codeEditorGroup);
        addField(autoOpenField);
        setFieldTooltip(autoOpenField,
            "Открывать список автодополнения и описание параметров метода при вводе символов", //$NON-NLS-1$
            codeEditorGroup);

        IntegerFieldEditor timeoutField = new IntegerFieldEditor(
            ContentAssistSettings.PREF_TIMEOUT,
            "Автооткрытие: Задержка (мс)",
            codeEditorGroup,
            5);
        timeoutField.setValidRange(0, 10_000);
        addField(timeoutField);
        Text timeoutText = timeoutField.getTextControl(codeEditorGroup);
        GridData timeoutTextData = new GridData();
        timeoutTextData.widthHint = 40;
        timeoutTextData.grabExcessHorizontalSpace = false;
        timeoutTextData.horizontalAlignment = SWT.LEFT;
        timeoutText.setLayoutData(timeoutTextData);

        BooleanFieldEditor serverCallField = new BooleanFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED,
            "Подсвечивать серверные вызовы", //$NON-NLS-1$
            codeEditorGroup);
        addField(serverCallField);
        setFieldTooltip(serverCallField,
            "Подсвечивать серверные вызовы в клиентском коде особыми цветами", //$NON-NLS-1$
            codeEditorGroup);

        ThemeAwareColorFieldEditor serverCallColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR,
            "Цвет серверных вызовов:", //$NON-NLS-1$
            codeEditorGroup);
        addField(serverCallColorField);
        setFieldTooltip(serverCallColorField, SERVER_CALL_COLOR_TOOLTIP, codeEditorGroup);

        ThemeAwareColorFieldEditor serverCallContextColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR,
            "Цвет серверных вызовов с контекстом:", //$NON-NLS-1$
            codeEditorGroup);
        addField(serverCallContextColorField);
        setFieldTooltip(serverCallContextColorField, SERVER_CALL_CONTEXT_COLOR_TOOLTIP, codeEditorGroup);

        BooleanFieldEditor implicitVariableField = new BooleanFieldEditor(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED,
            "Подсвечивать создаваемые переменные", //$NON-NLS-1$
            codeEditorGroup);
        addField(implicitVariableField);
        setFieldTooltip(implicitVariableField,
            "Подсвечивать особым цветом имя переменной в месте создания: первое присваивание и переменная цикла Для / Для Каждого", //$NON-NLS-1$
            codeEditorGroup);

        ThemeAwareColorFieldEditor implicitVariableColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR,
            "Цвет создаваемых переменных:", //$NON-NLS-1$
            codeEditorGroup);
        addField(implicitVariableColorField);
        setFieldTooltip(implicitVariableColorField, IMPLICIT_VARIABLE_COLOR_TOOLTIP, codeEditorGroup);

        BooleanFieldEditor bracketHintField = new BooleanFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            "Отображать начало конструкции в её конце", //$NON-NLS-1$
            codeEditorGroup);
        addField(bracketHintField);
        setFieldTooltip(bracketHintField,
            "Показывать начало блочной конструкции (Процедура, Если, Пока, Для, Попытка, #Область, #Если)\n"
            + "полупрозрачным текстом рядом с её закрывающим словом (КонецПроцедуры, КонецЕсли и т.д.),\n"
            + "если конструкция занимает много видимых строк.", //$NON-NLS-1$
            codeEditorGroup);

        IntegerFieldEditor bracketHintMinLinesField = new IntegerFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES,
            "Минимальное расстояние в строках", //$NON-NLS-1$
            codeEditorGroup,
            5);
        bracketHintMinLinesField.setValidRange(0, 10_000);
        addField(bracketHintMinLinesField);
        String bracketHintMinLinesTooltip =
            "Минимальное количество ВИДИМЫХ строк (с учётом свёрнутых блоков) между началом\n"
            + "и концом конструкции, при котором показывается подсказка. Если открывающая часть\n"
            + "вообще не видна, подсказка показывается всегда."; //$NON-NLS-1$
        setFieldTooltip(bracketHintMinLinesField, bracketHintMinLinesTooltip, codeEditorGroup);
        Text bracketHintMinLinesText = bracketHintMinLinesField.getTextControl(codeEditorGroup);
        bracketHintMinLinesText.setToolTipText(bracketHintMinLinesTooltip);
        GridData bracketHintMinLinesTextData = new GridData();
        bracketHintMinLinesTextData.widthHint = 40;
        bracketHintMinLinesTextData.grabExcessHorizontalSpace = false;
        bracketHintMinLinesTextData.horizontalAlignment = SWT.LEFT;
        bracketHintMinLinesText.setLayoutData(bracketHintMinLinesTextData);

        // BooleanFieldEditor.createControl() подменяет layout родителя на GridLayout —
        // отдельный host, иначе ломается сетка группы «Редактор кода».
        if (ComfortJdtAvailability.isJdtUiAvailable())
        {
            Composite spellingIdentsHost = new Composite(codeEditorGroup, SWT.NONE);
            GridData spellingIdentsHostData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            spellingIdentsHostData.horizontalSpan = 2;
            spellingIdentsHost.setLayoutData(spellingIdentsHostData);
            BooleanFieldEditor spellingIdentsField = new BooleanFieldEditor(
                ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE,
                "Проверять орфографию в идентификаторах в видимой области", //$NON-NLS-1$
                spellingIdentsHost);
            addField(spellingIdentsField);
            setFieldTooltip(spellingIdentsField,
                "При включённой орфографии Comfort (словарь «Русский/Английский (Комфорт-HUNSPELL)»)\n"
                + "проверять в видимой области модуля имена (идентификаторы) и строковые литералы.\n"
                + "Если выключено — проверяются только обычные слова в комментариях;\n"
                + "слова с заглавной буквой не на первой позиции (как в CamelCase) пропускаются.", //$NON-NLS-1$
                spellingIdentsHost);
            createCommonDictionaryLink();
        }
        else
            createInstallJdtSpellingLink(codeEditorGroup);

        // FieldEditor в конструкторе обнуляет margin* группы — вернуть отступ
        // под заголовком «Редактор кода», иначе первое поле слипается с рамкой.
        restoreGroupContentInsets(codeEditorGroup);

        createTextEditorsGroup();

        createLoggingGroup();

        // Поле «Символы» намеренно не добавляется:
        // значение задано константой ContentAssistSettings.CHARSET_VALUE
    }

    /**
     * Шапка страницы: фирменная иконка слева от надписи «Настройки плагина…».
     * Описание не отдаётся в {@code setDescription} — иначе фреймворк нарисует
     * тот же текст вторым, отдельной строкой над содержимым.
     */
    private void createHeader()
    {
        Composite parent = getFieldEditorParent();

        Composite row = new Composite(parent, SWT.NONE);
        GridData rowGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        rowGd.horizontalSpan = 2;
        row.setLayoutData(rowGd);
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.spacing = 8;
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.marginLeft = 0;
        rowLayout.marginTop = 0;
        rowLayout.marginRight = 0;
        rowLayout.marginBottom = 0;
        rowLayout.center = true;
        row.setLayout(rowLayout);

        Image comfortIcon = Global.comfortIcon();
        if (comfortIcon != null)
            new Label(row, SWT.NONE).setImage(comfortIcon);

        Label descriptionLabel = new Label(row, SWT.NONE);
        descriptionLabel.setText(WORKSPACE_DESCRIPTION);
    }

    /** Ссылка установки Eclipse JDT — без него орфография Comfort недоступна. */
    private void createInstallJdtSpellingLink(Composite parent)
    {
        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>Установить модуль орфографии (JDT)</a>"); //$NON-NLS-1$
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        gd.verticalIndent = 8;
        link.setLayoutData(gd);
        link.setToolTipText(
            "Орфография Comfort использует Eclipse JDT UI (org.eclipse.jdt.ui).\n"
            + "В этой EDT модуль не установлен — откроется мастер с предвыбором минимума.\n"
            + "После установки перезапустите EDT."); //$NON-NLS-1$
        link.addListener(SWT.Selection, e ->
        {
            if (!"Установить модуль орфографии (JDT)".equals(e.text)) //$NON-NLS-1$
                return;
            ComfortPreferences.openInstallJdtForSpelling();
        });
    }

    /** Ссылка на общий morph-словарь (workspace). */
    private void createCommonDictionaryLink()
    {
        Composite parent = getFieldEditorParent();
        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>Общий пользовательский словарь</a>"); //$NON-NLS-1$
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        gd.verticalIndent = 8;
        link.setLayoutData(gd);
        link.setToolTipText(
            "Файл spelling-comfort-common.dic в каталоге состояния плагина (все проекты).\n"
            + "Лемма или лемма/флаг AOT. Проектный словарь — в Свойствах проекта → Комфорт."); //$NON-NLS-1$
        link.addListener(SWT.Selection, e ->
        {
            if (!"Общий пользовательский словарь".equals(e.text)) //$NON-NLS-1$
                return;
            if (!ComfortJdtAvailability.isJdtUiAvailable())
            {
                ComfortPreferences.openInstallJdtForSpelling();
                return;
            }
            SpellCheckHook.openCommonUserMorphDictionaryInEclipseEditor();
        });
    }

    /** Режим «По проекту»: только ссылка на словарь проекта. */
    private void createProjectDictionarySection(Composite parent)
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
        {
            Label hint = new Label(parent, SWT.WRAP);
            hint.setText(
                "Орфография Comfort требует Eclipse JDT UI. "
                + "Установите модуль, перезапустите EDT — затем здесь появится "
                + "пользовательский словарь проекта."); //$NON-NLS-1$
            GridData hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
            hintGd.widthHint = 420;
            hint.setLayoutData(hintGd);
            createInstallJdtSpellingLink(parent);
            return;
        }

        Label hint = new Label(parent, SWT.WRAP);
        hint.setText(
            "Пользовательский словарь орфографии этого проекта "
            + "(.comfort/spelling-comfort-project.dic). Коммитьте файл в git — "
            + "при merge строки сливаются; Comfort пересчитывает счётчик при загрузке."); //$NON-NLS-1$
        GridData hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintGd.widthHint = 420;
        hint.setLayoutData(hintGd);

        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>Пользовательский словарь</a>"); //$NON-NLS-1$
        GridData linkGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        linkGd.verticalIndent = 8;
        link.setLayoutData(linkGd);
        IProject project = getProject();
        link.setEnabled(project != null && project.isAccessible());
        link.setToolTipText(
            "Открыть .comfort/spelling-comfort-project.dic в редакторе. "
            + "После Save словарь подхватывается сразу."); //$NON-NLS-1$
        link.addListener(SWT.Selection, e ->
        {
            if (!"Пользовательский словарь".equals(e.text)) //$NON-NLS-1$
                return;
            IProject p = getProject();
            if (p == null || !p.isAccessible())
            {
                ToastNotification.show("Орфография", //$NON-NLS-1$
                    "Проект недоступен.", 5_000); //$NON-NLS-1$
                return;
            }
            SpellCheckHook.openProjectUserMorphDictionaryInEclipseEditor(p);
        });
    }

    /**
     * Флажок «Улучшать списки» + ссылка «Подробнее» справа от него на одной строке.
     *
     * <p>{@link BooleanFieldEditor} в DEFAULT-стиле рисует подпись прямо на самом
     * чекбоксе (отдельного {@link Label} нет) и растягивает его на все колонки того
     * parent'а, куда его добавляют, поэтому чекбокс кладётся в отдельный
     * однострочный {@code checkboxHost} (колонка 0 грида {@code parent}), а ссылка —
     * его сосед по гриду в колонке 1. Без промежуточного {@link RowLayout}: у него
     * ненулевые {@code marginLeft/marginRight} по умолчанию (3px), которые
     * {@code marginWidth = 0} не обнуляет, — это и давало лишний сдвиг вправо.
     */
    private void createReplaceListFiltersField()
    {
        Composite parent = getFieldEditorParent();

        Composite checkboxHost = new Composite(parent, SWT.NONE);
        checkboxHost.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        BooleanFieldEditor replaceListFiltersField = new BooleanFieldEditor(
            ComfortSettings.PREF_REPLACE_LIST_FILTERS,
            "Улучшать списки",
            checkboxHost);
        addField(replaceListFiltersField);
        setFieldTooltip(replaceListFiltersField, REPLACE_LIST_FILTERS_TOOLTIP, checkboxHost);

        Link docLink = new Link(parent, SWT.NONE);
        docLink.setText("<a>Подробнее</a>"); //$NON-NLS-1$
        docLink.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        docLink.addListener(SWT.Selection, e -> {
            if (!"Подробнее".equals(e.text)) //$NON-NLS-1$
                return;
            ComfortPreferences.openChangesUrl(REPLACE_LIST_FILTERS_DOC_URL);
        });
    }

    /** Флажок + два набора суффиксов для динамической группировки общих модулей (issue #117). */
    private void createGroupCommonModulesFields()
    {
        BooleanFieldEditor groupCommonModulesField = new BooleanFieldEditor(
            ComfortSettings.PREF_GROUP_COMMON_MODULES_ENABLED,
            "Группировать общие модули в навигаторе по имени", //$NON-NLS-1$
            getFieldEditorParent());
        addField(groupCommonModulesField);
        setFieldTooltip(groupCommonModulesField,
            "Чисто визуально сворачивает семейства общих модулей с одинаковой основой имени\n"
            + "(например ВариантыОтветов / ВариантыОтветовКлиент / ВариантыОтветовКлиентСервер)\n"
            + "в одну группу в дереве навигатора. Структура конфигурации не меняется."); //$NON-NLS-1$

        StringFieldEditor suffixes1Field = new StringFieldEditor(
            ComfortSettings.PREF_GROUP_COMMON_MODULES_SUFFIXES_1,
            "Суффиксы набора 1 (через запятую):", //$NON-NLS-1$
            50,
            getFieldEditorParent());
        addField(suffixes1Field);
        setFieldTooltip(suffixes1Field,
            "Комбинируемые суффиксы хвоста имени. Могут идти цепочкой в любом количестве\n"
            + "(например Клиент + Сервер → …КлиентСервер, Клиент + ПовтИсп → …КлиентПовтИсп)."); //$NON-NLS-1$

        StringFieldEditor suffixes2Field = new StringFieldEditor(
            ComfortSettings.PREF_GROUP_COMMON_MODULES_SUFFIXES_2,
            "Суффиксы набора 2 (через запятую):", //$NON-NLS-1$
            50,
            getFieldEditorParent());
        addField(suffixes2Field);
        setFieldTooltip(suffixes2Field,
            "Не более одного суффикса из набора в хвосте имени — в любой позиции относительно\n"
            + "элементов набора 1 (до, между или после): …СлужебныйКлиент, …КлиентСлужебныйСервер."); //$NON-NLS-1$
    }

    /** «Цвет фильтра» сразу под строкой «Улучшать списки». */
    private void createFilterMatchColorField()
    {
        ThemeAwareColorFieldEditor colorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_FILTER_MATCH_COLOR,
            "Цвет фильтра:", //$NON-NLS-1$
            getFieldEditorParent());
        addField(colorField);
        setFieldTooltip(colorField, FILTER_MATCH_COLOR_TOOLTIP);
    }

    /**
     * Color picker: store = светлый RGB; в тёмной теме в контроле — {@link ThemeAwareColors#invertLightness};
     * при Save из контрола — обратный invert → светлый в store.
     */
    private static final class ThemeAwareColorFieldEditor extends ColorFieldEditor
    {
        /** Страница открыта в тёмной теме → в контроле должен быть effective. */
        private boolean controlShowsDarkEffective;
        private RGB lastLightFromStore;

        ThemeAwareColorFieldEditor(String name, String labelText, Composite parent)
        {
            super(name, labelText, parent);
        }

        private void applyLightToControl(RGB lightFromStore)
        {
            lastLightFromStore = lightFromStore;
            controlShowsDarkEffective = ThemeAwareColors.isDarkTheme();
            // Явно: в тёмной — invert, без повторного isDarkTheme внутри toEffectiveRgb.
            RGB forControl = controlShowsDarkEffective
                ? ThemeAwareColors.invertLightness(lightFromStore)
                : lightFromStore;
            getColorSelector().setColorValue(forControl);
            // Повторно после отрисовки страницы — на случай если что-то перезапишет селектор.
            org.eclipse.swt.widgets.Button button = getColorSelector().getButton();
            if (button != null && !button.isDisposed())
            {
                RGB again = forControl;
                button.getDisplay().asyncExec(() -> {
                    if (getColorSelector() == null || getColorSelector().getButton() == null
                        || getColorSelector().getButton().isDisposed())
                        return;
                    getColorSelector().setColorValue(again);
                });
            }
        }

        @Override
        protected void doLoad()
        {
            if (getColorSelector() == null)
                return;
            IPreferenceStore store = getPreferenceStore();
            if (store == null)
                return;
            RGB raw = PreferenceConverter.getColor(store, getPreferenceName());
            RGB light = ThemeAwareColors.sanitizeStoredLightRgb(raw);
            if (light.red != raw.red || light.green != raw.green || light.blue != raw.blue)
                PreferenceConverter.setValue(store, getPreferenceName(), light);
            applyLightToControl(light);
        }

        @Override
        protected void doLoadDefault()
        {
            if (getColorSelector() == null)
                return;
            IPreferenceStore store = getPreferenceStore();
            if (store == null)
                return;
            applyLightToControl(PreferenceConverter.getDefaultColor(store, getPreferenceName()));
        }

        @Override
        protected void doStore()
        {
            if (getColorSelector() == null)
                return;
            IPreferenceStore store = getPreferenceStore();
            if (store == null)
                return;
            RGB fromControl = getColorSelector().getColorValue();
            RGB toStore;
            if (controlShowsDarkEffective || ThemeAwareColors.isDarkTheme())
            {
                // В контроле должен быть effective. Если там всё ещё светлый с store —
                // load не сработал: не инвертируем повторно (иначе испортим store).
                if (lastLightFromStore != null
                    && fromControl.red == lastLightFromStore.red
                    && fromControl.green == lastLightFromStore.green
                    && fromControl.blue == lastLightFromStore.blue)
                {
                    toStore = lastLightFromStore;
                }
                else
                {
                    toStore = ThemeAwareColors.invertLightness(fromControl);
                }
            }
            else
            {
                toStore = fromControl;
            }
            PreferenceConverter.setValue(store, getPreferenceName(), toStore);
            lastLightFromStore = toStore;
            SmartMatchHighlight.clearColorCache();
        }
    }

    private static final String IMPROVE_DEBUGGER_WINDOWS_TOOLTIP =
        "Включает автоматические доработки штатных окон отладки EDT.\n\n"
            + "Инспектор (F9, hover):\n"
            + "• кнопка × и «Инспектировать» в hover;\n"
            + "• закрепление отдельного окна без авто-закрытия;\n"
            + "• выбор строки по клику в любой колонке, подсветка активной ячейки;\n"
            + "• Ctrl+C — копирование ячейки, Ctrl+F / F3 — поиск по дереву;\n"
            + "• F2 — «Показать коллекцию», двойной щелчок по коллекции — то же; редактирование значения — для редактируемых скаляров.\n\n"
            + "«Переменные» и «Выражения»:\n"
            + "• префикс [N] у длинных строк в колонке «Значение»;\n"
            + "• F2 — «Показать коллекцию».\n\n"
            + "При выключении горячие клавиши в этих окнах не перехватываются.\n"
            + "Пункты контекстного меню и окно «Коллекция» остаются доступны."; //$NON-NLS-1$

    /**
     * Группа «Текстовые редакторы» — поведение, общее для всех текстовых полей и
     * редакторов, а не только для редактора модуля.
     */
    private void createTextEditorsGroup()
    {
        Group textEditorsGroup = new Group(getFieldEditorParent(), SWT.NONE);
        textEditorsGroup.setText("Текстовые редакторы");
        GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
        groupData.horizontalSpan = 2;
        groupData.verticalIndent = 8;
        textEditorsGroup.setLayoutData(groupData);

        GridLayout groupLayout = new GridLayout(2, false);
        groupLayout.marginWidth = 10;
        groupLayout.marginHeight = 8;
        groupLayout.marginTop = 6;          // чтобы заголовок группы не сливался с первым элементом
        groupLayout.horizontalSpacing = 8;
        groupLayout.verticalSpacing = 4;
        textEditorsGroup.setLayout(groupLayout);

        // BooleanFieldEditor.createControl() подменяет layout родителя на GridLayout —
        // отдельный host, иначе ломается сетка группы.
        Composite ctrlClickHost = new Composite(textEditorsGroup, SWT.NONE);
        GridData ctrlClickHostData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ctrlClickHostData.horizontalSpan = 2;
        ctrlClickHost.setLayoutData(ctrlClickHostData);
        BooleanFieldEditor ctrlClickSelectWordField = new BooleanFieldEditor(
            ComfortSettings.PREF_CTRL_CLICK_SELECT_WORD,
            "Ctrl+клик выделяет слово", //$NON-NLS-1$
            ctrlClickHost);
        addField(ctrlClickSelectWordField);
        setFieldTooltip(ctrlClickSelectWordField,
            "Ctrl+клик в текстовом поле выделяет слово под курсором — как двойной клик, —\n"
            + "если это слово ещё не выделено. Повторный клик (в том числе быстрый двойной\n"
            + "щелчок) работает штатно: в редакторе модуля — переход по гиперссылке.", //$NON-NLS-1$
            ctrlClickHost);
    }

    private void createLoggingGroup()
    {
        Group loggingGroup = new Group(getFieldEditorParent(), SWT.NONE);
        loggingGroup.setText("Журнал");
        GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
        groupData.horizontalSpan = 2;
        groupData.verticalIndent = 8;
        loggingGroup.setLayoutData(groupData);

        GridLayout groupLayout = new GridLayout(2, false);
        groupLayout.marginWidth = 10;
        groupLayout.marginHeight = 8;
        groupLayout.horizontalSpacing = 8;
        groupLayout.verticalSpacing = 4;
        loggingGroup.setLayout(groupLayout);

        Composite logRow = new Composite(loggingGroup, SWT.NONE);
        GridData logRowData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        logRowData.horizontalSpan = 2;
        logRow.setLayoutData(logRowData);
        GridLayout logRowLayout = new GridLayout(1, false);
        logRowLayout.marginWidth = 0;
        logRowLayout.marginHeight = 0;
        logRow.setLayout(logRowLayout);

        Composite logControls = new Composite(logRow, SWT.NONE);
        logControls.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        RowLayout controlsLayout = new RowLayout(SWT.HORIZONTAL);
        controlsLayout.spacing = 8;
        controlsLayout.marginWidth = 0;
        controlsLayout.marginHeight = 0;
        controlsLayout.center = true;
        logControls.setLayout(controlsLayout);

        // BooleanFieldEditor.createControl() заменяет layout родителя на GridLayout —
        // поэтому чекбокс в отдельном composite, а «Журнал» — сосед в RowLayout.
        Composite checkboxHost = new Composite(logControls, SWT.NONE);
        BooleanFieldEditor debugLogField = new BooleanFieldEditor(
            ComfortSettings.PREF_DEBUG_LOG,
            "Вести журнал",
            checkboxHost);
        addField(debugLogField);
        setFieldTooltip(debugLogField,
            "Журнал отладки: content assist, установщик «Сменить»/«Обновить» и др.\n"
            + "Окно: Показать представление → Прочее → Журнал Комфорт"); //$NON-NLS-1$

        Link logViewLink = new Link(logControls, SWT.NONE);
        logViewLink.setText("<a>Журнал</a>"); //$NON-NLS-1$
        logViewLink.setToolTipText("Открыть представление «Журнал Комфорт»"); //$NON-NLS-1$
        logViewLink.addListener(SWT.Selection, e -> {
            if (!"Журнал".equals(e.text)) //$NON-NLS-1$
                return;
            GlobalLog.showView();
        });
    }

    private void createVersionSection()
    {
        Composite versionSection = new Composite(getFieldEditorParent(), SWT.NONE);
        GridData sectionData = new GridData(SWT.FILL, SWT.TOP, true, false);
        sectionData.horizontalSpan = 2;
        sectionData.verticalIndent = 4;
        versionSection.setLayoutData(sectionData);

        GridLayout layout = new GridLayout(5, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 6;
        versionSection.setLayout(layout);

        createVersionRow(versionSection,
            "Используемая версия:", true); //$NON-NLS-1$
        createVersionRow(versionSection,
            "Актуальная версия:", false); //$NON-NLS-1$
    }

    private void createVersionRow(Composite parent, String labelText, boolean installedRow)
    {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Text versionText = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        versionText.setEditable(false);
        versionText.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Text dateText = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        GridData dateData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        dateData.widthHint = convertHorizontalDLUsToPixels(40);
        dateText.setLayoutData(dateData);
        dateText.setEditable(false);

        Link changesLink = new Link(parent, SWT.NONE);
        changesLink.setText("<a>Изменения</a>"); //$NON-NLS-1$
        changesLink.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        changesLink.addListener(SWT.Selection, e -> {
            if (!"Изменения".equals(e.text)) //$NON-NLS-1$
                return;
            String url = installedRow
                ? ComfortUpdateChecker.getInstalledVersion().getChangesUrl()
                : resolveLatestChangesUrl();
            ComfortPreferences.openChangesUrl(url);
        });

        Link rowUpdateLink = new Link(parent, SWT.NONE);
        rowUpdateLink.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        rowUpdateLink.addListener(SWT.Selection, e -> {
            if (!"Обновить".equals(e.text) && !"Сменить".equals(e.text)) //$NON-NLS-1$ //$NON-NLS-2$
                return;
            ComfortPreferences.openInstallNewSoftware();
        });

        if (installedRow)
        {
            installedVersionText = versionText;
            installedDateText = dateText;
            installedChangesLink = changesLink;
            updateLink = rowUpdateLink;
        }
        else
        {
            latestVersionText = versionText;
            latestDateText = dateText;
            latestChangesLink = changesLink;
            rowUpdateLink.setVisible(false);
        }
    }

    private void refreshVersionSection()
    {
        if (installedVersionText == null || installedVersionText.isDisposed())
            return;

        ComfortVersionInfo installed = ComfortUpdateChecker.getInstalledVersion();
        setVersionText(installedVersionText, installed.getDisplayVersion());
        installedDateText.setText(installed.getDisplayDate());

        ComfortVersionInfo latest = ComfortUpdateChecker.getCachedLatestVersion();
        if (latest == null)
        {
            setVersionText(latestVersionText,
                ComfortUpdateChecker.isCheckInProgress() ? "…" : "—"); //$NON-NLS-1$ //$NON-NLS-2$
            latestDateText.setText(
                ComfortUpdateChecker.isCheckInProgress() ? "…" : "—"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            setVersionText(latestVersionText, latest.getDisplayVersion());
            latestDateText.setText(latest.getDisplayDate());
        }

        updateChangesLink(installedChangesLink, installed.getChangesUrl());
        updateChangesLink(latestChangesLink, resolveLatestChangesUrl()); //$NON-NLS-1$
        updateVersionActionLink(latest);
    }

    private static void setVersionText(Text field, String text)
    {
        if (field == null || field.isDisposed())
            return;
        field.setText(text != null ? text : ""); //$NON-NLS-1$
        GridData gd = (GridData) field.getLayoutData();
        if (gd == null)
            gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        Point size = field.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        gd.widthHint = size.x;
        gd.horizontalAlignment = SWT.LEFT;
        gd.grabExcessHorizontalSpace = false;
        field.setLayoutData(gd);
    }

    private void updateVersionActionLink(ComfortVersionInfo latest)
    {
        if (updateLink == null || updateLink.isDisposed())
            return;
        boolean newerAvailable = latest != null && ComfortUpdateChecker.isUpdateAvailable();
        String label = newerAvailable ? "Обновить" : "Сменить"; //$NON-NLS-1$ //$NON-NLS-2$
        updateLink.setText("<a>" + label + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void updateChangesLink(Link link, String url)
    {
        if (link == null || link.isDisposed())
            return;
        boolean enabled = url != null && !url.isBlank();
        link.setEnabled(enabled);
        link.setText(enabled ? "<a>Изменения</a>" : "Изменения"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String resolveLatestChangesUrl()
    {
        ComfortVersionInfo latest = ComfortUpdateChecker.getCachedLatestVersion();
        return latest != null ? latest.getChangesUrl() : ""; //$NON-NLS-1$
    }

    private static final String HOME_PAGE_URL = "https://tormozit.github.io/EDT.Comfort"; //$NON-NLS-1$
    private static final String NEW_ISSUE_URL =
        "https://github.com/tormozit/EDT.Comfort/issues/new"; //$NON-NLS-1$

    /**
     * Ссылки «Клавиши», «Домашняя страница», «Создать заявку» на одной строке.
     *
     * <p>Родительский {@code parent} — грид на 2 колонки, а виджетов три, поэтому
     * они кладутся в общий {@link Composite} с {@link RowLayout}, а не как прямые
     * соседи по гриду. Все margin-поля {@link RowLayout} (включая отдельные
     * {@code marginLeft/Top/Right/Bottom}, у которых значение по умолчанию — 3px и
     * не сбрасывается через {@code marginWidth/marginHeight}) обнуляются явно —
     * иначе строка сдвигается вправо относительно остальных полей страницы.
     */
    private void createKeysLink()
    {
        Composite parent = getFieldEditorParent();

        Composite row = new Composite(parent, SWT.NONE);
        GridData rowGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        rowGd.horizontalSpan = 2;
        rowGd.verticalIndent = 8;
        row.setLayoutData(rowGd);
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.spacing = 8;
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.marginLeft = 0;
        rowLayout.marginTop = 0;
        rowLayout.marginRight = 0;
        rowLayout.marginBottom = 0;
        rowLayout.center = true;
        row.setLayout(rowLayout);

        Link keysLink = new Link(row, SWT.NONE);
        keysLink.setText("<a>Клавиши</a>"); //$NON-NLS-1$
        keysLink.setToolTipText("Настройки клавиш с фильтром «Комфорт»"); //$NON-NLS-1$
        keysLink.addListener(SWT.Selection, e -> {
            if (!"Клавиши".equals(e.text)) //$NON-NLS-1$
                return;
            IPreferencePageContainer container = getContainer();
            if (container instanceof IWorkbenchPreferenceContainer wb)
            {
                wb.openPage(ComfortKeysPreferences.KEYS_PREFERENCE_PAGE_ID, null);
                ComfortKeysPreferences.scheduleApplyEnhancements(container);
            }
        });

        Link homePageLink = new Link(row, SWT.NONE);
        homePageLink.setText("<a>Домашняя страница</a>"); //$NON-NLS-1$
        homePageLink.addListener(SWT.Selection, e -> {
            if (!"Домашняя страница".equals(e.text)) //$NON-NLS-1$
                return;
            ComfortPreferences.openChangesUrl(HOME_PAGE_URL);
        });

        Link newIssueLink = new Link(row, SWT.NONE);
        newIssueLink.setText("<a>Создать заявку</a>"); //$NON-NLS-1$
        newIssueLink.addListener(SWT.Selection, e -> {
            if (!"Создать заявку".equals(e.text)) //$NON-NLS-1$
                return;
            ComfortPreferences.openChangesUrl(buildNewIssueUrl());
        });
    }

    /**
     * URL «Создать заявку» с предзаполненным телом — версии, ОС, тема, ИР,
     * дополнительные плагины и флажки Комфорт.
     */
    private static String buildNewIssueUrl()
    {
        String encodedBody = java.net.URLEncoder.encode(
                NewIssueReport.buildBody(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20"); //$NON-NLS-1$ //$NON-NLS-2$
        return NEW_ISSUE_URL + "?body=" + encodedBody; //$NON-NLS-1$
    }

    /**
     * {@link FieldEditor} в конструкторе ставит родителю {@link GridLayout} с нулевыми
     * {@code margin*} — заголовок {@link Group} слипается с первым полем. Вызывать
     * после всех {@code new *FieldEditor(..., group)}.
     */
    private static void restoreGroupContentInsets(Group group)
    {
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 8;
        layout.marginTop = 6;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 4;
        group.setLayout(layout);
    }

    /**
     * Устанавливает tooltip на уже созданные виджеты {@link FieldEditor}.
     * Для {@link BooleanFieldEditor} в DEFAULT-стиле — только на флажок (подпись
     * на самой кнопке, отдельного {@link Label} нет).
     *
     * <p>Нельзя вызывать {@code getLabelControl(parent)} / {@code getChangeControl(parent)}:
     * если Label ещё нет, {@code getLabelControl} его создаёт — «призрачные» надписи
     * без флажка, которые проявляются при растягивании окна «Параметры».
     */
    private void setFieldTooltip(FieldEditor field, String tooltip)
    {
        applyFieldTooltip(field, tooltip);
    }

    private void setFieldTooltip(FieldEditor field, String tooltip, Composite parent)
    {
        if (parent != null && parent.isDisposed())
            return;
        applyFieldTooltip(field, tooltip);
    }

    private void applyFieldTooltip(FieldEditor field, String tooltip)
    {
        if (field == null || tooltip == null)
            return;
        Control change = existingChangeControl(field);
        if (change != null && !change.isDisposed())
            change.setToolTipText(TooltipText.wrap(change, tooltip));
        Control label = existingLabelControl(field);
        if (label != null && !label.isDisposed())
            label.setToolTipText(TooltipText.wrap(label, tooltip));
    }

    /** Уже созданная кнопка/поле, без {@code getXxxControl(parent)} — тот бросает при чужом parent. */
    private static Control existingChangeControl(FieldEditor field)
    {
        Control button = readControlField(field, "changeControl"); //$NON-NLS-1$
        if (button != null)
            return button;
        Control checkBox = readControlField(field, "checkBox"); //$NON-NLS-1$
        if (checkBox != null)
            return checkBox;
        Control text = readControlField(field, "textField"); //$NON-NLS-1$
        if (text != null)
            return text;
        Object selector = readInstanceField(field, "colorSelector"); //$NON-NLS-1$
        if (selector == null)
            return null;
        try
        {
            Object btn = selector.getClass().getMethod("getButton").invoke(selector); //$NON-NLS-1$
            if (btn instanceof Control control && !control.isDisposed())
                return control;
        }
        catch (Exception ignored)
        {
            // ColorSelector без getButton
        }
        return null;
    }

    private static Control existingLabelControl(FieldEditor field)
    {
        return readControlField(field, "label"); //$NON-NLS-1$
    }

    private static Control readControlField(Object target, String fieldName)
    {
        Object value = readInstanceField(target, fieldName);
        if (value instanceof Control control && !control.isDisposed())
            return control;
        return null;
    }

    private static Object readInstanceField(Object target, String fieldName)
    {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                java.lang.reflect.Field declared = c.getDeclaredField(fieldName);
                declared.setAccessible(true);
                return declared.get(target);
            }
            catch (NoSuchFieldException ignored)
            {
                // ищем выше
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean performOk()
    {
        boolean result = super.performOk();
        if (result)
        {
            SmartMatchHighlight.clearColorCache();
            BslEditorHighlightingHook.refreshAllEditors();
            BracketContentHintHook.refreshAllEditors();
            NavigatorFilterHook.refreshAllNavigators();
        }
        return result;
    }

    private void addFieldHint(String text)
    {
        Composite parent = getFieldEditorParent();
        Label hint = new Label(parent, SWT.WRAP);
        hint.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.horizontalSpan = 2;
        int indent = convertHorizontalDLUsToPixels(IDialogConstants.INDENT + 12);
        gd.horizontalIndent = indent;
        hint.setLayoutData(gd);
        hint.setForeground(ThemeAwareColors.effectiveSystemColor(
            parent.getDisplay(), SWT.COLOR_DARK_GRAY));

        parent.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                updateHintWrapWidth(parent, hint, gd, indent);
            }
        });
        parent.getDisplay().asyncExec(() -> updateHintWrapWidth(parent, hint, gd, indent));
    }

    private static void updateHintWrapWidth(Composite parent, Label hint, GridData gd, int indent)
    {
        if (hint.isDisposed() || parent.isDisposed())
            return;
        int width = parent.getClientArea().width - indent;
        if (width < 1 || gd.widthHint == width)
            return;
        gd.widthHint = width;
        parent.layout(false, false);
    }

    /**
     * Текст тела GitHub-заявки: окружение, свёрнутые доп. плагины и флажки.
     */
    private static final class NewIssueReport
    {
        private static final String BUNDLE_P2_CORE = "org.eclipse.equinox.p2.core"; //$NON-NLS-1$
        private static final String BUNDLE_P2_ENGINE = "org.eclipse.equinox.p2.engine"; //$NON-NLS-1$
        private static final String PROP_PROFILE_ROOT_IU =
            "org.eclipse.equinox.p2.type.root"; //$NON-NLS-1$
        private static final String PROP_IU_NAME = "org.eclipse.equinox.p2.name"; //$NON-NLS-1$
        private static final String COMFORT_BUNDLE_ID = "tormozit.comfort"; //$NON-NLS-1$

        static String buildBody()
        {
            StringBuilder body = new StringBuilder();
            body.append("**Версия плагина Комфорт:** ") //$NON-NLS-1$
                .append(ComfortVersionInfo.installed().getDisplayVersion()).append("\n") //$NON-NLS-1$
                .append("**Версия 1C:EDT:** ").append(edtVersion()).append("\n") //$NON-NLS-1$ //$NON-NLS-2$
                .append("**ОС:** ").append(osInfo()).append("\n") //$NON-NLS-1$ //$NON-NLS-2$
                .append("**Тема:** ").append(themeLabel()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$

            String irInfo = irTechnicalInfo();
            if (irInfo != null && !irInfo.isBlank())
                body.append("**ИР:**\n```\n").append(irInfo.strip()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$

            appendCollapsed(body, "Установленные плагины", extraPluginsText()); //$NON-NLS-1$
            appendCollapsed(body, "Флажки Комфорт", comfortFlagsText()); //$NON-NLS-1$
            body.append("\n"); //$NON-NLS-1$
            return body.toString();
        }

        private static void appendCollapsed(StringBuilder body, String title, String content)
        {
            body.append("<details>\n<summary>").append(title).append("</summary>\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            body.append("```\n").append(content); //$NON-NLS-1$
            if (!content.endsWith("\n")) //$NON-NLS-1$
                body.append('\n');
            body.append("```\n</details>\n"); //$NON-NLS-1$
        }

        /** Маркетинговая версия из about.mappings и OSGi-версия брендинг-бандла. */
        private static String edtVersion()
        {
            String marketing = aboutMapping("1"); //$NON-NLS-1$
            String osgi = osgiProductVersion();
            if (!marketing.isEmpty() && !osgi.isEmpty() && !marketing.equals(osgi))
                return marketing + " (" + osgi + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            if (!marketing.isEmpty())
                return marketing;
            if (!osgi.isEmpty())
                return osgi;
            return "—"; //$NON-NLS-1$
        }

        private static String osgiProductVersion()
        {
            try
            {
                IProduct product = Platform.getProduct();
                if (product != null && product.getDefiningBundle() != null)
                    return product.getDefiningBundle().getVersion().toString();
            }
            catch (RuntimeException ignored)
            {
                // версия продукта недоступна
            }
            return ""; //$NON-NLS-1$
        }

        private static String aboutMapping(String key)
        {
            try
            {
                IProduct product = Platform.getProduct();
                if (product == null)
                    return ""; //$NON-NLS-1$
                Bundle bundle = product.getDefiningBundle();
                if (bundle == null)
                    return ""; //$NON-NLS-1$
                URL url = bundle.getEntry("about.mappings"); //$NON-NLS-1$
                if (url == null)
                    return ""; //$NON-NLS-1$
                Properties props = new Properties();
                try (InputStream in = url.openStream())
                {
                    props.load(in);
                }
                String value = props.getProperty(key);
                return value != null ? value.strip() : ""; //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
                return ""; //$NON-NLS-1$
            }
        }

        private static String osInfo()
        {
            return System.getProperty("os.name", "?") + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + System.getProperty("os.version", "?") + " (" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + System.getProperty("os.arch", "?") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private static String themeLabel()
        {
            try
            {
                return ThemeAwareColors.isDarkTheme() ? "тёмная" : "светлая"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException ignored)
            {
                return "—"; //$NON-NLS-1$
            }
        }

        /**
         * {@code ирКлиент.ТехническаяИнформацияЛкс()}, если ИР подключён. {@code null},
         * если подключения нет или вызов не удался — заявка всё равно должна открыться.
         */
        private static String irTechnicalInfo()
        {
            IRSession session = IRApplication.getAnyConnectedSession();
            if (session == null)
                return null;
            try
            {
                return session.executeOnComThread(() -> {
                    Object irClient = session.getModule("ирКлиент"); //$NON-NLS-1$
                    return ComBridge.toString(ComBridge.invoke(irClient, "ТехническаяИнформацияЛкс")); //$NON-NLS-1$
                });
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        /** Корневые IU p2 кроме продукта EDT; при отсутствии — установленный Комфорт. */
        private static String extraPluginsText()
        {
            List<String> lines = queryExtraRootIus();
            ensureComfortListed(lines);
            if (lines.isEmpty())
                return "—"; //$NON-NLS-1$
            Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
            StringBuilder sb = new StringBuilder();
            for (String line : lines)
            {
                if (sb.length() > 0)
                    sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }

        private static void ensureComfortListed(List<String> lines)
        {
            for (String line : lines)
            {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("tormozit") || lower.contains("comfort") //$NON-NLS-1$ //$NON-NLS-2$
                    || lower.contains("комфорт")) //$NON-NLS-1$
                    return;
            }
            ComfortVersionInfo comfort = ComfortVersionInfo.installed();
            String version = comfort.getDisplayVersion();
            if (version == null || version.isBlank() || "—".equals(version)) //$NON-NLS-1$
            {
                Bundle bundle = Platform.getBundle(COMFORT_BUNDLE_ID);
                if (bundle != null && bundle.getVersion() != null)
                    version = bundle.getVersion().toString();
            }
            if (version == null || version.isBlank() || "—".equals(version)) //$NON-NLS-1$
                version = "—"; //$NON-NLS-1$
            lines.add("EDT Comfort " + version); //$NON-NLS-1$
        }

        private static List<String> queryExtraRootIus()
        {
            List<String> lines = new ArrayList<>();
            try
            {
                Bundle p2Core = Platform.getBundle(BUNDLE_P2_CORE);
                Bundle p2Engine = Platform.getBundle(BUNDLE_P2_ENGINE);
                if (p2Core == null || p2Engine == null)
                    return lines;

                BundleContext ctx = p2Core.getBundleContext();
                if (ctx == null)
                    return lines;
                ServiceReference<?> agentRef = ctx.getServiceReference(
                    "org.eclipse.equinox.p2.core.IProvisioningAgent"); //$NON-NLS-1$
                if (agentRef == null)
                    return lines;

                Object agent = ctx.getService(agentRef);
                if (agent == null)
                    return lines;

                try
                {
                    Class<?> registryClass = p2Engine.loadClass(
                        "org.eclipse.equinox.p2.engine.IProfileRegistry"); //$NON-NLS-1$
                    Object registry = agent.getClass()
                        .getMethod("getService", Class.class) //$NON-NLS-1$
                        .invoke(agent, registryClass);
                    if (registry == null)
                        return lines;

                    Object profile = selfProfile(registryClass, registry);
                    if (profile == null)
                        return lines;

                    Class<?> queryUtil = p2Engine.loadClass(
                        "org.eclipse.equinox.p2.query.QueryUtil"); //$NON-NLS-1$
                    Class<?> queryClass = p2Engine.loadClass(
                        "org.eclipse.equinox.p2.query.IQuery"); //$NON-NLS-1$
                    Class<?> iuClass = p2Engine.loadClass(
                        "org.eclipse.equinox.p2.metadata.IInstallableUnit"); //$NON-NLS-1$
                    Class<?> monitorClass = Platform.getBundle("org.eclipse.core.runtime") //$NON-NLS-1$
                        .loadClass("org.eclipse.core.runtime.IProgressMonitor"); //$NON-NLS-1$

                    Object query = p2Engine
                        .loadClass("org.eclipse.equinox.p2.engine.query.UserVisibleRootQuery") //$NON-NLS-1$
                        .getConstructor()
                        .newInstance();
                    Object result = profile.getClass()
                        .getMethod("query", queryClass, monitorClass) //$NON-NLS-1$
                        .invoke(profile, query, new NullProgressMonitor());
                    collectExtraIus(lines, result, profile, queryUtil, iuClass, false);

                    if (lines.isEmpty())
                    {
                        Object allQuery = queryUtil.getMethod("createIUAnyQuery").invoke(null); //$NON-NLS-1$
                        Object allResult = profile.getClass()
                            .getMethod("query", queryClass, monitorClass) //$NON-NLS-1$
                            .invoke(profile, allQuery, new NullProgressMonitor());
                        collectExtraIus(lines, allResult, profile, queryUtil, iuClass, true);
                    }
                }
                finally
                {
                    ctx.ungetService(agentRef);
                }
            }
            catch (ReflectiveOperationException | RuntimeException ignored)
            {
                return lines;
            }
            return lines;
        }

        /**
         * Профиль текущего экземпляра: {@code IProfileRegistry.SELF} ("_SELF_"),
         * иначе единственный существующий профиль.
         */
        private static Object selfProfile(Class<?> registryClass, Object registry)
            throws ReflectiveOperationException
        {
            Object profile = registryClass
                .getMethod("getProfile", String.class) //$NON-NLS-1$
                .invoke(registry, "_SELF_"); //$NON-NLS-1$
            if (profile != null)
                return profile;
            Object[] all = (Object[]) registryClass.getMethod("getProfiles").invoke(registry); //$NON-NLS-1$
            if (all != null && all.length == 1)
                return all[0];
            return null;
        }

        private static void collectExtraIus(
            List<String> lines, Object queryResult, Object profile, Class<?> queryUtil, Class<?> iuClass,
            boolean requireRootProperty)
            throws ReflectiveOperationException
        {
            @SuppressWarnings("unchecked")
            Iterator<Object> iterator = (Iterator<Object>) queryResult.getClass()
                .getMethod("iterator").invoke(queryResult); //$NON-NLS-1$
            Method isProduct = queryUtil.getMethod("isProduct", iuClass); //$NON-NLS-1$
            Method isCategory = queryUtil.getMethod("isCategory", iuClass); //$NON-NLS-1$
            Method getId = iuClass.getMethod("getId"); //$NON-NLS-1$
            Method getVersion = iuClass.getMethod("getVersion"); //$NON-NLS-1$
            Method getProperty2 = iuClass.getMethod("getProperty", String.class, String.class); //$NON-NLS-1$
            Method profileIuProperty = profile.getClass().getMethod(
                "getInstallableUnitProperty", iuClass, String.class); //$NON-NLS-1$

            String locale = Locale.getDefault().toString();
            while (iterator.hasNext())
            {
                Object iu = iterator.next();
                String id = String.valueOf(getId.invoke(iu));
                if (id.isBlank() || isEdtShippedId(id) || id.endsWith(".feature.jar")) //$NON-NLS-1$
                    continue;
                if (Boolean.TRUE.equals(isProduct.invoke(null, iu))
                    || Boolean.TRUE.equals(isCategory.invoke(null, iu)))
                    continue;
                if (requireRootProperty && !isRootIu(iu, profile, profileIuProperty, getProperty2))
                    continue;

                String name = localizedIuName(iu, getProperty2, locale);
                if (name.isBlank() || name.startsWith("%") || name.equals(id)) //$NON-NLS-1$
                    name = id;
                Object version = getVersion.invoke(iu);
                String versionText = version != null ? version.toString() : ""; //$NON-NLS-1$
                String line = versionText.isBlank() ? name : name + " " + versionText;
                if (!lines.contains(line))
                    lines.add(line);
            }
        }

        private static boolean isRootIu(
            Object iu, Object profile, Method profileIuProperty, Method getProperty2)
            throws ReflectiveOperationException
        {
            Object profileRoot = profileIuProperty.invoke(profile, iu, PROP_PROFILE_ROOT_IU);
            if (Boolean.TRUE.toString().equals(profileRoot))
                return true;
            Object iuRoot = getProperty2.invoke(iu, PROP_PROFILE_ROOT_IU, null);
            return Boolean.TRUE.toString().equals(iuRoot);
        }

        private static String localizedIuName(Object iu, Method getProperty2, String locale)
            throws ReflectiveOperationException
        {
            Object named = getProperty2.invoke(iu, PROP_IU_NAME, locale);
            if (named instanceof String text && !text.isBlank())
                return text;
            Object fallback = getProperty2.invoke(iu, PROP_IU_NAME, null);
            return fallback instanceof String text ? text : ""; //$NON-NLS-1$
        }

        private static boolean isEdtShippedId(String id)
        {
            return id.startsWith("com._1c.") || id.startsWith("com.e1c."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Флажки Комфорт: страница «Параметры → Комфорт» и соседние настройки-пометки. */
        private static String comfortFlagsText()
        {
            StringBuilder sb = new StringBuilder();
            appendFlag(sb, "Улучшать списки", ComfortSettings.isReplaceListFiltersEnabled()); //$NON-NLS-1$
            appendFlag(sb, "Улучшать окна отладчика", //$NON-NLS-1$
                ComfortSettings.isImproveDebuggerWindowsEnabled());
            appendFlag(sb, "Группировать общие модули в навигаторе по имени", //$NON-NLS-1$
                ComfortSettings.isGroupCommonModulesEnabled());
            appendFlag(sb, "Вертикальные вкладки в редакторе объекта", //$NON-NLS-1$
                ComfortSettings.isMdEditorVerticalTabsEnabled());
            appendFlag(sb, "Автооткрытие подсказок при вводе", isContentAssistAutoOpen()); //$NON-NLS-1$
            appendFlag(sb, "Ctrl+клик выделяет слово", //$NON-NLS-1$
                ComfortSettings.isCtrlClickSelectWordEnabled());
            appendFlag(sb, "Подсвечивать серверные вызовы", //$NON-NLS-1$
                ComfortSettings.isServerCallHighlightingEnabled());
            appendFlag(sb, "Подсвечивать создаваемые переменные", //$NON-NLS-1$
                ComfortSettings.isImplicitVariableHighlightingEnabled());
            appendFlag(sb, "Отображать начало конструкции в её конце", //$NON-NLS-1$
                ComfortSettings.isBracketContentHintEnabled());
            appendFlag(sb, "Проверять орфографию в идентификаторах в видимой области", //$NON-NLS-1$
                ComfortSettings.isSpellingCheckIdentifiersVisible());
            appendFlag(sb, "Вести журнал", ComfortSettings.isDebugLogEnabled()); //$NON-NLS-1$
            appendFlag(sb, "Подсказки при наведении без Ctrl", //$NON-NLS-1$
                ComfortSettings.isHoverHintsEnabled());
            appendFlag(sb, "Игнорировать сокращения в CamelCase", //$NON-NLS-1$
                ComfortSettings.isSpellingIgnoreCamelCaseAbbreviations());
            return sb.toString();
        }

        private static boolean isContentAssistAutoOpen()
        {
            ContentAssistSettings settings = ContentAssistSettings.getInstance();
            if (settings == null)
                return ContentAssistSettings.DEFAULT_ENABLED;
            return settings.getPreferenceStore().getBoolean(ContentAssistSettings.PREF_ENABLED);
        }

        private static void appendFlag(StringBuilder sb, String label, boolean on)
        {
            sb.append(on ? "[x] " : "[ ] ").append(label).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
