package tormozit;

import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.IResourceProvider;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Панель «Текущая строка» (см. {@link CompareCurrentLinesPanel}) в редакторах сравнения EGit:
 * <ul>
 *   <li>{@code GitCompareFileRevisionEditorInput} — рабочая копия/индекс с ревизией
 *   (Staging, «Сравнить с HEAD» и т.п.);</li>
 *   <li>{@code GitCompareEditorInput} — две ревизии / рабочее дерево с коммитом
 *   (список файлов коммита, «Сравнить с предыдущей»).</li>
 * </ul>
 *
 * <p>Это не наш редактор — встраиваем панель в уже существующее дерево виджетов после
 * открытия, а не через переопределение {@code createContents} (как в
 * {@link PasteWithCompareActions}). Классы лежат во внутренних пакетах
 * {@code org.eclipse.egit.ui} — детектируем по имени класса, без компилируемой зависимости.
 *
 * <p>Внутри — стандартный {@link TextMergeViewer} (те же {@code fLeft}/{@code fRight}, что
 * и в «Вставить со сравнением»), поэтому синхронизация — тот же {@link TwoSideCurrentLinesSync}.
 */
public final class GitCompareCurrentLinesHook
{
    private static final String INPUT_FILE_REVISION = "GitCompareFileRevisionEditorInput"; //$NON-NLS-1$
    /** Точное окончание FQCN — не путать с {@code GitCompareFileRevisionEditorInput}. */
    private static final String INPUT_GIT_COMPARE_SUFFIX = ".GitCompareEditorInput"; //$NON-NLS-1$

    private static final String PANEL_ATTACHED_KEY = "tormozit.gitCompareCurrentLinesAttached"; //$NON-NLS-1$

    private static final String SUFFIX_WORKING = " (рабочий)"; //$NON-NLS-1$
    private static final String SUFFIX_MAIN = " (основной)"; //$NON-NLS-1$
    private static final String SUFFIX_PREVIOUS = " (предыдущий)"; //$NON-NLS-1$
    /**
     * Подпись пустой стороны при add/delete в сравнении коммитов
     * (у EGit CLabel пустой — иначе суффикс некуда повесить).
     */
    private static final String EMPTY_SIDE_LABEL = "<Отсутствует>"; //$NON-NLS-1$

    private static final int MAX_FAST_ATTEMPTS = 40;
    private static final int FAST_RETRY_DELAY_MS = 50;
    private static final int MAX_SLOW_ATTEMPTS = 120;
    private static final int SLOW_RETRY_DELAY_MS = 500;

    private static final int ATTACH_WAIT = 0;
    private static final int ATTACH_DONE = 1;

    /**
     * Ожидающие восстановления каретки после {@link #showInModule} — ключ: редактор сравнения,
     * значение: куда её вернуть. Заполняется в {@code showInModule}, снимается и применяется
     * в {@code partActivated} того же редактора (см. там подробное объяснение таймингов).
     * {@link java.util.WeakHashMap} — не удерживает закрытый редактор, если реактивации не было.
     */
    private static final java.util.Map<IEditorPart, PendingCaretRestore> pendingCaretRestores =
        new java.util.WeakHashMap<>();

    private record PendingCaretRestore(StyledText widget, int offset)
    {
    }

    private GitCompareCurrentLinesHook()
    {
    }

    public static void install()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        for (IWorkbenchWindow w : wb.getWorkbenchWindows())
            hookWindow(w);

