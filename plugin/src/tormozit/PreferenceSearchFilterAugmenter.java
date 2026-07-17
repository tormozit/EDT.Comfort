package tormozit;

import java.util.List;

import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PatternFilter;

/**
 * Подключает к дереву категорий диалога «Параметры» дополнительный фильтр,
 * учитывающий {@link PreferenceSearchIndex} — тексты контролов страниц,
 * а не только их заголовки и keywords.
 */
final class PreferenceSearchFilterAugmenter
{
    private static final String WIRED_KEY = "tormozit.PreferenceSearchFilterAugmenter.wired"; //$NON-NLS-1$

    private static final String[] FILTERED_TREE_FIELD_CANDIDATES =
            {"filteredTree", "fFilteredTree", "tree"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private PreferenceSearchFilterAugmenter()
    {
    }

    static FilteredTree resolveDialogFilteredTree(PreferenceDialog dialog)
    {
        for (String candidate : FILTERED_TREE_FIELD_CANDIDATES)
        {
            Object value = Global.getField(dialog, candidate);
            if (value instanceof FilteredTree filteredTree)
                return filteredTree;
        }
        return null;
    }

    static void wireInto(PreferenceDialog dialog, FilteredTree filteredTree)
    {
        if (filteredTree == null || filteredTree.isDisposed())
            return;
        TreeViewer viewer = filteredTree.getViewer();
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(WIRED_KEY)))
            return;
        tree.setData(WIRED_KEY, Boolean.TRUE);

        ViewerFilter[] existing = viewer.getFilters();
        PatternFilter original = findPatternFilter(existing);
        if (original == null)
            return;

        PatternFilter augmented = new PatternFilter()
        {
            @Override
            protected boolean isLeafMatch(Viewer v, Object element)
            {
                String pattern = filteredTree.getFilterControl() == null ? null
                        : filteredTree.getFilterControl().getText();
                boolean originalMatch = isOriginalLeafMatch(original, v, element);
                String nodeId = element instanceof IPreferenceNode node ? node.getId() : null;
                boolean indexMatch = nodeId != null && PreferenceSearchIndex.getInstance().matches(nodeId, pattern);
                if (nodeId != null && pattern != null && !pattern.isBlank())
                {
                    Global.tempLog("preferenceSearchFilter", //$NON-NLS-1$
                            "pattern=[" + pattern + "] node=" + nodeId //$NON-NLS-1$ //$NON-NLS-2$
                            + " originalMatch=" + originalMatch + " indexMatch=" + indexMatch); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return originalMatch || indexMatch;
            }
        };

        ViewerFilter[] replaced = existing.clone();
        for (int i = 0; i < replaced.length; i++)
        {
            if (replaced[i] == original)
                replaced[i] = augmented;
        }
        viewer.setFilters(replaced);

        // FilteredTree обновляет паттерн только у своего собственного (original)
        // экземпляра PatternFilter — без этого слушателя у augmented паттерн
        // навсегда остаётся пустым, а PatternFilter.select() с пустым паттерном
        // безусловно матчит всё дерево.
        Text filterControl = filteredTree.getFilterControl();
        if (filterControl != null && !filterControl.isDisposed())
        {
            augmented.setPattern(filterControl.getText());
            filterControl.addModifyListener(e -> augmented.setPattern(filterControl.getText()));
            filterControl.addModifyListener(e -> applyContentOverlay(dialog, filterControl));
            // Обе кнопки — в одном узком ряду (одна колонка в GridLayout
            // родителя вместо двух), иначе суммарная ширина заметно растёт.
            Composite buttonsRow = FilterHistoryUi.createButtonsRow(filterControl.getParent());
            wireHistory(filterControl, buttonsRow);
            wireIndexButton(dialog, filterControl, tree.getDisplay(), buttonsRow);
            if (buttonsRow != null)
                filterControl.getParent().layout(true, true);
        }

        wireHighlighting(viewer, filterControl);

        // Раскрашивает не только дерево категорий, но и сами контролы уже
        // показанной страницы (справа) — именно там пользователь видит текст,
        // из-за которого страница вообще нашлась.
        applyContentOverlay(dialog, filterControl);
        viewer.addSelectionChangedListener(e -> scheduleContentOverlayRetries(dialog, filterControl, tree.getDisplay()));

        PreferenceSearchIndex.getInstance().addListener(nodeId ->
        {
            if (tree.isDisposed())
                return;
            tree.getDisplay().asyncExec(() ->
            {
                if (tree.isDisposed() || filteredTree.getFilterControl() == null
                        || filteredTree.getFilterControl().isDisposed())
                    return;
                String current = filteredTree.getFilterControl().getText();
                if (current != null && !current.isBlank())
                    viewer.refresh();
            });
        });
    }

