package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaControlContext;

/**
 * Делает штатное окно настроек динамического списка
 * ({@code com._1c.g5.v8.dt.internal.form.ui.dynamiclist.aef.dialogs.DynamicListQueryDialog})
 * немодальным — чтобы при открытом окне можно было работать с навигатором и панелью
 * «Свойства» (issue #428, мотив 1C‑EDT #555). Дополнительно выводит в заголовок окна полный
 * путь к реквизиту, например {@code Справочник.Валюты.Форма.ФормаСписка.Реквизит.ДинамическийСписок}.
 *
 * <p>Модальность в SWT/Win32 полностью эмулируется: {@code Shell.setVisible(true)} добавляет
 * shell в {@code Display.modalShells[]}, {@code Control.isActive()} возвращает {@code false}
 * для прочих shell'ов, {@code Shell.updateModal()} делает {@code EnableWindow(handle, isActive())}.
 * Нативного модального стиля окна нет. Поэтому достаточно убрать бит {@link SWT#APPLICATION_MODAL}
 * из package‑private поля {@code Widget.style} и/или вызвать package‑private
 * {@code Display.clearModal(shell)}, после чего вернуть {@code EnableWindow} прочим shell'ам.
 *
 * <p>Перехват — двумя фильтрами {@link Display#addFilter}:
 * <ul>
 * <li>{@link SWT#Resize} — срабатывает в {@code Window.create()} (до {@code open()}), пока shell
 * ещё невиден и ещё не в {@code modalShells[]}: снимаем бит стиля заранее, модальность не
 * возникает вовсе;</li>
 * <li>{@link SWT#Show} — гарантированный fallback и обработка повторных показов: снимаем бит,
 * {@code clearModal}, повторно включаем sibling‑окна.</li>
 * </ul>
 *
 * <p>Собственное подменю «Комфорт» во встроенных QL‑редакторах этого же окна ставит отдельный
 * хук {@link QueryTextEditDialogHook}; координация — через разные ключи {@code shell.setData}.
 */
public final class DynamicListSettingsDialogHook implements IStartup
{
    private static final String SHELL_HOOKED_KEY = "tormozit.dynListSettingsShellHooked"; //$NON-NLS-1$
    private static final String TITLE_KEY = "tormozit.dynListSettingsTitle"; //$NON-NLS-1$
    private static final String ATTR_NAME_KEY = "tormozit.dynListSettingsAttrName"; //$NON-NLS-1$
    private static final String DUPLICATE_KEY = "tormozit.dynListSettingsDuplicate"; //$NON-NLS-1$
    private static final String DEMODALIZE_SCHEDULED_KEY = "tormozit.dynListSettingsDemodalizeScheduled"; //$NON-NLS-1$
    private static final String DEMODALIZED_KEY = "tormozit.dynListSettingsDemodalized"; //$NON-NLS-1$
    private static final String EDITOR_AT_OPEN_KEY = "tormozit.dynListSettingsEditorAtOpen"; //$NON-NLS-1$
    private static final String LINK_WRAPPED_KEY = "tormozit.dynListSettingsLinkWrapped"; //$NON-NLS-1$
    /** Метка на гиперссылке: клик по ней уже открывал именно окно настроек динамического списка. */
    private static final String LINK_IS_DYNLIST_KEY = "tormozit.dynListSettingsLinkIsDynList"; //$NON-NLS-1$

    /** Ссылка, по которой только что кликнули; метку получит, если появится окно настроек. */
    private static org.eclipse.ui.forms.widgets.Hyperlink pendingLink;

    private static final String DYNAMIC_LIST_COMPONENT_CLASS =
        "com._1c.g5.v8.dt.internal.form.ui.dynamiclist.aef.components.FormDynamicListComponent"; //$NON-NLS-1$

    private static final String LINK_MESSAGES_CLASS =
        "com._1c.g5.v8.dt.internal.form.ui.dynamiclist.aef.components.Messages"; //$NON-NLS-1$
    private static final String LINK_LABEL_FIELD =
        "DynamicListComponentParameterizationInfoFactory_Label_open"; //$NON-NLS-1$

    /** Надпись гиперссылки настройки динамического списка («Открыть» / «Open»); резолвится один раз. */
    private static volatile String settingsLinkLabel;

    private static final String DIALOG_CLASS_SUFFIX = "DynamicListQueryDialog"; //$NON-NLS-1$
    private static final String ATTRIBUTE_TOKEN = "Реквизит"; //$NON-NLS-1$

    /**
     * Окно уже принадлежит shell'у workbench (JFace создаёт его с родителем — активным shell),
     * поэтому за редактор оно не «тонет». Закрепление через Win32 owner нужно лишь как запас,
     * если тестирование покажет обратное — тогда {@code true}.
     */
    private static final boolean PIN_ABOVE_OWNER = false;

    private static final int TITLE_RETRY_LIMIT = 12;

    /**
     * Задержка снятия модальности после показа окна (мс).
     *
     * <p>Диалог инициализируется отложенно: {@code fillMainTableChooser} → {@code asyncExec} →
     * слушатель выбора основной таблицы → {@code updateDcsSettingsService} читает схему СКД
     * динамического списка внутри {@code LocalEditingContext}. Всё это крутится уже внутри
     * {@code Window.open()}. Если снять модальность до конца этой цепочки, derived‑data движок
     * модели формы ({@code FormExtDynamicListSettingsCollector} пересобирает ту же схему)
     * работает одновременно с чтением — рваное чтение даёт
     * {@code NPE DataSet.getName() … dataSet is null} в
     * {@code DcsAvailableSettingsSourceForSchema.initDataSetInfos}. Модальность на время
     * инициализации замораживает конкурента.
     */
    private static final int DEMODALIZE_DELAY_MS = 500;

    /**
     * Задержки повторных попыток активации редактора-владельца и выделения реквизита после «ОК»
     * (мс, накопительно ≈ 3.5 с). Растянуто, т.к. форму-владельца может понадобиться заново
     * открыть, а её страницы строятся не мгновенно.
     */
    private static final int[] OWNER_ACTIVATION_DELAYS = { 140, 250, 400, 600, 900, 1300 };

    /** Задержки ожидания заново построенного компонента списка для повтора записи (мс). */
    private static final int[] SAVE_REPLAY_DELAYS = { 60, 120, 200, 350, 550, 800, 1200 };

    /** Сколько держать модель‑заглушку на отвязанном компоненте (мс) — см. {@code silenceStockSave}. */
    private static final int STUB_LIFETIME_MS = 2000;

    private static final String DCS_UI_UTIL_CLASS = "com._1c.g5.v8.dt.dcs.ui.util.DcsUiUtil"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.editor.FormEditor"; //$NON-NLS-1$

    /**
     * Открытые немодальные окна настроек: ключ — URI реквизита-динамического-списка. Нельзя
     * плодить дубли одного списка (issue #428): при повторном открытии второе окно закрываем,
     * первое выводим на передний план. Доступ только из UI-потока.
     */
    private static final Map<String, Shell> OPEN_BY_LIST = new HashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = DynamicListSettingsDialogHook::handleEvent;
        display.addFilter(SWT.Resize, listener);
        display.addFilter(SWT.Show, listener);

        // Display-фильтр не может подавить событие (Display.filterEvent всегда false), поэтому
        // на саму ссылку «Открыть» один раз ставим обёртку её AEF-слушателя: клик = открыть ИЛИ,
        // если окно этого списка уже есть, активировать его. Ссылка — единственная точка входа.
        Listener linkGuard = DynamicListSettingsDialogHook::wrapSettingsHyperlink;
        display.addFilter(SWT.MouseEnter, linkGuard);
        display.addFilter(SWT.FocusIn, linkGuard);
        // MouseDown обязателен: после «ОК» AEF пересоздаёт панель «Свойства», и если курсор уже
        // стоит над ссылкой, MouseEnter для НОВОГО виджета не приходит — обёртка не встаёт, и клик
        // открывает окно в обход дедупа. MouseDown приходит до MouseUp, на котором
        // AbstractHyperlink вызывает handleActivate, — успеваем обернуть.
        display.addFilter(SWT.MouseDown, linkGuard);

