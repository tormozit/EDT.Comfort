package tormozit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Живой счётчик совпадений в стандартном диалоге «Найти/Заменить» ({@code org.eclipse.ui.texteditor.FindReplaceDialog},
 * пакетный класс, поэтому доступ только через {@link Global#getField}) — подсчёт идёт в фоне
 * ({@link Job}), не блокируя ввод; во время подсчёта в статусной строке диалога показывается
 * «Поиск…». Также переименовывает кнопку «Выбрать всё» в «Выделить все».
 *
 * <p>Документ берётся из {@code fTarget} диалога (viewer, для которого открыт поиск) — так счётчик
 * работает для любого {@link ITextViewer} (BSL, XML, язык запросов, вложенные EmbeddedEditor),
 * а не только для {@code ITextEditor} активного редактора Workbench. Для окон сравнения текстов
 * (2-way {@code TextMergeViewer} и 3-way {@code ThreeSideTextMergeViewer}) {@code fTarget} —
 * обёртка merge-вьюера: документ берётся из сфокусированной панели.
 *
 * <p>Прокрутка/выделение первого совпадения по мере ввода намеренно не реализованы — это уже
 * делает штатный флажок «Инкрементный» диалога.
 *
 * https://github.com/1C-Company/1c-edt-issues/issues/1500
 */
public final class FindReplaceLiveMatchCountHook implements IStartup
{
    private static final String TAG = "FindReplaceLiveCount"; //$NON-NLS-1$

    private static final String DIALOG_CLASS_NAME = "org.eclipse.ui.texteditor.FindReplaceDialog"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.findReplaceLiveCountSession"; //$NON-NLS-1$
    private static final String SELECT_ALL_LABEL = "&Выделить все"; //$NON-NLS-1$

    private static final int MIN_CHARS = 2;
    private static final int SEARCH_DELAY_MS = 150;
    private static final int MAX_MATCHES = 50000;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(FindReplaceLiveMatchCountHook::install);
    }

    private static void install()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, FindReplaceLiveMatchCountHook::handleShow);
    }

    /**
     * {@code FindReplaceDialog} переиспользуется (скрывается/показывается) между открытиями,
     * поэтому {@link Session} кешируется на {@code shell} — иначе повторный показ не обновлял бы
     * ссылку на текущий редактор/документ.
     */
    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;

        Object dialog = shell.getData();
        if (dialog == null || !DIALOG_CLASS_NAME.equals(dialog.getClass().getName()))
            return;

        Object existing = shell.getData(SESSION_KEY);
        if (existing instanceof Session session)
        {
            session.onShow();
            return;
        }

        Session session = new Session(shell, dialog);
        if (session.attach())
            shell.setData(SESSION_KEY, session);
    }

    /** Состояние подключения к одному экземпляру диалога (полей — на весь срок жизни shell). */
    private static final class Session
    {
        private final Shell shell;
        private final Object dialog;

        private Combo findField;
        private Label statusLabel;
        private Button selectAllButton;
        private Button caseCheckBox;
        private Button wholeWordCheckBox;
        private Button regExCheckBox;

        private ITextViewer viewer;

        private Job job;
        private volatile long generation;

        Session(Shell shell, Object dialog)
        {
            this.shell = shell;
            this.dialog = dialog;
        }

        boolean attach()
        {
            findField = (Combo)Global.getField(dialog, "fFindField"); //$NON-NLS-1$
            statusLabel = (Label)Global.getField(dialog, "fStatusLabel"); //$NON-NLS-1$
            selectAllButton = (Button)Global.getField(dialog, "fSelectAllButton"); //$NON-NLS-1$
            caseCheckBox = (Button)Global.getField(dialog, "fCaseCheckBox"); //$NON-NLS-1$
            wholeWordCheckBox = (Button)Global.getField(dialog, "fWholeWordCheckBox"); //$NON-NLS-1$
            regExCheckBox = (Button)Global.getField(dialog, "fIsRegExCheckBox"); //$NON-NLS-1$

            if (findField == null || findField.isDisposed()
                || statusLabel == null || statusLabel.isDisposed())
            {
                FindReplaceLiveCountDebug.problem("attach: fFindField/fStatusLabel не найдены"); //$NON-NLS-1$
                return false;
            }

            if (selectAllButton != null && !selectAllButton.isDisposed())
                selectAllButton.setText(SELECT_ALL_LABEL);

            resolveViewer();

            findField.addModifyListener(e -> onModify());
            addSettingsListener(caseCheckBox);
            addSettingsListener(wholeWordCheckBox);
            addSettingsListener(regExCheckBox);
            shell.addDisposeListener(e -> cancelJob());

            FindReplaceLiveCountDebug.log("attach OK"); //$NON-NLS-1$
            onModify();
            return true;
        }

        /** Диалог показан повторно (переиспользованный экземпляр) — обновить ссылку на редактор/документ. */
        void onShow()
        {
            cancelJob();
            generation++;
            resolveViewer();
            if (statusLabel != null && !statusLabel.isDisposed())
                statusLabel.setText(""); //$NON-NLS-1$
            onModify();
        }

        private void addSettingsListener(Button checkBox)
        {
            if (checkBox == null || checkBox.isDisposed())
                return;
            checkBox.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> onModify()));
        }

        /**
         * Viewer, по которому ищет диалог: сначала {@code fTarget} → enclosing {@link ITextViewer}
         * ({@code TextViewer$FindReplaceTarget.this$0}); для сравнения текстов — сфокусированная
         * панель merge-вьюера ({@code fFocusPart} / {@code focusViewer}); иначе fallback через
         * активный {@link ITextEditor}.
         */
        private void resolveViewer()
        {
            viewer = null;

            Object target = Global.getField(dialog, "fTarget"); //$NON-NLS-1$
            if (target != null)
                viewer = viewerFromFindTarget(target);
            if (viewer == null)
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                IEditorPart editorPart = page != null ? page.getActiveEditor() : null;
                ITextEditor textEditor = editorPart != null ? TextEditor.resolveTextEditor(editorPart) : null;
                viewer = textEditor != null ? TextEditor.getSourceViewer(textEditor) : null;
            }
            FindReplaceLiveCountDebug.log("resolveViewer: " //$NON-NLS-1$
                + (viewer != null ? viewer.getClass().getSimpleName() : "null")); //$NON-NLS-1$
        }

        /**
         * Обычный редактор: {@code this$0} сам {@link ITextViewer}. Сравнение текстов:
         * {@code this$0} — {@code TextMergeViewer} ({@code fFocusPart}) или
         * {@code ThreeSideTextMergeViewer} ({@code focusViewer}), оба держат
         * {@code MergeSourceViewer} сфокусированной панели.
         */
        private static ITextViewer viewerFromFindTarget(Object target)
        {
            Object enclosing = Global.getField(target, "this$0"); //$NON-NLS-1$
            if (enclosing instanceof ITextViewer textViewer)
                return textViewer;
            if (enclosing == null)
                return null;
            ITextViewer focused = MergeViewerReflection.extractSourceViewer(enclosing, "fFocusPart"); //$NON-NLS-1$
            if (focused != null)
                return focused;
            return MergeViewerReflection.extractSourceViewer(enclosing, "focusViewer"); //$NON-NLS-1$
        }

        private void onModify()
        {
            cancelJob();

            String findString = findField.getText();
            if (findString.length() < MIN_CHARS)
            {
                generation++;
                statusLabel.setText(""); //$NON-NLS-1$
                return;
            }

            statusLabel.setText("Поиск..."); //$NON-NLS-1$

            IDocument document = viewer != null ? viewer.getDocument() : null;
            String fullText;
            try
            {
                fullText = document != null ? document.get() : null;
            }
            catch (RuntimeException e)
            {
                fullText = null;
            }
            if (fullText == null)
            {
                statusLabel.setText(""); //$NON-NLS-1$
                return;
            }

            boolean caseSensitive = caseCheckBox != null && !caseCheckBox.isDisposed()
                && caseCheckBox.getSelection();
            boolean wholeWord = wholeWordCheckBox != null && !wholeWordCheckBox.isDisposed()
                && wholeWordCheckBox.getSelection();
            boolean regEx = regExCheckBox != null && !regExCheckBox.isDisposed()
                && regExCheckBox.getSelection();

            long myGeneration = ++generation;
            String text = fullText;
            job = new Job("Комфорт: подсчёт совпадений Find/Replace") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    return runSearch(text, findString, caseSensitive, wholeWord, regEx, myGeneration, monitor);
                }
            };
            job.setSystem(true);
            job.schedule(SEARCH_DELAY_MS);
        }

        private void cancelJob()
        {
            if (job != null)
                job.cancel();
        }

        private IStatus runSearch(String fullText, String findString, boolean caseSensitive,
            boolean wholeWord, boolean regEx, long myGeneration, IProgressMonitor monitor)
        {
            int count = 0;

            if (regEx)
            {
                Pattern pattern;
                try
                {
                    pattern = Pattern.compile(findString, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                }
                catch (PatternSyntaxException e)
                {
                    postResult(0, myGeneration);
                    return Status.OK_STATUS;
                }
                Matcher matcher = pattern.matcher(fullText);
                int from = 0;
                while (from <= fullText.length() && matcher.find(from))
                {
                    if (monitor.isCanceled() || generation != myGeneration)
                        return Status.CANCEL_STATUS;
                    count++;
                    from = matcher.end() > matcher.start() ? matcher.end() : matcher.end() + 1;
                    if (count > MAX_MATCHES)
                        break;
                }
            }
            else
            {
                int from = 0;
                while (true)
                {
                    if (monitor.isCanceled() || generation != myGeneration)
                        return Status.CANCEL_STATUS;
                    int idx = PlainTextSearch.indexOf(fullText, findString, from, caseSensitive, wholeWord);
                    if (idx < 0)
                        break;
                    count++;
                    from = idx + Math.max(findString.length(), 1);
                    if (count > MAX_MATCHES)
                        break;
                }
            }

            postResult(count, myGeneration);
            return Status.OK_STATUS;
        }

        private void postResult(int count, long myGeneration)
        {
            Display display = shell.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (shell.isDisposed() || generation != myGeneration)
                    return;
                statusLabel.setText(formatCount(count));
            });
        }

        private static String formatCount(int count)
        {
            if (count <= 0)
                return "Совпадений не найдено"; //$NON-NLS-1$
            return count + " " + matchWord(count) + " найдено"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String matchWord(int count)
        {
            int mod100 = count % 100;
            int mod10 = count % 10;
            if (mod100 >= 11 && mod100 <= 14)
                return "совпадений"; //$NON-NLS-1$
            if (mod10 == 1)
                return "совпадение"; //$NON-NLS-1$
            if (mod10 >= 2 && mod10 <= 4)
                return "совпадения"; //$NON-NLS-1$
            return "совпадений"; //$NON-NLS-1$
        }
    }

    /** Поиск по строке без затрагивания реального выделения в редакторе (в фоновом потоке). */
    private static final class PlainTextSearch
    {
        private PlainTextSearch() {}

        static int indexOf(String text, String search, int from, boolean caseSensitive, boolean wholeWord)
        {
            if (search.isEmpty() || from > text.length() - search.length())
                return -1;
            int end = text.length() - search.length();
            for (int i = Math.max(from, 0); i <= end; i++)
            {
                if (text.regionMatches(!caseSensitive, i, search, 0, search.length())
                    && (!wholeWord || isWordBoundary(text, i, i + search.length())))
                    return i;
            }
            return -1;
        }

        private static boolean isWordBoundary(String text, int matchStart, int matchEnd)
        {
            if (matchStart > 0 && IdentifierSelectionSupport.isIdentifierChar(text.charAt(matchStart - 1)))
                return false;
            if (matchEnd < text.length() && IdentifierSelectionSupport.isIdentifierChar(text.charAt(matchEnd)))
                return false;
            return true;
        }
    }

    /**
     * Диагностика живого счётчика Find/Replace.
     *
     * <p>Включение: Параметры → Комфорт → «Общее логирование».
     */
    private static final class FindReplaceLiveCountDebug
    {
        private FindReplaceLiveCountDebug() {}

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
