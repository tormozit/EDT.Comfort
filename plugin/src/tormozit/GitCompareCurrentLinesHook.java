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
 * Панель «Текущая строка» (см. {@link CompareCurrentLinesPanel}) в редакторе сравнения
 * файла из индекса Git — {@code org.eclipse.egit.ui.internal.revision.GitCompareFileRevisionEditorInput}
 * (открывается, например, командой EGit «Сравнить файл в индексе с ревизией HEAD» из
 * списка застейдженных/индексированных файлов в панели Staging).
 *
 * <p>Это не наш редактор — встраиваем панель в уже существующее дерево виджетов после
 * открытия, а не через переопределение {@code createContents} (как в
 * {@link PasteWithCompareActions}). Класс {@code GitCompareFileRevisionEditorInput} лежит
 * во внутреннем пакете бандла {@code org.eclipse.egit.ui} — детектируем по имени класса,
 * без компилируемой зависимости.
 *
 * <p>Внутри — стандартный {@link TextMergeViewer} (те же {@code fLeft}/{@code fRight}, что
 * и в «Вставить со сравнением»), поэтому синхронизация — тот же {@link TwoSideCurrentLinesSync}.
 */
public final class GitCompareCurrentLinesHook
{
    private static final String TAG = "GitCompareCurrentLines"; //$NON-NLS-1$
    private static final String EDITOR_INPUT_CLASS_SNIPPET = "GitCompareFileRevisionEditorInput"; //$NON-NLS-1$

    private static final String PANEL_ATTACHED_KEY = "tormozit.gitCompareCurrentLinesAttached"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;
    private static final int RETRY_DELAY_MS = 50;

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
                if (ed != null)
                    restorePendingCaret(ed);
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
        if (input == null || !input.getClass().getName().contains(EDITOR_INPUT_CLASS_SNIPPET))
            return;
        if (!(input instanceof CompareEditorInput editorInput))
            return;
        scheduleAttach(editorInput, editor, 0);
    }

    private static void scheduleAttach(CompareEditorInput editorInput, IEditorPart editor, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (attempt >= MAX_ATTEMPTS)
        {
            log("attach: не удалось найти TextMergeViewer после " + MAX_ATTEMPTS + " попыток"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        display.timerExec(attempt == 0 ? 100 : RETRY_DELAY_MS, () ->
        {
            if (!tryAttach(editorInput, editor))
                scheduleAttach(editorInput, editor, attempt + 1);
        });
    }

    /**
     * @return {@code true} — попытки прекращены (успех либо окончательно не подходит),
     *         {@code false} — контролы ещё не готовы, нужна повторная попытка.
     */
    private static boolean tryAttach(CompareEditorInput editorInput, IEditorPart editor)
    {
        Object paneObj = Global.getField(editorInput, "fContentInputPane"); //$NON-NLS-1$
        if (!(paneObj instanceof CompareViewerSwitchingPane pane) || pane.isDisposed())
            return false;

        Viewer viewer = pane.getViewer();
        if (viewer == null)
            return false;
        if (!(viewer instanceof TextMergeViewer mergeViewer))
        {
            log("attach: содержимое не текстовое (viewer=" + viewer.getClass().getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }

        Control viewerControl = viewer.getControl();
        if (viewerControl == null || viewerControl.isDisposed() || viewerControl.getParent() != pane)
            return false;

        attach(pane, viewerControl, editorInput, mergeViewer, editor);
        return true;
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
        String rawLeftLabel = config != null ? config.getLeftLabel(null) : null;
        String rawRightLabel = config != null ? config.getRightLabel(null) : null;
        String leftLabel = markWorkingCopyLabel(rawLeftLabel);
        String rightLabel = markWorkingCopyLabel(rawRightLabel);

        CompareCurrentLinesPanel panel = CompareCurrentLinesPanel.create(wrapper,
            labelOrDefault(leftLabel, "Слева:"), labelOrDefault(rightLabel, "Справа:")); //$NON-NLS-1$ //$NON-NLS-2$
        panel.getControl().setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        pane.setContent(wrapper);
        pane.layout(true, true);

        StyledText leftText = MergeViewerReflection.extractStyledText(viewer, "fLeft"); //$NON-NLS-1$
        StyledText rightText = MergeViewerReflection.extractStyledText(viewer, "fRight"); //$NON-NLS-1$

        if (leftText == null || rightText == null)
            log("attach: не удалось извлечь StyledText (left=" + (leftText != null) //$NON-NLS-1$
                + " right=" + (rightText != null) + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        String irTitle = labelOrDefault(leftLabel, "Слева") + " / " + labelOrDefault(rightLabel, "Справа"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String irSyntaxVariant = IrCompareValuesHandler.syntaxVariantFor(resolveCompareType(editorInput));
        panel.setCompareInIrSupplier(() ->
            supplyFullTextsForIr(leftText, rightText, irTitle, leftLabel, rightLabel, irSyntaxVariant));

        /*
         * «Показать в модуле» ведёт только в рабочую копию (реальный файл в workspace) —
         * определяем сторону по исходной подписи EGit («Локальный: ...», см. markWorkingCopyLabel)
         * ДО добавления суффикса, и резолвим её IFile через IResourceProvider (публичный API
         * org.eclipse.compare, не reflection — LocalResourceTypedElement его реализует).
         */
        boolean leftIsWorkingCopy = rawLeftLabel != null && rawLeftLabel.startsWith("Локальный"); //$NON-NLS-1$
        boolean rightIsWorkingCopy = rawRightLabel != null && rawRightLabel.startsWith("Локальный"); //$NON-NLS-1$
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
            scheduleAttach(editorInput, editor, 0);
        });
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
            Global.tempLogException("showInModule", "GitCompareCurrentLinesHook: " + workingCopyFile.getFullPath(), e); //$NON-NLS-1$ //$NON-NLS-2$
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

    /**
     * EGit подписывает сторону сравнения с копией рабочего каталога как «Локальный: ИмяФайла» —
     * добавляем суффикс, чтобы было явно видно, какая сторона рабочая (левая/правая — зависит
     * от команды сравнения, «Локальный» не всегда одна и та же сторона).
     */
    private static String markWorkingCopyLabel(String label)
    {
        if (label != null && label.startsWith("Локальный")) //$NON-NLS-1$
            return label + " (рабочий)"; //$NON-NLS-1$
        return label;
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }
}
