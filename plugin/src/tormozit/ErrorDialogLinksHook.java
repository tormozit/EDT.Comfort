package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/**
 * В окне ошибки платформы EDT ({@code RuntimeExecutionErrorDialog} и аналоги с
 * заголовком «Ошибка» / кнопкой «Сведения»), а также в jface {@link ErrorDialog}
 * и workbench {@code InternalDialog}, добавляет кнопку «Ссылки»: из текста ошибки
 * извлекаются пути объектов метаданных, затем открывается {@link MdObjectPickDialog}.
 */
public final class ErrorDialogLinksHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.errorDialogLinksPatched"; //$NON-NLS-1$
    private static final String SCHEDULED_KEY = "tormozit.errorDialogLinksScheduled"; //$NON-NLS-1$
    private static final String PAINT_HOOK_KEY = "tormozit.errorDialogLinksPaint"; //$NON-NLS-1$
    private static final int MAX_ASYNC_RETRIES = 8;
    private static final String LINKS_BUTTON_LABEL = "Ссылки"; //$NON-NLS-1$
    private static final String LINKS_BUTTON_TOOLTIP =
        "Показать ссылки на объекты метаданных из текста ошибки"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Ошибка"; //$NON-NLS-1$
    private static final String DETAILS_SNIPPET = "Сведения"; //$NON-NLS-1$
    private static final String INTERNAL_DIALOG =
        "org.eclipse.ui.internal.statushandlers.InternalDialog"; //$NON-NLS-1$
    private static final String RUNTIME_EXECUTION_ERROR_DIALOG =
        "com._1c.g5.v8.dt.platform.services.ui.runtimes.RuntimeExecutionErrorDialog"; //$NON-NLS-1$
    private static final String STATUS_DIALOG =
        "com._1c.g5.v8.dt.common.ui.dialogs.StatusDialog"; //$NON-NLS-1$

    private static final Set<String> NESTED_FOLDERS = Set.of(
        "Forms", "Templates", "Commands", "Recalculations", "Items", "Help"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static volatile String[] topFolders;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event -> requestPatch(display, event);
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    /**
     * Патч синхронно в фильтре Show/Activate — до первой отрисовки. Отложенный
     * {@code timerExec(50)×20} как раз давал вспышку «сначала без кнопки, через секунду с ней»:
     * Activate приходит на пустой shell в {@code createShell}, цикл ретраев тикает ~1 с,
     * а окно уже показано с «ОК»/«Сведения».
     */
    private static void requestPatch(Display display, Event event)
    {
        Shell shell = shellFromEvent(event);
        if (shell == null || shell.isDisposed())
            return;
        if (!isCandidateShell(shell) || alreadyPatched(shell))
            return;

        if (tryPatch(shell))
            return;
        scheduleAsyncRetry(display, shell, 0);
        armPaintRetry(shell);
    }

    private static Shell shellFromEvent(Event event)
    {
        if (event.widget instanceof Shell shell)
            return shell;
        if (event.widget instanceof Button button)
        {
            if (!isOkOrDetailsLabel(button.getText()))
                return null;
            return button.getShell();
        }
        return null;
    }

    private static boolean isCandidateShell(Shell shell)
    {
        if (isKnownErrorDialog(shell.getData()))
            return true;
        return DIALOG_TITLE.equals(shell.getText());
    }

    private static boolean alreadyPatched(Shell shell)
    {
        Button links = findDialogButtonByLabel(shell, LINKS_BUTTON_LABEL);
        if (links != null && !links.isDisposed())
        {
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }
        if (shell.getData(PATCHED_KEY) != null)
            shell.setData(PATCHED_KEY, null);
        return false;
    }

    private static void scheduleAsyncRetry(Display display, Shell shell, int attempt)
    {
        if (attempt >= MAX_ASYNC_RETRIES || shell.isDisposed() || alreadyPatched(shell))
            return;
        if (shell.getData(SCHEDULED_KEY) != null && attempt == 0)
            return;
        shell.setData(SCHEDULED_KEY, Boolean.TRUE);
        display.asyncExec(() ->
        {
            if (shell.isDisposed())
                return;
            shell.setData(SCHEDULED_KEY, null);
            if (tryPatch(shell))
                return;
            if (isCandidateShell(shell))
                scheduleAsyncRetry(display, shell, attempt + 1);
        });
    }

    private static void armPaintRetry(Shell shell)
    {
        if (shell.getData(PAINT_HOOK_KEY) != null)
            return;
        Listener[] holder = new Listener[1];
        holder[0] = event ->
        {
            shell.removeListener(SWT.Paint, holder[0]);
            shell.setData(PAINT_HOOK_KEY, null);
            tryPatch(shell);
        };
        shell.setData(PAINT_HOOK_KEY, Boolean.TRUE);
        shell.addListener(SWT.Paint, holder[0]);
    }

    private static boolean isKnownErrorDialog(Object data)
    {
        if (data instanceof ErrorDialog)
            return true;
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return INTERNAL_DIALOG.equals(name)
            || RUNTIME_EXECUTION_ERROR_DIALOG.equals(name)
            || STATUS_DIALOG.equals(name);
    }

    private static boolean isOkOrDetailsLabel(String text)
    {
        if (text == null || text.isEmpty())
            return false;
        String label = text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (label.contains(DETAILS_SNIPPET))
            return true;
        String ok = IDialogConstants.OK_LABEL;
        if (ok != null && label.equals(ok.replace("&", ""))) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        return "OK".equals(label) || "ОК".equals(label); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean tryPatch(Shell shell)
    {
        if (shell.isDisposed())
            return false;
        if (alreadyPatched(shell))
            return true;

        Button barButton = findOkButton(shell);
        if (barButton == null)
            return false;

        Composite buttonBar = barButton.getParent();
        if (buttonBar == null || buttonBar.isDisposed())
            return false;
        if (!(buttonBar.getLayout() instanceof GridLayout layout))
            return false;

        Button links = new Button(buttonBar, SWT.PUSH);
        links.setText(LINKS_BUTTON_LABEL);
        links.setToolTipText(LINKS_BUTTON_TOOLTIP + Global.pluginSignForTooltip());
        applyButtonLayoutData(links, barButton);
        // Справа от «Сведения» кнопка уезжает за край: панель HORIZONTAL_ALIGN_END
        // и уже упакована под две кнопки. Ставим «Ссылки» слева от OK.
        links.moveAbove(barButton);
        layout.numColumns++;

        Object dialog = shell.getData();
        links.addListener(SWT.Selection, e -> openLinks(shell, dialog));
        relayoutButtonBar(shell, buttonBar, links);
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        return true;
    }

    private static Button findOkButton(Shell shell)
    {
        Button button = findDialogButtonByLabel(shell, IDialogConstants.OK_LABEL);
        if (button != null)
            return button;
        button = findDialogButtonByLabel(shell, "OK"); //$NON-NLS-1$
        if (button != null)
            return button;
        return findDialogButtonByLabel(shell, "ОК"); //$NON-NLS-1$
    }

    private static void applyButtonLayoutData(Button button, Button reference)
    {
        GridData data = new GridData(GridData.HORIZONTAL_ALIGN_FILL);
        int widthHint = 0;
        if (reference != null && !reference.isDisposed()
                && reference.getLayoutData() instanceof GridData src)
            widthHint = src.widthHint;
        Point minSize = button.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        data.widthHint = Math.max(widthHint, minSize.x);
        button.setLayoutData(data);
    }

    private static Button findDialogButtonByLabel(Composite composite, String text)
    {
        if (text == null)
            return null;
        String want = text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
        for (Control child : composite.getChildren())
        {
            if (child instanceof Button button)
            {
                String label = button.getText();
                if (label != null && want.equals(label.replace("&", ""))) //$NON-NLS-1$ //$NON-NLS-2$
                    return button;
            }
            if (child instanceof Composite childComposite)
            {
                Button found = findDialogButtonByLabel(childComposite, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void openLinks(Shell shell, Object dialog)
    {
        if (shell.isDisposed())
            return;
        openLinksForText(shell, collectErrorText(shell, dialog));
    }

    /** То же для произвольного текста ошибки (окно {@link ErrorDescriptionWindow}). */
    static void openLinksForText(Shell shell, String errorText)
    {
        if (shell == null || shell.isDisposed())
            return;

        List<String> fullNames = extractFullNames(errorText);
        if (fullNames.isEmpty())
        {
            ToastNotification.show("Ссылки", "В тексте ошибки не найдены пути к объектам метаданных", 5000); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
            ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
            : null;
        IProject project = Global.getActiveProject(page, true);
        if (project == null)
            return;

        MdObjectPickDialog pick = MdObjectPickDialog.forMetadataNames(shell, fullNames);
        if (pick.open() != Window.OK)
            return;
        String chosen = pick.getSelectedFullName();
        if (chosen == null || chosen.isBlank())
            return;

        if (!GoToDefinition.openByFullName(chosen, shell, page, project))
        {
            ToastNotification.show("Ссылки", //$NON-NLS-1$
                "В проекте " + project.getName() + " не найден объект:\n" + chosen, 5000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String collectErrorText(Shell shell, Object dialog)
    {
        StringBuilder sb = new StringBuilder();

        if (dialog instanceof ErrorDialog)
        {
            Object message = Global.getField(dialog, "message"); //$NON-NLS-1$
            if (message instanceof String s && !s.isBlank())
                sb.append(s).append('\n');
            Object statusObj = Global.getField(dialog, "status"); //$NON-NLS-1$
            if (statusObj instanceof IStatus status)
                appendStatus(status, sb);
        }

        appendStatusAdapter(dialog, sb);
        appendWidgetTexts(shell, sb);
        return sb.toString();
    }

    private static void appendStatusAdapter(Object dialog, StringBuilder sb)
    {
        if (dialog == null)
            return;
        try
        {
            Object adapter = Global.invoke(dialog, "getCurrentStatusAdapter"); //$NON-NLS-1$
            if (adapter == null)
                return;
            Object statusObj = Global.invoke(adapter, "getStatus"); //$NON-NLS-1$
            if (statusObj instanceof IStatus status)
                appendStatus(status, sb);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void appendStatus(IStatus status, StringBuilder sb)
    {
        if (status == null)
            return;
        String msg = status.getMessage();
        if (msg != null && !msg.isBlank())
            sb.append(msg).append('\n');
        Throwable t = status.getException();
        while (t != null)
        {
            String em = t.getMessage();
            if (em != null && !em.isBlank())
                sb.append(em).append('\n');
            t = t.getCause();
        }
        IStatus[] children = status.getChildren();
        if (children != null)
        {
            for (IStatus child : children)
                appendStatus(child, sb);
        }
    }

    private static void appendWidgetTexts(Composite composite, StringBuilder sb)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof Label label)
            {
                String text = label.getText();
                if (text != null && !text.isBlank())
                    sb.append(text).append('\n');
            }
            else if (child instanceof StyledText styled)
            {
                String text = styled.getText();
                if (text != null && !text.isBlank())
                    sb.append(text).append('\n');
            }
            else if (child instanceof Text text)
            {
                String value = text.getText();
                if (value != null && !value.isBlank())
                    sb.append(value).append('\n');
            }
            else if (child instanceof org.eclipse.swt.widgets.List list)
            {
                for (String item : list.getItems())
                {
                    if (item != null && !item.isBlank())
                        sb.append(item).append('\n');
                }
            }
            else if (child instanceof Composite childComposite)
            {
                appendWidgetTexts(childComposite, sb);
            }
        }
    }

    static List<String> extractFullNames(String text)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (text == null || text.isBlank())
            return List.of();

        String normalized = text.replace('\\', '/');
        for (String folder : topFolders())
        {
            String marker = folder + "/"; //$NON-NLS-1$
            int from = 0;
            while (from < normalized.length())
            {
                int idx = normalized.indexOf(marker, from);
                if (idx < 0)
                    break;
                from = idx + 1;
                if (idx > 0 && isIdentChar(normalized.charAt(idx - 1)))
                    continue;
                String objectPath = extractObjectPath(normalized, idx, folder);
                if (objectPath == null)
                    continue;
                String fullName = GetRef.pathToFullName("src/" + objectPath); //$NON-NLS-1$
                if (fullName != null && !fullName.isBlank())
                    names.add(fullName);
            }
        }
        return new ArrayList<>(names);
    }

    private static String extractObjectPath(String normalized, int folderStart, String folder)
    {
        int i = folderStart + folder.length();
        if (i >= normalized.length() || normalized.charAt(i) != '/')
            return null;
        i++;

        int nameEnd = scanSegmentEnd(normalized, i);
        String objectName = segmentName(normalized, i, nameEnd);
        if (objectName == null)
            return null;
        i = nameEnd;

        StringBuilder path = new StringBuilder(folder).append('/').append(objectName);
        if (i < normalized.length() && normalized.charAt(i) == '/')
        {
            String nestedFolder = readSegment(normalized, i + 1);
            if (nestedFolder != null && NESTED_FOLDERS.contains(nestedFolder))
            {
                int afterFolder = i + 1 + nestedFolder.length();
                if (afterFolder < normalized.length() && normalized.charAt(afterFolder) == '/')
                {
                    int nestedFrom = afterFolder + 1;
                    String nestedName = segmentName(normalized, nestedFrom,
                        scanSegmentEnd(normalized, nestedFrom));
                    if (nestedName != null)
                        path.append('/').append(nestedFolder).append('/').append(nestedName);
                }
            }
        }
        return path.toString();
    }

    private static String readSegment(String text, int from)
    {
        return segmentName(text, from, scanSegmentEnd(text, from));
    }

    private static int scanSegmentEnd(String text, int from)
    {
        if (from >= text.length())
            return from;
        int end = from;
        while (end < text.length())
        {
            char c = text.charAt(end);
            if (c == '/' || Character.isWhitespace(c))
                break;
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '|' || c == '*')
                break;
            end++;
        }
        return end;
    }

    /**
     * Имя сегмента пути: у файла объекта выгрузки ({@code Имя.xml}) и EDT ({@code Имя.mdo})
     * расширение срезается, прочие имена с точкой ({@code Module.bsl}) отбрасываются.
     */
    private static String segmentName(String text, int from, int end)
    {
        if (end <= from)
            return null;
        String seg = stripObjectFileExt(trimTrailingPunct(text.substring(from, end)));
        if (seg.isEmpty() || "Ext".equals(seg) || seg.indexOf('.') >= 0) //$NON-NLS-1$
            return null;
        return seg;
    }

    private static String stripObjectFileExt(String seg)
    {
        int dot = seg.lastIndexOf('.');
        if (dot <= 0)
            return seg;
        String ext = seg.substring(dot);
        if (".xml".equalsIgnoreCase(ext) || ".mdo".equalsIgnoreCase(ext)) //$NON-NLS-1$ //$NON-NLS-2$
            return seg.substring(0, dot);
        return seg;
    }

    private static String trimTrailingPunct(String seg)
    {
        int end = seg.length();
        while (end > 0)
        {
            char c = seg.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']' || c == '}')
                end--;
            else
                break;
        }
        return end == seg.length() ? seg : seg.substring(0, end);
    }

    private static boolean isIdentChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Панель кнопок jface — {@code HORIZONTAL_ALIGN_END} без grab: после pack она
     * шириной ровно в две кнопки. {@code buttonBar.layout} не двигает родителя,
     * третья кнопка рисуется правее «Сведения» и обрезается краем окна.
     */
    private static void relayoutButtonBar(Shell shell, Composite buttonBar, Button links)
    {
        buttonBar.layout(true, true);
        Composite parent = buttonBar.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
        shell.layout(true, true);

        Point need = buttonBar.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        Point have = buttonBar.getSize();
        int extra = Math.max(0, need.x - have.x);
        if (extra == 0 && !links.isDisposed())
        {
            Point linksSize = links.getSize();
            extra = Math.max(0, links.getLocation().x + linksSize.x - have.x);
        }
        if (extra > 0)
        {
            Point shellSize = shell.getSize();
            shell.setSize(shellSize.x + extra, shellSize.y);
            shell.layout(true, true);
        }
    }

    private static String[] topFolders()
    {
        String[] cached = topFolders;
        if (cached != null)
            return cached;
        List<String> list = new ArrayList<>();
        for (String folder : MdTypeMapping.FOLDER_TO_RU.keySet())
        {
            if (!NESTED_FOLDERS.contains(folder))
                list.add(folder);
        }
        list.sort((a, b) -> Integer.compare(b.length(), a.length()));
        topFolders = list.toArray(String[]::new);
        return topFolders;
    }
}
