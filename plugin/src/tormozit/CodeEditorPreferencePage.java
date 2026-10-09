package tormozit;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

/** Страница «Параметры → Комфорт → Редактор кода». */
public final class CodeEditorPreferencePage extends ComfortPreferencePage
{
    @Override
    protected void createFieldEditors()
    {
        Composite parent = getFieldEditorParent();
        BooleanFieldEditor autoOpenField = new BooleanFieldEditor(
            ContentAssistSettings.PREF_ENABLED,
            "Автооткрытие подсказок при вводе",
            parent);
        addField(autoOpenField);
        setFieldTooltip(autoOpenField,
            "Открывать список автодополнения и описание параметров метода при вводе символов", //$NON-NLS-1$
            parent);

        IntegerFieldEditor timeoutField = new IntegerFieldEditor(
            ContentAssistSettings.PREF_TIMEOUT,
            "Автооткрытие: Задержка (мс)",
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

        BooleanFieldEditor serverCallField = new BooleanFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED,
            "Подсвечивать серверные вызовы", //$NON-NLS-1$
            parent);
        addField(serverCallField);
        setFieldTooltip(serverCallField,
            "Подсвечивать серверные вызовы в клиентском коде особыми цветами", //$NON-NLS-1$
            parent);

        ThemeAwareColorFieldEditor serverCallColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR,
            "Цвет серверных вызовов:", //$NON-NLS-1$
            parent);
        addField(serverCallColorField);
        setFieldTooltip(serverCallColorField, SERVER_CALL_COLOR_TOOLTIP, parent);
        indentUnderCheckbox(serverCallColorField, parent);

        ThemeAwareColorFieldEditor serverCallContextColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR,
            "Цвет серверных вызовов с контекстом:", //$NON-NLS-1$
            parent);
        addField(serverCallContextColorField);
        setFieldTooltip(serverCallContextColorField, SERVER_CALL_CONTEXT_COLOR_TOOLTIP, parent);
        indentUnderCheckbox(serverCallContextColorField, parent);

        BooleanFieldEditor implicitVariableField = new BooleanFieldEditor(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED,
            "Подсвечивать создаваемые переменные", //$NON-NLS-1$
            parent);
        addField(implicitVariableField);
        setFieldTooltip(implicitVariableField,
            "Подсвечивать особым цветом имя переменной в месте создания: первое присваивание и переменная цикла Для / Для Каждого", //$NON-NLS-1$
            parent);

        ThemeAwareColorFieldEditor implicitVariableColorField = new ThemeAwareColorFieldEditor(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR,
            "Цвет создаваемых переменных:", //$NON-NLS-1$
            parent);
        addField(implicitVariableColorField);
        setFieldTooltip(implicitVariableColorField, IMPLICIT_VARIABLE_COLOR_TOOLTIP, parent);
        indentUnderCheckbox(implicitVariableColorField, parent);

        BooleanFieldEditor bracketHintField = new BooleanFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            "Отображать начало конструкции в её конце", //$NON-NLS-1$
            parent);
        addField(bracketHintField);
        setFieldTooltip(bracketHintField,
            "Показывать начало блочной конструкции (Процедура, Если, Пока, Для, Попытка, #Область, #Если)\n"
            + "полупрозрачным текстом рядом с её закрывающим словом (КонецПроцедуры, КонецЕсли и т.д.),\n"
            + "если конструкция занимает много видимых строк.", //$NON-NLS-1$
            parent);

        IntegerFieldEditor bracketHintMinLinesField = new IntegerFieldEditor(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES,
            "Минимальное расстояние в строках", //$NON-NLS-1$
            parent,
            5);
        bracketHintMinLinesField.setValidRange(0, 10_000);
        addField(bracketHintMinLinesField);
        String bracketHintMinLinesTooltip =
            "Минимальное количество ВИДИМЫХ строк (с учётом свёрнутых блоков) между началом\n"
            + "и концом конструкции, при котором показывается подсказка. Если открывающая часть\n"
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
            "Ширина индикатора условий компиляции", //$NON-NLS-1$
            parent,
            3);
        compileContextWidthField.setValidRange(0, 200);
        addField(compileContextWidthField);
        String compileContextWidthTooltip =
            "Ширина в символах поля строки состояния, которое показывает условия препроцессора\n"
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
            "Автоматически сворачиваемые области", //$NON-NLS-1$
            parent);
        addField(autoCollapseRegionsField);
        String autoCollapseRegionsTooltip =
            "Имена областей (#Область) через запятую, например: АФВ, БСП.\n"
            + "Такие области сворачиваются при открытии модуля и по команде\n"
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
                "Проверять орфографию в идентификаторах в видимой области", //$NON-NLS-1$
                spellingIdentsHost);
            addField(spellingIdentsField);
            setFieldTooltip(spellingIdentsField,
                "При включённой орфографии Comfort (словарь «Русский/Английский (Комфорт-HUNSPELL)»)\n"
                + "проверять в видимой области модуля имена (идентификаторы) и строковые литералы.\n"
                + "Если выключено — проверяются только обычные слова в комментариях;\n"
                + "слова с заглавной буквой не на первой позиции (как в CamelCase) пропускаются.", //$NON-NLS-1$
                spellingIdentsHost);
        }
        else
            createInstallJdtSpellingLink(parent);
    }
}
