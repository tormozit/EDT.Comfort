package tormozit;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.swt.widgets.Shell;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.internal.CompareUIPlugin;
import org.eclipse.compare.internal.ViewerDescriptor;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbenchPage;

/**
 * Общая логика команды «Вставить со сравнением» для handler и контекстного меню.
 */
public final class PasteWithCompareActions
{
    public static final String MENU_LABEL = "Вставить со сравнением"; //$NON-NLS-1$

    private PasteWithCompareActions()
    {
    }

    public static void run(Shell shell, IWorkbenchPart part, IEditorPart editorPart)
    {
        run(shell, TextEditor.resolveContext(part, editorPart));
    }

    public static void run(Shell shell, TextEditor.Context ctx)
    {
        if (ctx == null)
        {
            ToastNotification.show(MENU_LABEL,
                "Откройте текстовый редактор с фокусом в поле текста.", 4000);
            return;
        }

        if (!ctx.editable)
        {
            ToastNotification.show(MENU_LABEL,
                "Редактор доступен только для чтения.", 4000);
            return;
        }

        String clipboardText = TextEditor.readClipboardText(shell);
        if (clipboardText == null || clipboardText.isEmpty())
        {
            ToastNotification.show(MENU_LABEL,
                "Буфер обмена пуст или не содержит текста.", 4000);
            return;
        }

        try
        {
            StringFragmentCompareInput input = new StringFragmentCompareInput(
                ctx.selectedText,
                clipboardText,
                ctx.compareViewerType,
                ctx);

            String newText = input.openDialog();
            if (newText == null)
                return;

            TextEditor.replaceSelectionAndSelect(ctx, newText);
        }
        catch (Exception e)
        {
            Global.log("PasteWithCompareActions.run: " + e); //$NON-NLS-1$
            ToastNotification.show(MENU_LABEL,
                "Не удалось открыть сравнение: " + e.getMessage(), 5000);
        }
    }

    public static boolean isAvailable(Shell shell, IWorkbenchPart part, IEditorPart editorPart)
    {
        return isAvailable(shell, TextEditor.resolveContext(part, editorPart));
    }

    public static boolean isAvailable(Shell shell, TextEditor.Context ctx)
    {
        return ctx != null
            && ctx.editable
            && TextEditor.clipboardHasText(shell);
    }

    /**
     * Модальное сравнение двух строковых фрагментов с редактируемой правой панелью.
     */
    private static final class StringFragmentCompareInput extends CompareEditorInput
    {
        private static final String OK_LABEL = "Вставить"; //$NON-NLS-1$
        /** id из {@code plugin.xml} бандла {@code com._1c.g5.v8.dt.bsl.ui}. */
        private static final String BSL_XTEXT_VIEWER_ID =
            "com._1c.g5.v8.dt.bsl.Bsl.compare.contentMergeViewers"; //$NON-NLS-1$
        /** id из {@code fragment.xml} бандла {@code com._1c.g5.v8.dt.bsl.gumtree.ui}. */
        private static final String BSL_SEMANTIC_VIEWER_ID = "BslMergeViewerCreator"; //$NON-NLS-1$

        private final String compareViewerType;
        private final EditableStringCompareElement leftElement;
        private final EditableStringCompareElement rightElement;
        /** Контекст вызывающего редактора — левая панель сравнения всегда его фрагмент (см. {@link #showInModule}). */
        private final TextEditor.Context ctx;
        /** Устанавливается только при нажатии OK в диалоге (не опрашивать {@link #okPressed()} вручную). */
        private boolean insertConfirmed;

        /** Панель «Текущая строка» под панелями сравнения — {@code null} до {@link #createContents}. */
        private CompareCurrentLinesPanel currentLinesPanel;
        /** {@code StyledText} панелей сравнения текущего {@link TextMergeViewer} (для отслеживания каретки). */
        private StyledText leftEditorText;
        private StyledText rightEditorText;
        /** Пункт «Сравнить ИР» в тулбаре добавляется один раз (см. {@link #addCompareInIrToolbarActionOnce}). */
        private boolean toolbarActionAdded;

