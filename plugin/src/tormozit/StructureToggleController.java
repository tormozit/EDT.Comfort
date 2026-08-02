package tormozit;

import org.eclipse.compare.ITypedElement;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.ToolBar;

/**
 * Кнопка «Структура» — панель {@link CompareDialogStructurePanel} (дерево различающихся
 * секций модуля, см. {@link BslModuleStructureDiff}) над текстом попарного сравнения BSL.
 * Общий для всех видов окон «Сравнение текста», поддерживающих встроенный язык —
 * {@link CompareDialogCurrentLinesHook} (штатный EDT {@code DtCompareEditorInput}),
 * {@link GitCompareCurrentLinesHook} (EGit «Сравнить с … »), см. также, где ещё добавляется
 * «Сравнить ИР» ({@code IrCompareValuesHandler.MENU_LABEL}) — тот же охват, плюс 3-way
 * «Объединение» ({@link ThreeSideMergeCurrentLinesHook}) — но ТОЛЬКО у plain-варианта
 * ({@code ThreeSideTextMergeDialog}, без разбора структуры); у structured-варианта
 * ({@code CompareBslModuleWithParsingModuleStructureDialog}) уже есть штатная панель
 * структуры — дублировать её не нужно (см. проверку {@code node != null} в вызывающем коде).
 *
 * <p>Состояние (показана/скрыта) персистентно между сессиями EDT —
 * {@link ComfortSettings#isCompareStructureVisible()}, по умолчанию выключена. При выборе узла
 * дерева — скролл+подсветка диапазона в существующем документе (не подмена содержимого
 * вьюера); выбор корневого узла — штатное поведение, без подсветки. Разбор текста при включении
 * панели выполняется синхронно на UI-потоке — для очень больших модулей возможна заметная
 * задержка.
 */
final class StructureToggleController
{
    /** Запасной вариант высоты, если по какой-то причине не удалось вычислить по 6 строкам дерева. */
    private static final int FALLBACK_PANEL_HEIGHT = 160;
    private static final int DEFAULT_VISIBLE_ROWS = 6;
    private static final int MIN_PANEL_HEIGHT = 60;

    private final Composite wrapper;
    private final Control viewerControl;
    private final StyledText leftText;
    private final StyledText rightText;
    /** Среднее поле (результат объединения) — только у 3-way, см. {@link #setResultText}. */
    private StyledText resultText;
    private final String leftLabel;
    private final String rightLabel;
    /** Вид окна сравнения — ключ для раздельного запоминания высоты панели (см. ComfortSettings). */
    private final String contextId;
    private CompareDialogStructurePanel panel;
    private Sash sash;
    private GridData panelLayoutData;
    private boolean lineHighlightPrimed;
    /** Только для разовой активации {@code CursorLinePainter} — см. {@link #setSourceViewers}. */
    private SourceViewer leftSourceViewer;
    private SourceViewer rightSourceViewer;

    StructureToggleController(Composite wrapper, Control viewerControl, StyledText leftText,
        StyledText rightText, String leftLabel, String rightLabel, String contextId)
    {
        this.wrapper = wrapper;
        this.viewerControl = viewerControl;
        this.leftText = leftText;
        this.rightText = rightText;
        this.leftLabel = leftLabel;
        this.rightLabel = rightLabel;
        this.contextId = contextId;
    }

    /**
     * Среднее поле (результат объединения, 3-way) — по умолчанию нет (2-way/git/paste), пара
     * секций там просто не с чем сопоставлять. Вызывается сразу после конструктора, только
     * из {@link ThreeSideMergeCurrentLinesHook} — отдельный сеттер, а не параметр конструктора,
     * чтобы не трогать остальные 3 места создания.
     */
    void setResultText(StyledText resultText)
    {
        this.resultText = resultText;
    }

    /**
     * {@link SourceViewer} лево/право — только для {@link #primeLineHighlightOnce}, не для
     * навигации (там по-прежнему работаем с {@link StyledText} напрямую). Опционально: если
     * вызывающий код их не передал (или они недоступны через рефлексию), приоритет — вызов
     * {@code setFocus()} остаётся как запасной вариант.
     */
    void setSourceViewers(SourceViewer leftSourceViewer, SourceViewer rightSourceViewer)
    {
        this.leftSourceViewer = leftSourceViewer;
        this.rightSourceViewer = rightSourceViewer;
    }

