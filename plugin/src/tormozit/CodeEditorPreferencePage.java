package tormozit;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/** Страница «Параметры → Комфорт → Редактор кода». */
public final class CodeEditorPreferencePage extends ComfortPreferencePage
{
    @Override
    protected void createFieldEditors()
    {
        Composite parent = getFieldEditorParent();
        BooleanFieldEditor autoOpenField = new BooleanFieldEditor(
            ContentAssistSettings.PREF_ENABLED,
            ComfortVisibleSettings.label(ContentAssistSettings.PREF_ENABLED),
            parent);
        addField(autoOpenField);
        setFieldTooltip(autoOpenField,
            "Открывать список автодополнения и описание параметров метода при вводе символов", //$NON-NLS-1$
            parent);

        IntegerFieldEditor timeoutField = new IntegerFieldEditor(
            ContentAssistSettings.PREF_TIMEOUT,
            ComfortVisibleSettings.label(ContentAssistSettings.PREF_TIMEOUT),
            parent,
            5);
        timeoutField.setValidRange(0, 10_000);
        addField(timeoutField);
        Text timeoutText = timeoutField.getTextControl(parent);
        GridData timeoutTextData = new GridData();
        timeoutTextData.widthHint = 40;
        timeoutTextData.grabExcessHorizontalSpace = false;
        timeoutTextData.horizontalAlignment = SWT.LEFT;
        timeoutText.setLayoutData(timeoutTextData);
        // Смещение вправо: поле зависит от флажка выше.
        GridData timeoutLabelData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        timeoutLabelData.horizontalIndent = 20;
        timeoutField.getLabelControl(parent).setLayoutData(timeoutLabelData);

        BooleanFieldEditor naparnikRowField = new BooleanFieldEditor(
            ComfortSettings.PREF_NAPARNIK_ASSIST_ROW_ENABLED,
            ComfortVisibleSettings.label(ComfortSettings.PREF_NAPARNIK_ASSIST_ROW_ENABLED),
            parent);
        addField(naparnikRowField);
        setFieldTooltip(naparnikRowField,
            "Первая строка списка — предложение 1С:Напарника (запрос к серверу при каждом открытии списка).\n" //$NON-NLS-1$
            + "Enter — верхняя строка предложения, Ctrl+Enter — всё, Tab — очередное слово.\n" //$NON-NLS-1$
            + "Нужны флажок «Улучшать списки» и включённое завершение кода в «Параметры → 1С:Напарник».", //$NON-NLS-1$
            parent);

        BooleanFieldEditor collapseParamsField = new BooleanFieldEditor(
            ComfortSettings.PREF_COLLAPSE_ASSIST_PARAMS,
            ComfortVisibleSettings.label(ComfortSettings.PREF_COLLAPSE_ASSIST_PARAMS),
            parent);
        addField(collapseParamsField);
        setFieldTooltip(collapseParamsField,
            "В подписи метода в списке автодополнения показывать вместо имён параметров их число: "
            + "«Метод(3)» вместо «Метод(Параметр1, Параметр2, Параметр3)».", //$NON-NLS-1$
            parent);

        IntegerFieldEditor sideHintDelayField = new IntegerFieldEditor(
            ComfortSettings.PREF_ASSIST_SIDE_HINT_DELAY,
            ComfortVisibleSettings.label(ComfortSettings.PREF_ASSIST_SIDE_HINT_DELAY),
            parent,
            5);
        sideHintDelayField.setValidRange(0, ComfortSettings.MAX_ASSIST_SIDE_HINT_DELAY);
        addField(sideHintDelayField);
        String sideHintDelayTooltip =
            "Пауза после смены строки списка автодополнения, после которой обновляется его боковая "
            + "подсказка. Штатно в EDT — 500 мс."; //$NON-NLS-1$
        setFieldTooltip(sideHintDelayField, sideHintDelayTooltip, parent);
        Text sideHintDelayText = sideHintDelayField.getTextControl(parent);
        sideHintDelayText.setToolTipText(TooltipText.wrap(sideHintDelayText, sideHintDelayTooltip));
        GridData sideHintDelayTextData = new GridData();
        sideHintDelayTextData.widthHint = 40;
        sideHintDelayTextData.grabExcessHorizontalSpace = false;
        sideHintDelayTextData.horizontalAlignment = SWT.LEFT;
        sideHintDelayText.setLayoutData(sideHintDelayTextData);

        Link choiceStatisticsLink = new Link(parent, SWT.NONE);
        choiceStatisticsLink.setText("<a>Статистика выбора слов автодополнения</a>"); //$NON-NLS-1$
        GridData choiceStatisticsLinkData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        choiceStatisticsLinkData.horizontalSpan = 2;
        choiceStatisticsLink.setLayoutData(choiceStatisticsLinkData);
        choiceStatisticsLink.setToolTipText(TooltipText.wrap(choiceStatisticsLink,
            "Сколько раз какое слово платформы вставлено из списка автодополнения в каждом контексте. "
            + "Часто выбираемые слова поднимаются в списке. Статистика слов метаданных — "
            + "в Свойствах проекта → Комфорт.")); //$NON-NLS-1$
        choiceStatisticsLink.addListener(SWT.Selection,
            event -> BslExpectedTypePremiums.openChoiceStatistics(choiceStatisticsLink.getShell(), null));

        BooleanFieldEditor serverCallField = new BooleanFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED,
            ComfortVisibleSettings.label(ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED),
            parent);
        addField(serverCallField);
        setFieldTooltip(serverCallField,
            "Подсвечивать серверные вызовы в клиентском коде особыми цветами", //$NON-NLS-1$
            parent);

