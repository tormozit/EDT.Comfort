package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
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

    /** ИР: имя обработчика в кавычках, интервал 0.1 и однократный вызов. */
    static Point applyIdleHandler(ICompletionProposal proposal, IDocument document, ITextViewer viewer,
        int caret, Integer stateMask)
    {
        if (!(proposal instanceof ConfigurableCompletionProposal cp) || document == null)
            return null;
        String replacement = cp.getReplacementString();
        int open = replacement != null ? replacement.indexOf('(') : -1;
        String name = open >= 0 ? replacement.substring(0, open).trim() : ""; //$NON-NLS-1$
        if (!name.equalsIgnoreCase("ПодключитьОбработчикОжидания") //$NON-NLS-1$
            && !name.equalsIgnoreCase("AttachIdleHandler")) //$NON-NLS-1$
            return null;
        Point applied = null;
        try
        {
            int close = replacement.lastIndexOf(')');
            if (caret < 0 || caret > document.getLength() || close < open
                || !replacement.substring(open + 1, close).matches("[\\s,]*") //$NON-NLS-1$
                || SmartContentAssistProcessor.isCommentAssistContext(document, caret)
                || SmartContentAssistProcessor.isStringLiteralAssistContext(document, caret)
                || caret < document.getLength() && document.getChar(caret) == '(')
                return null;
            int start = cp.getReplacementOffset();
            Point selected = viewer != null ? viewer.getSelectedRange() : null;
            int length = caret - start + (selected != null && selected.x == caret ? selected.y : 0);
            if (stateMask != null && (stateMask & 262144) != 0)
                length = cp.getReplaceContextLength();
            if (start < 0 || length < 0 || start + length > document.getLength()
                || start > 0 && document.getChar(start - 1) == '.')
                return null;
            String prefix = (replacement.startsWith(" ") ? " " : "") + name + "(\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String text = prefix + "\", 0.1, " + (name.equalsIgnoreCase("AttachIdleHandler") ? "True" : "Истина") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            if (start + length == document.getLength() || document.getChar(start + length) != ';')
                text += ";"; //$NON-NLS-1$
            SmartContentAssistProcessor.clearCtorPendingApplyState();
            document.replace(start, length, text);
            applied = new Point(start + prefix.length(), 0);
            reopenAfterInsertion(viewer, document, "idle-handler"); //$NON-NLS-1$
            return applied;
        }
        catch (BadLocationException | RuntimeException error)
        {
            return applied;
        }

    }

    /** ИР: «Левая часть присвоения» — каретка справа от «=», затем новый список слов. */
    static Point applyAssignment(ICompletionProposal proposal, IDocument document, ITextViewer viewer,
        int caret, Integer stateMask)
    {
        // Вставкой слов ИР управляет сам адаптер.
        if (proposal instanceof IrCompletionProposal)
            return null;
        Point applied = null;
        try
        {
            Point selected = viewer != null ? viewer.getSelectedRange() : null;
            if (selected != null && selected.y != 0)
                return null;
            String tail = document.get(caret,
                document.getLineInformationOfOffset(caret).getOffset()
                    + document.getLineInformationOfOffset(caret).getLength() - caret);
            if (!tail.isBlank() && !tail.trim().equals(";")) //$NON-NLS-1$
                return null;
            if (!BslExpectedTypePremiums.canInsertAssignment(document, caret, proposal))
                return null;
            String name = SmartContentAssistProcessor.filterMatchName(proposal);
            ConfigurableCompletionProposal cp = proposal instanceof ConfigurableCompletionProposal value ? value : null;
            int start = cp != null ? cp.getReplacementOffset()
                : caret - SmartContentAssistProcessor.computeIdentifierFilter(document, caret).length();
            int length = caret - start;
            if (cp != null && stateMask != null && (stateMask & 262144) != 0)
                length = cp.getReplaceContextLength();
            if (start < 0 || length < 0 || start + length > document.getLength() || name.isBlank())
                return null;
            String text = name + " = "; //$NON-NLS-1$
            SmartContentAssistProcessor.clearCtorPendingApplyState();
            document.replace(start, length, text + (tail.isBlank() ? ";" : "")); //$NON-NLS-1$ //$NON-NLS-2$
            applied = new Point(start + text.length(), 0);
            reopenAfterInsertion(viewer, document, "assignment"); //$NON-NLS-1$
            return applied;
        }
        catch (Exception error)
        {
            return applied;
        }
    }

    /** {@code ВидПовторногоВызоваАвтодополнения = 1}: новый вызов команды после завершения вставки. */
    static void reopenAfterInsertion(ITextViewer viewer, IDocument document, String reason)
    {
        ITextViewer target = viewer != null ? viewer : ContentAssistSessionReloader.getActiveViewer();
        ContentAssistSessionReloader reloader = target instanceof SourceViewer sourceViewer
            ? ContentAssistSessionReloader.forViewer(sourceViewer) : null;
        var assistant = reloader != null ? reloader.getAssistant() : null;
        if (target == null || target.getDocument() != document || assistant == null)
            return;
        ContentAssistSessionReloader.requestGatedShow(target, "insert-repeat", () -> //$NON-NLS-1$
        {
            if (target.getDocument() != document || target.getTextWidget().isDisposed())
                return;
            if (!ContentAssistSessionReloader.isModelCurrent(document)
                && document instanceof org.eclipse.jface.text.IDocumentExtension4 stamped)
            {
                long stamp = stamped.getModificationStamp();
                int caret = SmartContentAssistProcessor.resolveWidgetCaret(target);
                org.eclipse.core.runtime.jobs.Job job = new org.eclipse.core.runtime.jobs.Job(
                    "Комфорт: автодополнение после вставки") //$NON-NLS-1$
                {
                    @Override
                    protected org.eclipse.core.runtime.IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
                    {
                        boolean ready = ContentAssistSessionReloader.waitModelReadyUpTo(document, Integer.MAX_VALUE,
                            () -> monitor.isCanceled() || stamped.getModificationStamp() != stamp);
                        if (ready && stamped.getModificationStamp() == stamp)
                            ContentAssistSessionReloader.requestGatedShow(target, "insert-repeat-ready", () -> { //$NON-NLS-1$
                                boolean valid = !target.getTextWidget().isDisposed()
                                    && target.getDocument() == document && stamped.getModificationStamp() == stamp
                                    && SmartContentAssistProcessor.resolveWidgetCaret(target) == caret
                                    && !ContentAssistPopupSync.isPopupVisible(assistant);
                                if (valid)
                                    reopenAfterInsertion(target, document, reason);
                            });
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    }
                };
                job.setSystem(true);
                job.setPriority(org.eclipse.core.runtime.jobs.Job.INTERACTIVE);
                job.schedule();
                return;
            }
            ContentAssistPopupSync.showPossibleCompletionsAsCommand(assistant);
        });
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
