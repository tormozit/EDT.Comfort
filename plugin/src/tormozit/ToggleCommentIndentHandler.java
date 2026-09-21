package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.DefaultPositionUpdater;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IPositionUpdater;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension2;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Штатная команда «Переключить комментарий» (org.eclipse.xtext.ui.ToggleCommentAction) при
 * включении комментария вставляет "//" в начало каждой строки (0-я колонка), не учитывая отступ —
 * см. {@code TextViewer.shiftRight}. Снятие комментария ({@code STRIP_PREFIX}) в штатной
 * реализации уже игнорирует отступ корректно, поэтому здесь не дублируется: делегируется тому же
 * {@link ITextOperationTarget}, которым пользуется штатный обработчик. Переопределяется только
 * включение комментария — вставка в колонку минимального отступа среди выделенных строк.
 */
public class ToggleCommentIndentHandler extends AbstractHandler {

	private static final String COMMENT_PREFIX = "//";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
		BslXtextEditor bslEditor = GetRef.getActiveBslEditor(editorPart);
		if (bslEditor == null)
			return null;
		ITextEditor editor = bslEditor;
		if (!editor.isEditable())
			return null;
		if (editor instanceof ITextEditorExtension2 && !((ITextEditorExtension2) editor).validateEditorInputState())
			return null;

		IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		ISelection selection = editor.getSelectionProvider().getSelection();
		if (document == null || !(selection instanceof ITextSelection))
			return null;

		ITextSelection textSelection = (ITextSelection) selection;
		int startLine = textSelection.getStartLine();
		int endLine = textSelection.getEndLine();
		if (startLine < 0 || endLine < 0)
			return null;

		ITextOperationTarget operationTarget = editor.getAdapter(ITextOperationTarget.class);
		if (operationTarget == null)
			return null;

		try {
			if (isRangeCommented(document, startLine, endLine)) {
				// штатное снятие комментария уже учитывает отступ каждой строки индивидуально —
				// используем ту же реализацию, что и оригинальная команда, а не свою копию.
				if (operationTarget.canDoOperation(ITextOperationTarget.STRIP_PREFIX))
					operationTarget.doOperation(ITextOperationTarget.STRIP_PREFIX);
			} else {
				commentWithMinIndent(editor, document, textSelection, startLine, endLine);
			}
		} catch (BadLocationException e) {
			// не должно происходить: диапазон строк взят из актуального выделения
		}
		return null;
	}

	private boolean isRangeCommented(IDocument document, int startLine, int endLine) throws BadLocationException {
		for (int line = startLine; line <= endLine; line++) {
			IRegion region = document.getLineInformation(line);
			String text = document.get(region.getOffset(), region.getLength());
			if (!text.stripLeading().startsWith(COMMENT_PREFIX))
				return false;
		}
		return true;
	}

	private void commentWithMinIndent(ITextEditor editor, IDocument document, ITextSelection textSelection,
			int startLine, int endLine) throws BadLocationException {
		int minIndent = Integer.MAX_VALUE;
		for (int line = startLine; line <= endLine; line++) {
			IRegion region = document.getLineInformation(line);
			String text = document.get(region.getOffset(), region.getLength());
			if (text.isBlank())
				continue;
			int indent = text.length() - text.stripLeading().length();
			minIndent = Math.min(minIndent, indent);
		}
		if (minIndent == Integer.MAX_VALUE)
			minIndent = 0;

		String selectionCategory = "tormozit.toggleComment.selection";
		Position selectionPosition = new Position(textSelection.getOffset(), textSelection.getLength());
		IPositionUpdater selectionUpdater = new DefaultPositionUpdater(selectionCategory);
		boolean positionTracked = true;
		try {
			document.addPositionCategory(selectionCategory);
			document.addPositionUpdater(selectionUpdater);
			document.addPosition(selectionCategory, selectionPosition);
		} catch (BadLocationException | BadPositionCategoryException e) {
			positionTracked = false;
		}

		IRewriteTarget rewriteTarget = editor.getAdapter(IRewriteTarget.class);
		if (rewriteTarget != null)
			rewriteTarget.beginCompoundChange();
		try {
			for (int line = startLine; line <= endLine; line++) {
				IRegion region = document.getLineInformation(line);
				int insertOffset = region.getOffset() + Math.min(minIndent, region.getLength());
				document.replace(insertOffset, 0, COMMENT_PREFIX);
			}
		} finally {
			if (rewriteTarget != null)
				rewriteTarget.endCompoundChange();
			if (positionTracked) {
				document.removePositionUpdater(selectionUpdater);
				try {
					document.removePosition(selectionCategory, selectionPosition);
					document.removePositionCategory(selectionCategory);
				} catch (BadPositionCategoryException e) {
					// категория уже снята — нечего восстанавливать
				}
				if (!selectionPosition.isDeleted())
					editor.getSelectionProvider()
						.setSelection(new TextSelection(document, selectionPosition.getOffset(), selectionPosition.getLength()));
			}
		}
	}
}
