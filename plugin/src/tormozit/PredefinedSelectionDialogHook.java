package tormozit;

import java.util.IdentityHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Диалог «Выбор предопределенных данных»: полное имя объекта метаданных, сортировка по имени
 * и многословный фильтр с историей и подсветкой совпадений (#727).
 */
public class PredefinedSelectionDialogHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.predefinedSelectionPatched"; //$NON-NLS-1$
    private static final String TEMP_LOG_TOPIC = "predefined-selection-first-frame"; //$NON-NLS-1$
    private static final String DIALOG_TITLE =
            "Выбор предопределенных данных"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
            "com._1c.g5.v8.dt.internal.md.ui.controls.value.PredefinedSelectionDialog"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.isDisposed())
                return;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isPredefinedSelectionShell(shell))
                return;
            // Decorations.setVisible(): SWT.Show отправляется ДО Win32 ShowWindow.
            // Дерево и кнопки JFace уже созданы. Завершаем доработку синхронно:
            // timerExec/asyncExec здесь дали бы первый кадр со штатным порядком строк.
            Global.tempLog(TEMP_LOG_TOPIC, "before patch: shellVisible=" + shell.getVisible()); //$NON-NLS-1$
            boolean patched = tryPatch(shell);
            Global.tempLog(TEMP_LOG_TOPIC, "after patch: patched=" + patched //$NON-NLS-1$
                + ", shellVisible=" + shell.getVisible()); //$NON-NLS-1$
        };

        display.addFilter(SWT.Show, listener);
    }

    private static boolean isPredefinedSelectionShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && DIALOG_CLASS.equals(data.getClass().getName()))
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog == null)
            dialog = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName()))
            return false;

        Object input = Global.getField(dialog, "input"); //$NON-NLS-1$
        if (!(input instanceof EObject))
            return false;

        String fullName = GetRef.eObjectToFullName((EObject) input);
        if (fullName == null || fullName.isEmpty())
            return false;

        Global.invoke(dialog, "setMessage", fullName); //$NON-NLS-1$

        // Поле tree и одноколоночный GridLayout подтверждены байткодом PredefinedSelectionDialog.
        Object treeObject = Global.getField(dialog, "tree"); //$NON-NLS-1$
        if (!(treeObject instanceof TreeViewer viewer) || viewer.getTree().isDisposed()
                || !(viewer.getContentProvider() instanceof ITreeContentProvider content)
                || !(viewer.getLabelProvider() instanceof ILabelProvider labels))
            return false;

        Object selection = viewer.getStructuredSelection().getFirstElement();
        viewer.setComparator(new ViewerComparator()
        {
            @Override
            public int compare(Viewer source, Object left, Object right)
            {
                return getComparator().compare(labels.getText(left), labels.getText(right));
            }
        });
        if (selection != null)
            viewer.setSelection(new StructuredSelection(selection), true);
        Global.tempLog(TEMP_LOG_TOPIC, "sorted: shellVisible=" + shell.getVisible() //$NON-NLS-1$
            + ", roots=" + viewer.getTree().getItemCount()); //$NON-NLS-1$

        if (ComfortSettings.isReplaceListFiltersEnabled())
        {
            Session session = new Session(viewer, content, labels);
            session.install();
        }
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        return true;
    }

    private static final class Session
    {
        private final TreeViewer viewer;
        private final ITreeContentProvider content;
        private final ILabelProvider labels;
        private final Map<Object, Boolean> visible = new IdentityHashMap<>();
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        private FilterInputBox input;
        private Object rememberedSelection;
        private boolean applying;

        Session(TreeViewer viewer, ITreeContentProvider content, ILabelProvider labels)
        {
            this.viewer = viewer;
            this.content = content;
            this.labels = labels;
            rememberedSelection = viewer.getStructuredSelection().getFirstElement();
        }

        void install()
        {
            Composite parent = viewer.getTree().getParent();
            FilterInputBox.Options options = new FilterInputBox.Options();
            options.scope = FilterInputBox.Scope.PREDEFINED_SELECTION_DIALOG;
            input = FilterInputBox.create(parent, options, this::apply);
            input.widget().moveAbove(viewer.getTree());
            viewer.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new HighlightLabels(this)));
            viewer.addFilter(new ViewerFilter()
            {
                @Override
                public boolean select(Viewer source, Object parentElement, Object element)
                {
                    return accepts(element);
                }
            });
            viewer.addSelectionChangedListener(event -> {
                if (!applying && !event.getSelection().isEmpty())
                    rememberedSelection = viewer.getStructuredSelection().getFirstElement();
            });
            FilterInputBoxListNavigation.installTreeNavigation(input.inputControl(), viewer.getTree());
            parent.layout(true, true);
            input.scheduleFocusWhenReady();
        }

        private boolean accepts(Object element)
        {
            if (matcher.isEmpty)
                return true;
            Boolean cached = visible.get(element);
            if (cached != null)
                return cached;
            // Сохраняем путь к совпавшему потомку; каждый узел проверяется один раз на запрос.
            visible.put(element, Boolean.FALSE);
            boolean matches = matcher.matches(labels.getText(element));
            if (!matches)
            {
                Object[] children = content.getChildren(element);
                if (children != null)
                    for (Object child : children)
                        if (accepts(child))
                        {
                            matches = true;
                            break;
                        }
            }
            visible.put(element, matches);
            return matches;
        }

        private void apply()
        {
            if (viewer.getTree().isDisposed())
                return;
            Object current = viewer.getStructuredSelection().getFirstElement();
            if (current != null)
                rememberedSelection = current;
            matcher = new SmartMatcher(input.getText());
            visible.clear();
            applying = true;
            viewer.getTree().setRedraw(false);
            try
            {
                viewer.refresh();
                viewer.expandAll();
                if (rememberedSelection != null && accepts(rememberedSelection))
                    viewer.setSelection(new StructuredSelection(rememberedSelection), true);
                else
                    viewer.setSelection(StructuredSelection.EMPTY);
            }
            finally
            {
                viewer.getTree().setRedraw(true);
                applying = false;
            }
        }

        private static final class HighlightLabels extends LabelProvider implements IStyledLabelProvider
        {
            private final Session session;

            HighlightLabels(Session session)
            {
                this.session = session;
            }

            @Override
            public StyledString getStyledText(Object element)
            {
                String text = session.labels.getText(element);
                StyledString styled = new StyledString(text != null ? text : ""); //$NON-NLS-1$
                SmartMatchHighlight.applyRanges(styled, session.matcher.getHighlightRanges(styled.getString()),
                    session.viewer.getTree());
                return styled;
            }

            @Override
            public Image getImage(Object element)
            {
                return session.labels.getImage(element);
            }

            @Override
            public void dispose()
            {
                session.labels.dispose();
                super.dispose();
            }
        }
    }
}
