package tormozit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
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

    private static final String THEME_AWARE_COLOR_TOOLTIP =
            "В контроле — цвет текущей темы. В хранилище — всегда вариант для светлой темы.\n"
            + "При сохранении в тёмной теме цвет из контрола обратно пересчитывается (HSL: инверсия светлоты)."; //$NON-NLS-1$

    private static final String FILTER_MATCH_COLOR_TOOLTIP =
            "Цвет подсветки найденных фрагментов в списках с улучшенным фильтром.\n"
            + THEME_AWARE_COLOR_TOOLTIP;

    private static final String SERVER_CALL_COLOR_TOOLTIP =
            "Цвет подсветки серверных вызовов в клиентском коде.\n"
            + THEME_AWARE_COLOR_TOOLTIP;

    private static final String SERVER_CALL_CONTEXT_COLOR_TOOLTIP =
            "Цвет подсветки серверных вызовов с контекстом (&НаСервере).\n"
            + THEME_AWARE_COLOR_TOOLTIP;

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
        else
            setDescription("Настройки плагина Комфорт (Tormozit).");
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
        groupLayout.horizontalSpacing = 8;  // расстояние между колонками
        groupLayout.verticalSpacing = 4;    // расстояние между строками
        codeEditorGroup.setLayout(groupLayout);

        addField(new BooleanFieldEditor(
            ContentAssistSettings.PREF_ENABLED,
            "Автооткрытие подсказок при вводе",
            codeEditorGroup));

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
            "Подсвечивать серверные вызовы в клиентском коде другим цветом.\n"
            + "При выключении серверные вызовы подсвечиваются стандартным стилем builtin-функций EDT."); //$NON-NLS-1$

        ThemeAwareColorFieldEditor serverCallColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR,
            "Цвет серверных вызовов:", //$NON-NLS-1$
            codeEditorGroup);
        addField(serverCallColorField);
        setFieldTooltip(serverCallColorField, SERVER_CALL_COLOR_TOOLTIP);

        ThemeAwareColorFieldEditor serverCallContextColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR,
            "Цвет серверных вызовов с контекстом:", //$NON-NLS-1$
            codeEditorGroup);
        addField(serverCallContextColorField);
        setFieldTooltip(serverCallContextColorField, SERVER_CALL_CONTEXT_COLOR_TOOLTIP);

        BooleanFieldEditor bracketHintField = new BooleanFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            "Отображать начало конструкции в её конце", //$NON-NLS-1$
            codeEditorGroup);
        addField(bracketHintField);
        setFieldTooltip(bracketHintField,
            "Показывать начало блочной конструкции (Процедура, Если, Пока, Для, Попытка, #Область, #Если)\n"
            + "полупрозрачным текстом рядом с её закрывающим словом (КонецПроцедуры, КонецЕсли и т.д.),\n"
            + "если конструкция занимает много видимых строк. Цвет подсказки берётся из подсветки\n"
            + "самого закрывающего слова. Подсказка не показывается, если справа от закрывающего\n"
            + "слова есть ещё код/комментарий, или если на её строке стоит каретка."); //$NON-NLS-1$

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
            + "вообще не видна на экране (прокручена или свёрнута), подсказка показывается всегда,\n"
            + "независимо от этого значения."; //$NON-NLS-1$
        setFieldTooltip(bracketHintMinLinesField, bracketHintMinLinesTooltip);
        Text bracketHintMinLinesText = bracketHintMinLinesField.getTextControl(codeEditorGroup);
        bracketHintMinLinesText.setToolTipText(bracketHintMinLinesTooltip);
        GridData bracketHintMinLinesTextData = new GridData();
        bracketHintMinLinesTextData.widthHint = 40;
        bracketHintMinLinesTextData.grabExcessHorizontalSpace = false;
        bracketHintMinLinesTextData.horizontalAlignment = SWT.LEFT;
        bracketHintMinLinesText.setLayoutData(bracketHintMinLinesTextData);

        // BooleanFieldEditor.createControl() подменяет layout родителя на GridLayout —
        // отдельный host, иначе ломается сетка группы «Редактор кода».
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
        createLoggingGroup();

        // Поле «Символы» намеренно не добавляется:
        // значение задано константой ContentAssistSettings.CHARSET_VALUE
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
            SpellCheckHook.openCommonUserMorphDictionaryInEclipseEditor();
        });
    }

    /** Режим «По проекту»: только ссылка на словарь проекта. */
    private void createProjectDictionarySection(Composite parent)
    {
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
     * URL «Создать заявку» с предзаполненным телом — версия плагина, версия
     * 1C:EDT и ОС, чтобы не просить об этом отдельно в каждой заявке.
     */
    private static String buildNewIssueUrl()
    {
        StringBuilder body = new StringBuilder();
        body.append("**Версия плагина Комфорт:** ") //$NON-NLS-1$
            .append(ComfortVersionInfo.installed().getDisplayVersion()).append("\n") //$NON-NLS-1$
            .append("**Версия 1C:EDT:** ").append(getEdtVersion()).append("\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**ОС:** ").append(getOsInfo()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$

        String irInfo = getIrTechnicalInfo();
        if (irInfo != null && !irInfo.isBlank())
            body.append("**ИР:**\n```\n").append(irInfo.strip()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
        body.append("\n"); //$NON-NLS-1$

        String encodedBody = java.net.URLEncoder.encode(body.toString(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20"); //$NON-NLS-1$ //$NON-NLS-2$
        return NEW_ISSUE_URL + "?body=" + encodedBody; //$NON-NLS-1$
    }

    /**
     * {@code ирКлиент.ТехническаяИнформацияЛкс()}, если ИР подключён. {@code null},
     * если подключения нет или вызов не удался — заявка всё равно должна открыться.
     */
    private static String getIrTechnicalInfo()
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

    private static String getEdtVersion()
    {
        try
        {
            org.eclipse.core.runtime.IProduct product = org.eclipse.core.runtime.Platform.getProduct();
            if (product != null && product.getDefiningBundle() != null)
                return product.getDefiningBundle().getVersion().toString();
        }
        catch (RuntimeException ignored)
        {
            // версия продукта недоступна
        }
        return "—"; //$NON-NLS-1$
    }

    private static String getOsInfo()
    {
        return System.getProperty("os.name", "?") + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + System.getProperty("os.version", "?") + " (" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + System.getProperty("os.arch", "?") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Устанавливает tooltip на управляющий элемент {@link FieldEditor}.
     * Для {@link BooleanFieldEditor} — на чекбокс и подпись.
     */
    private void setFieldTooltip(FieldEditor field, String tooltip)
    {
        setFieldTooltip(field, tooltip, getFieldEditorParent());
    }

    private void setFieldTooltip(FieldEditor field, String tooltip, Composite parent)
    {
        if (parent == null || parent.isDisposed())
            return;
        parent.getDisplay().asyncExec(() -> applyFieldTooltip(field, tooltip, parent));
    }

    private void applyFieldTooltip(FieldEditor field, String tooltip, Composite parent)
    {
        if (parent.isDisposed() || field == null)
            return;
        try
        {
            java.lang.reflect.Method changeMethod = field.getClass().getDeclaredMethod(
                "getChangeControl", Composite.class); //$NON-NLS-1$
            changeMethod.setAccessible(true);
            Control change = (Control) changeMethod.invoke(field, parent);
            if (change != null && !change.isDisposed())
                change.setToolTipText(tooltip);
        }
        catch (Exception ignored)
        {
            // getChangeControl недоступен
        }
        try
        {
            java.lang.reflect.Method labelMethod = field.getClass().getDeclaredMethod(
                "getLabelControl", Composite.class); //$NON-NLS-1$
            labelMethod.setAccessible(true);
            Control label = (Control) labelMethod.invoke(field, parent);
            if (label != null && !label.isDisposed())
                label.setToolTipText(tooltip);
        }
        catch (Exception ignored)
        {
            // getLabelControl недоступен
        }
    }

    @Override
    public boolean performOk()
    {
        boolean result = super.performOk();
        if (result)
        {
            SmartMatchHighlight.clearColorCache();
            BslServerCallHighlightingHook.refreshAllEditors();
            BracketContentHintHook.refreshAllEditors();
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
}