        StringFragmentCompareInput(String leftText, String rightText, String compareViewerType,
            TextEditor.Context ctx)
        {
            super(createConfiguration());
            this.compareViewerType = compareViewerType != null && !compareViewerType.isBlank()
                ? compareViewerType
                : "txt"; //$NON-NLS-1$
            this.ctx = ctx;
            leftElement = new EditableStringCompareElement(
                "selection." + this.compareViewerType, leftText, this.compareViewerType, false); //$NON-NLS-1$
            rightElement = new EditableStringCompareElement(
                "clipboard." + this.compareViewerType, rightText, this.compareViewerType, true); //$NON-NLS-1$
        }

        /**
         * Под панелями сравнения — на всю ширину окна панель «Текущая строка»
         * с содержимым строки под кареткой слева и справа.
         */
        @Override
        public Control createContents(Composite parent)
        {
            /*
             * parent — композит из org.eclipse.compare.internal.CompareDialog.createDialogArea
             * (стандартный Dialog.createDialogArea со штатными отступами JFace в DLU) — в нём же
             * рядом с нашим container лежит статусная строка диалога («Слева: X:Y, Справа: X:Y,
             * входящее изменение #N» — CompareContainer.setStatusMessage). Уменьшаем отступы
             * этого композита напрямую, без обращения к самому CompareDialog (internal-класс,
             * создаётся не нами — через CompareUI.openCompareDialog).
             */
            if (parent.getLayout() instanceof GridLayout parentLayout)
            {
                parentLayout.marginHeight = 2;
                parentLayout.verticalSpacing = 2;
            }
            Composite container = new Composite(parent, SWT.NONE);
            GridLayout containerLayout = new GridLayout(1, false);
            containerLayout.marginWidth = 0;
            containerLayout.marginHeight = 0;
            container.setLayout(containerLayout);

            /*
             * Подписи — из тех же CompareConfiguration.leftLabel/rightLabel, что показаны
             * заголовками над панелями сравнения выше (единый источник, без дублирования строк).
             */
            currentLinesPanel = CompareCurrentLinesPanel.create(container,
                getCompareConfiguration().getLeftLabel(null),
                getCompareConfiguration().getRightLabel(null));
            currentLinesPanel.setCompareInIrSupplier(this::supplyFullTextsForIr);
            Composite currentLinesComposite = currentLinesPanel.getControl();

            Control comparePane = super.createContents(container);
            comparePane.moveAbove(currentLinesComposite);

            comparePane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            currentLinesComposite.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

            return container;
        }

        /**
         * Выбор просмотрщика — в момент создания viewer по реальному {@link ICompareInput}:
         * {@code CompareContentViewerSwitchingPane} принимает только дескриптор из списка,
         * найденного для этого input (иначе остаётся «Сравнение по умолчанию»).
         */
        @Override
        public Viewer findContentViewer(Viewer oldViewer, ICompareInput input, Composite parent)
        {
            applyPreferredBslViewerDescriptor(oldViewer, input);
            Viewer viewer = super.findContentViewer(oldViewer, input, parent);
            hookCurrentLineTracking(viewer);
            return viewer;
        }

