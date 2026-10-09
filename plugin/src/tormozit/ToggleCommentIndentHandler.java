package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension2;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Штатная команда «Переключить комментарий» (org.eclipse.xtext.ui.ToggleCommentAction) при
 * включении комментария вставляет "//" в начало каждой строки (0-я колонка), не учитывая отступ —
 * см. {@code TextViewer.shiftRight}. Включение комментария вставляет префикс в колонку
 * минимального отступа среди выделенных строк. Обе операции заменяют блок одним изменением
 * документа, чтобы не запускать слушателей BSL-редактора отдельно для каждой строки.
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
			boolean uncomment = isRangeCommented(document, startLine, endLine);
			if (!uncomment || operationTarget.canDoOperation(ITextOperationTarget.STRIP_PREFIX))
				toggleLineBlock(editor, document, textSelection, startLine, endLine, uncomment);
		} catch (BadLocationException e) {
			// Диапазон строк взят из актуального выделения.
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

	private static int visualColumn(String text, int length, int tabWidth) {
		int col = 0;
		for (int i = 0; i < length; i++)
			col += text.charAt(i) == '\t' ? tabWidth - col % tabWidth : 1;
		return col;
	}

	private void toggleLineBlock(ITextEditor editor, IDocument document, ITextSelection textSelection,
			int startLine, int endLine, boolean uncomment) throws BadLocationException {
		int tabWidth = 4;
		ITextOperationTarget operationTarget = editor.getAdapter(ITextOperationTarget.class);
		if (operationTarget instanceof ITextViewer) {
			StyledText widget = ((ITextViewer) operationTarget).getTextWidget();
			if (widget != null && !widget.isDisposed() && widget.getTabs() > 0)
				tabWidth = widget.getTabs();
		}

		// минимальный отступ — в видимых колонках (табуляция = до ближайшей позиции табуляции),
		// чтобы "//" встали в одну колонку и при смеси табуляций с пробелами
		int minIndent = Integer.MAX_VALUE;
		boolean padWithTabs = false;
		for (int line = startLine; !uncomment && line <= endLine; line++) {
			IRegion region = document.getLineInformation(line);
			String text = document.get(region.getOffset(), region.getLength());
			if (text.isBlank())
				continue;
			int leading = text.length() - text.stripLeading().length();
			int indent = visualColumn(text, leading, tabWidth);
			if (indent < minIndent) {
				minIndent = indent;
				padWithTabs = text.substring(0, leading).indexOf('\t') >= 0;
			}
		}
		if (minIndent == Integer.MAX_VALUE)
			minIndent = 0;

		Position selectionPosition = new Position(textSelection.getOffset(), textSelection.getLength());
		int blockStart = document.getLineOffset(startLine);
		IRegion lastLine = document.getLineInformation(endLine);
		int blockEnd = lastLine.getOffset() + lastLine.getLength();
		StringBuilder replacement = new StringBuilder(blockEnd - blockStart);
		int delta = 0;

		IRewriteTarget rewriteTarget = editor.getAdapter(IRewriteTarget.class);
		if (rewriteTarget != null)
			rewriteTarget.beginCompoundChange();
		try {
			for (int line = startLine; line <= endLine; line++) {
				IRegion region = document.getLineInformation(line);
				String text = document.get(region.getOffset(), region.getLength());
				if (uncomment) {
					int prefixAt = text.indexOf(COMMENT_PREFIX);
					replacement.append(text, 0, prefixAt).append(text, prefixAt + COMMENT_PREFIX.length(), text.length());
					int at = region.getOffset() + prefixAt + delta;
					int selectionEnd = selectionPosition.getOffset() + selectionPosition.getLength();
					int mappedStart = mapRemovedOffset(selectionPosition.getOffset(), at, COMMENT_PREFIX.length());
					int mappedEnd = mapRemovedOffset(selectionEnd, at, COMMENT_PREFIX.length());
					selectionPosition.setOffset(mappedStart);
					selectionPosition.setLength(mappedEnd - mappedStart);
					delta -= COMMENT_PREFIX.length();
					if (line < endLine)
						replacement.append(document.getLineDelimiter(line));
					continue;
				}
				int pos = 0;
				int col = 0;
				boolean straddle = false;
				while (col < minIndent && pos < text.length()) {
					char ch = text.charAt(pos);
					if (ch != ' ' && ch != '\t')
						break;
					int next = ch == '\t' ? col + tabWidth - col % tabWidth : col + 1;
					if (next > minIndent) {
						straddle = true; // табуляция перескакивает колонку — вставляем перед ней
						break;
					}
					col = next;
					pos++;
				}
				StringBuilder insert = new StringBuilder();
				if (!straddle) {
					// строке не хватает непечатных символов до колонки вставки — дополняем
					while (col < minIndent) {
						int tabEnd = col + tabWidth - col % tabWidth;
						if (padWithTabs && tabEnd <= minIndent) {
							insert.append('\t');
							col = tabEnd;
						} else {
							insert.append(' ');
							col++;
						}
					}
				}
				insert.append(COMMENT_PREFIX);
				replacement.append(text, 0, pos).append(insert).append(text, pos, text.length());
				// Та же привязка границ, что у DefaultPositionUpdater при построчной вставке:
				// вставка на начале сдвигает выделение, на конце не расширяет его.
				int at = region.getOffset() + pos + delta;
				int selectionStart = selectionPosition.getOffset();
				if (at <= selectionStart)
					selectionPosition.setOffset(selectionStart + insert.length());
				else if (at < selectionStart + selectionPosition.getLength())
					selectionPosition.setLength(selectionPosition.getLength() + insert.length());
				delta += insert.length();
				if (line < endLine)
					replacement.append(document.getLineDelimiter(line));
			}
			document.replace(blockStart, blockEnd - blockStart, replacement.toString());
			editor.getSelectionProvider().setSelection(
					new TextSelection(document, selectionPosition.getOffset(), selectionPosition.getLength()));
		} finally {
			if (rewriteTarget != null)
				rewriteTarget.endCompoundChange();
		}
	}

	private static int mapRemovedOffset(int offset, int at, int length) {
		return offset <= at ? offset : offset - Math.min(offset - at, length);
	}
}