        log("install Resize + Show + hyperlink-guard filters"); //$NON-NLS-1$
    }

    // --- двойной клик по полю списка: открыть настройки и активировать поле (#1007) ----------

    /** Поле набора данных, которое нужно выделить в только что открытом окне настроек. */
    private static final class FieldReveal
    {
        final String[] names;

        final boolean title;

        /** Заново показывает список в палитре, если она переключилась на другое. */
        final Runnable showListInPalette;

        /** Возвращает палитре прежнее содержимое, когда ссылка уже не нужна. */
        final Runnable restoreSelection;

        FieldReveal(String[] names, boolean title, Runnable showListInPalette, Runnable restoreSelection)
        {
            this.names = names;
            this.title = title;
            this.showListInPalette = showListInPalette;
            this.restoreSelection = restoreSelection;
        }

        void showList()
        {
            if (showListInPalette != null)
                showListInPalette.run();
        }

        void restore()
        {
            if (restoreSelection != null)
                restoreSelection.run();
        }
    }

    private static final String TEMP_TOPIC = "dl-field-reveal"; //$NON-NLS-1$

    private static final int REVEAL_ATTEMPTS = 20;

    /** Запрос, ждущий появления окна настроек; забирается в {@link #onDelayedSetup}. */
    private static FieldReveal pendingReveal;

    /**
     * Открывает окно настроек списка (через ссылку «Открыть» панели «Свойства», где уже выделен
     * реквизит-список) и активирует в нём поле набора данных. Если окно этого списка уже
     * открыто — выводит его вперёд.
     *
     * @param names имена поля (русское и английское) — совпадение по пути поля набора данных
     * @param title {@code true} — активировать ячейку «Заголовок», иначе «Путь»
     * @param showListInPalette показать список в палитре — повторяется, пока палитра показывает другое
     * @param restoreSelection вернуть палитре прежнее содержимое — вызывается, как только окно
     *        открылось (или открыть не удалось)
     */
    static void openFieldSettings(IWorkbenchPage workbenchPage, EObject attr, boolean title,
        Runnable showListInPalette, Runnable restoreSelection, String... names)
    {
        FieldReveal request = new FieldReveal(names, title, showListInPalette, restoreSelection);
        Shell existing = attr == null ? null : OPEN_BY_LIST.get(EcoreUtil.getURI(attr).toString());
        Global.tempLog(TEMP_TOPIC, "[v10] openFieldSettings names=" + java.util.Arrays.toString(names) //$NON-NLS-1$
            + " title=" + title + " existing=" + (existing != null && !existing.isDisposed())); //$NON-NLS-1$ //$NON-NLS-2$
        if (existing != null && !existing.isDisposed())
        {
            request.restore();
            activateExistingShell(existing);
            scheduleReveal(existing, request, 0);
            return;
        }
        pendingReveal = request;
        scheduleOpenLink(workbenchPage, attr, 0);
    }

    private static void scheduleOpenLink(IWorkbenchPage workbenchPage, EObject attr, int attempt)
    {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(attempt == 0 ? 50 : 100, () -> {
            if (pendingReveal == null)
            {
                Global.tempLog(TEMP_TOPIC, "openLink attempt=" + attempt + ": pendingReveal уже снят"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (activateSettingsLink(workbenchPage, attr))
                return;
            Global.tempLog(TEMP_TOPIC, "openLink attempt=" + attempt + ": ссылки нет"); //$NON-NLS-1$ //$NON-NLS-2$
            if (attempt < REVEAL_ATTEMPTS)
                scheduleOpenLink(workbenchPage, attr, attempt + 1);
            else
            {
                Global.tempLog(TEMP_TOPIC, "settings link not found"); //$NON-NLS-1$
                FieldReveal request = pendingReveal;
                pendingReveal = null;
                request.restore();
            }
        });
    }

    /** Ищет в панели «Свойства» ссылку «Открыть» и активирует её. */
    private static boolean activateSettingsLink(IWorkbenchPage workbenchPage, EObject attr)
    {
        Object sheetPage = propertySheetPage(workbenchPage);
        org.eclipse.swt.widgets.Composite palette =
            sheetPage == null ? null : PropertySheetUiContext.findPaletteContent(sheetPage);
        if (palette == null || palette.isDisposed())
        {
            Global.tempLog(TEMP_TOPIC, "palette=" + (palette == null ? "null" : "disposed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }
        // Палитра перестраивается после смены выделения не сразу: пока она показывает прежний
        // объект (например, само поле), ссылки «Открыть» в ней чужие — ждём сам список.
        EObject shown = PropertySheetControlInterop.selectionEObjectForCopy(sheetPage);
        if (!sameObject(shown, attr))
        {
            Global.tempLog(TEMP_TOPIC, "палитра показывает " //$NON-NLS-1$
                + (shown == null ? "null" : shown.eClass().getName() + " " + Global.invoke(shown, "getName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (pendingReveal != null)
                pendingReveal.showList();
            return false;
        }
        java.util.List<org.eclipse.ui.forms.widgets.Hyperlink> links = new java.util.ArrayList<>();
        collectSettingsLinks(palette, links);
        for (int i = 0; i < links.size(); i++)
        {
            org.eclipse.ui.forms.widgets.Hyperlink link = links.get(i);
            org.eclipse.swt.graphics.Rectangle bounds = link.getDisplay().map(link.getParent(), null, link.getBounds());
            Global.tempLog(TEMP_TOPIC, "candidate #" + i + " marked=" + (link.getData(LINK_IS_DYNLIST_KEY) != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " y=" + bounds.y + " section=[" + sectionTitle(link) + "] rowLabel=[" + rowLabel(link) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "] siblings=" + siblingTexts(link)); //$NON-NLS-1$
        }
        if (links.isEmpty())
            return false;
        // «Настройка списка» — единственная ссылка «Открыть» в секции «Объект»; «Просмотр» и
        // «Редактировать» (тоже «Открыть») лежат в «Использовании». Секция «Объект» достраивается
        // позже остальных: пока её ссылки нет, ждём, а не жмём чужую (#1007).
        org.eclipse.ui.forms.widgets.Hyperlink target = null;
        for (org.eclipse.ui.forms.widgets.Hyperlink link : links)
        {
            String section = sectionTitle(link);
            String near = (siblingTexts(link) + siblingTexts(link.getParent())).toLowerCase();
            if ("Объект".equalsIgnoreCase(section) || "Object".equalsIgnoreCase(section) //$NON-NLS-1$ //$NON-NLS-2$
                || near.contains("настройка списка") || near.contains("list settings")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                target = link;
                break;
            }
        }
        for (org.eclipse.ui.forms.widgets.Hyperlink link : links)
        {
            if (link.getData(LINK_IS_DYNLIST_KEY) != null)
            {
                target = link;
                break;
            }
        }
        if (target == null)
        {
            Global.tempLog(TEMP_TOPIC, "ссылки секции «Объект» ещё нет, ждём"); //$NON-NLS-1$
            return false;
        }
        Event wrapEvent = new Event();
        wrapEvent.widget = target;
        wrapSettingsHyperlink(wrapEvent);
        org.eclipse.ui.forms.events.HyperlinkEvent hyperlinkEvent =
            new org.eclipse.ui.forms.events.HyperlinkEvent(target, target.getHref(), target.getText(), 0);
        Global.tempLog(TEMP_TOPIC, "activating link #" + links.indexOf(target) + " of " + links.size() //$NON-NLS-1$ //$NON-NLS-2$
            + " rowLabel=[" + rowLabel(target) + "] wrapped=" + (target.getData(LINK_WRAPPED_KEY) != null) //$NON-NLS-1$ //$NON-NLS-2$
            + " listeners=" + hyperlinkListeners(target).size()); //$NON-NLS-1$
        for (org.eclipse.ui.forms.events.IHyperlinkListener listener : hyperlinkListeners(target))
            listener.linkActivated(hyperlinkEvent);
        return true;
    }

    private static Object propertySheetPage(IWorkbenchPage workbenchPage)
    {
        if (workbenchPage == null)
            return null;
        for (org.eclipse.ui.IViewReference reference : workbenchPage.getViewReferences())
        {
            org.eclipse.ui.IViewPart view = reference.getView(false);
            if (view != null && PropertyNameIdentifierHook.isPropertySheetView(view))
                return PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        }
        return null;
    }

    /** Тот же объект модели: экземпляр палитры может быть другой копией того же реквизита. */
    private static boolean sameObject(EObject shown, EObject expected)
    {
        if (shown == null || expected == null)
            return true; // палитра не говорит, что показывает, — проверить нечем
        if (shown == expected || EcoreUtil.isAncestor(expected, shown))
            return true;
        String expectedUri = EcoreUtil.getURI(expected).toString();
        String shownUri = EcoreUtil.getURI(shown).toString();
        return shownUri.equals(expectedUri) || shownUri.startsWith(expectedUri + "/"); //$NON-NLS-1$
    }

    private static void collectSettingsLinks(org.eclipse.swt.widgets.Composite parent,
        java.util.List<org.eclipse.ui.forms.widgets.Hyperlink> result)
    {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren())
        {
            if (child.isDisposed() || !child.isVisible())
                continue;
            if (child instanceof org.eclipse.ui.forms.widgets.Hyperlink link && isSettingsLinkLabel(link.getText()))
                result.add(link);
            else if (child instanceof org.eclipse.swt.widgets.Composite composite)
                collectSettingsLinks(composite, result);
        }
    }

    private static String sectionTitle(org.eclipse.swt.widgets.Control control)
    {
        for (org.eclipse.swt.widgets.Control cur = control.getParent(); cur != null; cur = cur.getParent())
            if (cur instanceof org.eclipse.ui.forms.widgets.Section section)
                return String.valueOf(section.getText());
        return ""; //$NON-NLS-1$
    }

    /** Тексты соседних надписей рядом со ссылкой — для опознания её в логе. */
    private static String siblingTexts(org.eclipse.swt.widgets.Control control)
    {
        StringBuilder texts = new StringBuilder();
        org.eclipse.swt.widgets.Composite parent = control.getParent();
        if (parent == null)
            return ""; //$NON-NLS-1$
        for (org.eclipse.swt.widgets.Control child : parent.getChildren())
        {
            String text = child instanceof org.eclipse.swt.widgets.Label label ? label.getText()
                : child instanceof org.eclipse.ui.forms.widgets.Hyperlink link ? link.getText() : null;
            texts.append(child.getClass().getSimpleName());
            if (text != null)
                texts.append('=').append(text);
            texts.append("; "); //$NON-NLS-1$
        }
        return texts.toString();
    }

    /**
     * SWT-надпись в той же строке палитры слева от ссылки (по пересечению по вертикали
     * в координатах экрана), или пусто, если надписи — не SWT-виджеты.
     */
    private static String rowLabel(org.eclipse.swt.widgets.Control link)
    {
        org.eclipse.swt.widgets.Composite section = link.getParent();
        while (section != null && !(section instanceof org.eclipse.ui.forms.widgets.Section))
            section = section.getParent();
        if (section == null)
            return ""; //$NON-NLS-1$
        org.eclipse.swt.graphics.Rectangle linkBounds = link.getDisplay().map(link.getParent(), null, link.getBounds());
        StringBuilder found = new StringBuilder();
        collectRowLabels(section, linkBounds, found);
        return found.toString();
    }

    private static void collectRowLabels(org.eclipse.swt.widgets.Composite parent,
        org.eclipse.swt.graphics.Rectangle row, StringBuilder found)
    {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren())
        {
            if (child.isDisposed() || !child.isVisible())
                continue;
            if (child instanceof org.eclipse.swt.widgets.Label label)
            {
                org.eclipse.swt.graphics.Rectangle b = child.getDisplay().map(parent, null, child.getBounds());
                if (b.x < row.x && b.y < row.y + row.height && b.y + b.height > row.y)
                    found.append(label.getText()).append(';');
            }
            else if (child instanceof org.eclipse.swt.widgets.Composite composite)
                collectRowLabels(composite, row, found);
        }
    }

    /** Забирает запрос выделения поля для только что настроенного окна. */
    private static void consumePendingReveal(Shell shell)
    {
        FieldReveal request = pendingReveal;
        pendingReveal = null;
        Global.tempLog(TEMP_TOPIC, "consumePendingReveal request=" //$NON-NLS-1$
            + (request == null ? "null" : java.util.Arrays.toString(request.names))); //$NON-NLS-1$
        if (request != null)
        {
            request.restore();
            scheduleReveal(shell, request, 0);
        }
    }

    private static void scheduleReveal(Shell shell, FieldReveal request, int attempt)
    {
        Display display = shell.isDisposed() ? null : shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(150, () -> {
            if (shell.isDisposed())
            {
                Global.tempLog(TEMP_TOPIC, "reveal attempt=" + attempt + ": окно закрыто"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Global.tempLog(TEMP_TOPIC, "reveal attempt=" + attempt); //$NON-NLS-1$
            boolean done;
            try
            {
                done = revealField(shell, request);
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.tempLogException(TEMP_TOPIC, "revealField failed", e); //$NON-NLS-1$
                done = true;
            }
            if (!done && attempt < REVEAL_ATTEMPTS)
                scheduleReveal(shell, request, attempt + 1);
            else if (!done)
                Global.tempLog(TEMP_TOPIC, "reveal gave up"); //$NON-NLS-1$
        });
    }

    /** @return {@code true} — ячейка активирована (или искать больше нечего) */
    private static boolean revealField(Shell shell, FieldReveal request)
    {
        Object dialog = resolveDialog(shell);
        if (dialog == null
            || !(Global.getField(dialog, "context") instanceof DataCompositionSchemaControlContext context)) //$NON-NLS-1$
        {
            Global.tempLog(TEMP_TOPIC, "  нет диалога/context, dialog=" + dialog); //$NON-NLS-1$
            return false;
        }
        com._1c.g5.v8.dt.dcs.ui.datasets.DataSets dataSets = null;
        StringBuilder pageNames = new StringBuilder();
        for (com._1c.g5.v8.dt.dcs.ui.EditorPage page : context.getPages())
        {
            pageNames.append(page == null ? "null" : page.getClass().getSimpleName()).append(','); //$NON-NLS-1$
            if (page instanceof com._1c.g5.v8.dt.dcs.ui.datasets.DataSets found)
                dataSets = found;
        }
        if (dataSets == null || context.getDataCompositionSchema() == null)
        {
            Global.tempLog(TEMP_TOPIC, "  dataSets=" + (dataSets != null) + " schema=" //$NON-NLS-1$ //$NON-NLS-2$
                + (context.getDataCompositionSchema() != null) + " pages=" + pageNames); //$NON-NLS-1$
            return false;
        }
        com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField field =
            findField(context.getDataCompositionSchema(), request.names);
        if (field == null)
        {
            StringBuilder paths = new StringBuilder();
            for (com._1c.g5.v8.dt.dcs.model.schema.DataSet dataSet : context.getDataCompositionSchema().getDataSets())
            {
                java.util.Iterator<EObject> contents = dataSet.eAllContents();
                while (contents.hasNext())
                    if (contents.next() instanceof com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField f)
                        paths.append(f.getDataPath()).append(',');
            }
            Global.tempLog(TEMP_TOPIC, "  field not found " + java.util.Arrays.toString(request.names) //$NON-NLS-1$
                + " dataSets=" + context.getDataCompositionSchema().getDataSets().size() + " paths=" + paths); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        EObject owner = field.eContainer();
        while (owner != null && !(owner instanceof com._1c.g5.v8.dt.dcs.model.schema.DataSet))
            owner = owner.eContainer();
        // В окне динамического списка набор один и дерева наборов нет (getDataSetsViewer() == null,
        // getCurrentDataSet() == null) — переключать нечего, таблица полей уже показывает его.
        if (owner != null && dataSets.getDataSetsViewer() != null && dataSets.getCurrentDataSet() != null
            && dataSets.getCurrentDataSet() != owner)
        {
            Global.tempLog(TEMP_TOPIC, "  выбираю набор данных " //$NON-NLS-1$
                + ((com._1c.g5.v8.dt.dcs.model.schema.DataSet) owner).getName() + ", текущий=" //$NON-NLS-1$
                + (dataSets.getCurrentDataSet() == null ? "null" : dataSets.getCurrentDataSet().getName())); //$NON-NLS-1$
            dataSets.getDataSetsViewer().setSelection(new org.eclipse.jface.viewers.StructuredSelection(owner), true);
            return false;
        }
        Object fieldsViewer = dataSets.getCurrentFieldsViewer();
        if (fieldsViewer == null)
            fieldsViewer = Global.getField(dataSets, "queryFieldsFullViewer"); //$NON-NLS-1$
        Object treeViewer = fieldsViewer == null ? null : Global.invoke(fieldsViewer, "getViewer"); //$NON-NLS-1$
        if (treeViewer == null)
        {
            Global.tempLog(TEMP_TOPIC, "  fieldsViewer=" + fieldsViewer + " treeViewer=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        Global.invoke(treeViewer, "setSelection", //$NON-NLS-1$
            (org.eclipse.jface.viewers.ISelection) new org.eclipse.jface.viewers.StructuredSelection(field), true);
        try
        {
            Class<?> columnEnum = fieldsViewer.getClass().getClassLoader()
                .loadClass("com._1c.g5.v8.dt.dcs.ui.datasets.fields.DataSetsFieldsViewerBase$FieldsColumn"); //$NON-NLS-1$
            Object column = Global.invoke(columnEnum, "valueOf", request.title ? "TITLE_COL_INDEX" : "PATH_COL_INDEX"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Object index = Global.invoke(fieldsViewer, "getColumnIndex", column); //$NON-NLS-1$
            // TableEx раскладывает логические колонки по нескольким строкам и колонкам сетки
            // (у поля набора «Путь» в первой строке, «Заголовок» — во второй), а setCellSelection
            // принимает координаты самой сетки. Перевод — штатный: getDataItem/getDataColumn
            // от логической колонки и первой строки сетки, занятой выделенным полем.
            Object table = Global.invoke(treeViewer, "getTable"); //$NON-NLS-1$
            Object grid = table == null ? null : Global.invoke(table, "getDataControl"); //$NON-NLS-1$
            Object gridRows = grid == null ? null : Global.invoke(grid, "getSelectionIndices"); //$NON-NLS-1$
            if (!(index instanceof Integer col) || !(gridRows instanceof int[] selected) || selected.length == 0)
            {
                Global.tempLog(TEMP_TOPIC, "  column=" + column + " index=" + index + " grid=" + grid //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " rows=" + (gridRows instanceof int[] r ? java.util.Arrays.toString(r) : String.valueOf(gridRows))); //$NON-NLS-1$
                return false;
            }
            int baseRow = java.util.Arrays.stream(selected).min().getAsInt();
            org.eclipse.swt.graphics.Point logical = new org.eclipse.swt.graphics.Point(col.intValue(), baseRow);
            Object dataItem = Global.invoke(table, "getDataItem", logical); //$NON-NLS-1$
            Object gridColumn = Global.invoke(table, "getDataColumn", logical); //$NON-NLS-1$
            Object gridRow = dataItem == null ? null : Global.invoke(grid, "indexOf", dataItem); //$NON-NLS-1$
            if (!(gridColumn instanceof Integer cellX) || !(gridRow instanceof Integer cellY) || cellY.intValue() < 0)
            {
                Global.tempLog(TEMP_TOPIC, "  нет ячейки сетки: col=" + col + " base=" + baseRow //$NON-NLS-1$ //$NON-NLS-2$
                    + " dataItem=" + dataItem + " gridColumn=" + gridColumn + " gridRow=" + gridRow); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return false;
            }
            Global.invoke(table, "setCellSelection", new org.eclipse.swt.graphics.Point(cellX.intValue(), cellY.intValue())); //$NON-NLS-1$
            Global.tempLog(TEMP_TOPIC, "cell activated col=" + col + " gridRows=" + java.util.Arrays.toString(selected) //$NON-NLS-1$ //$NON-NLS-2$
                + " cell=" + cellX + "," + cellY); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        catch (ClassNotFoundException | RuntimeException e)
        {
            Global.tempLogException(TEMP_TOPIC, "revealField", e); //$NON-NLS-1$
            return true;
        }
    }

    private static com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField findField(
        com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema schema, String[] names)
    {
        for (com._1c.g5.v8.dt.dcs.model.schema.DataSet dataSet : schema.getDataSets())
        {
            java.util.Iterator<EObject> contents = dataSet.eAllContents();
            while (contents.hasNext())
            {
                if (!(contents.next() instanceof com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField field))
                    continue;
                for (String name : names)
                    if (name != null && name.equalsIgnoreCase(field.getDataPath()))
                        return field;
            }
        }
        return null;
    }

    // --- ссылка «Открыть» = открыть или активировать окно ----------------------------------

    private static void wrapSettingsHyperlink(Event event)
    {
        if (!(event.widget instanceof org.eclipse.ui.forms.widgets.Hyperlink hl) || hl.isDisposed())
            return;
        if (!isSettingsLinkLabel(hl.getText()))
            return;

        if (hl.getData(LINK_WRAPPED_KEY) != null)
            return;

        java.util.List<org.eclipse.ui.forms.events.IHyperlinkListener> originals = hyperlinkListeners(hl);
        if (originals.isEmpty())
            return;

        hl.setData(LINK_WRAPPED_KEY, Boolean.TRUE);
        try
        {
            for (org.eclipse.ui.forms.events.IHyperlinkListener l : originals)
                hl.removeHyperlinkListener(l);
            hl.addHyperlinkListener(new SettingsLinkListener(originals));
            log("wrapped settings hyperlink"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DynamicListSettingsDialogDebug.problem("wrap hyperlink: " + e); //$NON-NLS-1$
        }
    }

    /**
     * Слушатели гиперссылки из {@code AbstractHyperlink.listeners} (поле
     * {@code ListenerList<IHyperlinkListener>}). Берём ВСЕХ, а не только AEF‑овский
     * {@code SwtLinkView$1}: по логу класс слушателя оказался другим, а фильтрация по имени
     * класса молча оставляла ссылку неперехваченной.
     */
    private static java.util.List<org.eclipse.ui.forms.events.IHyperlinkListener> hyperlinkListeners(
        org.eclipse.ui.forms.widgets.Hyperlink hl)
    {
        java.util.List<org.eclipse.ui.forms.events.IHyperlinkListener> result = new java.util.ArrayList<>();
        Object listenerList = Global.getField(hl, "listeners"); //$NON-NLS-1$
        Object arr = listenerList == null ? null : Global.invoke(listenerList, "getListeners"); //$NON-NLS-1$
        if (arr instanceof Object[] listeners)
        {
            for (Object l : listeners)
            {
                if (l instanceof org.eclipse.ui.forms.events.IHyperlinkListener hyperlinkListener)
                    result.add(hyperlinkListener);
            }
        }
        return result;
    }

    private static boolean isSettingsLinkLabel(String text)
    {
        if (text == null || text.isBlank())
            return false;
        String label = settingsLinkLabel;
        if (label == null)
        {
            label = "Открыть"; //$NON-NLS-1$
            try
            {
                Object v = Class.forName(LINK_MESSAGES_CLASS).getField(LINK_LABEL_FIELD).get(null);
                if (v instanceof String s && !s.isBlank())
                    label = s;
            }
            catch (Exception ignored)
            {
                // остаётся дефолт
            }
            settingsLinkLabel = label;
        }
        return text.equals(label) || "Открыть".equals(text) || "Open".equals(text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class SettingsLinkListener
        extends org.eclipse.ui.forms.events.HyperlinkAdapter
    {
        private final java.util.List<org.eclipse.ui.forms.events.IHyperlinkListener> delegates;

        SettingsLinkListener(java.util.List<org.eclipse.ui.forms.events.IHyperlinkListener> delegates)
        {
            this.delegates = delegates;
        }

        @Override
        public void linkActivated(org.eclipse.ui.forms.events.HyperlinkEvent e)
        {
            boolean known = e.widget instanceof org.eclipse.ui.forms.widgets.Hyperlink hl
                && hl.getData(LINK_IS_DYNLIST_KEY) != null;
            // Ссылок «Открыть» в панели «Свойства» несколько (настройка списка, условное
            // оформление и т. п.), и различить их до клика нечем: у LinkViewModel только текст,
            // обратной ссылки на компонент у View нет. Поэтому перехватываем ТОЛЬКО ту ссылку,
            // клик по которой уже приводил к появлению DynamicListQueryDialog (метку ставит
            // onShellDetected). Иначе клик по чужой ссылке при открытом окне настроек списка
            // подменялся активацией нашего окна — условное оформление переставало открываться.
            if (!known)
            {
                pendingLink = e.widget instanceof org.eclipse.ui.forms.widgets.Hyperlink link ? link : null;
                for (org.eclipse.ui.forms.events.IHyperlinkListener l : delegates)
                    l.linkActivated(e);
                return;
            }

            EObject attr = FormEditorHook.selectedDynamicListAttribute();
            Shell existing = attr == null ? null
                : OPEN_BY_LIST.get(EcoreUtil.getURI(attr).toString());
            log("linkActivated attr=" + (attr == null ? "null" : "ok") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " existing=" + (existing != null && !existing.isDisposed())); //$NON-NLS-1$
            if (existing != null && !existing.isDisposed())
            {
                log("settings link — activating existing window"); //$NON-NLS-1$
                activateExistingShell(existing);
                return;
            }
            for (org.eclipse.ui.forms.events.IHyperlinkListener l : delegates)
                l.linkActivated(e);
        }

        @Override
        public void linkEntered(org.eclipse.ui.forms.events.HyperlinkEvent e)
        {
            for (org.eclipse.ui.forms.events.IHyperlinkListener l : delegates)
                l.linkEntered(e);
        }

        @Override
        public void linkExited(org.eclipse.ui.forms.events.HyperlinkEvent e)
        {
            for (org.eclipse.ui.forms.events.IHyperlinkListener l : delegates)
                l.linkExited(e);
        }
    }

    private static void handleEvent(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;

        if (Boolean.TRUE.equals(shell.getData(SHELL_HOOKED_KEY)))
        {
            if (event.type == SWT.Show && !Boolean.TRUE.equals(shell.getData(DUPLICATE_KEY)))
            {
                Object editor = shell.getData(EDITOR_AT_OPEN_KEY);
                scheduleDelayedSetup(shell, editor instanceof IEditorPart p ? p : null);
                reassert(shell);
            }
            return;
        }

        if (!isDynamicListSettingsShell(shell))
            return;

        onShellDetected(shell, event.type);
    }

    /**
     * Перехват окна. Здесь — только то, что гарантированно не трогает модель: захват редактора
     * и постановка слушателей. Всё остальное (чтение реквизита, дедуп, заголовок, снятие
     * модальности) откладывается на {@link #DEMODALIZE_DELAY_MS} — см. {@link #onDelayedSetup}.
     */
    private static void onShellDetected(Shell shell, int eventType)
    {
        shell.setData(SHELL_HOOKED_KEY, Boolean.TRUE);
        Global.tempLog(TEMP_TOPIC, "onShellDetected type=" + eventType + " pending=" + (pendingReveal != null) //$NON-NLS-1$ //$NON-NLS-2$
            + " pendingLink=" + (pendingLink != null)); //$NON-NLS-1$

        // Клик, который к этому привёл, был именно по ссылке настроек динамического списка —
        // помечаем её, чтобы впредь перехватывать только эту ссылку (а не чужие «Открыть»).
        if (pendingLink != null && !pendingLink.isDisposed())
        {
            pendingLink.setData(LINK_IS_DYNLIST_KEY, Boolean.TRUE);
            log("marked settings hyperlink"); //$NON-NLS-1$
        }
        pendingLink = null;

        IEditorPart editorAtOpen = activeEditor();
        IEditorInput formInput = editorInputOf(editorAtOpen);
        String formEditorId = editorIdOf(editorAtOpen);
        Object dialog = resolveDialog(shell);
        if (editorAtOpen != null)
            shell.setData(EDITOR_AT_OPEN_KEY, editorAtOpen);

        log("detected type=" + eventType //$NON-NLS-1$
            + " visible=" + shell.getVisible() + " editorId=" + formEditorId); //$NON-NLS-1$ //$NON-NLS-2$

        // Пока панель «Свойства» ещё показывает этот список — запомнить её AEF-компонент и модель.
        captureDynamicListComponent(shell);

        // Окно уже есть, компонент списка захвачен — палитре, переключённой на список ради
        // ссылки «Открыть», можно вернуть прежнее содержимое, не дожидаясь настройки окна.
        if (pendingReveal != null)
            pendingReveal.restore();

        installMaintenance(shell);
        installOwnerAttributeActivation(shell, dialog, formInput, formEditorId);

        // На SWT.Resize окно ещё невидимо — отсчёт стартует по SWT.Show (см. handleEvent).
        if (shell.getVisible())
            scheduleDelayedSetup(shell, editorAtOpen);
    }

    /**
     * Отложенная часть: выполняется, когда штатная инициализация диалога уже завершена
     * (см. {@link #DEMODALIZE_DELAY_MS}).
     */
    private static void onDelayedSetup(Shell shell, IEditorPart editorAtOpen)
    {
        // Ключ дедупа — из ВЫДЕЛЕНИЯ в редакторе форм (тот же источник, что у SettingsLinkListener),
        // иначе URI может не совпасть с реквизитом из view-model диалога. Имя — из view-model.
        EObject selectedAttr = FormEditorHook.selectedDynamicListAttribute();
        EObject dialogAttr = resolveAttribute(shell);
        EObject attr = selectedAttr != null ? selectedAttr : dialogAttr;
        String attrName = attributeName(dialogAttr != null ? dialogAttr : selectedAttr);
        String listKey = attr == null ? null : EcoreUtil.getURI(attr).toString();

        if (attrName != null)
            shell.setData(ATTR_NAME_KEY, attrName);

        Global.tempLog(TEMP_TOPIC, "onDelayedSetup attr=" + attrName + " pending=" + (pendingReveal != null)); //$NON-NLS-1$ //$NON-NLS-2$
        if (listKey != null && closeDuplicateFor(listKey, shell))
        {
            Global.tempLog(TEMP_TOPIC, "onDelayedSetup: дубль окна, закрыт"); //$NON-NLS-1$
            Shell kept = OPEN_BY_LIST.get(listKey);
            if (kept != null && !kept.isDisposed())
                consumePendingReveal(kept);
            log("duplicate settings window for " + listKey //$NON-NLS-1$
                + " — closed, existing activated"); //$NON-NLS-1$
            return;
        }
        if (listKey != null)
        {
            OPEN_BY_LIST.put(listKey, shell);
            shell.addDisposeListener(e -> OPEN_BY_LIST.remove(listKey, shell));
        }

        log("onDelayedSetup attr=" + attrName + " key=" + listKey); //$NON-NLS-1$ //$NON-NLS-2$
        if (Global.getField(resolveDialog(shell), "context") instanceof DataCompositionSchemaControlContext context) //$NON-NLS-1$
        {
            DcsComputedValueTypes.install(context);
            TableExHeaderAccent.install(context);
            DataCompositionSchemaEditorHook.QueryFieldNavigation.install(context);
        }
        shell.setData(DEMODALIZED_KEY, Boolean.TRUE);
        demodalize(shell);
        pinAboveOwner(shell);
        scheduleTitle(shell, editorAtOpen, 0);
        consumePendingReveal(shell);
    }

    /**
     * Если для этого списка уже открыто немодальное окно настроек — вывести его вперёд, а новое
     * (переданное) закрыть. Второе окно к этому моменту уже создало свою вкладку СКД и перехватило
     * глобальный провайдер {@code DcsUiUtil} — после закрытия дубля возвращаем провайдер первого.
     *
     * @return {@code true}, если {@code newShell} — дубль и его нужно бросить
     */
    private static boolean closeDuplicateFor(String listKey, Shell newShell)
    {
        Shell existing = OPEN_BY_LIST.get(listKey);
        if (existing == null || existing.isDisposed() || existing == newShell)
            return false;

        newShell.setData(DUPLICATE_KEY, Boolean.TRUE);
        demodalize(newShell); // не дать мигнуть модальностью до закрытия
        newShell.getDisplay().asyncExec(() ->
        {
            if (!newShell.isDisposed())
                newShell.close();
            reassertDcsProvider(existing);
            activateExistingShell(existing);
        });
        return true;
    }

    private static void activateExistingShell(Shell existing)
    {
        if (existing == null || existing.isDisposed())
            return;
        if (existing.getMinimized())
            existing.setMinimized(false);
        existing.setVisible(true);
        existing.setActive();
        existing.setFocus();
    }

    /** Возвращает глобальному {@code DcsUiUtil} провайдер и control-context окна {@code shell}. */
    private static void reassertDcsProvider(Shell shell)
    {
        Object dialog = resolveDialog(shell);
        if (dialog == null)
            return;
        Object svc = Global.getField(dialog, "dcsSettingsService"); //$NON-NLS-1$
        Object ctx = Global.getField(dialog, "context"); //$NON-NLS-1$
        try
        {
            Class<?> util = Class.forName(DCS_UI_UTIL_CLASS);
            if (svc != null)
                Global.invoke(util, "setSettingsProvider", svc); //$NON-NLS-1$
            if (ctx != null)
                Global.invoke(util, "setActualControlContext", ctx); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DynamicListSettingsDialogDebug.problem("reassert DCS provider: " + e); //$NON-NLS-1$
        }
    }

    /**
     * По «ОК» — активировать редактор формы-владельца (при необходимости — заново открыть) и
     * выделить в нём реквизит (issue #428). Признак «ОК» — {@code Window.getReturnCode() ==
     * Window.OK}: {@code okPressed()} проставляет его перед закрытием, а закрытие по [X]/Esc/
     * «Отмена» ставит {@code CANCEL}.
     *
     * <p>{@code dialog} и вход редактора захватываются в момент перехвата — на dispose из
     * {@code shell.getData()} их уже может не быть. Активацию делаем серией отложенных попыток:
     * после закрытия окна {@code FormDynamicListComponent.handleLinkClicked} пишет изменения в
     * модель формы (BM‑транзакция), а следующий рефреш AEF пересобирает дерево реквизитов и
     * сбрасывает выделение; плюс заново открытая форма строит страницы не мгновенно.
     */
    private static void installOwnerAttributeActivation(Shell shell, Object dialog,
        IEditorInput formInput, String formEditorId)
    {
        shell.addDisposeListener(e ->
        {
            Object attrName = shell.getData(ATTR_NAME_KEY);
            Object rc = dialog == null ? null : Global.invoke(dialog, "getReturnCode"); //$NON-NLS-1$
            // Снимаем запись всегда — иначе карта потечёт на окнах, закрытых не по «ОК».
            DynamicListComponentRef componentRef = COMPONENT_BY_SHELL.remove(shell);

            log("shell disposed, returnCode=" + rc + " attr=" + attrName //$NON-NLS-1$ //$NON-NLS-2$
                + " duplicate=" + shell.getData(DUPLICATE_KEY)); //$NON-NLS-1$
            if (dialog == null || !(attrName instanceof String name) || name.isBlank())
                return;

            if (!(rc instanceof Integer code) || code.intValue() != Window.OK)
            {
                log("closed without OK (returnCode=" + rc + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }

            // Панель «Свойства» так и не ушла с этого списка — компонент жив, штатный путь цел.
            // Вмешиваться нечем и незачем: EDT сохранит сама, редактор активен, реквизит выделен
            // (панель его и показывает). Чем ближе к штатному поведению, тем меньше рисков.
            if (componentRef != null && Global.invoke(componentRef.component(), "getModel") != null) //$NON-NLS-1$
            {
                log("stock path intact — no intervention"); //$NON-NLS-1$
                return;
            }

            // Дальше — только нештатный случай: пока окно было открыто, панель ушла на другой
            // объект и отвязала компонент. Штатное сохранение обречено, лечим сами.
            // СИНХРОННО, до возврата из Window.open(): сначала вернуть панель на этот список,
            // чтобы задачи её перестройки встали в очередь движка первыми.
            ensureOwnerEditorActive(formInput, formEditorId);
            boolean restored = FormEditorHook.selectAttributeInActiveForm(name);
            log("OK: sync owner restore before save -> " + restored); //$NON-NLS-1$

            saveThroughLiveModel(componentRef, dialog);

            scheduleOwnerActivation(name, formInput, formEditorId, 0);
        });
    }

    private static void scheduleOwnerActivation(String attributeName, IEditorInput formInput,
        String formEditorId, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= OWNER_ACTIVATION_DELAYS.length)
            return;

        display.timerExec(OWNER_ACTIVATION_DELAYS[attempt], () ->
        {
            ensureOwnerEditorActive(formInput, formEditorId);
            boolean selected = FormEditorHook.selectAttributeInActiveForm(attributeName);
            log("OK: activate owner attr " + attributeName //$NON-NLS-1$
                + " attempt " + attempt + " -> " + selected); //$NON-NLS-1$ //$NON-NLS-2$
            // Выделили и дали дереву время перестроиться коммитом формы — дальше не дёргаем фокус.
            if (selected && attempt >= 2)
                return;
            scheduleOwnerActivation(attributeName, formInput, formEditorId, attempt + 1);
        });
    }

    /** Активирует редактор формы по сохранённому входу; если закрыт — открывает заново. */
    private static void ensureOwnerEditorActive(IEditorInput input, String editorId)
    {
        if (input == null)
            return;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page == null)
                return;

            IEditorPart editor = page.findEditor(input);
            if (editor != null)
                page.activate(editor);
            else
                page.openEditor(input, editorId != null ? editorId : FORM_EDITOR_ID);
        }
        catch (Exception ex)
        {
            DynamicListSettingsDialogDebug.problem("reopen owner editor: " + ex); //$NON-NLS-1$
        }
    }

    private static IEditorInput editorInputOf(IEditorPart editor)
    {
        try
        {
            return editor == null ? null : editor.getEditorInput();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String editorIdOf(IEditorPart editor)
    {
        try
        {
            return editor == null || editor.getSite() == null ? null : editor.getSite().getId();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static EObject resolveAttribute(Shell shell)
    {
        Object dialog = resolveDialog(shell);
        Object model = dialog == null ? null : Global.getField(dialog, "model"); //$NON-NLS-1$
        Object attr = model == null ? null : Global.invoke(model, "getAttribute"); //$NON-NLS-1$
        return attr instanceof EObject eo ? eo : null;
    }

    private static String attributeName(EObject attr)
    {
        if (attr == null)
            return null;
        Object nameObj = Global.invoke(attr, "getName"); //$NON-NLS-1$
        return nameObj instanceof String s && !s.isBlank() ? s : null;
    }

    /** Повторный показ / восстановление окна: снова снять модальность и починить заголовок. */
    private static void reassert(Shell shell)
    {
        if (Boolean.TRUE.equals(shell.getData(DEMODALIZED_KEY)))
            demodalize(shell);
        Object title = shell.getData(TITLE_KEY);
        if (title instanceof String s && !s.equals(shell.getText()))
            shell.setText(s);
        pinAboveOwner(shell);
    }

    // --- сохранение по «ОК» при неактивном редакторе формы ------------------------------------

    /**
     * AEF‑компонент динамического списка в панели «Свойства» и его модель на момент открытия окна.
     *
     * <p>Штатное сохранение ({@code FormDynamicListComponent.lambda$1}) ставится в очередь движка
     * панели уже ПОСЛЕ закрытия окна и читает {@code getModel()} в момент выполнения. Если к тому
     * времени панель показывает другой объект, компонент отвязан, {@code getModel()} возвращает
     * {@code null} — и изменения молча теряются (в журнале ошибок: {@code Exception while executing
     * runnable in queue 'PROPERTY_PALETTE_ENGINE' … getModel() is null}). Сцена при этом жива —
     * иначе очередь была бы {@code IRunnableQueue.IMMEDIATE}.
     *
     * <p>Подставлять ЗАПОМНЕННУЮ модель бесполезно: {@code Component.setModel(null)} зовёт
     * {@code disposeModel()} → {@code detachFromModel()}, и модель уходит в offline с вычищенными
     * значениями — запись падает с {@code IllegalStateException: Model is offline}. Поэтому берётся
     * ЖИВАЯ модель компонента, который панель построила заново для того же списка (выделение
     * возвращается тут же, синхронно). Запомненная модель нужна лишь как признак того, что при
     * открытии окна панель действительно показывала этот список.
     */
    private record DynamicListComponentRef(Object component, Object model, Object scene)
    {
    }

    /** Компонент панели «Свойства», захваченный при открытии окна (ключ — shell окна). */
    private static final Map<Shell, DynamicListComponentRef> COMPONENT_BY_SHELL = new HashMap<>();

    /**
     * Находит {@code FormDynamicListComponent} в панели «Свойства», пока она ещё показывает этот
     * динамический список, и запоминает его вместе с моделью.
     */
    private static void captureDynamicListComponent(Shell shell)
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage workbenchPage = window == null ? null : window.getActivePage();
            if (workbenchPage == null)
                return;

            for (org.eclipse.ui.IViewReference ref : workbenchPage.getViewReferences())
            {
                org.eclipse.ui.IViewPart view = ref.getView(false);
                if (!PropertyNameIdentifierHook.isPropertySheetView(view))
                    continue;

                Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
                Object scene = page == null ? null : Global.invoke(page, "getScene"); //$NON-NLS-1$
                Object root = scene == null ? null : Global.invoke(scene, "getComponent"); //$NON-NLS-1$
                Object component = findDynamicListComponent(root, 0);
                Object model = component == null ? null : Global.invoke(component, "getModel"); //$NON-NLS-1$
                if (model != null)
                {
                    // Карту чистит слушатель закрытия окна (installOwnerAttributeActivation).
                    // Своего DisposeListener здесь быть НЕ должно: SWT вызывает их в порядке
                    // добавления, и этот сработал бы раньше — восстановление получало бы пустую
                    // карту и молча ничего не делало.
                    COMPONENT_BY_SHELL.put(shell, new DynamicListComponentRef(component, model, scene));
                    log("captured dynlist component " + component.getClass().getSimpleName()); //$NON-NLS-1$
                    return;
                }
            }
            log("dynlist component NOT captured"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DynamicListSettingsDialogDebug.problem("capture component: " + e); //$NON-NLS-1$
        }
    }

    private static Object findDynamicListComponent(Object component, int depth)
    {
        return findDynamicListComponent(component, depth, null);
    }

    /** @param exclude компонент, который пропустить (например прежний, уже отвязанный) */
    private static Object findDynamicListComponent(Object component, int depth, Object exclude)
    {
        if (component == null || depth > 12)
            return null;
        if (component != exclude && DYNAMIC_LIST_COMPONENT_CLASS.equals(component.getClass().getName()))
            return component;
        for (Object child : AefFieldFocus.childComponents(component))
        {
            Object found = findDynamicListComponent(child, depth + 1, exclude);
            if (found != null)
                return found;
        }
        return null;
    }

    /**
     * Штатное сохранение по «ОК» при неактивном редакторе формы не срабатывает: оно захвачено на
     * компонент, который панель «Свойства» к тому моменту отвязала, а его модель погашена
     * ({@code Model is offline}). Подставить модель обратно нельзя — ни запомненную (мертва), ни
     * свежую (панель перестраивается ПОЗЖЕ: очередь движка выполняет задачу синхронно, а штатное
     * сохранение встаёт в неё сразу после возврата из {@code Window.open()}).
     *
     * <p>Поэтому запись повторяется на ЖИВОЙ модели заново построенного компонента —
     * {@link #replaySet} вызывает тот же самый штатный
     * {@code IFormDynamicListQueryModel.set(...)}, что и EDT. Логика записи в BM остаётся внутри
     * модели EDT, плагин лишь повторяет её вызов значениями из вью‑модели диалога.
     */
    private static void saveThroughLiveModel(DynamicListComponentRef ref, Object dialog)
    {
        if (ref == null || dialog == null)
        {
            log("save replay skipped: no captured component / dialog"); //$NON-NLS-1$
            return;
        }

        Object current = Global.invoke(ref.component(), "getModel"); //$NON-NLS-1$
        if (current != null)
        {
            log("component model alive — stock save will do the job"); //$NON-NLS-1$
            return;
        }

        Object viewModel = Global.getField(dialog, "model"); //$NON-NLS-1$
        if (viewModel == null)
        {
            log("dialog view-model not found"); //$NON-NLS-1$
            return;
        }
        scheduleSaveReplay(ref, viewModel, 0);
        silenceStockSave(ref);
    }

    /**
     * Гасит падение штатного сохранения. Снять его runnable с очереди нельзя, а его первое же
     * действие — {@code getModel().set(…)} на отвязанном компоненте, то есть {@code NPE} в журнал
     * ошибок при каждом сохранении с неактивным редактором формы. Поэтому компоненту подставляется
     * заглушка: динамический прокси на интерфейсах настоящей модели, все методы которого ничего не
     * делают. Штатный вызов проходит вхолостую (запись выполняет {@link #replaySet}), исключения
     * нет.
     *
     * <p>Заглушка снимается по таймеру: если бы панель переиспользовала этот компонент,
     * {@code setModel} позвал бы на прокси {@code detachFromModel()}.
     */
    private static void silenceStockSave(DynamicListComponentRef ref)
    {
        try
        {
            Class<?> modelClass = ref.model().getClass();
            Class<?>[] interfaces = modelClass.getInterfaces();
            if (interfaces.length == 0)
            {
                log("stub not installed: model has no interfaces"); //$NON-NLS-1$
                return;
            }

            Object stub = java.lang.reflect.Proxy.newProxyInstance(modelClass.getClassLoader(),
                interfaces, (proxy, method, args) -> stubResult(proxy, method, args));

            if (!Global.setFieldForce(ref.component(), "model", stub)) //$NON-NLS-1$
            {
                log("stub NOT installed"); //$NON-NLS-1$
                return;
            }
            log("stub model installed (silences stock save NPE)"); //$NON-NLS-1$

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.timerExec(STUB_LIFETIME_MS, () ->
                {
                    if (Global.invoke(ref.component(), "getModel") == stub) //$NON-NLS-1$
                        Global.setFieldForce(ref.component(), "model", null); //$NON-NLS-1$
                });
        }
        catch (Exception e)
        {
            DynamicListSettingsDialogDebug.problem("install stub model: " + e); //$NON-NLS-1$
        }
    }

    /**
     * Результат вызова метода заглушки. Возвращать {@code null} на всё нельзя: вызывающий код
     * ждёт объекты (например {@code IModel.validate()} → {@code IStatus}, и его
     * {@code getSeverity()} тут же падал бы с {@code NPE}). Поэтому для интерфейсного результата
     * отдаётся такая же вложенная заглушка — у неё {@code getSeverity()} вернёт {@code 0},
     * то есть {@code IStatus.OK}.
     */
    private static Object stubResult(Object proxy, java.lang.reflect.Method method, Object[] args)
    {
        Class<?> type = method.getReturnType();

        // Методы Object: без них прокси ломается на сравнении, в множествах и в конкатенации строк.
        switch (method.getName())
        {
        case "equals" -> { //$NON-NLS-1$
            if (args != null && args.length == 1)
                return Boolean.valueOf(proxy == args[0]);
        }
        case "hashCode" -> { //$NON-NLS-1$
            if (args == null || args.length == 0)
                return Integer.valueOf(System.identityHashCode(proxy));
        }
        case "toString" -> { //$NON-NLS-1$
            if (args == null || args.length == 0)
                return "ComfortStubModel"; //$NON-NLS-1$
        }
        default -> { /* обычный метод модели */ }
        }

        if (type.isInterface())
            return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] { type }, DynamicListSettingsDialogHook::stubResult);

        return defaultValue(type);
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive() || type == void.class)
            return null;
        if (type == boolean.class)
            return Boolean.FALSE;
        if (type == char.class)
            return Character.valueOf('\0');
        if (type == long.class)
            return Long.valueOf(0L);
        if (type == float.class)
            return Float.valueOf(0f);
        if (type == double.class)
            return Double.valueOf(0d);
        return Integer.valueOf(0);
    }

    /** Панель перестраивается не мгновенно — ждём появления живого компонента серией попыток. */
    private static void scheduleSaveReplay(DynamicListComponentRef ref, Object viewModel, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= SAVE_REPLAY_DELAYS.length)
        {
            log("save replay GAVE UP after " + attempt + " attempts"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        display.timerExec(SAVE_REPLAY_DELAYS[attempt], () ->
        {
            Object root = Global.invoke(ref.scene(), "getComponent"); //$NON-NLS-1$
            // Именно ЗАНОВО построенный компонент: на старом висит модель-заглушка
            // (silenceStockSave), и запись в неё была бы молчаливой потерей изменений.
            Object fresh = findDynamicListComponent(root, 0, ref.component());
            Object liveModel = fresh == null ? null : Global.invoke(fresh, "getModel"); //$NON-NLS-1$
            if (liveModel == null)
            {
                scheduleSaveReplay(ref, viewModel, attempt + 1);
                return;
            }

            boolean saved = replaySet(liveModel, viewModel);
            log("save replay attempt=" + attempt + " -> " + saved); //$NON-NLS-1$ //$NON-NLS-2$
            if (!saved)
            {
                scheduleSaveReplay(ref, viewModel, attempt + 1);
                return;
            }

            // Только Model.commit() — это getChange().apply() + уведомление. Scene.commit(component)
            // вызывать НЕЛЬЗЯ: он завершается engine.flushRunnableQueue(), а ожидание очереди
            // движка из UI‑потока вешает EDT намертво (штатный код делает это в потоке движка).
            Global.invoke(liveModel, "commit"); //$NON-NLS-1$
            log("save replay committed"); //$NON-NLS-1$
        });
    }

    /** Тот же вызов, что делает штатный {@code FormDynamicListComponent} после «ОК». */
    private static boolean replaySet(Object liveModel, Object viewModel)
    {
        try
        {
            Global.invoke(liveModel, "set", //$NON-NLS-1$
                Global.invoke(viewModel, "getQuery"), //$NON-NLS-1$
                Global.invoke(viewModel, "isDynamicallyReadData"), //$NON-NLS-1$
                Global.invoke(viewModel, "isAutoFillAvailableField"), //$NON-NLS-1$
                Global.invoke(viewModel, "getMainTable"), //$NON-NLS-1$
                Global.invoke(viewModel, "getSettings"), //$NON-NLS-1$
                Global.invoke(viewModel, "getKeyType"), //$NON-NLS-1$
                Global.invoke(viewModel, "getKeyFields"), //$NON-NLS-1$
                Global.invoke(viewModel, "getDataSetFields"), //$NON-NLS-1$
                Global.invoke(viewModel, "getCalculatedFields"), //$NON-NLS-1$
                Global.invoke(viewModel, "getParameters")); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            DynamicListSettingsDialogDebug.problem("save replay: " + e); //$NON-NLS-1$
            log("save replay FAILED: " + e); //$NON-NLS-1$
            return false;
        }
    }

    // --- демодализация -----------------------------------------------------------------------

    /**
     * Ставит отложенную часть перехвата через {@link #DEMODALIZE_DELAY_MS} после показа окна —
     * один раз на shell. До срабатывания окно остаётся штатно модальным, а хук не читает модель,
     * так что отложенная инициализация диалога (СКД‑схема динамического списка) идёт как в
     * штатной EDT.
     */
    private static void scheduleDelayedSetup(Shell shell, IEditorPart editorAtOpen)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (shell.getData(DEMODALIZE_SCHEDULED_KEY) != null)
            return;

        shell.setData(DEMODALIZE_SCHEDULED_KEY, Boolean.TRUE);
        shell.getDisplay().timerExec(DEMODALIZE_DELAY_MS, () ->
        {
            if (!shell.isDisposed())
                onDelayedSetup(shell, editorAtOpen);
        });
    }

    private static void demodalize(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;

        Object styleObj = Global.getField(shell, "style"); //$NON-NLS-1$
        if (styleObj instanceof Integer style && (style & SWT.APPLICATION_MODAL) != 0)
        {
            Global.setField(shell, "style", Integer.valueOf(style & ~SWT.APPLICATION_MODAL)); //$NON-NLS-1$
            log("cleared APPLICATION_MODAL (style 0x" //$NON-NLS-1$
                + Integer.toHexString(style) + ")"); //$NON-NLS-1$
        }

        Display display = shell.getDisplay();
        // Идемпотентно: убирает shell из modalShells[], если он там есть, иначе no‑op.
        Global.invoke(display, "clearModal", shell); //$NON-NLS-1$
        reenableSiblings(display, shell);
    }

    private static void reenableSiblings(Display display, Shell ourShell)
    {
        // Если поверх открыт ЧУЖОЙ модальный дочерний диалог (пикер поля/типа/выражения) —
        // блокировка workbench правильная, не вмешиваемся.
        Object modal = Global.invoke(display, "getModalShell"); //$NON-NLS-1$
        if (modal != null && modal != ourShell)
            return;

        for (Shell s : display.getShells())
        {
            if (s == ourShell || s.isDisposed())
                continue;

            Object st = Global.getField(s, "style"); //$NON-NLS-1$
            if (st instanceof Integer i
                && (i & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0)
                continue;

            if (!s.getEnabled())
                s.setEnabled(true);
            Global.invoke(s, "updateModal"); //$NON-NLS-1$
            WinWindowActivator.setWindowEnabled(WinWindowActivator.hwndFromShell(s), true);
        }
    }

    // --- заголовок --------------------------------------------------------------------------

    private static void scheduleTitle(Shell shell, IEditorPart editorAtOpen, int attempt)
    {
        if (shell.isDisposed())
            return;

        Display display = shell.getDisplay();
        display.timerExec(attempt == 0 ? 0 : 60, () ->
        {
            if (shell.isDisposed())
                return;

            String path = resolvePath(shell, editorAtOpen);
            if (path != null)
            {
                if (!path.equals(shell.getText()))
                    shell.setText(path);
                shell.setData(TITLE_KEY, path);
                log("title set: " + path); //$NON-NLS-1$
                return;
            }

            if (attempt < TITLE_RETRY_LIMIT)
                scheduleTitle(shell, editorAtOpen, attempt + 1);
            else
                DynamicListSettingsDialogDebug.problem("полный путь реквизита не определён"); //$NON-NLS-1$
        });
    }

    private static String resolvePath(Shell shell, IEditorPart editorAtOpen)
    {
        EObject attrEo = resolveAttribute(shell);
        String attrName = attributeName(attrEo);
        if (attrEo == null || attrName == null)
            return null;

        String formPath = resolveFormPath(attrEo, editorAtOpen);
        if (formPath == null)
            return null;

        return formPath + '.' + ATTRIBUTE_TOKEN + '.' + attrName;
    }

    private static String resolveFormPath(EObject attrEo, IEditorPart editorAtOpen)
    {
        for (EObject c = attrEo.eContainer(); c != null; c = c.eContainer())
        {
            if ("Form".equals(c.eClass().getName())) //$NON-NLS-1$
            {
                String p = GetRef.eObjectToFullName(c);
                if (p != null)
                    return p;
                break;
            }
        }

        if (editorAtOpen != null)
        {
            String p = GetRef.getRefFromEditor(editorAtOpen);
            if (p != null)
                return p;
        }

        IEditorPart now = activeEditor();
        return now != null ? GetRef.getRefFromEditor(now) : null;
    }

    // --- закрепление над workbench ---------------------------------------------------------

    private static void pinAboveOwner(Shell shell)
    {
        if (!PIN_ABOVE_OWNER || shell == null || shell.isDisposed())
            return;

        Shell owner = resolveOwnerShell();
        WinWindowActivator.clearShellTopmost(shell);
        WinWindowActivator.setShellAboveOwner(shell, owner, owner != null);
    }

    private static Shell resolveOwnerShell()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows != null && windows.length > 0)
                    window = windows[0];
            }
            if (window != null)
            {
                Shell workbenchShell = window.getShell();
                if (workbenchShell != null && !workbenchShell.isDisposed())
                    return workbenchShell;
            }
        }
        catch (Exception ignored)
        {
            // fallback: без владельца
        }
        return null;
    }

    // --- обслуживание -------------------------------------------------------------------------

    private static void installMaintenance(Shell shell)
    {
        Listener maintenance = e ->
        {
            if (shell.isDisposed())
                return;
            if (Boolean.TRUE.equals(shell.getData(DEMODALIZED_KEY)))
                demodalize(shell);
            Object title = shell.getData(TITLE_KEY);
            if (title instanceof String s && !s.equals(shell.getText()))
                shell.setText(s);
            pinAboveOwner(shell);
        };
        shell.addListener(SWT.Show, maintenance);
        shell.addListener(SWT.Activate, maintenance);
        shell.addListener(SWT.Deiconify, maintenance);
    }

    // --- распознавание окна ----------------------------------------------------------------

    /**
     * Только по классу диалога. Проверять заголовок нельзя: {@code "Динамический список"} —
     * заголовок и у штатного окна с сообщением об ошибке в тексте запроса. По заголовку такое
     * окно принималось за окно настроек, попадало в дедуп по тому же реквизиту и закрывалось
     * через полсекунды — пользователь не успевал прочитать ошибку.
     */
    private static boolean isDynamicListSettingsShell(Shell shell)
    {
        return resolveDialog(shell) != null;
    }

    private static Object resolveDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;

        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? shell.getData() : shell.getData(key);
            if (data != null && data.getClass().getName().endsWith(DIALOG_CLASS_SUFFIX))
                return data;
        }
        return null;
    }

    private static IEditorPart activeEditor()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            return page == null ? null : page.getActiveEditor();
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** Короткая запись в журнал «Комфорт» — вызовов много, читаемость важнее. */
    private static void log(String msg)
    {
        DynamicListSettingsDialogDebug.log(msg);
    }

    /**
     * Отладочный журнал канала «Комфорт». Включение: Параметры → Комфорт → «Вести журнал».
     */
    private static final class DynamicListSettingsDialogDebug
    {
        private static final String TAG = "DynListSettings"; //$NON-NLS-1$

        private DynamicListSettingsDialogDebug()
        {
        }

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
