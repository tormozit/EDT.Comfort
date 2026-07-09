package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;

/**
 * Элемент автодополнения из таблицы слов ИР (порт {@code НаборСловИзИР} / RDT).
 */
public final class IrCompletionProposal implements
    ICompletionProposal,
    ICompletionProposalExtension,
    ICompletionProposalExtension2,
    ICompletionProposalExtension3
{
    private static final char STABLE_KEY_SEP = '\u0001';

    private volatile String displayString;
    private final String filterName;
    private final String wordValue;
    private final String dictionaryKey;
    private final String listTypeLabel;
    private final String parentContextType;
    private final String templateText;
    private final boolean method;
    private final boolean returnsValue;
    private final boolean replaceParentOnInsert;
    private int irPriority;
    private final String stableCacheKey;
    private volatile String activationHtml;
    /** Штатная иконка assist EDT (borrow от delegate или {@link BslAssistListImages}). */
    private volatile Image stockAssistImage;
    /**
     * Позиция каретки, вычисленная в {@link #apply(IDocument, char, int)}.
     * Возвращается из {@link #getSelection} — Eclipse применит её через
     * {@code viewer.setSelectedRange()} в {@code CompletionProposalPopup.insertProposal()}.
     * Значение {@code -1} означает «не вычислено».
     */
    private int pendingCaretAfterApply = -1;

    public IrCompletionProposal(
        String displayString, String filterName, String templateText, boolean method, int irPriority,
        String wordValue, String dictionaryKey, String listTypeLabel, String parentContextType,
        boolean returnsValue, boolean replaceParentOnInsert)
    {
        this.displayString = displayString != null ? displayString : ""; //$NON-NLS-1$
        this.filterName = filterName != null ? filterName : this.displayString;
        this.wordValue = wordValue != null ? wordValue : this.filterName;
        this.dictionaryKey = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
        this.listTypeLabel = listTypeLabel != null ? listTypeLabel : ""; //$NON-NLS-1$
        this.parentContextType = parentContextType != null ? parentContextType : ""; //$NON-NLS-1$
        this.templateText = templateText;
        this.method = method;
        this.returnsValue = returnsValue;
        this.replaceParentOnInsert = replaceParentOnInsert;
        this.irPriority = irPriority;
        this.stableCacheKey = buildStableCacheKey(this.filterName, this.dictionaryKey);
    }

    public void setIrPriority(int priority)
    {
        this.irPriority = priority;
    }
    
    public static String buildStableCacheKey(String filterName, String dictionaryKey)
    {
        String filter = filterName != null ? filterName : ""; //$NON-NLS-1$
        String dict = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
        return filter + STABLE_KEY_SEP + dict;
    }

    public String getFilterName()
    {
        return filterName;
    }

    public String getWordValue()
    {
        return wordValue;
    }

    public String getDictionaryKey()
    {
        return dictionaryKey;
    }

    public String getStableCacheKey()
    {
        return stableCacheKey;
    }

    public int getIrPriority()
    {
        return irPriority;
    }

    public boolean isMethod()
    {
        return method;
    }

    public boolean isReturnsValue()
    {
        return returnsValue;
    }

    public boolean isReplaceParentOnInsert()
    {
        return replaceParentOnInsert;
    }

    public String getListTypeLabel()
    {
        return listTypeLabel;
    }

    public String getParentContextType()
    {
        return parentContextType;
    }

    /**
     * Результат {@code ОписаниеТекущегоСловаАвтодополнения} (RDT {@code ПриАктивизацииСтрокиТ9}).
     */
    void applyActivation(String type, String description, boolean rawHtml)
    {
        if (type != null)
        {
            displayString = IrBslCompletionSupport.buildActivatedListDisplay(
                wordValue, method, listTypeLabel, type, parentContextType);
        }
        String html = IrBslCompletionSupport.formatActivationHtml(description, rawHtml);
        if (html != null)
            activationHtml = html;
    }

    @Override
    public String getDisplayString()
    {
        return displayString;
    }

    void applyStockAssistImage(Image image)
    {
        if (image != null && !image.isDisposed())
            stockAssistImage = image;
    }

    @Override
    public Image getImage()
    {
        Image borrowed = stockAssistImage;
        if (borrowed != null && !borrowed.isDisposed())
            return borrowed;
        return BslAssistListImages.resolve(method, returnsValue);
    }

    @Override
    public Point getSelection(IDocument document)
    {
        int pending = pendingCaretAfterApply;
        pendingCaretAfterApply = -1;
        return pending >= 0 ? new Point(pending, 0) : null;
    }

    @Override
    public IContextInformation getContextInformation()
    {
        return null;
    }

    @Override
    public String getAdditionalProposalInfo()
    {
        if (activationHtml != null && !activationHtml.isEmpty())
            return activationHtml;
        if (ComfortSettings.isReplaceListFiltersEnabled())
        {
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
            if (reloader != null)
            {
                String cached = reloader.getIrMergedHtml(stableCacheKey);
                if (cached != null && !cached.isEmpty())
                    return cached;
            }
        }
        return null;
    }

    @Override
    public IInformationControlCreator getInformationControlCreator()
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null)
        {
            IInformationControlCreator cached = reloader.getAssistBrowserCreator();
            if (cached != null)
                return cached;
        }
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        IInformationControlCreator creator = BslCompletionSideHintResolver.resolveAssistBrowserCreator(viewer);
        if (creator == null && reloader != null)
            creator = BslCompletionSideHintResolver.resolveAssistBrowserCreatorFromEditor(reloader.getBslEditor());
        return creator;
    }

    @Override
    public void apply(IDocument document)
    {
        apply(document, (char) 0, resolveCaretOffset(document));
    }

    private int resolveCaretOffset(IDocument document)
    {
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        if (viewer != null)
        {
            Point selectedRange = viewer.getSelectedRange();
            if (selectedRange != null)
                return selectedRange.x;
        }
        return 0;
    }

    @Override
    public void apply(IDocument document, char trigger, int offset)
    {
        if (document == null)
            return;
        int replaceStart;
        int replaceLen;
        if (replaceParentOnInsert)
        {
            replaceStart = computeMaskPrefixStart(document, offset);
            replaceLen = Math.max(0, offset - replaceStart);
        }
        else
        {
            replaceStart = getPrefixCompletionStart(document, offset);
            replaceLen = Math.max(0, offset - replaceStart);
        }
        pendingCaretAfterApply = -1;
        try
        {
            InsertPlan plan = buildInsertPlan();
            document.replace(replaceStart, replaceLen, plan.text);
            pendingCaretAfterApply = replaceStart + plan.caretOffset;
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("apply: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
    {
        if (viewer != null && viewer.getDocument() != null)
            apply(viewer.getDocument(), trigger, offset);
    }

    /** Активация обрабатывается в {@link SmartCompletionProposal#selected}. */
    @Override
    public void selected(ITextViewer viewer, boolean smartToggle) {}

    @Override
    public void unselected(ITextViewer viewer) {}

    @Override
    public boolean validate(IDocument document, int offset,
        org.eclipse.jface.text.DocumentEvent event)
    {
        return SmartContentAssistProcessor.proposalMatchesFilter(this, document, offset, event);
    }

    @Override
    public boolean isValidFor(IDocument document, int offset)
    {
        return SmartContentAssistProcessor.proposalMatchesFilter(this, document, offset, null);
    }

    @Override
    public char[] getTriggerCharacters()
    {
        return null;
    }

    @Override
    public int getContextInformationPosition()
    {
        return -1;
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
    {
        return filterName;
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset)
    {
        return SmartContentAssistProcessor.computeIdentifierWordStart(document, completionOffset);
    }

    public String getTemplateText()
    {
        return templateText;
    }

    void setPendingCaretAfterApply(int offset)
    {
        pendingCaretAfterApply = offset;
    }

    InsertPlan buildInsertPlan()
    {
        if (templateText != null && !templateText.isEmpty())
            return planFromTemplate(templateText);
        if (method)
            return new InsertPlan(filterName + "()", filterName.length() + 1); //$NON-NLS-1$
        return new InsertPlan(filterName, filterName.length());
    }

    static InsertPlan planFromTemplate(String template)
    {
        int caret = template.length();
        String text = template;
        int marker = template.indexOf("<!>"); //$NON-NLS-1$
        if (marker >= 0)
        {
            caret = marker;
            text = template.substring(0, marker) + template.substring(marker + 3);
        }
        else
        {
            marker = template.indexOf("<?>"); //$NON-NLS-1$
            if (marker >= 0)
            {
                caret = marker;
                text = template.substring(0, marker) + template.substring(marker + 3);
            }
            else
            {
                marker = template.indexOf("~<?>"); //$NON-NLS-1$
                if (marker >= 0)
                {
                    caret = marker;
                    text = template.substring(0, marker) + template.substring(marker + 4);
                }
            }
        }
        return new InsertPlan(text, caret);
    }

    static final class InsertPlan
    {
        String text;
        int caretOffset;

        InsertPlan(String text, int caretOffset)
        {
            this.text = text;
            this.caretOffset = caretOffset;
        }
    }

    /**
     * Вычисляет позицию начала маски модуля (включая {@code .} перед маской)
     * для предложений с {@code replaceParentOnInsert}.
     * <p>Сканирует влево от {@code offset}: пропускает {@code .}, затем идентификаторные
     * символы маски (как {@link SmartContentAssistProcessor#computeIdentifierWordStart},
     * но захватывает {@code .} и текст до него).
     */
    static int computeMaskPrefixStart(IDocument document, int offset)
    {
        if (document == null || offset < 0)
            return offset;
        try
        {
            int pos = offset;
            if (pos > 0 && document.getChar(pos - 1) == '.')
                pos--;
            while (pos > 0 && SmartContentAssistProcessor.isFilterChar(document.getChar(pos - 1)))
                pos--;
            return pos;
        }
        catch (BadLocationException e)
        {
            return SmartContentAssistProcessor.computeIdentifierWordStart(document, offset);
        }
    }

    /**
     * Иконки member assist — как {@code BslProposalProvider.getExternalMethPropImg} /
     * {@code getExternalPropertyPropImg} (не var/meth из быстрой схемы).
     */
    private static final class BslAssistListImages
    {
        private static Image propertyImage;
        private static Image functionImage;
        private static Image procedureImage;

        private BslAssistListImages() {}

        static Image resolve(boolean method, boolean returnsValue)
        {
            if (!method)
            {
                if (propertyImage == null)
                    propertyImage = BslSharedImages.getImage(BslSharedImages.IMG_EXTERNAL_PROPERTY);
                return propertyImage;
            }
            if (returnsValue)
            {
                if (functionImage == null)
                    functionImage = BslSharedImages.getImage(BslSharedImages.IMG_EXTERNAL_FUNC);
                return functionImage;
            }
            if (procedureImage == null)
                procedureImage = BslSharedImages.getImage(BslSharedImages.IMG_EXTERNAL_PROC);
            return procedureImage;
        }
    }
}
