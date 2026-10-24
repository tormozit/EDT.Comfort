package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.ResourceBundle;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IChangeRulerColumn;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.LineChangeHover;
import org.eclipse.jface.text.source.LineNumberChangeRulerColumn;
import org.eclipse.jface.text.source.OverviewRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.FindReplaceAction;
import org.eclipse.ui.texteditor.MarkerAnnotationPreferences;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.ui.texteditor.quickdiff.IQuickDiffReferenceProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
/**
 * Обработчик команды «Вложенный текст» в контекстном меню BSL-редактора (#885).
 *
 * <p>При подключённом приложении ИР строковый литерал BSL под курсором открывается в редакторе
 * текста ИР ({@code ирКлиент.ОткрытьТекстЛкс}). Без ИР — в окне «Вложенный текст» плагина
 * ({@link EmbeddedTextDialog}, форматы и подсветка — {@link FormattedTextEditor}): выделение
 * литерала переносится в окно, а после «Применить» — выделение окна в модуль.
 *
 * <h3>Поддерживаемые форматы BSL-строк</h3>
 * <ul>
 *   <li>Однострочные: {@code "текст"}</li>
 *   <li>Многострочные с продолжением через {@code |}:
 *       <pre>
 *       "первая строка
 *       |вторая строка
 *       |третья строка"
 *       </pre></li>
 * </ul>
 */
public final class EditEmbeddedTextHandler
{
    private EditEmbeddedTextHandler() {}

    // =========================================================================
    // Публичный API
    // =========================================================================

