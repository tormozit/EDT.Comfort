package tormozit;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.graphics.Point;
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
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;

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
    private Text installedVersionText;
    private Text installedDateText;
    private Text latestVersionText;
    private Text latestDateText;
    private Link installedChangesLink;
    private Link latestChangesLink;
    private Link updateLink;

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
    public void createControl(Composite parent)
    {
        super.createControl(parent);
        refreshVersionSection();
        ComfortUpdateChecker.checkAsync(true, this::refreshVersionSection);
    }

    @Override
    protected void createFieldEditors()
    {
        createVersionSection();
        createKeysLink();

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

        createLoggingGroup();

        // Поле «Символы» намеренно не добавляется:
        // значение задано константой ContentAssistSettings.CHARSET_VALUE
    }

    private void createLoggingGroup()
    {
        Group loggingGroup = new Group(getFieldEditorParent(), SWT.NONE);
        loggingGroup.setText("Логирование");
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
            "Общее логирование",
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
            ContentAssistLog.showView();
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

    private void createKeysLink()
    {
        Composite parent = getFieldEditorParent();
        Link keysLink = new Link(parent, SWT.NONE);
        keysLink.setText("<a>Клавиши</a>"); //$NON-NLS-1$
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        gd.verticalIndent = 4;
        keysLink.setLayoutData(gd);
        keysLink.setToolTipText(
            "Перейти на страницу «Общие → Клавиши» с отбором по категории «Комфорт»"); //$NON-NLS-1$
        keysLink.addListener(SWT.Selection, e -> {
            if (!"Клавиши".equals(e.text)) //$NON-NLS-1$
                return;
            IPreferencePageContainer container = getContainer();
            if (container instanceof IWorkbenchPreferenceContainer wb)
            {
                wb.openPage(ComfortKeysPreferences.KEYS_PREFERENCE_PAGE_ID, null);
                ComfortKeysPreferences.applyCategoryFilterAsync();
            }
        });
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