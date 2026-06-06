package tormozit;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Страница настроек «Автооткрытие подсказки» в разделе
 * «Параметры → V8 → Встроенный язык».
 *
 * <p>Отображает только «Включено» и «Задержка».
 * Поле «Символы» скрыто — его значение захардкожено в
 * {@link ContentAssistSettings#CHARSET_VALUE}.
 */
public class ContentAssistPreferencePage
        extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage
{
    public ContentAssistPreferencePage()
    {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench)
    {
        setPreferenceStore(
            ContentAssistSettings.getInstance().getPreferenceStore());
        setDescription("Настройка автоматического открытия списка подсказок " +
            "в BSL-редакторе при вводе символов.");
    }

    @Override
    protected void createFieldEditors()
    {
        addField(new BooleanFieldEditor(
            ContentAssistSettings.PREF_ENABLED,
            "Включено",
            getFieldEditorParent()));

        IntegerFieldEditor timeoutField = new IntegerFieldEditor(
            ContentAssistSettings.PREF_TIMEOUT,
            "Задержка (мс)",
            getFieldEditorParent());
        timeoutField.setValidRange(0, 10_000);
        addField(timeoutField);

        // Поле «Символы» намеренно не добавляется:
        // значение задано константой ContentAssistSettings.CHARSET_VALUE
    }
}