    /** scopeId для {@link FilterHistoryStore} — отдельный от фильтра страницы «Клавиши». */
    private static final String HISTORY_SCOPE = "preferenceSearch"; //$NON-NLS-1$

    private static void wireHistory(Text filterControl, Composite buttonsRow)
    {
        FilterHistoryUi.wireKeyboard(filterControl, HISTORY_SCOPE);
        FilterHistoryUi.addHistoryButton(buttonsRow, filterControl, HISTORY_SCOPE);
    }

    private static final String OVERLAY_LABEL_KEY = "tormozit.PreferenceSearchFilterAugmenter.overlay.label"; //$NON-NLS-1$

    private static final String OVERLAY_BAR_KEY = "tormozit.PreferenceSearchFilterAugmenter.overlay.bar"; //$NON-NLS-1$

    /**
     * Кнопка-глиф справа от поля фильтра — единственный способ построить/
     * перестроить индекс (см. {@link PreferenceSearchIndex#forceRebuild}).
     * Индексация никогда не запускается автоматически: пока кэша нет вообще,
     * поле фильтра отключается с подсказкой «Обновить индекс фильтра»; устаревший
     * кэш (сигнатура не совпала) — поле остаётся рабочим на старых данных,
     * кнопка лишь мягко сигнализирует об этом.
     */
    private static void wireIndexButton(PreferenceDialog dialog, Text filterControl, Display display,
            Composite buttonsRow)
    {
        if (filterControl == null || filterControl.isDisposed() || buttonsRow == null || buttonsRow.isDisposed())
            return;

        String defaultMessage = filterControl.getMessage();

        // Объясняет непривычную логику поиска прямо в подсказке поля —
        // без этого AND-по-словам и гранулярность "один контрол" выглядят
        // как баг, а не как задуманное поведение.
        filterControl.setToolTipText(
                "Ищет по названию и ключевым словам страниц, а также по текстам настроек внутри них. " //$NON-NLS-1$
                + "Все слова запроса должны найтись в одном элементе."); //$NON-NLS-1$

        Label glyph = FilterHistoryUi.createGlyphButton(buttonsRow, "⟳", ""); //$NON-NLS-1$ //$NON-NLS-2$

        Shell[] overlayHolder = new Shell[1];

        PreferenceSearchIndex.ReadinessListener readinessListener = readiness ->
        {
            if (display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                applyReadinessUi(glyph, filterControl, defaultMessage, readiness);
                if (readiness == PreferenceSearchIndex.IndexReadiness.FRESH && overlayHolder[0] != null)
                {
                    if (!overlayHolder[0].isDisposed())
                        overlayHolder[0].dispose();
                    overlayHolder[0] = null;
                }
            });
        };
        PreferenceSearchIndex.getInstance().addReadinessListener(readinessListener);
        // Диалог открывается заново при каждом Window > Preferences — без
        // отписки слушатели копились бы в статическом синглтоне навсегда.
        filterControl.addDisposeListener(
                e -> PreferenceSearchIndex.getInstance().removeReadinessListener(readinessListener));

        // Отразить уже известное на момент подписки состояние — ensureLoaded
        // мог отработать раньше (см. порядок вызовов в PreferenceSearchIndexHook).
        PreferenceSearchIndex.IndexReadiness current = PreferenceSearchIndex.getInstance().getReadiness();
        if (current != null)
            applyReadinessUi(glyph, filterControl, defaultMessage, current);