        ThemeAwareColorFieldEditor serverCallColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR,
            ComfortVisibleSettings.label(ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR),
            parent);
        addField(serverCallColorField);
        setFieldTooltip(serverCallColorField, SERVER_CALL_COLOR_TOOLTIP, parent);
        indentUnderCheckbox(serverCallColorField, parent);

        ThemeAwareColorFieldEditor serverCallContextColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR,
            ComfortVisibleSettings.label(ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR),
            parent);
        addField(serverCallContextColorField);
        setFieldTooltip(serverCallContextColorField, SERVER_CALL_CONTEXT_COLOR_TOOLTIP, parent);
        indentUnderCheckbox(serverCallContextColorField, parent);

        BooleanFieldEditor implicitVariableField = new BooleanFieldEditor(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED,
            ComfortVisibleSettings.label(ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED),
            parent);
        addField(implicitVariableField);
        setFieldTooltip(implicitVariableField,
            "Подсвечивать особым цветом имя переменной в месте создания: первое присваивание и переменная цикла Для / Для Каждого", //$NON-NLS-1$
            parent);

        ThemeAwareColorFieldEditor implicitVariableColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR,
            ComfortVisibleSettings.label(ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR),
            parent);
        addField(implicitVariableColorField);
        setFieldTooltip(implicitVariableColorField, IMPLICIT_VARIABLE_COLOR_TOOLTIP, parent);
        indentUnderCheckbox(implicitVariableColorField, parent);

        Composite queryLiteralHost = new Composite(parent, SWT.NONE);
        queryLiteralHost.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        BooleanFieldEditor queryLiteralField = new BooleanFieldEditor(
            ComfortSettings.PREF_QUERY_LITERAL_HIGHLIGHTING_ENABLED,
            ComfortVisibleSettings.label(ComfortSettings.PREF_QUERY_LITERAL_HIGHLIGHTING_ENABLED),
            queryLiteralHost);
        addField(queryLiteralField);
        setFieldTooltip(queryLiteralField,
            "Раскрашивать текст запроса (литерал, начинающийся с ВЫБРАТЬ или УНИЧТОЖИТЬ) и подчёркивать в нём ошибки прямо при наборе.\n" //$NON-NLS-1$
            + "Отметки хаба проверки запросов в таких литералах заменяются живыми.", //$NON-NLS-1$
            queryLiteralHost);

        Link queryCheckLink = new Link(parent, SWT.NONE);
        queryCheckLink.setText("<a>Настройка автопроверки</a>"); //$NON-NLS-1$
        queryCheckLink.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        queryCheckLink.setToolTipText(TooltipText.wrap(queryCheckLink,
            "Открыть настройку автопроверки запросов в модуле для активного проекта")); //$NON-NLS-1$
        queryCheckLink.addListener(SWT.Selection, event ->
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IProject project = Global.getActiveProject(
                window != null && window.getActivePage() != null ? window.getActivePage().getActivePart() : null,
                true);
            if (project != null)
                ProblemViewHook.openCheckSettings(queryCheckLink.getShell(), project, "bsl-ql-hub"); //$NON-NLS-1$
        });

        BooleanFieldEditor bracketHintField = new BooleanFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            ComfortVisibleSettings.label(ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED),
            parent);
        addField(bracketHintField);
        setFieldTooltip(bracketHintField,
            "Показывать начало блочной конструкции (Процедура, Если, Пока, Для, Попытка, #Область, #Если) "
            + "полупрозрачным текстом рядом с её закрывающим словом (КонецПроцедуры, КонецЕсли и т.д.), "
            + "если конструкция занимает много видимых строк.", //$NON-NLS-1$
            parent);

        IntegerFieldEditor bracketHintMinLinesField = new IntegerFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES,
            ComfortVisibleSettings.label(ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES),
            parent,
            5);
        bracketHintMinLinesField.setValidRange(0, 10_000);
        addField(bracketHintMinLinesField);
        String bracketHintMinLinesTooltip =
            "Минимальное количество ВИДИМЫХ строк (с учётом свёрнутых блоков) между началом "
            + "и концом конструкции, при котором показывается подсказка. Если открывающая часть "
            + "вообще не видна, подсказка показывается всегда."; //$NON-NLS-1$
        setFieldTooltip(bracketHintMinLinesField, bracketHintMinLinesTooltip, parent);
        Text bracketHintMinLinesText = bracketHintMinLinesField.getTextControl(parent);
        bracketHintMinLinesText.setToolTipText(TooltipText.wrap(bracketHintMinLinesText, bracketHintMinLinesTooltip));
        GridData bracketHintMinLinesTextData = new GridData();
        bracketHintMinLinesTextData.widthHint = 40;
        bracketHintMinLinesTextData.grabExcessHorizontalSpace = false;
        bracketHintMinLinesTextData.horizontalAlignment = SWT.LEFT;
        bracketHintMinLinesText.setLayoutData(bracketHintMinLinesTextData);
        // Смещение вправо: поле зависит от флажка выше.
        GridData bracketHintMinLinesLabelData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        bracketHintMinLinesLabelData.horizontalIndent = 20;
        bracketHintMinLinesField.getLabelControl(parent).setLayoutData(bracketHintMinLinesLabelData);

        IntegerFieldEditor compileContextWidthField = new IntegerFieldEditor(
            ComfortSettings.PREF_COMPILE_CONTEXT_STATUS_WIDTH,
            ComfortVisibleSettings.label(ComfortSettings.PREF_COMPILE_CONTEXT_STATUS_WIDTH),
            parent,
            3);
        compileContextWidthField.setValidRange(0, 200);
        addField(compileContextWidthField);
        String compileContextWidthTooltip =
            "Ширина в символах поля строки состояния, которое показывает условия препроцессора "
            + "и директиву компиляции для позиции каретки в модуле. 0 — не показывать."; //$NON-NLS-1$
        setFieldTooltip(compileContextWidthField, compileContextWidthTooltip, parent);
        Text compileContextWidthText = compileContextWidthField.getTextControl(parent);
        compileContextWidthText.setToolTipText(TooltipText.wrap(compileContextWidthText, compileContextWidthTooltip));
        GridData compileContextWidthTextData = new GridData();
        compileContextWidthTextData.widthHint = 40;
        compileContextWidthTextData.grabExcessHorizontalSpace = false;
        compileContextWidthTextData.horizontalAlignment = SWT.LEFT;
        compileContextWidthText.setLayoutData(compileContextWidthTextData);

        StringFieldEditor autoCollapseRegionsField = new StringFieldEditor(
            ComfortSettings.PREF_AUTO_COLLAPSE_REGIONS,
            ComfortVisibleSettings.label(ComfortSettings.PREF_AUTO_COLLAPSE_REGIONS),
            parent);
        addField(autoCollapseRegionsField);
        String autoCollapseRegionsTooltip =
            "Имена областей (#Область) через запятую, например: АФВ, БСП.\n"
            + "Такие области сворачиваются при открытии модуля и по команде "
            + "«Сбросить сворачиваемые группы»."; //$NON-NLS-1$
        setFieldTooltip(autoCollapseRegionsField, autoCollapseRegionsTooltip, parent);
        Text autoCollapseRegionsText = autoCollapseRegionsField.getTextControl(parent);
        autoCollapseRegionsText.setToolTipText(TooltipText.wrap(autoCollapseRegionsText, autoCollapseRegionsTooltip));
        GridData autoCollapseRegionsTextData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        autoCollapseRegionsTextData.widthHint = 200;
        autoCollapseRegionsText.setLayoutData(autoCollapseRegionsTextData);

        // BooleanFieldEditor.createControl() подменяет layout родителя на GridLayout —
        // отдельный host, иначе ломается сетка страницы.
        if (ComfortJdtAvailability.isJdtUiAvailable())
        {
            Composite spellingIdentsHost = new Composite(parent, SWT.NONE);
            GridData spellingIdentsHostData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            spellingIdentsHostData.horizontalSpan = 2;
            spellingIdentsHost.setLayoutData(spellingIdentsHostData);
            BooleanFieldEditor spellingIdentsField = new BooleanFieldEditor(
                ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE,
                ComfortVisibleSettings.label(ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE),
                spellingIdentsHost);
            addField(spellingIdentsField);
            setFieldTooltip(spellingIdentsField,
                "При включённой орфографии Comfort (словарь «Русский/Английский (Комфорт-HUNSPELL)») "
                + "проверять в видимой области модуля имена (идентификаторы) и строковые литералы.\n"
                + "Если выключено — проверяются только обычные слова в комментариях и литералы внутри НСтр(); "
                + "слова с заглавной буквой не на первой позиции (как в CamelCase) пропускаются.", //$NON-NLS-1$
                spellingIdentsHost);
        }

        BooleanFieldEditor economicalCommentField = new BooleanFieldEditor(
            ComfortSettings.PREF_ECONOMICAL_METHOD_COMMENT,
            ComfortVisibleSettings.label(ComfortSettings.PREF_ECONOMICAL_METHOD_COMMENT),
            parent);
        addField(economicalCommentField);
        setFieldTooltip(economicalCommentField,
            "В этом режиме добавляется минимально необходимый комментарий для указания типа функции или параметра.", //$NON-NLS-1$
            parent);
    }
}
