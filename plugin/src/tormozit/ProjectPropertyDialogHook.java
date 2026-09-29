package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.internal.dialogs.PropertyDialog;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/** Переключение проекта в штатном окне «Свойства проекта» с сохранением выбранной страницы. */
public final class ProjectPropertyDialogHook implements IStartup
{
    private static final String LOG_TOPIC = "projectPropertyDialog"; //$NON-NLS-1$
    private static final String PROJECT_SELECTOR_KEY =
        "tormozit.comfort.projectPropertyDialog.selector"; //$NON-NLS-1$
    private static final String PENDING_KEY =
        "tormozit.comfort.projectPropertyDialog.pending"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        Global.tempLog(LOG_TOPIC, "earlyStartup display=" + display); //$NON-NLS-1$
        if (display != null && !display.isDisposed())
            display.asyncExec(() ->
            {
                Listener listener = event ->
                {
                    if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                        return;
                    PreferenceDialog found = findPreferenceDialog(shell);
                    Global.tempLog(LOG_TOPIC, "shell event=" + event.type + " title=" //$NON-NLS-1$ //$NON-NLS-2$
                        + shell.getText() + " data=" + typeName(shell.getData()) //$NON-NLS-1$
                        + " dialog=" + typeName(found)); //$NON-NLS-1$
                    if (!(found instanceof PropertyDialog dialog))
                        return;
                    Shell propertyShell = dialog.getShell();
                    if (propertyShell == null || propertyShell.isDisposed()
                            || propertyShell.getData(PENDING_KEY) != null)
                        return;
                    propertyShell.setData(PENDING_KEY, Boolean.TRUE);
                    scheduleInstall(display, dialog, propertyShell, 0);
                };
                display.addFilter(SWT.Show, listener);
                display.addFilter(SWT.Activate, listener);
                Global.tempLog(LOG_TOPIC, "filters installed"); //$NON-NLS-1$
            });
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        for (Shell current = shell; current != null && !current.isDisposed();
                current = current.getParent() instanceof Shell parent ? parent : null)
            if (current.getData() instanceof PreferenceDialog dialog)
                return dialog;
        return null;
    }

    private static String typeName(Object value)
    {
        return value == null ? "null" : value.getClass().getName(); //$NON-NLS-1$
    }

    private static void scheduleInstall(Display display, PropertyDialog dialog, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        Global.tempLog(LOG_TOPIC, "install attempt=" + attempt + " title=" + shell.getText()); //$NON-NLS-1$ //$NON-NLS-2$
        if (install(dialog, shell) || attempt >= 30)
            return;
        display.timerExec(100, () -> scheduleInstall(display, dialog, shell, attempt + 1));
    }

    private static boolean install(PropertyDialog dialog, Shell shell)
    {
        if (shell.getData(PROJECT_SELECTOR_KEY) != null)
            return true;

        if (!(dialog.getSelection() instanceof IStructuredSelection selection))
        {
            Global.tempLog(LOG_TOPIC, "not a project selection: " //$NON-NLS-1$
                + typeName(dialog.getSelection()));
            return true;
        }
        Object selected = selection.getFirstElement();
        IProject current = projectFromSelection(selected);
        Global.tempLog(LOG_TOPIC, "selection first=" + typeName(selected) //$NON-NLS-1$
            + " inner=" + typeName(selected instanceof IStructuredSelection nested //$NON-NLS-1$
                ? nested.getFirstElement() : selected)
            + " project=" + (current != null ? current.getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        if (current == null)
            return true;

        List<IProject> projects = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen())
                projects.add(project);
        Global.tempLog(LOG_TOPIC, "project=" + current.getName() //$NON-NLS-1$
            + " openProjects=" + projects.size()); //$NON-NLS-1$
        if (projects.size() < 2)
            return true;
        projects.sort(Comparator.comparing(IProject::getName, String.CASE_INSENSITIVE_ORDER));

        TreeViewer tree = dialog.getTreeViewer();
        if (tree == null || tree.getControl() == null)
        {
            Global.tempLog(LOG_TOPIC, "tree not ready"); //$NON-NLS-1$
            return false;
        }
        FilteredTree filteredTree = findFilteredTree(tree.getControl());
        if (filteredTree == null)
        {
            Global.tempLog(LOG_TOPIC, "filtered tree not found: " //$NON-NLS-1$
                + typeName(tree.getControl().getParent()));
            return false;
        }
        Composite leftArea = filteredTree.getParent();
        if (!(leftArea.getLayout() instanceof GridLayout))
        {
            Global.tempLog(LOG_TOPIC, "unexpected left layout: " //$NON-NLS-1$
                + typeName(leftArea.getLayout()));
            return false;
        }
        ((GridLayout) leftArea.getLayout()).verticalSpacing = 6;

        Composite row = new Composite(leftArea, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        row.setLayout(layout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        row.moveAbove(filteredTree);

        Label label = new Label(row, SWT.NONE);
        label.setText("Проект:"); //$NON-NLS-1$
        Combo combo = new Combo(row, SWT.READ_ONLY);
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        int currentIndex = -1;
        for (int i = 0; i < projects.size(); i++)
        {
            IProject project = projects.get(i);
            combo.add(project.getName());
            if (project.equals(current))
                currentIndex = i;
        }
        if (currentIndex < 0)
        {
            Global.tempLog(LOG_TOPIC, "current project missing from open projects"); //$NON-NLS-1$
            row.dispose();
            return true;
        }
        combo.select(currentIndex);
        final int originalIndex = currentIndex;
        Control[] lastPageFocus = { null };
        Listener focusTracker = event ->
        {
            if (event.widget instanceof Control focused
                    && dialog.getSelectedPage() instanceof IPreferencePage page
                    && isDescendant(focused, page.getControl()))
                lastPageFocus[0] = focused;
        };
        shell.getDisplay().addFilter(SWT.FocusIn, focusTracker);
        shell.addDisposeListener(event -> shell.getDisplay().removeFilter(SWT.FocusIn, focusTracker));
        Control focused = shell.getDisplay().getFocusControl();
        if (dialog.getSelectedPage() instanceof IPreferencePage page
                && isDescendant(focused, page.getControl()))
            lastPageFocus[0] = focused;
        combo.addListener(SWT.Selection, event ->
        {
            int selectedIndex = combo.getSelectionIndex();
            if (selectedIndex < 0 || selectedIndex == originalIndex)
                return;
            combo.setEnabled(false);
            IProject target = projects.get(selectedIndex);
            shell.getDisplay().asyncExec(() -> switchProject(dialog, shell, combo, originalIndex,
                target, lastPageFocus[0]));
        });
        leftArea.layout(true, true);
        shell.setData(PROJECT_SELECTOR_KEY, Boolean.TRUE);
        Global.tempLog(LOG_TOPIC, "selector installed current=" + current.getName()); //$NON-NLS-1$
        return true;
    }

    private static IProject projectFromSelection(Object selected)
    {
        if (selected == null)
            return null;
        if (selected instanceof IStructuredSelection nested)
            return nested.size() == 1 ? projectFromSelection(nested.getFirstElement()) : null;
        if (selected instanceof IProject project)
            return project;
        if (selected instanceof IDtProject dtProject)
            return dtProject.getWorkspaceProject();
        return Adapters.adapt(selected, IProject.class);
    }

    private static Object targetElement(PropertyDialog dialog, IProject target)
    {
        Object first = ((IStructuredSelection) dialog.getSelection()).getFirstElement();
        Object original = first instanceof IStructuredSelection nested ? nested.getFirstElement() : first;
        Object replacement = original instanceof IDtProject
            ? Global.getDtProjectFromWorkspaceProject(target) : target;
        if (replacement == null)
            return null;
        return first instanceof IStructuredSelection ? new StructuredSelection(replacement) : replacement;
    }

    private static FilteredTree findFilteredTree(Control control)
    {
        for (Control parent = control; parent != null; parent = parent.getParent())
            if (parent instanceof FilteredTree filteredTree)
                return filteredTree;
        return null;
    }

    private static void switchProject(PropertyDialog dialog, Shell shell, Combo combo,
            int originalIndex, IProject target, Control lastPageFocus)
    {
        if (shell.isDisposed())
            return;
        Object replacement = targetElement(dialog, target);
        if (replacement == null)
        {
            Global.tempLog(LOG_TOPIC, "target element unavailable: " + target.getName()); //$NON-NLS-1$
            combo.select(originalIndex);
            combo.setEnabled(true);
            return;
        }
        String pageId = currentPageId(dialog);
        PageState pageState = PageState.capture(dialog, lastPageFocus);
        Shell owner = shell.getParent() instanceof Shell parent ? parent : null;
        if (!Global.invokeVoid(dialog, "okPressed") || !shell.isDisposed() //$NON-NLS-1$
                || dialog.getReturnCode() != Window.OK)
        {
            if (!combo.isDisposed())
            {
                combo.select(originalIndex);
                combo.setEnabled(true);
            }
            return;
        }
        if (owner == null || owner.isDisposed())
            return;
        PreferenceDialog next = PreferencesUtil.createPropertyDialogOn(owner, replacement,
            pageId, null, null, PreferencesUtil.OPTION_NONE);
        if (next != null)
        {
            next.getShell().getDisplay().asyncExec(() -> pageState.restoreWhenReady(next, 0));
            next.open();
        }
    }

    private static String currentPageId(PreferenceDialog dialog)
    {
        TreeViewer tree = dialog.getTreeViewer();
        if (tree != null && tree.getSelection() instanceof IStructuredSelection selection
                && selection.getFirstElement() instanceof IPreferenceNode node)
            return node.getId();
        return null;
    }

    private static boolean isDescendant(Control control, Control ancestor)
    {
        if (control == null || ancestor == null)
            return false;
        for (Control candidate = control; candidate != null; candidate = candidate.getParent())
            if (candidate == ancestor)
                return true;
        return false;
    }

    /** Позиция внутри страницы, фокус и выделенная строка; данные объектов разных проектов не смешиваются. */
    private static final class PageState
    {
        private final int[] focusPath;
        private final int[] listPath;
        private final Class<?> focusType;
        private final Class<?> listType;
        private final String[] treeRowPath;
        private final String rowText;
        private final int rowIndex;

        private PageState(int[] focusPath, int[] listPath, Class<?> focusType,
                Class<?> listType, String[] treeRowPath, String rowText, int rowIndex)
        {
            this.focusPath = focusPath;
            this.listPath = listPath;
            this.focusType = focusType;
            this.listType = listType;
            this.treeRowPath = treeRowPath;
            this.rowText = rowText;
            this.rowIndex = rowIndex;
        }

        static PageState capture(PreferenceDialog dialog, Control lastFocus)
        {
            if (!(dialog.getSelectedPage() instanceof IPreferencePage page)
                    || page.getControl() == null)
                return new PageState(null, null, null, null, null, null, -1);
            Control root = page.getControl();
            int[] focusPath = path(root, lastFocus);
            Control list = hasSelection(lastFocus) ? lastFocus : findSelectedList(root);
            int[] listPath = path(root, list);
            Class<?> focusType = lastFocus != null ? lastFocus.getClass() : null;
            Class<?> listType = list != null ? list.getClass() : null;
            if (list instanceof Tree tree && tree.getSelectionCount() > 0)
            {
                TreeItem item = tree.getSelection()[0];
                java.util.List<String> names = new ArrayList<>();
                for (TreeItem current = item; current != null; current = current.getParentItem())
                    names.add(0, current.getText());
                return new PageState(focusPath, listPath, focusType, listType,
                    names.toArray(String[]::new), null, -1);
            }
            if (list instanceof Table table && table.getSelectionCount() > 0)
            {
                TableItem item = table.getSelection()[0];
                return new PageState(focusPath, listPath, focusType, listType,
                    null, tableRowText(item), table.indexOf(item));
            }
            if (list instanceof org.eclipse.swt.widgets.List swtList && swtList.getSelectionCount() > 0)
            {
                int index = swtList.getSelectionIndex();
                return new PageState(focusPath, listPath, focusType, listType,
                    null, swtList.getItem(index), index);
            }
            return new PageState(focusPath, null, focusType, null, null, null, -1);
        }

        void restoreWhenReady(PreferenceDialog dialog, int attempt)
        {
            if (dialog.getShell() == null || dialog.getShell().isDisposed()
                    || !(dialog.getSelectedPage() instanceof IPreferencePage page)
                    || page.getControl() == null)
                return;
            Control root = page.getControl();
            Control list = resolve(root, listPath);
            if (listPath != null && (list == null || list.getClass() != listType
                    || !list.isVisible()))
            {
                retry(dialog, attempt);
                return;
            }
            if (list instanceof Tree tree && treeRowPath != null)
            {
                TreeItem selected = findTreeRow(tree, treeRowPath);
                if (selected == null && attempt < 30)
                {
                    retry(dialog, attempt);
                    return;
                }
                if (selected != null)
                {
                    tree.setSelection(selected);
                    tree.showItem(selected);
                    Event event = new Event();
                    event.item = selected;
                    tree.notifyListeners(SWT.Selection, event);
                }
            }
            else if (list instanceof Table table && rowText != null)
            {
                if (table.getItemCount() == 0 && attempt < 30)
                {
                    retry(dialog, attempt);
                    return;
                }
                TableItem selected = null;
                if (rowIndex >= 0 && rowIndex < table.getItemCount()
                        && rowText.equals(tableRowText(table.getItem(rowIndex))))
                    selected = table.getItem(rowIndex);
                for (TableItem item : table.getItems())
                {
                    if (selected != null)
                        break;
                    if (rowText.equals(tableRowText(item)))
                    {
                        selected = item;
                        break;
                    }
                }
                if (selected == null && rowIndex >= 0 && rowIndex < table.getItemCount())
                    selected = table.getItem(rowIndex);
                if (selected != null)
                {
                    table.setSelection(selected);
                    table.showItem(selected);
                    Event event = new Event();
                    event.item = selected;
                    table.notifyListeners(SWT.Selection, event);
                }
            }
            else if (list instanceof org.eclipse.swt.widgets.List swtList && rowText != null)
            {
                int index = swtList.indexOf(rowText);
                if (index < 0 && swtList.getItemCount() == 0 && attempt < 30)
                {
                    retry(dialog, attempt);
                    return;
                }
                if (index < 0 && rowIndex >= 0 && rowIndex < swtList.getItemCount())
                    index = rowIndex;
                if (index >= 0)
                {
                    swtList.select(index);
                    swtList.showSelection();
                    swtList.notifyListeners(SWT.Selection, new Event());
                }
            }
            Control focus = resolve(root, focusPath);
            if (focus != null && focus.getClass() == focusType
                    && focus.isVisible() && focus.isEnabled())
                focus.setFocus();
            else if (list != null && list.isVisible())
                list.setFocus();
        }

        private void retry(PreferenceDialog dialog, int attempt)
        {
            if (attempt < 30)
                dialog.getShell().getDisplay().timerExec(100,
                    () -> restoreWhenReady(dialog, attempt + 1));
        }

        private static String tableRowText(TableItem item)
        {
            StringBuilder result = new StringBuilder();
            int columns = Math.max(1, item.getParent().getColumnCount());
            for (int column = 0; column < columns; column++)
                result.append('\t').append(item.getText(column));
            return result.toString();
        }

        private static TreeItem findTreeRow(Tree tree, String[] names)
        {
            TreeItem[] siblings = tree.getItems();
            TreeItem matched = null;
            for (int depth = 0; depth < names.length; depth++)
            {
                matched = null;
                for (TreeItem item : siblings)
                    if (names[depth].equals(item.getText()))
                    {
                        matched = item;
                        break;
                    }
                if (matched == null)
                    return null;
                if (depth + 1 < names.length)
                {
                    if (!matched.getExpanded())
                    {
                        matched.setExpanded(true);
                        Event event = new Event();
                        event.item = matched;
                        tree.notifyListeners(SWT.Expand, event);
                    }
                    siblings = matched.getItems();
                }
            }
            return matched;
        }

        private static Control findSelectedList(Control root)
        {
            if (root == null || root.isDisposed() || !root.isVisible())
                return null;
            if (hasSelection(root))
                return root;
            if (root instanceof Composite composite)
                for (Control child : composite.getChildren())
                {
                    Control found = findSelectedList(child);
                    if (found != null)
                        return found;
                }
            return null;
        }

        private static boolean hasSelection(Control control)
        {
            return control instanceof Tree tree && tree.getSelectionCount() > 0
                || control instanceof Table table && table.getSelectionCount() > 0
                || control instanceof org.eclipse.swt.widgets.List list && list.getSelectionCount() > 0;
        }

        private static int[] path(Control root, Control control)
        {
            if (!isDescendant(control, root))
                return null;
            java.util.List<Integer> indices = new ArrayList<>();
            for (Control current = control; current != root; current = current.getParent())
            {
                Composite parent = current.getParent();
                Control[] children = parent.getChildren();
                for (int index = 0; index < children.length; index++)
                    if (children[index] == current)
                    {
                        indices.add(0, index);
                        break;
                    }
            }
            return indices.stream().mapToInt(Integer::intValue).toArray();
        }

        private static Control resolve(Control root, int[] path)
        {
            if (path == null || root == null || root.isDisposed())
                return null;
            Control current = root;
            for (int index : path)
            {
                if (!(current instanceof Composite parent) || index < 0
                        || index >= parent.getChildren().length)
                    return null;
                current = parent.getChildren()[index];
            }
            return current;
        }
    }
}