        glyph.addListener(SWT.MouseUp, e ->
        {
            // Клик всегда запускает пересборку, даже если индекс уже
            // свежий — пользователь мог намеренно захотеть обновить его
            // (например, не доверяя сигнатуре), это явное действие.
            Shell overlay = createBlockingOverlay(dialog);
            overlayHolder[0] = overlay;
            Shell dialogShell = dialog.getShell();
            if (dialogShell != null && !dialogShell.isDisposed())
                dialogShell.addDisposeListener(de -> PreferenceSearchIndex.getInstance().cancelRebuild());
            PreferenceSearchIndex.getInstance().forceRebuild(dialog.getPreferenceManager(), display,
                    (done, total) -> display.asyncExec(() ->
                    {
                        if (overlayHolder[0] != null && !overlayHolder[0].isDisposed())
                            updateOverlayProgress(overlayHolder[0], done, total);
                    }));
        });
    }

    private static void applyReadinessUi(Label glyph, Text filterControl, String defaultMessage,
            PreferenceSearchIndex.IndexReadiness readiness)
    {
        if (glyph.isDisposed())
            return;
        switch (readiness)
        {
            case FRESH ->
            {
                glyph.setForeground(null);
                glyph.setToolTipText("Обновить индекс фильтра. Индекс актуален. (Комфорт)"); //$NON-NLS-1$
                if (!filterControl.isDisposed())
                {
                    filterControl.setEnabled(true);
                    filterControl.setMessage(defaultMessage);
                }
            }
            case STALE ->
            {
                glyph.setForeground(accentColor(glyph));
                glyph.setToolTipText(
                        "Обновить индекс фильтра. Обнаружены изменения состава плагинов — индекс устарел. (Комфорт)"); //$NON-NLS-1$
                if (!filterControl.isDisposed())
                {
                    filterControl.setEnabled(true);
                    filterControl.setMessage(defaultMessage);
                }
            }
            case NOT_BUILT ->
            {
                glyph.setForeground(accentColor(glyph));
                glyph.setToolTipText("Обновить индекс фильтра. Индекс ещё не построен. (Комфорт)"); //$NON-NLS-1$
                if (!filterControl.isDisposed())
                {
                    filterControl.setEnabled(false);
                    filterControl.setMessage("Обновить индекс фильтра"); //$NON-NLS-1$
                }
            }
            default ->
            {
            }
        }
    }

    private static Color accentColor(Control control)
    {
        Display display = control.getDisplay();
        return display.getSystemColor(SmartMatchHighlight.isDarkTheme() ? SWT.COLOR_YELLOW : SWT.COLOR_DARK_YELLOW);
    }

    /**
     * Плавающий Shell поверх контентной области диалога (дерево + текущая
     * страница) на время построения индекса — блокирует ввод, но НЕ
     * перекрывает панель кнопок (OK/Отмена/Применить), так что закрыть
     * диалог можно в любой момент, не дожидаясь завершения индексации.
     */
    private static Shell createBlockingOverlay(PreferenceDialog dialog)
    {
        Shell dialogShell = dialog.getShell();
        if (dialogShell == null || dialogShell.isDisposed())
            return null;

        Shell overlay = new Shell(dialogShell, SWT.NO_TRIM | SWT.ON_TOP);
        overlay.setLayout(new GridLayout(1, false));
        overlay.setBackground(dialogShell.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

        Label label = new Label(overlay, SWT.CENTER);
        label.setText("Индексация страниц: 0/0"); //$NON-NLS-1$
        label.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        ProgressBar bar = new ProgressBar(overlay, SWT.SMOOTH | SWT.HORIZONTAL);
        GridData barData = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        barData.widthHint = 320;
        bar.setLayoutData(barData);
        bar.setMinimum(0);
        bar.setMaximum(1);

        overlay.setData(OVERLAY_LABEL_KEY, label);
        overlay.setData(OVERLAY_BAR_KEY, bar);

        positionOverlay(overlay, dialog, dialogShell);
        overlay.layout(true, true);
        overlay.open();

        // overlay — отдельный top-level Shell, он НЕ следует автоматически
        // за перемещением/ресайзом родительского диалога (родитель у Shell
        // влияет только на стек окон и время жизни, не на позицию) — без
        // этого слежения оверлей "отклеивается" от диалога при перетаскивании.
        Listener trackListener = e -> positionOverlay(overlay, dialog, dialogShell);
        dialogShell.addListener(SWT.Move, trackListener);
        dialogShell.addListener(SWT.Resize, trackListener);
        overlay.addDisposeListener(e ->
        {
            if (!dialogShell.isDisposed())
            {
                dialogShell.removeListener(SWT.Move, trackListener);
                dialogShell.removeListener(SWT.Resize, trackListener);
            }
        });

        return overlay;
    }

    /**
     * Границы — вся клиентская область диалога за вычетом панели кнопок
     * снизу. Панель кнопок ищем рефлексией (штатное protected-поле JFace
     * {@code Dialog.buttonBar}); если не нашли — грубый отступ снизу как
     * fallback, не пытаемся точно угадать компоновку чужого диалога.
     */
    private static void positionOverlay(Shell overlay, PreferenceDialog dialog, Shell dialogShell)
    {
        org.eclipse.swt.graphics.Rectangle clientArea = dialogShell.getClientArea();
        Point topLeftDisplay = dialogShell.toDisplay(clientArea.x, clientArea.y);

        int height = clientArea.height;
        Object buttonBarObj = Global.getField(dialog, "buttonBar"); //$NON-NLS-1$
        if (buttonBarObj instanceof Control buttonBar && !buttonBar.isDisposed())
        {
            org.eclipse.swt.graphics.Rectangle barBounds = buttonBar.getBounds();
            Point barTopLeftDisplay = buttonBar.getParent().toDisplay(barBounds.x, barBounds.y);
            int barTopRelative = barTopLeftDisplay.y - topLeftDisplay.y;
            if (barTopRelative > 0 && barTopRelative < height)
                height = barTopRelative;
        }
        else
        {
            height = Math.max(60, height - 60);
        }

        overlay.setBounds(topLeftDisplay.x, topLeftDisplay.y, clientArea.width, height);
    }

    private static void updateOverlayProgress(Shell overlay, int done, int total)
    {
        if (overlay == null || overlay.isDisposed())
            return;
        Object labelObj = overlay.getData(OVERLAY_LABEL_KEY);
        Object barObj = overlay.getData(OVERLAY_BAR_KEY);
        if (labelObj instanceof Label label && !label.isDisposed())
            label.setText("Индексация страниц: " + done + "/" + total); //$NON-NLS-1$ //$NON-NLS-2$
        if (barObj instanceof ProgressBar bar && !bar.isDisposed())
        {
            bar.setMaximum(Math.max(total, 1));
            bar.setSelection(done);
        }
    }

    /**
     * При переключении страницы в дереве штатное создание/показ новой
     * страницы иногда происходит не в том же такте UI-потока, что наш
     * слушатель выбора — единственный {@code asyncExec} может промахнуться
     * мимо ещё не готовой страницы. Повторяем несколько раз с небольшой
     * паузой (тот же приём, что poll-цикл в {@code ComfortKeysPageHook}) —
     * дёшево, т.к. сама подсветка — это просто перебор уже существующих
     * контролов и setBackground.
     */
    private static void scheduleContentOverlayRetries(PreferenceDialog dialog, Text filterControl, Display display)
    {
        if (display == null || display.isDisposed())
            return;
        int[] delays = {0, 100, 250, 500};
        for (int delay : delays)
        {
            display.timerExec(delay, () ->
            {
                if (!display.isDisposed())
                    applyContentOverlay(dialog, filterControl);
            });
        }
    }

    private static void applyContentOverlay(PreferenceDialog dialog, Text filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return;
        Object selected = dialog.getSelectedPage();
        if (!(selected instanceof IPreferencePage page))
            return;
        Control control = page.getControl();
        if (!(control instanceof Composite composite) || composite.isDisposed())
            return;

        SmartMatcher matcher = new SmartMatcher(filterControl.getText());
        installContentOverlay(composite, matcher);
    }

    private static void installContentOverlay(Composite composite, SmartMatcher matcher)
    {
        for (Control child : composite.getChildren())
        {
            String rawText = null;
            if (child instanceof Label label)
                rawText = label.getText();
            else if (child instanceof Button button)
                rawText = button.getText();
            else if (child instanceof CLabel clabel)
                rawText = clabel.getText();
            else if (child instanceof Link link)
                rawText = PreferenceSearchIndex.stripMarkup(link.getText());

            if (rawText != null)
            {
                // Гранула совпадения — этот конкретный контрол: все слова
                // запроса должны быть именно в его тексте, а не "одолжены" у
                // соседних виджетов страницы. Текст и подсказка (tooltip)
                // считаются одним атомом (см. PreferenceSearchIndex) — иначе
                // подсветка тут разошлась бы с тем, что реально нашлось в
                // индексе. При несовпадении — явный сброс фона (не оставляем
                // подсветку от предыдущего текста фильтра).
                String text = PreferenceSearchIndex.fuseTextAndTooltip(child, rawText);
                boolean isMatch = text != null && !matcher.isEmpty && matcher.matches(text);
                child.setBackground(isMatch ? controlHighlightColor(child) : null);
            }

            if (child instanceof Composite childComposite)
                installContentOverlay(childComposite, matcher);
        }
    }

    private static Color cachedLightHighlight;

    private static Color cachedDarkHighlight;

    private static Color controlHighlightColor(Control control)
    {
        Display display = control.getDisplay();
        if (SmartMatchHighlight.isDarkTheme())
        {
            if (cachedDarkHighlight == null || cachedDarkHighlight.isDisposed())
                cachedDarkHighlight = new Color(display, 90, 74, 30);
            return cachedDarkHighlight;
        }
        if (cachedLightHighlight == null || cachedLightHighlight.isDisposed())
            cachedLightHighlight = new Color(display, 255, 246, 178);
        return cachedLightHighlight;
    }

    private static void wireHighlighting(TreeViewer viewer, Text filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return;

        // Не оборачиваем/не запоминаем текущий viewer.getLabelProvider() —
        // StructuredViewer.setLabelProvider() при замене сам диспозит старый
        // provider, и делегирование в уже disposed объект было бы хрупким.
        // Элементы дерева — всегда IPreferenceNode, у которого есть тот же
        // самый getLabelText()/getLabelImage(), что использовал бы стоковый
        // provider — используем их напрямую, без обёртки над чужим provider.
        viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(
                new PreferenceSearchLabelDecorator(filterControl)));
    }

    /**
     * Подсвечивает найденные фрагменты в подписи узла (заголовок/keywords —
     * штатное совпадение), либо, если совпадение найдено только внутри текстов
     * контролов страницы (см. {@link PreferenceSearchIndex}), подсвечивает
     * подпись целиком — точный офсет внутри страницы не имеет смысла показывать
     * в дереве категорий.
     */
    private static final class PreferenceSearchLabelDecorator extends LabelProvider implements IStyledLabelProvider
    {
        private final Text filterControl;

        PreferenceSearchLabelDecorator(Text filterControl)
        {
            this.filterControl = filterControl;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            String label = element instanceof IPreferenceNode node ? node.getLabelText() : String.valueOf(element);
            StyledString styled = new StyledString(label == null ? "" : label); //$NON-NLS-1$
            if (filterControl.isDisposed())
                return styled;
            String pattern = filterControl.getText();
            if (pattern == null || pattern.isBlank() || label == null)
                return styled;

            SmartMatcher matcher = new SmartMatcher(pattern);
            // Гранула совпадения — заголовок целиком: подсвечиваем вхождения
            // только если ВСЕ слова запроса найдены в самом заголовке, а не
            // "одолжены" у текстов контролов страницы.
            if (matcher.matches(label))
            {
                List<SmartMatcher.HighlightRange> ranges = matcher.getHighlightRanges(label.toLowerCase());
                SmartMatchHighlight.applyRanges(styled, ranges, filterControl);
            }
            else if (element instanceof IPreferenceNode node
                    && PreferenceSearchIndex.getInstance().matches(node.getId(), pattern))
            {
                styled.setStyle(0, styled.length(), SmartMatchHighlight.styler(filterControl));
            }
            return styled;
        }

        @Override
        public Image getImage(Object element)
        {
            return element instanceof IPreferenceNode node ? node.getLabelImage() : null;
        }
    }

    private static boolean isOriginalLeafMatch(PatternFilter original, Viewer v, Object element)
    {
        Object result = Global.invoke(original, "isLeafMatch", v, element); //$NON-NLS-1$
        return Boolean.TRUE.equals(result);
    }

    private static PatternFilter findPatternFilter(ViewerFilter[] filters)
    {
        if (filters == null)
            return null;
        for (ViewerFilter filter : filters)
        {
            if (filter instanceof PatternFilter patternFilter)
                return patternFilter;
        }
        return null;
    }
}