    /**
     * Определение BSL-стороны — НЕ через «расширение по последней точке в имени» (ломается на
     * именах общих модулей вида «ОбщегоНазначения.Модуль», где точка — разделитель
     * квалификатора, а не расширение файла): по {@code getType()} напрямую (канонический тип
     * 1C) и явному суффиксу {@code .bsl} в имени — тот же приём, что
     * {@code CompareTabularDocumentsInIr.isMxlxTypedElement}.
     */
    static boolean isBslCompare(ITypedElement left, ITypedElement right)
    {
        return isBslTypedElement(left) || isBslTypedElement(right);
    }

    private static boolean isBslTypedElement(ITypedElement element)
    {
        if (element == null)
            return false;
        if ("bsl".equalsIgnoreCase(element.getType())) //$NON-NLS-1$
            return true;
        String name = element.getName();
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".bsl"); //$NON-NLS-1$
    }

    /**
     * Кнопка «Структура» — рядом с заголовком {@code ViewForm.topLeft} (например,
     * заголовок «Сравнение текста» у {@code CompareViewerPane}, или дропдаун «Объединение
     * встроенного языка» у 3-way), а не в {@code toolBarManager}/{@code topCenter} (тот
     * прижимается к ПРАВОМУ краю панели, из-за чего кнопка визуально оказывается далеко от
     * заголовка независимо от порядка элементов внутри него). {@code CompareViewerPane} сам
     * является {@code ViewForm} — оборачиваем текущий {@code topLeft} вместе со своим
     * {@code ToolBar} в общий composite и ставим его как новый {@code topLeft}.
     *
     * <p>Идемпотентно: повторный вызов для уже обёрнутой панели пропускается (маркер в
     * {@code viewForm.getData}), чтобы не наслаивать обёртки при повторной перестройке тулбара.
     */
    static void placeToggleButtonAtViewFormTopLeft(ViewForm viewForm, StructureToggleController controller)
    {
        String marker = "comfort.structureToggleAtTopLeft"; //$NON-NLS-1$
        if (Boolean.TRUE.equals(viewForm.getData(marker)))
            return;
        viewForm.setData(marker, Boolean.TRUE);

        Control originalTopLeft = viewForm.getTopLeft();
        Composite wrapper = new Composite(viewForm, SWT.NONE);
        GridLayout wrapperLayout = new GridLayout(originalTopLeft != null ? 2 : 1, false);
        wrapperLayout.marginWidth = 0;
        wrapperLayout.marginHeight = 0;
        wrapper.setLayout(wrapperLayout);
        if (originalTopLeft != null && !originalTopLeft.isDisposed())
        {
            originalTopLeft.setParent(wrapper);
            originalTopLeft.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        }
        ToolBar toggleBar = new ToolBar(wrapper, SWT.FLAT);
        toggleBar.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        ToolBarManager toggleBarManager = new ToolBarManager(toggleBar);
        toggleBarManager.add(controller.createToggleAction());
        toggleBarManager.update(true);
        viewForm.setTopLeft(wrapper);
        viewForm.layout(true, true);
    }

    Action createToggleAction()
    {
        Action action = new Action("Структура", IAction.AS_CHECK_BOX) //$NON-NLS-1$
        {
            @Override
            public void run()
            {
                setVisible(isChecked());
            }
        };
        action.setToolTipText("Структура различий модуля" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        boolean initiallyVisible = ComfortSettings.isCompareStructureVisible();
        action.setChecked(initiallyVisible);
        if (initiallyVisible)
        {
            /*
             * НЕ synchronously здесь — createToggleAction() вызывается прямо во время
             * построения тулбара, когда leftText/rightText уже существуют, но реальный текст
             * документа может ещё не быть загружен (тот же класс гонки, что и с фокусом —
             * см. primeLineHighlightOnce). Из-за этого при персистентном "Структура включена"
             * с прошлой сессии дерево иногда строилось по пустому/неполному содержимому (0
             * отличий) — выключить/включить чинило, потому что к этому моменту текст уже
             * успевал подгрузиться. Откладываем на asyncExec — та же причина, тот же приём.
             */
            Display display = wrapper != null && !wrapper.isDisposed() ? wrapper.getDisplay() : null;
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> setVisible(true));
            else
                setVisible(true);
        }
        return action;
    }

    private void setVisible(boolean visible)
    {
        ComfortSettings.setCompareStructureVisible(visible);
        if (visible)
        {
            if (panel != null && !panel.getControl().isDisposed())
                return;
            if (leftText == null || leftText.isDisposed() || rightText == null || rightText.isDisposed())
            {
                Global.tempLog("CompareDialogStructure", "setVisible: leftText/rightText недоступен " //$NON-NLS-1$ //$NON-NLS-2$
                    + "(leftText=" + leftText + " rightText=" + rightText + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return;
            }
            primeLineHighlightOnce();
            String leftContent = leftText.getText();
            String rightContent = rightText.getText();
            Global.tempLog("CompareDialogStructure", "setVisible: leftChars=" + leftContent.length() //$NON-NLS-1$ //$NON-NLS-2$
                + " rightChars=" + rightContent.length()); //$NON-NLS-1$
            panel = CompareDialogStructurePanel.create(wrapper, leftContent, rightContent,
                labelOrDefault(leftLabel, "Слева"), labelOrDefault(rightLabel, "Справа"), //$NON-NLS-1$ //$NON-NLS-2$
                this::onNodeSelected, this::onNodeDoubleClicked);
            Global.tempLog("CompareDialogStructure", "setVisible: viewerControl=" //$NON-NLS-1$ //$NON-NLS-2$
                + (viewerControl != null ? System.identityHashCode(viewerControl) : "null") //$NON-NLS-1$
                + " wrapperChildrenBeforeMoveAbove=" + describeWrapperChildren()); //$NON-NLS-1$
            panel.getControl().moveAbove(viewerControl);
            Global.tempLog("CompareDialogStructure", //$NON-NLS-1$
                "setVisible: wrapperChildrenAfterMoveAbove=" + describeWrapperChildren()); //$NON-NLS-1$ //$NON-NLS-2$
            panelLayoutData = new GridData(SWT.FILL, SWT.FILL, true, false);
            panelLayoutData.heightHint = ComfortSettings.getCompareStructureHeight(contextId, computeDefaultHeight());
            panel.getControl().setLayoutData(panelLayoutData);

            // Полоса изменения высоты панели структуры — перетаскивается мышью между ней и текстом.
            sash = new Sash(wrapper, SWT.HORIZONTAL);
            sash.moveAbove(viewerControl);
            sash.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            sash.addListener(SWT.Selection, this::onSashDragged);
        }
        else if (panel != null)
        {
            if (!panel.getControl().isDisposed())
                panel.getControl().dispose();
            if (sash != null && !sash.isDisposed())
                sash.dispose();
            panel = null;
            sash = null;
            panelLayoutData = null;
        }
        if (!wrapper.isDisposed())
            wrapper.layout(true, true);
    }

    /** Диагностика — класс+identityHashCode прямых детей {@link #wrapper}, по порядку. */
    private String describeWrapperChildren()
    {
        if (wrapper == null || wrapper.isDisposed())
            return "(disposed)"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (Control child : wrapper.getChildren())
            sb.append('[').append(child.getClass().getSimpleName()).append(' ')
                .append(System.identityHashCode(child)).append("] "); //$NON-NLS-1$
        return sb.toString();
    }

    private void onSashDragged(Event event)
    {
        // Двигаем в реальном времени (на каждое SWT.DRAG-событие тоже) — не только по отпусканию.
        if (panelLayoutData == null || wrapper.isDisposed())
            return;
        int newHeight = Math.max(MIN_PANEL_HEIGHT, event.y);
        panelLayoutData.heightHint = newHeight;
        wrapper.setRedraw(false); // без этого частый layout во время live-перетаскивания заметно мерцает
        try
        {
            wrapper.layout(true, true);
        }
        finally
        {
            wrapper.setRedraw(true);
        }
        // Сохраняем только по отпусканию — не на каждое DRAG-событие (не бомбить preference store).
        if (event.detail != SWT.DRAG)
            ComfortSettings.setCompareStructureHeight(contextId, newHeight);
    }

    /** Высота на {@link #DEFAULT_VISIBLE_ROWS} строк дерева — по умолчанию, если для {@link #contextId} ничего не сохранено. */
    private int computeDefaultHeight()
    {
        int height = panel.computeHeightForRows(DEFAULT_VISIBLE_ROWS);
        return height > 0 ? height : FALLBACK_PANEL_HEIGHT;
    }

    /**
     * Подсветка «текущей строки» — штатный JFace {@code org.eclipse.jface.text.CursorLinePainter}
     * (декомпилирован в {@code .tmp/bundles/jface-text/CursorLinePainter.javap.txt}, подтверждено
     * байткодом, не догадка): он подписывается как {@code StyledText.LineBackgroundListener}
     * (метод {@code lineGetBackground} читает {@code getCaretOffset()} НАПРЯМУЮ и вживую при
     * каждой перерисовке — значит, после подписки любая последующая программная перестановка
     * каретки подсвечивается верно) только ВНУТРИ {@code paint(int)}, а тот вызывается JFace
     * при смене выделения ЧЕРЕЗ САМ {@code ITextViewer} ({@code setSelectedRange}/выбор мышью) —
     * прямой вызов {@code StyledText.setSelectionRange} этот путь не задействует и подписки не
     * вызывает. Поэтому «Структура» слева не подсвечивалась, пока пользователь не кликал в левое
     * поле САМ хотя бы раз (после чего подписка уже стоит навсегда).
     *
     * <p>Проводим точно такую же «активацию» сами — через {@link SourceViewer#setSelectedRange}
     * (тот самый штатный API), выставляя ТЕКУЩЕЕ же выделение (ничего не меняя визуально) —
     * этого достаточно, чтобы {@code paint()} отработал один раз и подписка встала.
     * {@code setFocus()} оставлен запасным вариантом, если {@link SourceViewer} недоступен
     * (например, рефлексия не нашла поле).
     */
    private void primeLineHighlightOnce()
    {
        if (lineHighlightPrimed)
            return;
        lineHighlightPrimed = true;
        /*
         * Если «Структура» уже была включена в прошлой сессии (персистентное состояние),
         * setVisible(true) вызывается прямо во время построения тулбара — окно ещё не показано.
         * Откладываем на asyncExec (тот же приём, что FilterInputBox.scheduleFocusWhenReady),
         * чтобы это происходило уже ПОСЛЕ того, как штатный UI закончит показывать окно.
         */
        Display display = wrapper != null && !wrapper.isDisposed() ? wrapper.getDisplay() : null;
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            boolean leftPrimed = primeSourceViewer(leftSourceViewer, leftText);
            boolean rightPrimed = primeSourceViewer(rightSourceViewer, rightText);
            Global.tempLog("CompareDialogStructure", "primeLineHighlightOnce (asyncExec): " //$NON-NLS-1$ //$NON-NLS-2$
                + "leftPrimed=" + leftPrimed + " rightPrimed=" + rightPrimed); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    /** @return {@code true}, если удалось провести активацию (через SourceViewer или запасной setFocus) */
    private static boolean primeSourceViewer(SourceViewer sourceViewer, StyledText fallbackText)
    {
        if (sourceViewer != null && sourceViewer.getTextWidget() != null && !sourceViewer.getTextWidget().isDisposed())
        {
            Point range = sourceViewer.getSelectedRange();
            sourceViewer.setSelectedRange(range.x, range.y);
            return true;
        }
        return fallbackText != null && !fallbackText.isDisposed() && fallbackText.setFocus();
    }

    private void onNodeSelected(BslModuleStructureDiff.DiffNode node)
    {
        if (node == null)
            return; // корень — штатное поведение, без подсветки
        /*
         * По просьбе — не выделять весь метод, а сделать его ПЕРВУЮ строку текущей с обеих
         * сторон (как обычный клик по строке — тогда сработает уже готовая синхронизация
         * «Текущая строка», см. TwoSideCurrentLinesSync.hook, слушает CaretListener/SWT.Modify,
         * которые StyledText шлёт и на программные изменения каретки).
         */
        // Общий отступ строк от верха — единый для всех полей, чтобы строка заголовка метода
        // оказалась на ОДНОМ уровне везде, а не у каждого поля по своей пропорции высоты.
        int topOffsetLines = computeSharedTopOffsetLines();
        if (node.hasLeft())
            activateFirstLine(leftText, node.leftOffset, node.leftLength, topOffsetLines);
        if (node.hasRight())
            activateFirstLine(rightText, node.rightOffset, node.rightLength, topOffsetLines);
        if (node.kind != BslModuleStructureDiff.Kind.SYNTAX_ERROR)
            activateMatchingSectionInResult(node.label, topOffsetLines);
    }

    /**
     * Двойной клик — переход к первому отличию ВНУТРИ метода, а не к его заголовку. Не пишем
     * свой построчный diff — сначала штатная активация заголовка (как при обычном клике, чтобы
     * встроенная навигация точно знала, откуда искать), затем штатная же команда
     * {@code org.eclipse.compare.selectNextChange} («Следующее различие» в тулбаре — та же
     * команда работает и в 2-way, и в 3-way, см. лог фактического порядка тулбара в
     * GitCompareCurrentLinesHook, где она видна как штатный пункт).
     *
     * <p>Только для {@code CHANGED} (метод есть с обеих сторон, есть что сравнивать внутри).
     * У ADDED/REMOVED/SYNTAX_ERROR другой стороны для сравнения нет — «следующее различие»
     * там уводит навигацию к следующему по документу изменению вообще, не имеющему отношения
     * к выбранному узлу, — вредно, а не полезно.
     */
    private void onNodeDoubleClicked(BslModuleStructureDiff.DiffNode node)
    {
        if (node == null)
            return;
        onNodeSelected(node);
        if (node.kind != BslModuleStructureDiff.Kind.CHANGED)
            return;
        try
        {
            org.eclipse.ui.handlers.IHandlerService handlerService = org.eclipse.ui.PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getService(org.eclipse.ui.handlers.IHandlerService.class);
            if (handlerService != null)
                handlerService.executeCommand("org.eclipse.compare.selectNextChange", null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLog("CompareDialogStructure", "onNodeDoubleClicked: selectNextChange failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Берём высоту видимой области первого доступного поля (обычно совпадает у всех) — единая точка отсчёта. */
    private int computeSharedTopOffsetLines()
    {
        StyledText reference = leftText != null && !leftText.isDisposed() ? leftText
            : rightText != null && !rightText.isDisposed() ? rightText
            : resultText != null && !resultText.isDisposed() ? resultText : null;
        if (reference == null)
            return 0;
        int lineHeight = Math.max(1, reference.getLineHeight());
        int visibleLines = Math.max(1, reference.getClientArea().height / lineHeight);
        return visibleLines / 3;
    }

    /**
     * Среднее поле не участвует в сопоставлении структуры (сравниваются только левый/правый
     * тексты, см. {@link BslModuleStructureDiff#diff}) — офсета для него в {@code DiffNode} нет.
     * Разбираем результат отдельно и ищем секцию с тем же именем (меткой), что и выбранный узел —
     * при точном совпадении имени метода/секции это тот же метод, даже если объединение изменило
     * содержимое. Разбор — на каждый клик (не кэшируется): текст результата меняется в процессе
     * объединения, а разбор одного модуля быстрый (тот же {@link BslModuleStructureParser}, что
     * уже используется синхронно при каждом открытии панели).
     */
    private void activateMatchingSectionInResult(String label, int topOffsetLines)
    {
        if (resultText == null || resultText.isDisposed() || label == null)
            return;
        BslModuleStructureParser.ParseOutcome outcome = BslModuleStructureParser.parse(resultText.getText());
        if (outcome.root == null)
            return;
        BslModuleStructureParser.SectionNode section = findSection(outcome.root.children, label);
        if (section != null)
            activateFirstLine(resultText, section.offset, section.length, topOffsetLines);
    }

    private static BslModuleStructureParser.SectionNode findSection(
        java.util.List<BslModuleStructureParser.SectionNode> nodes, String label)
    {
        for (BslModuleStructureParser.SectionNode node : nodes)
        {
            if (node.label.equals(label))
                return node;
            BslModuleStructureParser.SectionNode found = findSection(node.children, label);
            if (found != null)
                return found;
        }
        return null;
    }

    /**
     * По чёткой формулировке задачи — активировать СТРОКУ ЗАГОЛОВКА МЕТОДА С ИМЕНЕМ МЕТОДА, а не
     * «первую содержательную строку после того, что нужно пропустить» (тот путь заводит в
     * бесконечный список частных случаев — пустые строки, директивы, один комментарий, несколько
     * комментариев подряд, и т.п., каждый следующий нужно долавливать отдельно). Вместо перечня
     * «что пропускать» ищем то, что ИЩЕМ: строку, начинающуюся с ключевого слова заголовка
     * (Процедура/Функция/Область), в пределах диапазона секции {@code [offset, offset+length)}.
     * Не найдено (раздел без такого заголовка, например «ОписаниеПеременных») — берём первую
     * строку диапазона как есть.
     */
    private static void activateFirstLine(StyledText text, int offset, int length, int topOffsetLines)
    {
        if (text == null || text.isDisposed())
            return;
        int max = text.getCharCount();
        int safeOffset = Math.max(0, Math.min(offset, max));
        int safeEndOffset = Math.max(safeOffset, Math.min(offset + Math.max(length, 0), max));
        int startLine = text.getLineAtOffset(safeOffset);
        int endLine = text.getLineAtOffset(safeEndOffset);

        int line = startLine;
        for (int i = startLine; i <= endLine; i++)
        {
            if (isHeaderLine(text.getLine(i)))
            {
                line = i;
                break;
            }
        }

        int lineStartOffset = text.getOffsetAtLine(line);
        text.setSelectionRange(lineStartOffset, 0); // только каретка, без выделения текста
        int targetTopIndex = Math.max(0, line - topOffsetLines);
        text.setTopIndex(targetTopIndex);
        Global.tempLog("CompareDialogStructure", "activateFirstLine: line=" + line //$NON-NLS-1$ //$NON-NLS-2$
            + " topOffsetLines=" + topOffsetLines + " targetTopIndex=" + targetTopIndex //$NON-NLS-1$ //$NON-NLS-2$
            + " topIndexRightAfterSet=" + text.getTopIndex() //$NON-NLS-1$
            + " lineCount=" + text.getLineCount() + " clientHeight=" + text.getClientArea().height //$NON-NLS-1$ //$NON-NLS-2$
            + " lineHeight=" + text.getLineHeight() + " visible=" + text.isVisible() //$NON-NLS-1$ //$NON-NLS-2$
            + " enabled=" + text.isEnabled() + " widget=" + System.identityHashCode(text)); //$NON-NLS-1$ //$NON-NLS-2$
        Display display = text.getDisplay();
        int finalLine = line;
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() ->
            {
                if (!text.isDisposed())
                    Global.tempLog("CompareDialogStructure", "activateFirstLine: line=" + finalLine //$NON-NLS-1$ //$NON-NLS-2$
                        + " topIndexAfterAsyncExec=" + text.getTopIndex()); //$NON-NLS-1$
            });
        }
    }

    private static final String[] HEADER_KEYWORDS = { "Процедура", "Функция", "Область" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static boolean isHeaderLine(String line)
    {
        String trimmed = line.strip();
        for (String keyword : HEADER_KEYWORDS)
        {
            if (trimmed.length() < keyword.length() || !trimmed.regionMatches(true, 0, keyword, 0, keyword.length()))
                continue;
            // Не подстрока идентификатора (например, «ПроцедураОбработки» — не заголовок).
            if (trimmed.length() == keyword.length() || !Character.isLetterOrDigit(trimmed.charAt(keyword.length())))
                return true;
        }
        return false;
    }

    private static String labelOrDefault(String text, String fallback)
    {
        return text != null && !text.isBlank() ? text : fallback;
    }
}