        wb.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null)
                    tryHandleEditor(ed);
            }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null)
                    tryHandleEditor(ed);
            }
            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed == null)
                    return;
                restorePendingCaret(ed);
                /*
                 * Повторная попытка: при partOpened редактор/вьюер ещё могут быть не готовы;
                 * у GitCompareEditorInput сначала дерево структуры, TextMergeViewer — после
                 * выбора файла. PANEL_ATTACHED_KEY делает повтор идемпотентным.
                 */
                tryHandleEditor(ed);
            }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void tryHandleEditor(IEditorPart editor)
    {
        Object input = editor.getEditorInput();
        if (!(input instanceof CompareEditorInput editorInput))
            return;
        if (!isSupportedGitCompareInput(input))
            return;
        scheduleAttach(editorInput, editor, 0, false);
    }

    private static boolean isSupportedGitCompareInput(Object input)
    {
        if (input == null)
            return false;
        String name = input.getClass().getName();
        return name.contains(INPUT_FILE_REVISION) || name.endsWith(INPUT_GIT_COMPARE_SUFFIX);
    }

    /**
     * Сравнение коммита с предыдущим (не Local/Index):
     * <ul>
     *   <li>{@code GitCompareEditorInput} с обеими {@code leftVersion}/{@code rightVersion};</li>
     *   <li>{@code GitCompareFileRevisionEditorInput} с двумя {@code FileRevisionTypedElement};</li>
     *   <li>то же input, но одна сторона пустая (файл добавлен/удалён в коммите) —
     *   ровно один {@code getLeftRevision}/{@code getRightRevision} не null;
     *   в заголовке EGit тогда «… и Текущая».</li>
     * </ul>
     */
    private static boolean isTwoCommitRevisionCompare(CompareEditorInput input, String leftLabel,
        String rightLabel)
    {
        if (input == null)
            return false;

        String name = input.getClass().getName();
        if (name.endsWith(INPUT_GIT_COMPARE_SUFFIX))
        {
            Object left = Global.getField(input, "leftVersion"); //$NON-NLS-1$
            Object right = Global.getField(input, "rightVersion"); //$NON-NLS-1$
            return left instanceof String ls && !ls.isBlank()
                && right instanceof String rs && !rs.isBlank();
        }

        if (!name.contains(INPUT_FILE_REVISION))
            return false;

        Object leftRev = Global.invoke(input, "getLeftRevision"); //$NON-NLS-1$
        Object rightRev = Global.invoke(input, "getRightRevision"); //$NON-NLS-1$
        if (leftRev == null && rightRev == null)
            return false;

        /*
         * Index тоже FileRevisionTypedElement — отсекаем по подписи стороны
         * («Index: …», «Локальный: …»). Пустая подпись у отсутствующей стороны
         * (add/delete) — норма, не Local/Index.
         */
        if (isLocalOrIndexLabel(leftLabel) || isLocalOrIndexLabel(rightLabel))
            return false;

        if (leftRev != null && rightRev != null)
            return true;

        /*
         * Add/delete: ровно одна ревизия. Присутствующая сторона должна иметь
         * ревизионную подпись (не Local/Index — уже проверено), отсутствующая — пустая.
         */
        String presentLabel = leftRev != null ? leftLabel : rightLabel;
        String absentLabel = leftRev != null ? rightLabel : leftLabel;
        if (presentLabel == null || presentLabel.isBlank())
            return false;
        return absentLabel == null || absentLabel.isBlank();
    }

    private static boolean isLocalOrIndexLabel(String label)
    {
        if (label == null || label.isBlank())
            return false;
        return label.startsWith("Локальный") //$NON-NLS-1$
            || label.startsWith("Local:") //$NON-NLS-1$
            || label.startsWith("Index:") //$NON-NLS-1$
            || label.startsWith("Индекс:"); //$NON-NLS-1$
    }

    private static void scheduleAttach(CompareEditorInput editorInput, IEditorPart editor, int attempt,
        boolean slow)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int max = slow ? MAX_SLOW_ATTEMPTS : MAX_FAST_ATTEMPTS;
        int delay = slow ? SLOW_RETRY_DELAY_MS : (attempt == 0 ? 100 : FAST_RETRY_DELAY_MS);
        if (attempt >= max)
        {
            if (!slow)
                scheduleAttach(editorInput, editor, 0, true);
            return;
        }
        display.timerExec(delay, () ->
        {
            if (editor.getSite() == null || editor.getSite().getPage() == null)
                return;
            int result = tryAttach(editorInput, editor);
            if (result == ATTACH_WAIT)
                scheduleAttach(editorInput, editor, attempt + 1, slow);
        });
    }

    /**
     * @return {@link #ATTACH_DONE} — успех/уже прикреплено;
     *         {@link #ATTACH_WAIT} — ещё не готово, нужна повторная попытка.
     */
    private static int tryAttach(CompareEditorInput editorInput, IEditorPart editor)
    {
        Object paneObj = Global.getField(editorInput, "fContentInputPane"); //$NON-NLS-1$
        if (!(paneObj instanceof CompareViewerSwitchingPane pane) || pane.isDisposed())
            return ATTACH_WAIT;

        if (Boolean.TRUE.equals(pane.getData(PANEL_ATTACHED_KEY)))
            return ATTACH_DONE;

        Viewer viewer = pane.getViewer();
        if (viewer == null)
            return ATTACH_WAIT;
        if (!(viewer instanceof TextMergeViewer mergeViewer))
        {
            /*
             * GitCompareEditorInput часто сначала показывает DiffTreeViewer (структура),
             * TextMergeViewer появляется после выбора файла — не сдаёмся.
             */
            return ATTACH_WAIT;
        }

        Control viewerControl = viewer.getControl();
        if (viewerControl == null || viewerControl.isDisposed())
            return ATTACH_WAIT;
        if (viewerControl.getParent() != pane)
            return ATTACH_WAIT;

        attach(pane, viewerControl, editorInput, mergeViewer, editor);
        return ATTACH_DONE;
    }

    private static void attach(CompareViewerSwitchingPane pane, Control viewerControl,
        CompareEditorInput editorInput, TextMergeViewer viewer, IEditorPart editor)
    {
        if (pane.isDisposed())
            return;
        if (Boolean.TRUE.equals(pane.getData(PANEL_ATTACHED_KEY)))
            return;
        pane.setData(PANEL_ATTACHED_KEY, Boolean.TRUE);

        /*
         * fContentInputPane (ViewForm) держит control вьюера напрямую в своей content-области
         * (setContent(fViewer.getControl())). Оборачиваем: переносим control вьюера в свой
         * composite вместе с панелью «Текущая строка» и подставляем этот composite как
         * новое содержимое ViewForm — без вмешательства в Splitter снаружи.
         */
        Composite wrapper = new Composite(pane, SWT.NONE);
        GridLayout wrapperLayout = new GridLayout(1, false);
        wrapperLayout.marginWidth = 0;
        wrapperLayout.marginHeight = 0;
        wrapper.setLayout(wrapperLayout);

        viewerControl.setParent(wrapper);
        viewerControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CompareConfiguration config = editorInput.getCompareConfiguration();
        String rawLeftLabel = resolveSideLabel(viewer, config, true);
        String rawRightLabel = resolveSideLabel(viewer, config, false);
        boolean twoCommitRev = isTwoCommitRevisionCompare(editorInput, rawLeftLabel, rawRightLabel);
        String leftLabel = markSideLabel(rawLeftLabel, twoCommitRev, true);
        String rightLabel = markSideLabel(rawRightLabel, twoCommitRev, false);
        /*
         * Сразу и после layout/async: штатный updateHeader (и EDT при смене варианта
         * сравнения) перечитывает подписи из CompareConfiguration / LabelProvider и
         * затирает наш CLabel.setText — поэтому пишем и в config, и в CLabel, и
         * повторяем после отложенных обновлений шапки.
         */
        applyHeaderLabels(config, viewer, leftLabel, rightLabel);

        CompareCurrentLinesPanel panel = CompareCurrentLinesPanel.create(wrapper,
            labelOrDefault(leftLabel, "Слева:"), labelOrDefault(rightLabel, "Справа:")); //$NON-NLS-1$ //$NON-NLS-2$
        panel.getControl().setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        pane.setContent(wrapper);
        pane.layout(true, true);
        applyHeaderLabels(config, viewer, leftLabel, rightLabel);
        scheduleHeaderLabelRefresh(config, viewer, leftLabel, rightLabel);

        StyledText leftText = MergeViewerReflection.extractStyledText(viewer, "fLeft"); //$NON-NLS-1$
        StyledText rightText = MergeViewerReflection.extractStyledText(viewer, "fRight"); //$NON-NLS-1$


        String irTitle = labelOrDefault(leftLabel, "Слева") + " / " + labelOrDefault(rightLabel, "Справа"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String irSyntaxVariant = IrCompareValuesHandler.syntaxVariantFor(resolveCompareType(editorInput));
        panel.setCompareInIrSupplier(() ->
            supplyFullTextsForIr(leftText, rightText, irTitle, leftLabel, rightLabel, irSyntaxVariant));

        /*
         * «Показать в модуле» ведёт только в рабочую копию (реальный файл в workspace) —
         * определяем сторону по исходной подписи EGit («Локальный: ...») ДО добавления суффикса,
         * и резолвим её IFile через IResourceProvider (публичный API org.eclipse.compare).
         */
        boolean leftIsWorkingCopy = isLocalWorkingCopyLabel(rawLeftLabel);
        boolean rightIsWorkingCopy = isLocalWorkingCopyLabel(rawRightLabel);
        StyledText workingCopyText = leftIsWorkingCopy ? leftText : rightIsWorkingCopy ? rightText : null;
        IFile workingCopyFile = leftIsWorkingCopy ? resolveTypedElementFile(editorInput, true)
            : rightIsWorkingCopy ? resolveTypedElementFile(editorInput, false)
            : null;

        addCompareInIrToolbarAction(pane, panel, workingCopyFile, workingCopyText, editor);

        TwoSideCurrentLinesSync.hook(panel, leftText, rightText);

        /*
         * Переключатель варианта сравнения («Сравнение текста»/«с учётом семантики»/
         * «встроенного языка», выпадающее меню в левом верхнем углу) пересоздаёт вьюер —
         * механизм переключения считает содержимым pane именно то, что мы туда положили
         * через setContent(wrapper), и при смене варианта уничтожает ЭТОТ control целиком
         * (наш wrapper, включая панель «Текущая строка» и добавленные кнопки тулбара), подставляя
         * новый вьюер напрямую. Переприсоединяемся с нуля при уничтожении wrapper.
         */
        wrapper.addDisposeListener(e ->
        {
            if (!pane.isDisposed())
                pane.setData(PANEL_ATTACHED_KEY, null);
            scheduleAttach(editorInput, editor, 0, false);
        });
    }

    /**
     * Заголовок стороны: сначала живой {@code CLabel} (уже заполнен {@code updateHeader}
     * через {@code GitCompareLabelProvider}), иначе fallback {@code CompareConfiguration}.
     */
    private static String resolveSideLabel(TextMergeViewer viewer, CompareConfiguration config, boolean left)
    {
        String fromHeader = MergeViewerReflection.extractLabelText(viewer,
            left ? "fLeftLabel" : "fRightLabel"); //$NON-NLS-1$ //$NON-NLS-2$
        if (fromHeader != null && !fromHeader.isBlank())
            return fromHeader;
        if (config == null)
            return null;
        return left ? config.getLeftLabel(null) : config.getRightLabel(null);
    }

    private static String markSideLabel(String label, boolean twoCommitRevision, boolean left)
    {
        if (twoCommitRevision)
        {
            /*
             * Add/delete: у отсутствующей стороны CLabel пустой — подставляем
             * {@link #EMPTY_SIDE_LABEL}, иначе суффикс некуда повесить и панель
             * показывает просто «Справа:» / «Слева:».
             */
            String base = label != null && !label.isBlank() ? label : EMPTY_SIDE_LABEL;
            return appendSuffixOnce(base, left ? SUFFIX_MAIN : SUFFIX_PREVIOUS);
        }
        return markWorkingCopyLabel(label);
    }

    private static boolean isLocalWorkingCopyLabel(String label)
    {
        return label != null && label.startsWith("Локальный"); //$NON-NLS-1$
    }

    /**
     * EGit подписывает сторону сравнения с копией рабочего каталога как «Локальный: ИмяФайла» —
     * добавляем суффикс, чтобы было явно видно, какая сторона рабочая.
     */
    private static String markWorkingCopyLabel(String label)
    {
        if (!isLocalWorkingCopyLabel(label))
            return label;
        return appendSuffixOnce(label, SUFFIX_WORKING);
    }

    private static String appendSuffixOnce(String label, String suffix)
    {
        if (label == null)
            return null;
        if (label.endsWith(suffix))
            return label;
        return label + suffix;
    }

    /**
     * Пишет помеченные подписи в {@link CompareConfiguration} и прямо в
     * {@code ContentMergeViewer.fLeftLabel}/{@code fRightLabel}.
     * <p>
     * Только config недостаточно при {@code GitCompareLabelProvider} (он перекрывает
     * fallback в {@code updateHeader}). Только CLabel недостаточно, если позже
     * вызывается {@code updateHeader} без наших значений в config — шапка откатывается
     * (типичный случай «Локальный: …» без «(рабочий)» после attach).
     */
    private static void applyHeaderLabels(CompareConfiguration config, TextMergeViewer viewer,
        String leftLabel, String rightLabel)
    {
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        if (config != null)
        {
            if (leftLabel != null)
                config.setLeftLabel(leftLabel);
            if (rightLabel != null)
                config.setRightLabel(rightLabel);
        }
        MergeViewerReflection.setLabelText(viewer, "fLeftLabel", leftLabel); //$NON-NLS-1$
        MergeViewerReflection.setLabelText(viewer, "fRightLabel", rightLabel); //$NON-NLS-1$
    }

    /**
     * Повторная установка шапки после отложенного {@code updateHeader} Eclipse/EDT
     * (layout, смена input, пересчёт вкладок сравнения).
     */
    private static void scheduleHeaderLabelRefresh(CompareConfiguration config, TextMergeViewer viewer,
        String leftLabel, String rightLabel)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
        display.timerExec(100, () -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
        display.timerExec(500, () -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
    }

    /**
     * Добавляет «Сравнить ИР» в левый край верхней командной панели просмотрщика сравнения —
     * тот же {@link IToolBarManager}, что уже содержит штатные кнопки навигации по различиям
     * ({@code CompareViewerPane.getToolBarManager}), а не в свою панель снизу.
     *
     * <p>{@code IToolBarManager} не даёт прямого «добавить в начало» без ID существующего
     * элемента (который у части штатных элементов может быть не задан) — пересобираем список:
     * наш пункт первым, затем то, что уже было.
     */
    private static void addCompareInIrToolbarAction(CompareViewerPane pane, CompareCurrentLinesPanel panel,
        IFile workingCopyFile, StyledText workingCopyText, IEditorPart editor)
    {
        IToolBarManager toolBarManager = CompareViewerPane.getToolBarManager(pane);
        if (toolBarManager == null)
            return;

        IContributionItem[] existingItems = toolBarManager.getItems();
        toolBarManager.removeAll();
        Action compareInIrAction = new Action(IrCompareValuesHandler.MENU_LABEL)
        {
            @Override
            public void run()
            {
                panel.triggerCompareInIr();
            }
        };
        compareInIrAction.setToolTipText(IrCompareValuesHandler.TOOLTIP + Global.pluginSignForTooltip());
        toolBarManager.add(compareInIrAction);

        Action showInModuleAction = new Action(ShowInModuleHandler.MENU_LABEL)
        {
            @Override
            public void run()
            {
                showInModule(pane, workingCopyFile, workingCopyText, editor);
            }
        };
        ImageDescriptor showInModuleIcon = ShowInModuleHandler.iconDescriptor();
        if (showInModuleIcon != null)
        {
            showInModuleAction.setImageDescriptor(showInModuleIcon);
            showInModuleAction.setText(""); //$NON-NLS-1$
        }
        showInModuleAction.setToolTipText(
            ShowInModuleHandler.MENU_LABEL + " — " + ShowInModuleHandler.TOOLTIP + Global.pluginSignForTooltip()); //$NON-NLS-1$
        showInModuleAction.setEnabled(workingCopyFile != null && workingCopyText != null);
        toolBarManager.add(showInModuleAction);

        toolBarManager.add(panel.createVisibilityToggleAction());
        toolBarManager.add(new Separator());
        for (IContributionItem item : existingItems)
            toolBarManager.add(item);

        toolBarManager.update(true);
    }

    /**
     * Не модальное окно (обычная вкладка редактора) — без подтверждения закрытия,
     * сразу открываем реальный редактор рабочей копии на текущей строке.
     */
    private static void showInModule(CompareViewerPane pane, IFile workingCopyFile, StyledText workingCopyText,
        IEditorPart editor)
    {
        if (workingCopyFile == null || workingCopyText == null || workingCopyText.isDisposed())
            return;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            if (page == null)
                return;
            int line1Based = CompareLineRangeMatcher.lineAtCaret(workingCopyText) + 1;
            Shell shell = pane.isDisposed() ? null : pane.getShell();

            /*
             * Стектрейс краша (Eclipse, не наш код): org.eclipse.compare.contentmergeviewer
             * .TextMergeViewer планирует отложенный UIJob-пересчёт подсветки различий, который
             * срабатывает именно при повторной активации вкладки сравнения (не сразу после
             * открытия модуля) — содержимое левой панели к этому моменту пересинхронизировано,
             * а каретка виджета осталась на старом смещении — StyledText.setCaretLocations падает
             * с «Index out of bounds» при попытке перерисовать каретку в недействительной позиции.
             * Патчить сам TextMergeViewer нельзя (платформенный код) — убираем триггер: сбрасываем
             * каретку в заведомо валидную позицию (0) сейчас, а возвращаем её обратно не по
             * таймеру (непредсказуемо относительно момента реактивации — так и не сработало), а
             * из {@code partActivated} этого же редактора, когда вкладка сравнения ДЕЙСТВИТЕЛЬНО
             * активируется — см. {@link #restorePendingCaret}.
             */
            int originalCaretOffset = workingCopyText.getCaretOffset();
            if (workingCopyText.getCharCount() > 0)
                workingCopyText.setCaretOffset(0);
            pendingCaretRestores.put(editor, new PendingCaretRestore(workingCopyText, originalCaretOffset));

            ShowInModuleHandler.openBslModule(workingCopyFile, line1Based, page, shell);
        }
        catch (Exception e)
        {
            ToastNotification.show(ShowInModuleHandler.MENU_LABEL,
                "Ошибка перехода в модуль: " + e, 6000); //$NON-NLS-1$
        }
    }

    /**
     * Вызывается из {@code partActivated} для КАЖДОГО редактора при активации — если для этого
     * редактора есть отложенное восстановление каретки, планируем его на два шага позже в
     * очереди событий ({@code asyncExec} внутри {@code asyncExec}), чтобы почти наверняка
     * оказаться ПОСЛЕ отложенного {@code UIJob}-пересчёта Eclipse (обычно однократный
     * {@code asyncExec} от момента реактивации) — см. подробности в {@link #showInModule}.
     * Точной гарантии порядка платформа не даёт, но так надёжнее фиксированной задержки.
     */
    private static void restorePendingCaret(IEditorPart editor)
    {
        PendingCaretRestore pending = pendingCaretRestores.remove(editor);
        if (pending == null || pending.widget().isDisposed())
            return;
        Display display = pending.widget().getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> display.asyncExec(() ->
        {
            if (pending.widget().isDisposed())
                return;
            int safeOffset = Math.min(pending.offset(), pending.widget().getCharCount());
            pending.widget().setCaretOffset(Math.max(0, safeOffset));
        }));
    }

    /**
     * {@code IResourceProvider} — публичный интерфейс {@code org.eclipse.compare}
     * (не reflection): реализуется, в частности, EGit-овским
     * {@code LocalResourceTypedElement} для стороны, которая является рабочей копией.
     */
    private static IFile resolveTypedElementFile(CompareEditorInput editorInput, boolean left)
    {
        Object result = editorInput.getCompareResult();
        if (!(result instanceof ICompareInput compareInput))
            return null;
        ITypedElement element = left ? compareInput.getLeft() : compareInput.getRight();
        if (!(element instanceof IResourceProvider resourceProvider))
            return null;
        IResource resource = resourceProvider.getResource();
        return resource instanceof IFile file ? file : null;
    }

    /** Полные тексты обеих сторон (не текущей строки) — для кнопки «Сравнить ИР». */
    private static CompareCurrentLinesPanel.FullTextPair supplyFullTextsForIr(
        StyledText leftText, StyledText rightText, String title, String leftLabel, String rightLabel,
        String syntaxVariant)
    {
        if (leftText == null || leftText.isDisposed() || rightText == null || rightText.isDisposed())
            return null;
        return new CompareCurrentLinesPanel.FullTextPair(leftText.getText(), rightText.getText(), title,
            leftLabel, rightLabel, syntaxVariant,
            CompareLineRangeMatcher.lineAtCaret(leftText), CompareLineRangeMatcher.lineAtCaret(rightText));
    }

    /**
     * Тип сравниваемого содержимого (расширение файла) — тот же
     * {@link ITypedElement#getType()}, что Eclipse Compare уже использует для выбора
     * merge-вьюера (см. {@link TextEditor#QL_COMPARE_EXTENSION} и соседние константы —
     * тот же принцип для собственного диалога сравнения).
     */
    private static String resolveCompareType(CompareEditorInput editorInput)
    {
        Object result = editorInput.getCompareResult();
        if (!(result instanceof ICompareInput compareInput))
            return null;
        ITypedElement left = compareInput.getLeft();
        if (left != null && left.getType() != null)
            return left.getType();
        ITypedElement right = compareInput.getRight();
        return right != null ? right.getType() : null;
    }

    private static String labelOrDefault(String text, String fallback)
    {
        return text != null && !text.isBlank() ? text : fallback;
    }

}