    /**
     * Быстрая проверка применимости команды: курсор находится внутри
     * строкового литерала BSL. Вызывается на UI-потоке при отображении меню.
     */
    public static boolean isApplicable(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return false;

        IXtextDocument doc = (IXtextDocument)viewer.getDocument();
        if (doc == null)
            return false;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection))
            return false;

        return BslStringLiteral.at(doc, ((ITextSelection) sel).getOffset()) != null;
    }

    /**
     * Открывает вложенный текст: при подключённом ИР — в редакторе текста ИР, иначе — в окне
     * «Вложенный текст» плагина. Подключение к ИР команда не начинает.
     */
    public static void editEmbeddedText(BslXtextEditor editor)
    {
        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        IRSession irSession = IRApplication.peekConnectedSession(dtProject);
        if (irSession == null || irSession.executor == null || irSession.executor.isShutdown())
        {
            EmbeddedTextDialog.edit(editor);
            return;
        }
        String ref = GetRef.buildExtendedRef(editor, false);
        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() ->
        {
            try
            {
                String textToOpen = irSession.selectTextLiteral();
                irSession.showWindow();
                irSession.openTextEditor(textToOpen, ref);
                ToastNotification.show("Редактор ИР", "Измененный вложенный текст вернется в EDT, если не будет изменяться там во время редактирования в приложении ИР!");
            }
            catch (Exception e)
            {
                Global.log("EditEmbeddedTextHandler: ошибка вызова ИР: " + e.getMessage()); //$NON-NLS-1$
                ToastNotification.show("Вложенный текст", "Ошибка вызова ИР: " + e.getMessage(), 5_000);
            }
        });
    }

    // =========================================================================
    // Извлечение строкового литерала BSL
    // =========================================================================

    /**
     * {@code true}, если {@code offset} внутри строкового литерала BSL (одно- или многострочного).
     */
    static boolean isCaretInStringLiteral(IXtextDocument doc, int offset)
    {
        return extractStringLiteral(doc, offset) != null;
    }

    /**
     * Возвращает содержимое строкового литерала BSL, в котором находится
     * позиция {@code offset}, без кавычек и символов продолжения {@code |}.
     *
     * <p>Поддерживает однострочные и многострочные (через {@code |}) литералы.
     * Возвращает {@code null}, если курсор не находится внутри литерала.
     */
    static String extractStringLiteral(IXtextDocument doc, int offset)
    {
        try
        {
//            QueryTextInfo literal = BslQueryWizardUtils.getCurrentStringLiteral(doc.module, doc.getLineOfOffset(offset), 0, 0);
            int cursorLine = doc.getLineOfOffset(offset);

            // ── Шаг 1: найти строку с открывающей кавычкой ─────────────────
            // Курсор может быть на строке продолжения (начинается с |),
            // поэтому идём назад до строки, которая НЕ является продолжением.

            int openingLineNum = cursorLine;
            for (int ln = cursorLine; ln >= 0; ln--)
            {
                IRegion li   = doc.getLineInformation(ln);
                String  text = doc.get(li.getOffset(), li.getLength());

                if (ln == cursorLine)
                {
                    if (!text.stripLeading().startsWith("|")) //$NON-NLS-1$
                    {
                        openingLineNum = ln; // курсор уже на строке с "
                        break;
                    }
                    // иначе — строка продолжения, идём выше
                }
                else
                {
                    // Строки выше курсора
                    if (!text.stripLeading().startsWith("|")) //$NON-NLS-1$
                    {
                        openingLineNum = ln;
                        break;
                    }
                }
            }

            // ── Шаг 2: найти открывающую кавычку на openingLineNum ──────────
            IRegion openLineInfo = doc.getLineInformation(openingLineNum);
            String  openLineText = doc.get(openLineInfo.getOffset(), openLineInfo.getLength());

            // Лимит поиска по X: если курсор на той же строке — до курсора;
            // если курсор на строке продолжения — весь текст строки.
            int scanLimit = (openingLineNum == cursorLine)
                ? offset - openLineInfo.getOffset()
                : openLineText.length();
            scanLimit = Math.min(scanLimit, openLineText.length());
            if (scanLimit < 0)
                return null;

            // Ищем последнюю незакрытую кавычку (при нечётном счётчике)
            int openQuoteIdx = -1;
            int quoteCount   = 0;
            for (int i = 0; i < scanLimit; i++)
            {
                if (openLineText.charAt(i) == '"')
                {
                    quoteCount++;
                    if (quoteCount % 2 == 1)
                        openQuoteIdx = i; // потенциальная открывающая
                    else
                        openQuoteIdx = -1; // закрылась
                }
            }

            if (openQuoteIdx < 0)
                return null; // курсор вне строки
            String afterOpenQuote = openLineText.substring(openQuoteIdx + 1);
            int closeIdx = afterOpenQuote.indexOf('"');
            if (closeIdx >= 0)
                return afterOpenQuote.substring(0, closeIdx);

            // Многострочный: собираем строки продолжения (начинаются с |)
            StringBuilder sb = new StringBuilder(afterOpenQuote);
            for (int ln = openingLineNum + 1; ln < doc.getNumberOfLines(); ln++)
            {
                IRegion li       = doc.getLineInformation(ln);
                String  lineText = doc.get(li.getOffset(), li.getLength());
                String  trimmed  = lineText.stripLeading();
                if (!trimmed.startsWith("|")) //$NON-NLS-1$
                    break; // не продолжение — литерал не закрыт корректно
                String lineContent = trimmed.substring(1); // убираем |
                int closeQuote = lineContent.indexOf('"');
                if (closeQuote >= 0)
                {
                    // Нашли закрывающую кавычку
                    sb.append('\n').append(lineContent, 0, closeQuote);
                    return sb.toString();
                }
                sb.append('\n').append(lineContent);
            }
            return null; // закрывающая кавычка не найдена
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    /**
     * Строковый литерал BSL в документе модуля: границы, значение (без кавычек, {@code ""} → {@code "},
     * строки продолжения без {@code |}, перевод строки — {@code \n}) и соответствие позиций значения
     * и документа. Комментарии и пустые строки между строками продолжения пропускаются.
     */
    private static final class BslStringLiteral
    {
        /** Позиция открывающей кавычки. */
        final int start;

        /** Позиция после закрывающей кавычки. */
        final int end;

        final String value;

        /** Позиция в документе для каждого символа значения; последний элемент — закрывающая кавычка. */
        private final int[] offsets;

        /** Отступ строк продолжения перед {@code |}. */
        final String continuationIndent;

        private BslStringLiteral(int start, int end, String value, int[] offsets, String continuationIndent)
        {
            this.start = start;
            this.end = end;
            this.value = value;
            this.offsets = offsets;
            this.continuationIndent = continuationIndent;
        }

        /** Литерал, внутри которого (между кавычками) стоит {@code offset}; {@code null} — вне литерала. */
        static BslStringLiteral at(IDocument doc, int offset)
        {
            try
            {
                int caretLine = doc.getLineOfOffset(offset);
                int line = caretLine;
                // Каретка на строке продолжения — разбор с начала литерала (строки выше без «|» и «//»).
                if (lineText(doc, caretLine).stripLeading().startsWith("|")) //$NON-NLS-1$
                {
                    while (line > 0)
                    {
                        line--;
                        String trimmed = lineText(doc, line).stripLeading();
                        if (!trimmed.startsWith("|") && !trimmed.startsWith("//") && !trimmed.isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
                            break;
                    }
                }
                Builder current = null;
                for (int ln = line; ln < doc.getNumberOfLines(); ln++)
                {
                    IRegion info = doc.getLineInformation(ln);
                    String text = doc.get(info.getOffset(), info.getLength());
                    int base = info.getOffset();
                    int i = 0;
                    if (current != null)
                    {
                        String trimmed = text.stripLeading();
                        if (trimmed.isEmpty() || trimmed.startsWith("//")) //$NON-NLS-1$
                            continue;
                        if (!trimmed.startsWith("|")) //$NON-NLS-1$
                            return null; // литерал не закрыт
                        String indent = text.substring(0, text.length() - trimmed.length());
                        current.lineBreak(indent);
                        i = indent.length() + 1;
                    }
                    while (i < text.length())
                    {
                        char c = text.charAt(i);
                        if (current == null)
                        {
                            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')
                                break; // комментарий до конца строки
                            if (c == '"')
                                current = new Builder(base + i, leadingWhitespace(text));
                            i++;
                            continue;
                        }
                        if (c == '"')
                        {
                            if (i + 1 < text.length() && text.charAt(i + 1) == '"')
                            {
                                current.add('"', base + i);
                                i += 2;
                                continue;
                            }
                            BslStringLiteral literal = current.finish(base + i);
                            current = null;
                            i++;
                            if (literal.start < offset && offset < literal.end)
                                return literal;
                            if (literal.start >= offset)
                                return null;
                            continue;
                        }
                        current.add(c, base + i);
                        i++;
                    }
                    if (current != null)
                        current.lineEnd(base + text.length());
                    else if (ln >= caretLine)
                        return null;
                }
                return null;
            }
            catch (BadLocationException e)
            {
                return null;
            }
        }

        private static String lineText(IDocument doc, int line) throws BadLocationException
        {
            IRegion info = doc.getLineInformation(line);
            return doc.get(info.getOffset(), info.getLength());
        }

        private static String leadingWhitespace(String text)
        {
            int i = 0;
            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t'))
                i++;
            return text.substring(0, i);
        }

        /** Позиция в значении для позиции в документе (внутри литерала). */
        int toValueOffset(int docOffset)
        {
            for (int i = 0; i < offsets.length; i++)
                if (offsets[i] >= docOffset)
                    return i;
            return value.length();
        }

        /** Позиция в документе для позиции в значении. */
        int toDocOffset(int valueOffset)
        {
            return offsets[Math.max(0, Math.min(valueOffset, value.length()))];
        }

        /**
         * Текст литерала BSL для значения: {@code "} удваивается, строки после первой — с отступом
         * и {@code |}. {@code map[i]} — позиция символа значения {@code i} от начала литерала.
         */
        String toSource(String newValue, String lineDelimiter, int[] map)
        {
            StringBuilder sb = new StringBuilder(newValue.length() + 16);
            sb.append('"');
            for (int i = 0; i < newValue.length(); i++)
            {
                map[i] = sb.length();
                char c = newValue.charAt(i);
                if (c == '\n')
                    sb.append(lineDelimiter).append(continuationIndent).append('|');
                else if (c == '"')
                    sb.append("\"\""); //$NON-NLS-1$
                else
                    sb.append(c);
            }
            map[newValue.length()] = sb.length();
            sb.append('"');
            return sb.toString();
        }

        private static final class Builder
        {
            private final int start;

            /** Отступ строки с открывающей кавычкой. */
            private final String openingIndent;

            private final StringBuilder value = new StringBuilder();

            private final List<Integer> offsets = new ArrayList<>();

            private String continuationIndent;

            /** Позиция конца строки, на которой литерал продолжается. */
            private int pendingLineEnd = -1;

            Builder(int start, String openingIndent)
            {
                this.start = start;
                this.openingIndent = openingIndent;
            }

            void add(char c, int docOffset)
            {
                value.append(c);
                offsets.add(docOffset);
            }

            void lineEnd(int docOffset)
            {
                pendingLineEnd = docOffset;
            }

            void lineBreak(String indent)
            {
                if (continuationIndent == null)
                    continuationIndent = indent;
                add('\n', pendingLineEnd);
            }

            BslStringLiteral finish(int closeQuoteOffset)
            {
                int[] map = new int[offsets.size() + 1];
                for (int i = 0; i < offsets.size(); i++)
                    map[i] = offsets.get(i);
                map[offsets.size()] = closeQuoteOffset;
                // Новые строки продолжения — с отступом существующих, иначе на шаг глубже строки с кавычкой.
                String indent = continuationIndent != null ? continuationIndent : openingIndent + '\t';
                return new BslStringLiteral(start, closeQuoteOffset + 1, value.toString(), map, indent);
            }
        }
    }

    // =========================================================================
    // Окно «Вложенный текст»
    // =========================================================================

    /**
     * Модальное окно редактирования строкового литерала: подсветка формата и «Готовый текст»
     * ({@link FormattedTextEditor}). «Применить» (Ctrl+Enter) заменяет литерал в модуле одним
     * изменением (одна отмена) и переносит выделение окна в модуль.
     */
    private static final class EmbeddedTextDialog extends Dialog
    {
        private static final String SETTINGS_SECTION = "tormozit.EmbeddedTextDialog"; //$NON-NLS-1$

        private static final String RAW_TAB_TITLE = "Сырой текст"; //$NON-NLS-1$

        private static final String PREVIEW_TAB_TITLE = "Готовый текст"; //$NON-NLS-1$

        /** Ширина линейки обзора — как у текстового редактора. */
        private static final int OVERVIEW_RULER_WIDTH = 12;

        private static final int INSERT_WITH_COMPARE_ID = IDialogConstants.CLIENT_ID + 1;

        private static final String TEXTEDITOR_BUNDLE = "org.eclipse.ui.workbench.texteditor"; //$NON-NLS-1$

        private static final String LINE_DIFFER_CLASS =
            "org.eclipse.ui.internal.texteditor.quickdiff.DocumentLineDiffer"; //$NON-NLS-1$

        private static final String QD_ADDITION = "org.eclipse.ui.workbench.texteditor.quickdiffAddition"; //$NON-NLS-1$

        private static final String QD_CHANGE = "org.eclipse.ui.workbench.texteditor.quickdiffChange"; //$NON-NLS-1$

        private static final String QD_DELETION = "org.eclipse.ui.workbench.texteditor.quickdiffDeletion"; //$NON-NLS-1$

        private static final String FIND_NEXT_COMMAND_ID = "org.eclipse.ui.edit.findNext"; //$NON-NLS-1$

        private static final String FIND_PREVIOUS_COMMAND_ID = "org.eclipse.ui.edit.findPrevious"; //$NON-NLS-1$

        /** Подписи действия «Найти/Заменить» не нужны: оно только открывает окно поиска. */
        private static final ResourceBundle EMPTY_BUNDLE = new ListResourceBundle()
        {
            @Override
            protected Object[][] getContents()
            {
                return new Object[0][];
            }
        };

        private final String initialText;

        private final int initialStart;

        private final int initialEnd;

        private final boolean editable;

        private SourceViewer viewer;

        private TextSourceViewerConfiguration textConfiguration;

        private SourceViewerDecorationSupport decorationSupport;

        private OverviewRuler overviewRuler;

        /** Номера строк и метки быстрого сравнения. */
        private LineNumberChangeRulerColumn changeColumn;

        private boolean quickDiffInstalled;

        /** «Найти/Заменить» по тексту окна; создаётся при первом вызове. */
        private FindReplaceAction findReplaceAction;

        private FormattedTextEditor formatted;

        private CTabFolder tabs;

        private CTabItem rawTab;

        private CTabItem previewTab;

        /** Вкладка до переключения: уход с «Готового текста» переносит его выделение в текст. */
        private CTabItem lastTab;

        private FormattedTextEditor.Preview preview;

        private final List<IHandlerActivation> handlerActivations = new ArrayList<>();

        private String resultText;

        private int resultStart;

        private int resultEnd;

        /** Редактор модуля и литерал, из которого открыто окно (для «Вставить со сравнением»). */
        private final BslXtextEditor moduleEditor;

        private final BslStringLiteral literal;

        private EmbeddedTextDialog(Shell parent, BslXtextEditor moduleEditor, BslStringLiteral literal, String text,
            int start, int end, boolean editable)
        {
            super(parent);
            this.moduleEditor = moduleEditor;
            this.literal = literal;
            this.initialText = text;
            this.initialStart = start;
            this.initialEnd = end;
            this.editable = editable;
        }

        /** Открыть литерал под кареткой модуля; после «Применить» — заменить его и выделить фрагмент. */
        static void edit(BslXtextEditor editor)
        {
            ISourceViewer moduleViewer = editor.getInternalSourceViewer();
            IDocument document = moduleViewer != null ? moduleViewer.getDocument() : null;
            if (document == null)
                return;
            // Модельные координаты (ITextViewer), не виджетные: свёртки выше литерала.
            Point selection = moduleViewer.getSelectedRange();
            BslStringLiteral literal = BslStringLiteral.at(document, selection.x);
            if (literal == null)
            {
                ToastNotification.show("Вложенный текст", "Каретка не в строковом литерале.", 3_000); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            int contentStart = literal.start + 1;
            int contentEnd = literal.end - 1;
            int selStart = literal.toValueOffset(Math.max(contentStart, Math.min(selection.x, contentEnd)));
            int selEnd = literal.toValueOffset(Math.max(contentStart, Math.min(selection.x + selection.y, contentEnd)));
            boolean editable = editor.isEditorInputModifiable();
            EmbeddedTextDialog dialog = new EmbeddedTextDialog(editor.getSite().getShell(), editor, literal,
                literal.value, selStart, selEnd, editable);
            if (dialog.open() != OK || !editable)
                return;
            // Перевод строки в окне может быть «\r\n»: значение литерала и выделение — в LF.
            Global.LfTextSlice result = Global.toLfWithSelection(dialog.resultText, dialog.resultStart, dialog.resultEnd);
            String newValue = result.text();
            if (newValue.equals(literal.value))
            {
                int start = literal.toDocOffset(result.start());
                editor.selectAndReveal(start, literal.toDocOffset(result.end()) - start);
                return;
            }
            if (!editor.validateEditorInputState())
                return;
            int[] map = new int[newValue.length() + 1];
            String source = literal.toSource(newValue, TextUtilities.getDefaultLineDelimiter(document), map);
            try
            {
                document.replace(literal.start, literal.end - literal.start, source);
            }
            catch (BadLocationException e)
            {
                ToastNotification.show("Вложенный текст", "Не удалось применить текст: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            int start = literal.start + map[Math.min(result.start(), newValue.length())];
            int end = literal.start + map[Math.min(result.end(), newValue.length())];
            editor.selectAndReveal(start, end - start);
        }

        @Override
        protected void configureShell(Shell newShell)
        {
            super.configureShell(newShell);
            newShell.setText(Global.withPluginWindowTitle("Вложенный текст")); //$NON-NLS-1$
        }

        @Override
        protected boolean isResizable()
        {
            return true;
        }

        @Override
        protected IDialogSettings getDialogBoundsSettings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            if (section == null)
                section = top.addNewSection(SETTINGS_SECTION);
            return section;
        }

        @Override
        protected Point getInitialSize()
        {
            if (getDialogBoundsSettings().get("DIALOG_WIDTH") == null) //$NON-NLS-1$
                return new Point(800, 560);
            return super.getInitialSize();
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite)super.createDialogArea(parent);
            GridLayout layout = (GridLayout)area.getLayout();
            layout.verticalSpacing = 0;

            ToolBar toolBar = new ToolBar(area, SWT.FLAT | SWT.HORIZONTAL);
            toolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Label separator = new Label(area, SWT.SEPARATOR | SWT.HORIZONTAL);
            separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            tabs = new CTabFolder(area, SWT.BOTTOM | SWT.FLAT);
            GridData tabsData = new GridData(SWT.FILL, SWT.FILL, true, true);
            // Предпочтительный размер всего текста не раздувает окно: прокручивается сам текст.
            tabsData.widthHint = 100;
            tabsData.heightHint = 100;
            tabs.setLayoutData(tabsData);
            rawTab = new CTabItem(tabs, SWT.NONE);
            rawTab.setText(RAW_TAB_TITLE);

            // Линейки как у текстового редактора: номера строк (по настройке редакторов) с метками
            // отличий от исходного текста литерала (быстрое сравнение) и линейка обзора.
            IPreferenceStore editorsStore = EditorsUI.getPreferenceStore();
            CompositeRuler verticalRuler = new CompositeRuler();
            changeColumn = new LineNumberChangeRulerColumn(EditorsUI.getSharedTextColors());
            verticalRuler.addDecorator(0, changeColumn);
            overviewRuler = new OverviewRuler(new DefaultMarkerAnnotationAccess(), OVERVIEW_RULER_WIDTH,
                EditorsUI.getSharedTextColors());
            viewer = new SourceViewer(tabs, verticalRuler, overviewRuler, true,
                SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
            viewer.getTextWidget().setFont(JFaceResources.getTextFont());
            changeColumn.setFont(JFaceResources.getTextFont());
            changeColumn.setForeground(EditorsUI.getSharedTextColors().getColor(PreferenceConverter.getColor(
                editorsStore, AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR)));
            changeColumn.setBackground(viewer.getTextWidget().getBackground());
            changeColumn.showLineNumbers(
                editorsStore.getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER));
            viewer.setDocument(new Document(initialText), new AnnotationModel());
            viewer.setEditable(editable);
            rawTab.setControl(viewer.getControl());
            tabs.setSelection(rawTab);
            installDecorationSupport();
            installQuickDiff();
            installContextMenu();
            // Подсветка вхождений выделенного слова и найденного поиском — как на вкладке макета:
            // просмотрщик окна по цепочке виджетов не находится, регистрируем явно.
            TextEditorOccurrencesSupport.registerViewer(viewer);

            // «Обычный» — штатная настройка текстового редактора, как у вкладки макета: с проверкой орфографии.
            // Пометки орфографии проверка кладёт в видимую модель просмотрщика — её и чистим при смене формата.
            textConfiguration = new TextSourceViewerConfiguration(EditorsUI.getPreferenceStore());
            formatted = new FormattedTextEditor(viewer, textConfiguration, viewer.getAnnotationModel(),
                this::onFormatApplied);
            formatted.createFormatItem(toolBar,
                "Формат вложенного текста: подсветка и вкладка «Готовый текст».\n«Авто» — по содержимому.", //$NON-NLS-1$
                "", //$NON-NLS-1$
                null);
            formatted.start(null);

            viewer.setSelectedRange(initialStart, initialEnd - initialStart);
            viewer.revealRange(initialStart, initialEnd - initialStart);
            tabs.addListener(SWT.Selection, e -> onTabSelected());
            installKeys();
            return area;
        }

        @Override
        public void create()
        {
            super.create();
            viewer.getTextWidget().setFocus();
        }

        /**
         * Быстрое сравнение с исходным текстом литерала, как в редакторе модуля: метки добавленных,
         * изменённых и удалённых строк на колонке номеров и линейке обзора, исходные строки — в подсказке
         * при наведении. Вычисляет отличия штатный {@code DocumentLineDiffer} — класс внутреннего пакета
         * ({@code x-internal}, ограничение только для компиляции), поэтому через загрузку класса из бандла:
         * нет класса — окно работает без быстрого сравнения.
         */
        private void installQuickDiff()
        {
            try
            {
                Bundle bundle = Platform.getBundle(TEXTEDITOR_BUNDLE);
                if (bundle == null)
                    return;
                Object differ = bundle.loadClass(LINE_DIFFER_CLASS).getConstructor().newInstance();
                differ.getClass().getMethod("setReferenceProvider", IQuickDiffReferenceProvider.class) //$NON-NLS-1$
                    .invoke(differ, new InitialTextReference(initialText));
                if (!(differ instanceof IAnnotationModel differModel)
                    || !(viewer.getAnnotationModel() instanceof IAnnotationModelExtension extension))
                    return;
                extension.addAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID, differModel);
                quickDiffInstalled = true;
                changeColumn.setAddedColor(quickDiffColor(QD_ADDITION));
                changeColumn.setChangedColor(quickDiffColor(QD_CHANGE));
                changeColumn.setDeletedColor(quickDiffColor(QD_DELETION));
                changeColumn.setHover(new LineChangeHover());
                changeColumn.setModel(viewer.getAnnotationModel());
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                // Без быстрого сравнения: кнопка «Сравнить» остаётся.
            }
        }

        /** Цвет меток быстрого сравнения — из «Выделения изменений» (настройки пометок редакторов). */
        private static org.eclipse.swt.graphics.Color quickDiffColor(String annotationType)
        {
            AnnotationPreference preference = EditorsUI.getAnnotationPreferenceLookup()
                .getAnnotationPreference(annotationType);
            if (preference == null || preference.getColorPreferenceKey() == null)
                return null;
            return EditorsUI.getSharedTextColors().getColor(
                PreferenceConverter.getColor(EditorsUI.getPreferenceStore(), preference.getColorPreferenceKey()));
        }

        /**
         * Команда «Вставить со сравнением» модуля: литерал в модуле выделяется и сравнивается с литералом,
         * собранным из текста окна (кавычки, {@code |}). Одобрили вставку — окно закрывается (литерал уже
         * заменён); отменили — окно остаётся, в модуле возвращается прежнее выделение.
         */
        private void insertWithCompare()
        {
            ISourceViewer moduleViewer = moduleEditor.getInternalSourceViewer();
            IDocument document = moduleViewer != null ? moduleViewer.getDocument() : null;
            if (document == null)
                return;
            String newValue = Global.normalizeLineSeparators(viewer.getDocument().get());
            String source = literal.toSource(newValue, TextUtilities.getDefaultLineDelimiter(document),
                new int[newValue.length() + 1]);
            Point before = moduleViewer.getSelectedRange();
            moduleEditor.selectAndReveal(literal.start, literal.end - literal.start);
            TextEditor.Context context = TextEditor.resolveContext(moduleEditor, moduleEditor);
            if (context != null && PasteWithCompareActions.runWithNewTextInserted(getShell(), context, source))
            {
                setReturnCode(CANCEL);
                close();
                return;
            }
            moduleViewer.setSelectedRange(before.x, before.y);
        }

        @Override
        protected void buttonPressed(int buttonId)
        {
            if (buttonId == INSERT_WITH_COMPARE_ID)
            {
                insertWithCompare();
                return;
            }
            super.buttonPressed(buttonId);
        }

        /** Исходный текст литерала — образец быстрого сравнения. */
        private static final class InitialTextReference implements IQuickDiffReferenceProvider
        {
            private final String text;

            private String id = "tormozit.embeddedText.reference"; //$NON-NLS-1$

            InitialTextReference(String text)
            {
                this.text = text;
            }

            @Override
            public IDocument getReference(IProgressMonitor monitor)
            {
                return new Document(text);
            }

            @Override
            public void dispose()
            {
            }

            @Override
            public String getId()
            {
                return id;
            }

            @Override
            public void setActiveEditor(ITextEditor editor)
            {
            }

            @Override
            public boolean isEnabled()
            {
                return true;
            }

            @Override
            public void setId(String id)
            {
                this.id = id;
            }
        }

        /** Новая настройка просмотрщика сбрасывает подсказки при наведении — ставим подсказку орфографии заново. */
        private void onFormatApplied()
        {
            SpellCheckHook.installSpellingQuickFixHover(viewer, textConfiguration);
            syncPreviewTab();
        }

        /**
         * Отрисовка пометок (волны орфографии) по настройкам «Аннотаций» — как в текстовом редакторе:
         * у отдельного просмотрщика её ставит не редактор, а мы.
         */
        private void installDecorationSupport()
        {
            decorationSupport = new SourceViewerDecorationSupport(viewer, overviewRuler,
                new DefaultMarkerAnnotationAccess(), EditorsUI.getSharedTextColors());
            for (Object preference : new MarkerAnnotationPreferences().getAnnotationPreferences())
                if (preference instanceof AnnotationPreference annotationPreference)
                    decorationSupport.setAnnotationPreference(annotationPreference);
            decorationSupport.setCursorLinePainterPreferenceKeys(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_CURRENT_LINE,
                AbstractDecoratedTextEditorPreferenceConstants.EDITOR_CURRENT_LINE_COLOR);
            decorationSupport.install(EditorsUI.getPreferenceStore());
        }

        /** Контекстное меню текста: правка и быстрое исправление (варианты замены слова с ошибкой). */
        private void installContextMenu()
        {
            Menu menu = new Menu(viewer.getTextWidget());
            addMenuItem(menu, "Отменить\tCtrl+Z", ITextOperationTarget.UNDO); //$NON-NLS-1$
            addMenuItem(menu, "Повторить\tCtrl+Y", ITextOperationTarget.REDO); //$NON-NLS-1$
            new MenuItem(menu, SWT.SEPARATOR);
            addMenuItem(menu, "Вырезать\tCtrl+X", ITextOperationTarget.CUT); //$NON-NLS-1$
            addMenuItem(menu, "Копировать\tCtrl+C", ITextOperationTarget.COPY); //$NON-NLS-1$
            addMenuItem(menu, "Вставить\tCtrl+V", ITextOperationTarget.PASTE); //$NON-NLS-1$
            addMenuItem(menu, "Выделить всё\tCtrl+A", ITextOperationTarget.SELECT_ALL); //$NON-NLS-1$
            new MenuItem(menu, SWT.SEPARATOR);
            MenuItem find = new MenuItem(menu, SWT.PUSH);
            find.setText("Найти/Заменить...\tCtrl+F"); //$NON-NLS-1$
            find.addListener(SWT.Selection, e -> openFindReplace());
            MenuItem bracket = new MenuItem(menu, SWT.PUSH);
            bracket.setText("Перейти по скобкам\tCtrl+]"); //$NON-NLS-1$
            bracket.addListener(SWT.Selection, e -> formatted.jumpToBracket(true, false));
            // У формата «Обычный» скобки не сопоставляются — пункт недоступен.
            menu.addListener(SWT.Show, e -> bracket.setEnabled(formatted != null && formatted.format() != null
                && formatted.format() != FormattedTextEditor.Format.TEXT));
            addMenuItem(menu, "Быстрое исправление\tCtrl+1", ISourceViewer.QUICK_ASSIST); //$NON-NLS-1$
            menu.addListener(SWT.Show, e ->
            {
                for (MenuItem item : menu.getItems())
                    if (item.getData() instanceof Integer operation)
                        item.setEnabled(viewer.canDoOperation(operation));
            });
            viewer.getTextWidget().setMenu(menu);
        }

        private void addMenuItem(Menu menu, String text, int operation)
        {
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText(text);
            item.setData(operation);
            item.addListener(SWT.Selection, e ->
            {
                if (viewer.canDoOperation(operation))
                    viewer.doOperation(operation);
            });
        }

        /** «Готовый текст» — вкладка у форматов с оформленным видом. */
        private void syncPreviewTab()
        {
            boolean wanted = formatted.format() != null && formatted.format().hasPreview();
            if (wanted && previewTab == null)
            {
                try
                {
                    Browser browser = new Browser(tabs, SWT.NONE);
                    preview = new FormattedTextEditor.Preview(browser, formatted);
                    previewTab = new CTabItem(tabs, SWT.NONE);
                    previewTab.setText(PREVIEW_TAB_TITLE);
                    previewTab.setControl(browser);
                }
                catch (SWTError e)
                {
                    // Браузер недоступен — без «Готового текста».
                }
            }
            else if (!wanted && previewTab != null)
            {
                if (tabs.getSelection() == previewTab)
                    tabs.setSelection(rawTab);
                Control browser = previewTab.getControl();
                previewTab.dispose();
                if (browser != null)
                    browser.dispose();
                previewTab = null;
                preview = null;
            }
        }

        private void onTabSelected()
        {
            CTabItem selected = tabs.getSelection();
            if (lastTab == previewTab && previewTab != null && selected != previewTab && preview != null)
                preview.transferSelectionToRaw();
            lastTab = selected;
            if (selected == previewTab && preview != null)
                preview.render();
            else
                viewer.getTextWidget().setFocus();
        }

        /** Штатное окно «Найти/Заменить» по тексту окна (как Ctrl+F в текстовом редакторе). */
        private void openFindReplace()
        {
            if (viewer.getFindReplaceTarget() == null)
                return;
            if (findReplaceAction == null)
                findReplaceAction = new FindReplaceAction(EMPTY_BUNDLE, "Editor.FindReplace.", getShell(), //$NON-NLS-1$
                    viewer.getFindReplaceTarget());
            findReplaceAction.run();
        }

        /**
         * Клавиши текстового редактора, как на вкладке макета: отмена/повтор, «Найти/Заменить» (Ctrl+F),
         * «Найти далее/ранее» (F3/Shift+F3), быстрое исправление (Ctrl+1), «Применить» (Ctrl+Enter).
         * Быстрый поиск (Ctrl+F3/Ctrl+Shift+F3) здесь не перехватывать: его выполняет глобальный фильтр
         * {@link TextEditorFastSearchKeyBindingHook} на любом поле текста, а его {@code doit = false}
         * клавишу до {@code VerifyKey} всё равно пропускает — второй поиск возвращал выделение назад.
         *
         * <p>Привязки этих команд действуют в контексте редактора текста, которого в окне нет. Поэтому
         * два пути: обработчики команд на время окна (если привязка сработает в окне — клавиша
         * поглощается до текста) и {@code VerifyKey} (до обработки клавиши самим текстом).
         */
        private void installKeys()
        {
            IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (handlerService != null)
            {
                ActiveShellExpression expression = new ActiveShellExpression(getShell());
                handlerActivations.add(handlerService.activateHandler(IWorkbenchCommandConstants.EDIT_UNDO,
                    new TextOperationHandler(ITextOperationTarget.UNDO), expression));
                handlerActivations.add(handlerService.activateHandler(IWorkbenchCommandConstants.EDIT_REDO,
                    new TextOperationHandler(ITextOperationTarget.REDO), expression));
                handlerActivations.add(handlerService.activateHandler(IWorkbenchCommandConstants.EDIT_FIND_AND_REPLACE,
                    new RunHandler(this::openFindReplace), expression));
                handlerActivations.add(handlerService.activateHandler(FIND_NEXT_COMMAND_ID,
                    new RunHandler(() -> TextEditorFastSearchHandler.executeFindNextFromBuffer(viewer.getTextWidget(), true)),
                    expression));
                handlerActivations.add(handlerService.activateHandler(FIND_PREVIOUS_COMMAND_ID,
                    new RunHandler(() -> TextEditorFastSearchHandler.executeFindNextFromBuffer(viewer.getTextWidget(), false)),
                    expression));
            }
            viewer.getTextWidget().addVerifyKeyListener(new VerifyKeyListener()
            {
                @Override
                public void verifyKey(VerifyEvent event)
                {
                    int modifiers = event.stateMask & SWT.MODIFIER_MASK;
                    if (modifiers == SWT.MOD1 && event.keyCode == '1')
                    {
                        event.doit = false;
                        if (viewer.canDoOperation(ISourceViewer.QUICK_ASSIST))
                            viewer.doOperation(ISourceViewer.QUICK_ASSIST);
                        return;
                    }
                    if (modifiers == SWT.MOD1 && (event.keyCode == 'f' || event.keyCode == 'F'))
                    {
                        event.doit = false;
                        openFindReplace();
                        return;
                    }
                    if (event.keyCode == SWT.F3 && (modifiers == 0 || modifiers == SWT.SHIFT))
                    {
                        event.doit = false;
                        TextEditorFastSearchHandler.executeFindNextFromBuffer(viewer.getTextWidget(), modifiers == 0);
                        return;
                    }
                    if (editable && (event.stateMask & SWT.MOD1) != 0
                        && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR))
                    {
                        event.doit = false;
                        okPressed();
                    }
                }
            });
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            if (editable)
            {
                Button insert = createButton(parent, INSERT_WITH_COMPARE_ID, PasteWithCompareActions.MENU_LABEL, false);
                insert.setToolTipText(TooltipText.wrap(insert,
                    "Сравнить литерал в модуле с текстом окна и вставить; окно закроется, если вставку одобрить")); //$NON-NLS-1$
                Button ok = createButton(parent, IDialogConstants.OK_ID, "Применить", false); //$NON-NLS-1$
                ok.setToolTipText(TooltipText.wrap(ok, "Применить текст к строковому литералу модуля и закрыть окно (Ctrl+Enter)")); //$NON-NLS-1$
                createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
            }
            else
            {
                Button close = createButton(parent, IDialogConstants.CANCEL_ID, "Закрыть", false); //$NON-NLS-1$
                close.setToolTipText(TooltipText.wrap(close, "Модуль только для чтения: изменения не применяются")); //$NON-NLS-1$
            }
        }

        @Override
        protected void okPressed()
        {
            if (tabs.getSelection() == previewTab && preview != null)
                preview.transferSelectionToRaw();
            resultText = viewer.getDocument().get();
            Point selection = viewer.getSelectedRange();
            resultStart = selection.x;
            resultEnd = selection.x + selection.y;
            super.okPressed();
        }

        @Override
        protected void cancelPressed()
        {
            if (confirmDiscard())
                super.cancelPressed();
        }

        @Override
        protected void handleShellCloseEvent()
        {
            if (confirmDiscard())
                super.handleShellCloseEvent();
        }

        private boolean confirmDiscard()
        {
            if (!editable || viewer == null || initialText.equals(viewer.getDocument().get()))
                return true;
            return MessageDialog.openQuestion(getShell(), Global.withPluginWindowTitle("Вложенный текст"), //$NON-NLS-1$
                "Закрыть окно, не применив изменения вложенного текста?"); //$NON-NLS-1$
        }

        @Override
        public boolean close()
        {
            IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (handlerService != null)
                handlerService.deactivateHandlers(handlerActivations);
            handlerActivations.clear();
            if (decorationSupport != null)
            {
                decorationSupport.dispose();
                decorationSupport = null;
            }
            if (quickDiffInstalled && viewer != null
                && viewer.getAnnotationModel() instanceof IAnnotationModelExtension extension)
            {
                // Отключает вычисление отличий от документа.
                extension.removeAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID);
                quickDiffInstalled = false;
            }
            return super.close();
        }

        /** Команда окна без своего состояния доступности. */
        private static final class RunHandler extends AbstractHandler
        {
            private final Runnable action;

            RunHandler(Runnable action)
            {
                this.action = action;
            }

            @Override
            public Object execute(ExecutionEvent event)
            {
                action.run();
                return null;
            }
        }

        /** Отмена/повтор в тексте окна. */
        private final class TextOperationHandler extends AbstractHandler
        {
            private final int operation;

            TextOperationHandler(int operation)
            {
                this.operation = operation;
            }

            @Override
            public boolean isEnabled()
            {
                return viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()
                    && viewer.canDoOperation(operation);
            }

            @Override
            public Object execute(ExecutionEvent event)
            {
                if (isEnabled())
                    viewer.doOperation(operation);
                return null;
            }
        }
    }
}
