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
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.internal.CompareUIPlugin;
import org.eclipse.compare.internal.ViewerDescriptor;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;

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
                ctx.compareViewerType);

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
        /** Устанавливается только при нажатии OK в диалоге (не опрашивать {@link #okPressed()} вручную). */
        private boolean insertConfirmed;

        StringFragmentCompareInput(String leftText, String rightText, String compareViewerType)
        {
            super(createConfiguration());
            this.compareViewerType = compareViewerType != null && !compareViewerType.isBlank()
                ? compareViewerType
                : "txt"; //$NON-NLS-1$
            leftElement = new EditableStringCompareElement(
                "selection." + this.compareViewerType, leftText, this.compareViewerType, false); //$NON-NLS-1$
            rightElement = new EditableStringCompareElement(
                "clipboard." + this.compareViewerType, rightText, this.compareViewerType, true); //$NON-NLS-1$
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
            return super.findContentViewer(oldViewer, input, parent);
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
