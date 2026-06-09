package tormozit;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Страница настроек плагина Comfort в разделе
 * «Параметры → Комфорт (Tormozit)».
 *
 * <p>Поле «Символы» автооткрытия подсказки скрыто — его значение захардкожено в
 * {@link ContentAssistSettings#CHARSET_VALUE}.
 */
public class ComfortPreferencePage
        extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage
{
    private static final String REPLACE_LIST_FILTERS_TOOLTIP =
            "Текст фильтра будет дробиться на фрагменты пробелами и будет требоваться и подсвечиваться вхождение каждого фрагмента с мягким учетом порядка.\n"
            + "Влияет на навигатор, список баз, быструю схему модуля, диалоги выбора типа и открытия объекта метаданных, список автодополнения"; //$NON-NLS-1$

    public ComfortPreferencePage()
    {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench)
    {
        setPreferenceStore(
            ContentAssistSettings.getInstance().getPreferenceStore());
        setDescription("Настройки плагина Комфорт (Tormozit).");
    }

    @Override
    protected void createFieldEditors()
    {
        BooleanFieldEditor replaceListFiltersField = new BooleanFieldEditor(
            ComfortSettings.PREF_REPLACE_LIST_FILTERS,
            "Заменять фильтры в списках",
            getFieldEditorParent());
        addField(replaceListFiltersField);
        setFieldTooltip(replaceListFiltersField, REPLACE_LIST_FILTERS_TOOLTIP);

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
            "Автооткрытие подсказки",
            codeEditorGroup));

        IntegerFieldEditor timeoutField = new IntegerFieldEditor(
            ContentAssistSettings.PREF_TIMEOUT,
            "Автооткрытие подсказки: Задержка (мс)",
            codeEditorGroup);
        timeoutField.setValidRange(0, 10_000);
        addField(timeoutField);

        BooleanFieldEditor contentAssistLogField = new BooleanFieldEditor(
            ComfortSettings.PREF_CONTENT_ASSIST_LOG,
            "Журнал Content Assist (отладка фильтра)",
            codeEditorGroup);
        addField(contentAssistLogField);
        setFieldTooltip(contentAssistLogField,
            "Показывает окно с логом фильтра и popupSync.\n"
            + "Окно: Показать представление → Прочее → Журнал Content Assist"); //$NON-NLS-1$

        // Поле «Символы» намеренно не добавляется:
        // значение задано константой ContentAssistSettings.CHARSET_VALUE
    }

    /**
     * Устанавливает tooltip на управляющий элемент {@link FieldEditor}.
     * Для {@link BooleanFieldEditor} — на чекбокс.
     */
    private void setFieldTooltip(FieldEditor field, String tooltip)
    {
        try {
            java.lang.reflect.Method m = field.getClass().getDeclaredMethod(
                "getChangeControl", Composite.class); //$NON-NLS-1$
            m.setAccessible(true);
            Control ctrl = (Control) m.invoke(field, getFieldEditorParent());
            if (ctrl != null && !ctrl.isDisposed()) {
                ctrl.setToolTipText(tooltip);
            }
        } catch (Exception ignored) {
            // Если метод недоступен — tooltip просто не появится
        }
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
        hint.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));

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