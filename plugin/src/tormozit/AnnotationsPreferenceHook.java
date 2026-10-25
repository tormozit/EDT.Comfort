package tormozit;

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.IElementComparer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Страница «Аннотации» окна «Параметры» (Общие → Редакторы → Текстовые редакторы
 * → Аннотации, {@code org.eclipse.ui.internal.editors.text.AnnotationsPreferencePage},
 * блок {@code AnnotationsConfigurationBlock}; пакет internal — доступ только
 * рефлексией), [#421](https://github.com/tormozit/EDT.Comfort/issues/421):
 *
 * <p>1. <b>Запоминание текущей строки.</b> Штатно {@code initialize()} после
 * создания страницы всегда асинхронно выбирает <i>первую</i> строку списка.
 * Комфорт запоминает метку последней выбранной строки ({@code ListItem.label},
 * {@link IDialogSettings} — переживает перезапуск EDT) и восстанавливает её при
 * открытии страницы <b>без</b> параметра: окно «Параметры», контекстное меню
 * вертикальной линейки, гиперссылки с других страниц. Если же страница открыта
 * командой «Параметры...» из контекстного меню линейки обзора, она получает
 * строку-параметр (метку аннотации под курсором): её кладёт в
 * {@code FilteredPreferenceDialog.pageData} метод
 * {@code PreferencesUtil.createPreferenceDialogOn}, и {@code applyData} выбирает
 * переданную строку сам — при {@code pageData != null} Комфорт не вмешивается.
 * Выбор строки — тоже {@code asyncExec}, но позже штатного (штатный ставится в
 * очередь при создании страницы, наш — после), поэтому наш выполняется последним
 * и побеждает; штатный выбор первой строки успевает отработать до нашего.
 *
 * <p>2–3. <b>Сторона линейки в подписи.</b> «Линейка обзора» → «Линейка обзора
 * (справа)», «Вертикальная линейка» → «Вертикальная линейка (слева)»: у обеих
 * линеек имена похожи, а сторона — главное различие для пользователя. Суффикс
 * дописывается к текущему тексту кнопки (мнемоник {@code &} из NLS сохраняется);
 * не-русская локаль не трогается. Раскладка страницы уже рассчитана по коротким
 * подписям, поэтому после переименования пересчитывается вся раскладка окна —
 * иначе новый текст обрезается шириной флажка (как на странице «Орфография»).
 *
 * <p>4. <b>Подсказки у всех флажков</b> ({@link TooltipText#wrap} — нативная
 * подсказка Windows сама строки не переносит).
 *
 * <p>5. В списке оставляются настройки вхождений и деклараций Xtext/BSL:
 * добавленные JDT строки используют те же ключи оформления в общем хранилище (#573).
 *
 * <p>6. После изменения цвета «Вхождений» оформление открытых BSL-модулей
 * перечитывается из настроек без переоткрытия редакторов (#573).
 */
public final class AnnotationsPreferenceHook implements IStartup
{
    private static final String PAGE_CLASS_NAME =
        "org.eclipse.ui.internal.editors.text.AnnotationsPreferencePage"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.annotationsPagePatched"; //$NON-NLS-1$

    private static final String SETTINGS_SECTION = "tormozit.AnnotationsPreferenceHook"; //$NON-NLS-1$

    private static final String KEY_SELECTED_ANNOTATION = "selectedAnnotation"; //$NON-NLS-1$

    private static final String VERTICAL_RULER_LABEL = "Вертикальная линейка"; //$NON-NLS-1$
    private static final String VERTICAL_RULER_SUFFIX = " (слева)"; //$NON-NLS-1$
    private static final String OVERVIEW_RULER_LABEL = "Линейка обзора"; //$NON-NLS-1$
    private static final String OVERVIEW_RULER_SUFFIX = " (справа)"; //$NON-NLS-1$

    private static final String TOOLTIP_VERTICAL_RULER =
        "Показывать аннотации выбранного типа маркерами на вертикальной линейке слева от текста редактора."; //$NON-NLS-1$

    private static final String TOOLTIP_OVERVIEW_RULER =
        "Показывать аннотации выбранного типа цветными метками на линейке обзора справа от текста редактора. Щелчок по метке прокручивает текст к аннотации."; //$NON-NLS-1$

    private static final String TOOLTIP_SHOW_IN_TEXT =
        "Показывать аннотации выбранного типа прямо в тексте редактора. Способ отображения выбирается в соседнем списке: подсветка, тильда, подчёркивание и другие."; //$NON-NLS-1$

    private static final String TOOLTIP_NAVIGATION_TARGET =
        "Команды перехода к следующей и предыдущей аннотации останавливаются в том числе на аннотациях выбранного типа."; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 30;

    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingWiring = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        EditorsUI.getPreferenceStore().addPropertyChangeListener(event ->
        {
            if ("occurrenceIndicationColor".equals(event.getProperty())) //$NON-NLS-1$
                display.asyncExec(AnnotationsPreferenceHook::refreshOpenModuleOccurrenceColors);
        });

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            if (current.getData() instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
    {
        synchronized (pendingWiring)
        {
            if (Boolean.TRUE.equals(pendingWiring.get(shell)))
                return;
            pendingWiring.put(shell, Boolean.TRUE);
        }

        IPageChangedListener pageListener = event -> tryPatchSelected(dialog, event.getSelectedPage());
        dialog.addPageChangedListener(pageListener);
        scheduleRetry(display, shell, dialog, 0);
    }

    private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (tryPatchSelected(dialog, dialog.getSelectedPage()) || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
    }

    private static boolean tryPatchSelected(PreferenceDialog dialog, Object selected)
    {
        if (!(selected instanceof IPreferencePage page) || !PAGE_CLASS_NAME.equals(page.getClass().getName()))
            return true;
        return tryPatch(page, dialog);
    }

    /** @return {@code true}, если страница не наша, уже пропатчена или пропатчена сейчас. */
    private static boolean tryPatch(IPreferencePage page, PreferenceDialog dialog)
    {
        try
        {
            Object block = Global.getField(page, "fConfigurationBlock"); //$NON-NLS-1$
            if (block == null)
            {
                Debug.log("tryPatch WAIT: fConfigurationBlock=null"); //$NON-NLS-1$
                return false;
            }

            Object viewerObj = Global.getField(block, "fAnnotationTypeViewer"); //$NON-NLS-1$
            if (!(viewerObj instanceof StructuredViewer viewer))
            {
                Debug.log("tryPatch WAIT: fAnnotationTypeViewer not ready"); //$NON-NLS-1$
                return false;
            }
            Control tableControl = viewer.getControl();
            if (tableControl == null || tableControl.isDisposed())
            {
                Debug.log("tryPatch WAIT: table control not ready"); //$NON-NLS-1$
                return false;
            }
            if (Boolean.TRUE.equals(tableControl.getData(PATCHED_KEY)))
                return true;

            enhanceCheckboxes(block);
            hideDuplicateOccurrences(viewer, block, "org.eclipse.xtext.ui.editor.defaultOccurrenceAnnotation"); //$NON-NLS-1$
            hideDuplicateOccurrences(viewer, block, "org.eclipse.xtext.ui.editor.declarationAnnotation"); //$NON-NLS-1$
            rememberSelectionOnRowChange(viewer);
            restoreRememberedRow(viewer, block, dialog);

            tableControl.setData(PATCHED_KEY, Boolean.TRUE);
            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Debug.log("tryPatch EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
    }

    /** Перечитывает оформление аннотаций, как при install(), сохраняя документ и редактор. */
    private static void refreshOpenModuleOccurrenceColors()
    {
        Set<BslXtextEditor> updated = new HashSet<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference reference : page.getEditorReferences())
                {
                    IEditorPart part = reference.getEditor(false);
                    BslXtextEditor editor = GetRef.getActiveBslEditor(part);
                    if (editor == null || !updated.add(editor))
                        continue;
                    if (editor.getInternalSourceViewer() == null
                        || editor.getInternalSourceViewer().getTextWidget() == null
                        || editor.getInternalSourceViewer().getTextWidget().isDisposed())
                        continue;
                    try
                    {
                        Object object = Global.getField(editor, "fSourceViewerDecorationSupport"); //$NON-NLS-1$
                        if (!(object instanceof SourceViewerDecorationSupport support))
                            continue;
                        Global.invokeVoid(support, "updateTextDecorations"); //$NON-NLS-1$
                        support.updateOverviewDecorations();
                    }
                    catch (RuntimeException e)
                    {
                    }
                }
            }
        }
    }

    /** Убирает только строки с полностью совпадающими ключами выбранной настройки Xtext. */
    private static void hideDuplicateOccurrences(StructuredViewer viewer, Object block, String annotationType)
    {
        AnnotationPreference preference = EditorsUI.getAnnotationPreferenceLookup()
            .getAnnotationPreference(annotationType);
        Object model = Global.getField(block, "fListModel"); //$NON-NLS-1$
        if (preference == null || !(model instanceof Object[] items))
            return;
        String label = preference.getPreferenceLabel();
        Object retained = null;
        for (Object item : items)
        {
            if ((Objects.equals(label, labelOf(item))
                || Objects.equals(label + " (" + annotationType + ")", labelOf(item))) //$NON-NLS-1$ //$NON-NLS-2$
                && Objects.equals(preference.getColorPreferenceKey(), Global.getField(item, "colorKey"))) //$NON-NLS-1$
            {
                retained = item;
                break;
            }
        }
        if (retained == null)
            return;
        Set<Object> hidden = new HashSet<>();
        String[] keys = { "colorKey", "textKey", "highlightKey", "overviewRulerKey", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "verticalRulerKey", "textStyleKey", "isNextPreviousNavigationKey" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Object item : items)
        {
            if (item == retained)
                continue;
            boolean same = true;
            for (String key : keys)
                same &= Objects.equals(Global.getField(retained, key), Global.getField(item, key));
            if (same)
                hidden.add(item);
        }
        if (hidden.isEmpty())
            return;
        Object retainedItem = retained;
        // applyData мог уже поставить выбор старого элемента в asyncExec.
        // Сопоставляем его с оставшейся строкой до штатного обработчика выбора.
        IElementComparer originalComparer = viewer.getComparer();
        viewer.setComparer(new IElementComparer()
        {
            @Override
            public boolean equals(Object first, Object second)
            {
                Object left = hidden.contains(first) ? retainedItem : first;
                Object right = hidden.contains(second) ? retainedItem : second;
                return originalComparer == null ? Objects.equals(left, right) : originalComparer.equals(left, right);
            }

            @Override
            public int hashCode(Object element)
            {
                Object canonical = hidden.contains(element) ? retainedItem : element;
                return originalComparer == null ? Objects.hashCode(canonical) : originalComparer.hashCode(canonical);
            }
        });
        Object selected = viewer.getStructuredSelection().getFirstElement();
        if (selected == null || hidden.contains(selected))
            viewer.setSelection(new StructuredSelection(retainedItem), true);
        Object[] filtered = (Object[]) Array.newInstance(items.getClass().getComponentType(), items.length - hidden.size());
        int index = 0;
        for (Object item : items)
        {
            if (!hidden.contains(item))
                filtered[index++] = item;
        }
        if (!Global.setFieldForce(block, "fListModel", filtered)) //$NON-NLS-1$
            throw new IllegalStateException("Cannot update annotation list model"); //$NON-NLS-1$
        viewer.setInput(filtered);
        String remembered = dialogSettings().get(KEY_SELECTED_ANNOTATION);
        for (Object item : hidden)
        {
            if (Objects.equals(remembered, labelOf(item)))
                dialogSettings().put(KEY_SELECTED_ANNOTATION, labelOf(retainedItem));
        }
    }

    /** Суффиксы стороны у линеек и подсказки всем флажкам страницы. */
    private static void enhanceCheckboxes(Object block)
    {
        Button verticalButton = asButton(Global.getField(block, "fShowInVerticalRulerCheckBox")); //$NON-NLS-1$
        Button overviewButton = asButton(Global.getField(block, "fShowInOverviewRulerCheckBox")); //$NON-NLS-1$

        boolean renamed = false;
        if (verticalButton != null)
        {
            renamed |= appendRulerSuffix(verticalButton, VERTICAL_RULER_LABEL, VERTICAL_RULER_SUFFIX);
            verticalButton.setToolTipText(TooltipText.wrap(verticalButton, TOOLTIP_VERTICAL_RULER));
        }

        if (overviewButton != null)
        {
            renamed |= appendRulerSuffix(overviewButton, OVERVIEW_RULER_LABEL, OVERVIEW_RULER_SUFFIX);
            overviewButton.setToolTipText(TooltipText.wrap(overviewButton, TOOLTIP_OVERVIEW_RULER));
        }

        Object showInTextCheck = Global.getField(block, "fShowInTextCheckBox"); //$NON-NLS-1$
        if (showInTextCheck instanceof Button textButton)
            textButton.setToolTipText(TooltipText.wrap(textButton, TOOLTIP_SHOW_IN_TEXT));

        Object navigationCheck = Global.getField(block, "fIsNextPreviousTargetCheckBox"); //$NON-NLS-1$
        if (navigationCheck instanceof Button navigationButton)
            navigationButton.setToolTipText(TooltipText.wrap(navigationButton, TOOLTIP_NAVIGATION_TARGET));

        // Раскладка страницы уже рассчитана по коротким NLS-подписям — без пересчёта
        // дописанный текст обрезается шириной флажка.
        if (renamed)
        {
            Button anyRulerButton = verticalButton != null ? verticalButton : overviewButton;
            if (anyRulerButton != null)
                relayout(anyRulerButton);
        }
    }

    private static Button asButton(Object obj)
    {
        return obj instanceof Button button ? button : null;
    }

    /** Пересчитывает раскладку окна «Параметры» под новую ширину подписей (как страница «Орфография»). */
    private static void relayout(Control control)
    {
        Shell shell = control.getShell();
        if (shell != null && !shell.isDisposed())
            shell.layout(true, true);
    }

    /** Дописывает {@code suffix}, если подпись — известная русская метка линейки; мнемоник сохраняется. */
    private static boolean appendRulerSuffix(Button button, String labelFragment, String suffix)
    {
        String text = button.getText();
        if (text == null || !text.contains(labelFragment) || text.contains(suffix))
            return false;
        button.setText(text + suffix);
        return true;
    }

    /** Запоминает метку выбираемой строки — и при переходе пользователя, и при восстановлении. */
    private static void rememberSelectionOnRowChange(StructuredViewer viewer)
    {
        viewer.addSelectionChangedListener(event ->
        {
            Object element = viewer.getStructuredSelection().getFirstElement();
            String label = labelOf(element);
            if (label == null || label.isEmpty())
                return;
            dialogSettings().put(KEY_SELECTED_ANNOTATION, label);
        });
    }

    /**
     * Восстанавливает запомненную строку, только если страница открыта без параметра
     * ({@code pageData == null}); см. javadoc класса.
     */
    private static void restoreRememberedRow(StructuredViewer viewer, Object block, PreferenceDialog dialog)
    {
        if (Global.getField(dialog, "pageData") != null) //$NON-NLS-1$
            return;
        String remembered = dialogSettings().get(KEY_SELECTED_ANNOTATION);
        if (remembered == null || remembered.isEmpty())
            return;
        Object item = findItemByLabel(block, remembered);
        if (item == null)
            return;
        Display display = viewer.getControl().getDisplay();
        display.asyncExec(() ->
        {
            if (viewer.getControl() == null || viewer.getControl().isDisposed())
                return;
            viewer.setSelection(new StructuredSelection(item), true);
        });
    }

    private static Object findItemByLabel(Object block, String label)
    {
        Object model = Global.getField(block, "fListModel"); //$NON-NLS-1$
        if (!(model instanceof Object[] items))
            return null;
        for (Object item : items)
        {
            if (label.equals(labelOf(item)))
                return item;
        }
        return null;
    }

    private static String labelOf(Object listItem)
    {
        Object label = Global.getField(listItem, "label"); //$NON-NLS-1$
        return label instanceof String text ? text : null;
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static final class Debug
    {
        private static final String TAG = "AnnotationsPreferenceHook"; //$NON-NLS-1$

        private Debug()
        {
        }

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