        /**
         * Работает только для штатного {@link TextMergeViewer} (обычное/Xtext сравнение):
         * доступ к его панелям — через приватные поля {@code fLeft}/{@code fRight}
         * (недокументированный внутренний API Eclipse Compare, публичного способа нет).
         * Для семантического просмотрщика BSL (GumTree) панель просто остаётся пустой.
         */
        private void hookCurrentLineTracking(Viewer viewer)
        {
            if (currentLinesPanel == null)
                return;

            if (!(viewer instanceof TextMergeViewer mergeViewer))
            {
                leftEditorText = null;
                rightEditorText = null;
                currentLinesPanel.renderPlain(0, ""); //$NON-NLS-1$
                currentLinesPanel.renderPlain(1, ""); //$NON-NLS-1$
                return;
            }

            leftEditorText = MergeViewerReflection.extractStyledText(mergeViewer, "fLeft"); //$NON-NLS-1$
            rightEditorText = MergeViewerReflection.extractStyledText(mergeViewer, "fRight"); //$NON-NLS-1$

            CompareConfiguration config = getCompareConfiguration();
            String semanticLeft = config != null ? config.getLeftLabel(null) : null;
            String semanticRight = config != null ? config.getRightLabel(null) : null;
            TwoSideCurrentLinesSync.hook(currentLinesPanel, leftEditorText, rightEditorText,
                mergeViewer, config, semanticLeft, semanticRight);
            addCompareInIrToolbarActionOnce();
        }

