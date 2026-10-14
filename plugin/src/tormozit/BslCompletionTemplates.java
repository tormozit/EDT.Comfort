package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.graphics.Point;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;

/** Встроенные шаблоны вставки слов из автодополнения BSL. */
final class BslCompletionTemplates
{
    private BslCompletionTemplates() {}

    /** Возвращает каретку после вставки либо {@code null}, если шаблон неприменим. */
    static Point apply(ICompletionProposal proposal, IDocument document, ITextViewer viewer,
        int caret, Integer stateMask)
    {
        if (document == null || caret < 0 || caret > document.getLength())
            return null;
        ConfigurableCompletionProposal cp = proposal instanceof ConfigurableCompletionProposal configurable
            ? configurable : null;
        String word = cp != null ? cp.getReplacementString()
            : proposal instanceof IrCompletionProposal ir ? ir.getWordValue() : null;
        if (word == null)
            return null;
        int open = word.indexOf('(');
        String name = (open >= 0 ? word.substring(0, open) : word).trim();
        if (!name.equalsIgnoreCase("НСтр") && !name.equalsIgnoreCase("NStr")) //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        try
        {
            if (caret < document.getLength() && document.getChar(caret) == '(')
                return null;
            int start = cp != null ? cp.getReplacementOffset()
                : ((IrCompletionProposal)proposal).getPrefixCompletionStart(document, caret);
            if (start < 0 || start > caret || (start > 0 && document.getChar(start - 1) == '.'))
                return null;
            String languageCode = mainLanguageCode(document);
            if (languageCode == null || languageCode.isBlank())
                return null;
            Point selection = viewer != null ? viewer.getSelectedRange() : null;
            int length = caret - start + (selection != null ? selection.y : 0);
            // COMPLETE — тот же диапазон замены, что у штатного предложения JFace.
            if (cp != null && stateMask != null && (stateMask & 262144) != 0)
                length = cp.getReplaceContextLength();
            String prefix = name + "(\"" + languageCode + " = '"; //$NON-NLS-1$ //$NON-NLS-2$
            if ((word.startsWith(" ") && cp != null) //$NON-NLS-1$
                || SmartCompletionProposal.needsSpaceAfterEquals(document, start, prefix))
                prefix = " " + prefix; //$NON-NLS-1$
            SmartContentAssistProcessor.clearCtorPendingApplyState();
            document.replace(start, length, prefix + "'\")"); //$NON-NLS-1$
            return new Point(start + prefix.length(), 0);
        }
        catch (BadLocationException | RuntimeException e)
        {
            return null;
        }
    }

    private static String mainLanguageCode(IDocument document)
    {
        if (!(document instanceof IXtextDocument xtextDocument) || xtextDocument.getResourceURI() == null)
            return null;
        IV8ProjectManager manager = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project project = manager != null ? manager.getProject(xtextDocument.getResourceURI()) : null;
        Configuration configuration = project instanceof IConfigurationAware aware ? aware.getConfiguration() : null;
        Language language = configuration != null ? configuration.getDefaultLanguage() : null;
        return language != null ? language.getLanguageCode() : null;
    }
}
