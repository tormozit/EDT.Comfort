package tormozit;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Штатный F12 и переход Комфорта по коду проверки в комментарии подавления. */
public final class BslCheckDefinitionHandler extends AbstractHandler
{
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*//\\s*@skip-check\\s+"); //$NON-NLS-1$
    private static final Pattern CODE = Pattern.compile("[^\\s,]+"); //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event)
    {
        tryOpen(event);
        return null;
    }

    static boolean tryOpen(ExecutionEvent event)
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        BslXtextEditor editor = GetRef.getActiveBslEditor(part);
        String code = codeAtSelection(editor, null);
        if (code == null)
            return false;
        IProject project = Global.getActiveProject(part, true);
        if (project == null)
            return true;
        Shell shell = HandlerUtil.getActiveShell(event);
        try
        {
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            Set<CheckUid> uids = repository != null ? repository.toUid(code, project) : Set.of();
            if (uids.size() == 1)
                ProblemViewHook.openCheckSettings(shell, project, repository.getShortUid(uids.iterator().next(), project));
            else
                ToastNotification.show("Перейти к определению", uids.isEmpty() //$NON-NLS-1$
                    ? "В проекте не найдена проверка «" + code + "»" //$NON-NLS-1$ //$NON-NLS-2$
                    : "Код «" + code + "» соответствует нескольким проверкам проекта", 5_000); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            ToastNotification.show("Перейти к определению", "Не удалось открыть проверку: " + e.getMessage(), 8_000); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return true;
    }

    /** Смещения ITextSelection относятся к документу, в том числе при свёрнутых строках. */
    private static String codeAtSelection(BslXtextEditor editor, ITextSelection selection)
    {
        if (editor == null)
            return null;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null || viewer.getDocument() == null || viewer.getSelectionProvider() == null)
            return null;
        if (selection == null)
        {
            if (!(viewer.getSelectionProvider().getSelection() instanceof ITextSelection textSelection))
                return null;
            selection = textSelection;
        }
        IDocument document = viewer.getDocument();
        try
        {
            IRegion line = document.getLineInformationOfOffset(selection.getOffset());
            return codeInLine(document.get(line.getOffset(), line.getLength()),
                selection.getOffset() - line.getOffset(), selection.getLength());
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    static String codeInLine(String line, int offset, int length)
    {
        Matcher codes = suppressionCodes(line);
        if (codes == null)
            return null;
        while (codes.find())
            if (offset >= codes.start() && offset <= codes.end() && length >= 0
                && offset + length <= codes.end())
                return codes.group();
        return null;
    }

    /** Общий разбор комментария для перехода к определению и поиска ссылок. */
    static Matcher suppressionCodes(String line)
    {
        Matcher directive = DIRECTIVE.matcher(line);
        if (!directive.find())
            return null;
        // Как BslSuppressionProvider EDT: коды разделены запятыми, пояснение начинается с « -».
        int explanation = line.indexOf(" -", directive.end()); //$NON-NLS-1$
        Matcher codes = CODE.matcher(line);
        codes.region(directive.end(), explanation >= 0 ? explanation : line.length());
        return codes;
    }

    /**
     * Условие зависит и от selection, и от activePart: смена панели должна пересчитать его,
     * даже если при возврате в модуль каретка остаётся на прежнем месте.
     * Часть приходит из контекста выражения; глобальная активная часть может ещё не обновиться.
     */
    public static final class SuppressionCodeTester extends PropertyTester
    {
        @Override
        public boolean test(Object receiver, String property, Object[] args, Object expectedValue)
        {
            IWorkbenchPart part = receiver instanceof IWorkbenchPart workbenchPart ? workbenchPart : null;
            BslXtextEditor editor = GetRef.getActiveBslEditor(part);
            String code = codeAtSelection(editor, null);
            return code != null;
        }
    }
}