        /**
         * Добавляет «Сравнить ИР» в левый край верхней командной панели просмотрщика
         * сравнения — тот же {@link IToolBarManager}, что уже содержит штатные кнопки
         * навигации по различиям ({@code CompareViewerPane.getToolBarManager}), а не в
         * свою панель снизу. {@code fContentInputPane} — приватное поле
         * {@link CompareEditorInput}, доступно через reflection и в подклассе.
         *
         * <p>{@code IToolBarManager} не даёт прямого «добавить в начало» без ID
         * существующего элемента — пересобираем список: наш пункт первым, затем то,
         * что уже было.
         *
         * @return {@code true}, если пункт добавлен (панель тулбара уже готова)
         */
        private boolean addCompareInIrToolbarAction()
        {
            Object paneObj = Global.getField(this, "fContentInputPane"); //$NON-NLS-1$
            if (!(paneObj instanceof CompareViewerPane pane) || pane.isDisposed())
                return false;
            IToolBarManager toolBarManager = CompareViewerPane.getToolBarManager(pane);
            if (toolBarManager == null)
                return false;

            IContributionItem[] existingItems = toolBarManager.getItems();
            toolBarManager.removeAll();
            Action compareInIrAction = new Action(IrCompareValuesHandler.MENU_LABEL)
            {
                @Override
                public void run()
                {
                    currentLinesPanel.triggerCompareInIr();
                }
            };
            compareInIrAction.setToolTipText(IrCompareValuesHandler.TOOLTIP + Global.pluginSignForTooltip());
            toolBarManager.add(compareInIrAction);
            Action showInModuleAction = new Action(ShowInModuleHandler.MENU_LABEL)
            {
                @Override
                public void run()
                {
                    showInModule();
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
            toolBarManager.add(showInModuleAction);
            toolBarManager.add(currentLinesPanel.createVisibilityToggleAction());
            toolBarManager.add(new Separator());
            for (IContributionItem item : existingItems)
                toolBarManager.add(item);

            toolBarManager.update(true);
            return true;
        }

        private void addCompareInIrToolbarActionOnce()
        {
            if (toolbarActionAdded)
                return;
            toolbarActionAdded = addCompareInIrToolbarAction();
        }

        /**
         * «Показать в модуле» — всегда ведёт в левую панель (реальный открытый редактор,
         * {@code ctx.selectedText}), независимо от того, где сейчас каретка: правая панель —
         * это буфер обмена, без файла. «Сохранить» тут не нужно (вставка — не цель перехода) —
         * диалог закрывается без применения (как {@link #cancelPressed()}), затем открывается
         * реальный модуль на строке, соответствующей каретке в левой панели.
         */
        private void showInModule()
        {
            if (ctx == null || ctx.editor == null || leftEditorText == null || leftEditorText.isDisposed())
                return;
            try
            {
                IFile file = ctx.editor.getEditorInput().getAdapter(IFile.class);
                if (file == null)
                {
                    Global.log("PasteWithCompareActions.showInModule: нет реального файла у левого редактора"); //$NON-NLS-1$
                    return;
                }

                int baseLine = ctx.viewer.getDocument().getLineOfOffset(ctx.offset);
                int relativeLine = CompareLineRangeMatcher.lineAtCaret(leftEditorText);
                int line1Based = baseLine + relativeLine + 1;

                IWorkbenchPage page = ctx.editor.getEditorSite().getPage();
                Shell workbenchShell = page.getWorkbenchWindow().getShell();

                Shell dialogShell = leftEditorText.getShell();
                ModalSaveCloseHelper.Choice choice = ModalSaveCloseHelper.confirmClose(dialogShell,
                    "Закрыть окно сравнения и перейти в модуль?"); //$NON-NLS-1$
                if (choice != ModalSaveCloseHelper.Choice.PROCEED)
                    return;

                cancelPressed();
                dialogShell.close();

                ShowInModuleHandler.openBslModule(file, line1Based, page, workbenchShell);
            }
            catch (Exception e)
            {
                ToastNotification.show(ShowInModuleHandler.MENU_LABEL,
                    "Ошибка перехода в модуль: " + e, 6000); //$NON-NLS-1$
            }
        }

        /** Полные тексты обеих сторон (не текущей строки) — для кнопки «Сравнить ИР». */
        private CompareCurrentLinesPanel.FullTextPair supplyFullTextsForIr()
        {
            if (leftEditorText == null || leftEditorText.isDisposed()
                || rightEditorText == null || rightEditorText.isDisposed())
                return null;
            CompareConfiguration config = getCompareConfiguration();
            String leftLabel = config != null ? config.getLeftLabel(null) : null;
            String rightLabel = config != null ? config.getRightLabel(null) : null;
            boolean mirrored = config != null && config.isMirrored();
            String liveLeft = TwoSideCurrentLinesSync.visualSideLabel(leftLabel, rightLabel, mirrored, true);
            String liveRight = TwoSideCurrentLinesSync.visualSideLabel(leftLabel, rightLabel, mirrored, false);
            String title = liveLeft + " / " + liveRight; //$NON-NLS-1$
            IFile editorFile = null;
            if (ctx != null && ctx.editor != null && ctx.editor.getEditorInput() != null)
                editorFile = ctx.editor.getEditorInput().getAdapter(IFile.class);
            return new CompareCurrentLinesPanel.FullTextPair(
                leftEditorText.getText(), rightEditorText.getText(), title, liveLeft, liveRight,
                IrCompareValuesHandler.syntaxVariantFor(compareViewerType),
                CompareLineRangeMatcher.lineAtCaret(leftEditorText),
                CompareLineRangeMatcher.lineAtCaret(rightEditorText),
                editorFile, editorFile);
        }

        /**
         * Для BSL: GumTree («С учётом семантики»), иначе Xtext («Сравнение встроенного языка»).
         */
        private void applyPreferredBslViewerDescriptor(Viewer oldViewer, Object input)
        {
            if (!"bsl".equals(compareViewerType)) //$NON-NLS-1$
                return;
            try
            {
                CompareConfiguration config = getCompareConfiguration();
                CompareUIPlugin plugin = CompareUIPlugin.getDefault();
                if (config == null || plugin == null)
                    return;
                ViewerDescriptor[] descriptors = plugin.findContentViewerDescriptor(
                    oldViewer, input, config);
                if (descriptors == null || descriptors.length == 0)
                    return;
                ViewerDescriptor preferred = findViewerDescriptorById(
                    descriptors, BSL_SEMANTIC_VIEWER_ID);
                if (preferred == null)
                    preferred = findViewerDescriptorById(descriptors, BSL_XTEXT_VIEWER_ID);
                if (preferred != null)
                    setContentViewerDescriptor(preferred);
            }
            catch (Exception e)
            {
                Global.log("StringFragmentCompareInput.applyPreferredBslViewerDescriptor: " + e); //$NON-NLS-1$
            }
        }

        private static ViewerDescriptor findViewerDescriptorById(
            ViewerDescriptor[] descriptors, String viewerId)
        {
            for (ViewerDescriptor descriptor : descriptors)
            {
                Object config = Global.getField(descriptor, "fConfiguration"); //$NON-NLS-1$
                if (config == null)
                    continue;
                Object id = Global.invoke(config, "getAttribute", "id"); //$NON-NLS-1$ //$NON-NLS-2$
                if (viewerId.equals(id))
                    return descriptor;
            }
            return null;
        }

        private static CompareConfiguration createConfiguration()
        {
            CompareConfiguration config = new CompareConfiguration();
            config.setLeftEditable(false);
            config.setRightEditable(true);
            config.setLeftLabel("Выделенный текст"); //$NON-NLS-1$
            config.setRightLabel("Новый текст"); //$NON-NLS-1$
            return config;
        }

        /**
         * Открывает модальный диалог сравнения.
         *
         * @return текст правой панели после подтверждения, или {@code null} при отмене
         */
        String openDialog()
        {
            insertConfirmed = false;
            CompareUI.openCompareDialog(this);
            if (!insertConfirmed)
                return null;
            IDocument doc = CompareUI.getDocument(rightElement);
            if (doc != null)
                return doc.get();
            return rightElement.getContent();
        }

        @Override
        protected Object prepareInput(IProgressMonitor monitor)
        {
            return new DiffNode(null, Differencer.CHANGE, null, leftElement, rightElement);
        }

        @Override
        public String getOKButtonLabel()
        {
            return OK_LABEL;
        }

        @Override
        public boolean okPressed()
        {
            insertConfirmed = true;
            return super.okPressed();
        }

        @Override
        public void cancelPressed()
        {
            insertConfirmed = false;
            super.cancelPressed();
        }

        /**
         * Кнопка «Вставить» доступна сразу: подтверждение не привязано к «грязности» правой панели.
         */
        @Override
        public boolean isSaveNeeded()
        {
            return true;
        }

        /**
         * Строковый элемент для модального сравнения через Eclipse Compare API.
         * {@link #getType()} возвращает расширение файла (например {@code bsl}) для выбора merge-viewer.
         */
        private static final class EditableStringCompareElement
            implements ITypedElement, IStreamContentAccessor, IEncodedStreamContentAccessor,
                IEditableContent, IModificationDate
        {
            private static final long MODIFICATION_DATE = System.currentTimeMillis();

            private String content;
            private final String name;
            private final String compareViewerType;
            private final boolean editable;

            EditableStringCompareElement(String name, String content, String compareViewerType, boolean editable)
            {
                this.name = name;
                this.content = content != null ? content : ""; //$NON-NLS-1$
                this.compareViewerType = compareViewerType;
                this.editable = editable;
            }

            String getContent()
            {
                return content;
            }

            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public Image getImage()
            {
                return null;
            }

            @Override
            public String getType()
            {
                return compareViewerType;
            }

            @Override
            public InputStream getContents()
            {
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String getCharset()
            {
                return StandardCharsets.UTF_8.name();
            }

            @Override
            public long getModificationDate()
            {
                return MODIFICATION_DATE;
            }

            @Override
            public boolean isEditable()
            {
                return editable;
            }

            @Override
            public void setContent(byte[] newContent)
            {
                if (!editable)
                    return;
                content = newContent != null
                    ? new String(newContent, StandardCharsets.UTF_8)
                    : ""; //$NON-NLS-1$
            }

            @Override
            public ITypedElement replace(ITypedElement dest, ITypedElement src)
            {
                if (!(dest instanceof EditableStringCompareElement destElem))
                    return dest;
                if (src == null)
                    return dest;
                if (src instanceof EditableStringCompareElement srcElem)
                    destElem.content = srcElem.content;
                else if (src instanceof IStreamContentAccessor accessor)
                {
                    try (InputStream in = accessor.getContents())
                    {
                        if (in != null)
                            destElem.content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    catch (IOException | CoreException e)
                    {
                        Global.log("EditableStringCompareElement.replace: " + e.getMessage()); //$NON-NLS-1$
                    }
                }
                return dest;
            }
        }

    }

}
