package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.internal.dialogs.PropertyDialog;
import org.eclipse.ui.internal.dialogs.WorkbenchPreferenceDialog;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/** Раскрытие разделов и переключение проекта в штатном окне параметров с сохранением выбранной страницы. */
public final class ProjectPropertyDialogHook implements IStartup
{
    private static final String PROJECT_SELECTOR_KEY =
        "tormozit.comfort.projectPropertyDialog.selector"; //$NON-NLS-1$
    private static final String TREE_EXPANDED_KEY =
        "tormozit.comfort.projectPropertyDialog.treeExpanded"; //$NON-NLS-1$
    private static final String PENDING_KEY =
        "tormozit.comfort.projectPropertyDialog.pending"; //$NON-NLS-1$
    private static final String ALL_PROJECTS = "Все"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() ->
            {
                Listener listener = event ->
                {
                    if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                        return;
                    PreferenceDialog dialog = findPreferenceDialog(shell);
                    if (!(dialog instanceof PropertyDialog) && !(dialog instanceof WorkbenchPreferenceDialog))
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

    private static void scheduleInstall(Display display, PreferenceDialog dialog, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (install(dialog, shell) || attempt >= 30)
            return;
        display.timerExec(100, () -> scheduleInstall(display, dialog, shell, attempt + 1));
    }

    /**
     * Строка «Проект:» над деревом страниц. В окне свойств проекта выбран проект, в окне «Параметры» —
     * «Все» (рабочая область); выбор другого элемента открывает ту же страницу в другом окне.
     */
    private static boolean install(PreferenceDialog dialog, Shell shell)
    {
        if (shell.getData(PROJECT_SELECTOR_KEY) != null)
            return true;

        IProject current = null;
        if (dialog instanceof PropertyDialog propertyDialog)
        {
            if (!(propertyDialog.getSelection() instanceof IStructuredSelection selection))
                return true;
            current = projectFromSelection(selection.getFirstElement());
            if (current == null)
                return true;
        }

        TreeViewer tree = dialog.getTreeViewer();
        if (tree == null || tree.getControl() == null || tree.getControl().isDisposed())
            return false;
        if (current != null && shell.getData(TREE_EXPANDED_KEY) == null)
        {
            // Уровень 1 — невидимый корень viewer, уровень 2 — корневые разделы страниц.
            tree.expandToLevel(2);
            shell.setData(TREE_EXPANDED_KEY, Boolean.TRUE);
        }

        List<IProject> projects = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen() && Global.getDtProjectFromWorkspaceProject(project) != null)
                projects.add(project);
        if (projects.isEmpty())
            return true;
        projects.sort(Comparator.comparing(IProject::getName, String.CASE_INSENSITIVE_ORDER));
        // Элемент 0 — «Все» (рабочая область).
        projects.add(0, null);

        FilteredTree filteredTree = findFilteredTree(tree.getControl());
        if (filteredTree == null)
            return false;
        Composite leftArea = filteredTree.getParent();
        if (!(leftArea.getLayout() instanceof GridLayout))
            return false;
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
        combo.setToolTipText(TooltipText.wrap(combo,
            "«Все» — настройки рабочей области (окно «Параметры»), проект — его свойства. Выбор открывает " //$NON-NLS-1$
            + "ту же страницу в другом окне; изменения текущего окна при этом применяются." //$NON-NLS-1$
            + Global.pluginSignForTooltip()));
        int currentIndex = -1;
        for (int i = 0; i < projects.size(); i++)
        {
            IProject project = projects.get(i);
            combo.add(project == null ? ALL_PROJECTS : project.getName());
            if (Objects.equals(project, current))
                currentIndex = i;
        }
        if (currentIndex < 0)
        {
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
        if (current == null)
            ProjectOverrideNote.install(dialog, combo, projects);
        leftArea.layout(true, true);
        shell.setData(PROJECT_SELECTOR_KEY, Boolean.TRUE);
        return true;
    }

    /**
     * Надпись в строке кнопок страницы окна «Параметры» (слева от штатных кнопок) для страниц, у
     * которых свойства проекта не дополняют настройки рабочей области, а замещают их копией: у каких
     * проектов копия уже есть, в подсказке — когда она появляется. Новые такие страницы — записью в
     * {@link #PAGES}.
     */
    private static final class ProjectOverrideNote
    {
        private static final String NOTE_KEY = "tormozit.comfort.projectOverrideNote"; //$NON-NLS-1$

        /**
         * @param preferencePageId страница окна «Параметры»
         * @param qualifier узел настроек
         * @param markerKey ключ узла проекта, означающий «у проекта своя копия настроек»
         * @param explanation когда и что копируется
         */
        private record Page(String preferencePageId, String qualifier, String markerKey, String explanation) {}

        private static final List<Page> PAGES = List.of(
            // BslPropertyPage.getPreferenceManager(), com.e1c.g5.v8.dt.formatter.bsl.ui 1.0.1400.v202605050943
            new Page("com.e1c.g5.v8.dt.formatter.ui.preferences.bslformatterpage", //$NON-NLS-1$
                "com.e1c.g5.v8.dt.formatter.bsl", "projectSpecificSettingsInited", //$NON-NLS-1$ //$NON-NLS-2$
                "При первом открытии этой страницы в свойствах проекта в проект копируются все настройки, " //$NON-NLS-1$
                + "сохранённые здесь. После этого их изменения здесь на проект не действуют, а настройки, " //$NON-NLS-1$
                + "которые здесь ещё не сохранялись, проект по-прежнему берёт отсюда.")); //$NON-NLS-1$

        static void install(PreferenceDialog dialog, Combo combo, List<IProject> projects)
        {
            Runnable update = () ->
            {
                Page page = pageOf(dialog);
                if (page != null && dialog.getSelectedPage() instanceof IPreferencePage preferencePage)
                    addNote(preferencePage.getControl(), page, combo, projects);
            };
            dialog.addPageChangedListener(event -> update.run());
            update.run();
        }

        /** Надпись — первым элементом строки кнопок страницы ({@code PreferencePage.createControl}). */
        private static void addNote(Control pageControl, Page page, Combo combo, List<IProject> projects)
        {
            if (!(pageControl instanceof Composite content) || content.isDisposed()
                    || content.getData(NOTE_KEY) != null)
                return;
            Composite buttonBar = null;
            for (Control child : content.getChildren())
                if (child instanceof Composite composite && composite.getLayout() instanceof GridLayout
                        && Arrays.stream(composite.getChildren()).anyMatch(Button.class::isInstance))
                    buttonBar = composite;
            if (buttonBar == null)
                return;
            content.setData(NOTE_KEY, Boolean.TRUE);
            Link note = new Link(buttonBar, SWT.NONE);
            List<String> own = projectsWithCopy(page, projects);
            note.setText("Своя копия у проектов: " + (own.isEmpty() ? "нет" : String.join(", ", own))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // Есть проекты, на которые эта страница не действует, — заметно.
            if (!own.isEmpty())
                note.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
            note.setToolTipText(TooltipText.wrap(note, page.explanation() + Global.pluginSignForTooltip()));
            GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            // Только свободное место строки: иначе ширина надписи входит в предпочтительную ширину
            // страницы, и окно «Параметры» расширяется при перетаскивании разделителя.
            noteData.widthHint = 0;
            note.setLayoutData(noteData);
            note.moveAbove(buttonBar.getChildren()[0]);
            note.addListener(SWT.Selection, event ->
            {
                int index = combo.indexOf(event.text);
                if (index > 0 && combo.isEnabled())
                {
                    combo.select(index);
                    combo.notifyListeners(SWT.Selection, new Event());
                }
            });
            ((GridLayout) buttonBar.getLayout()).numColumns++;
            // Строка кнопок штатно прижата вправо по своей ширине — надписи нужна вся строка.
            if (buttonBar.getLayoutData() instanceof GridData barData)
            {
                barData.horizontalAlignment = SWT.FILL;
                barData.grabExcessHorizontalSpace = true;
            }
            content.layout(true, true);
        }

        private static Page pageOf(PreferenceDialog dialog)
        {
            String id = currentPageId(dialog);
            for (Page page : PAGES)
                if (page.preferencePageId().equals(id))
                    return page;
            return null;
        }

        /** Ссылки на проекты с копией настроек страницы. */
        private static List<String> projectsWithCopy(Page page, List<IProject> projects)
        {
            List<String> own = new ArrayList<>();
            for (IProject project : projects)
                if (project != null && new ProjectScope(project).getNode(page.qualifier()).getBoolean(page.markerKey(), false))
                    own.add("<a href=\"" + project.getName() + "\">" + project.getName().replace("&", "&&") + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return own;
        }
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

    /** @param target проект или {@code null} — «Все» (окно «Параметры») */
    private static void switchProject(PreferenceDialog dialog, Shell shell, Combo combo,
            int originalIndex, IProject target, Control lastPageFocus)
    {
        if (shell.isDisposed())
            return;
        Object replacement = target == null ? null
            : dialog instanceof PropertyDialog propertyDialog ? targetElement(propertyDialog, target) : target;
        if (target != null && replacement == null)
        {
            combo.select(originalIndex);
            combo.setEnabled(true);
            return;
        }
        // Страницы параметров и свойств проекта — разные узлы с разными id, общая у них только подпись.
        boolean sameKind = target != null && dialog instanceof PropertyDialog;
        String pageId = currentPageId(dialog);
        List<String> labelPath = labelPath(dialog);
        PageState pageState = PageState.capture(dialog, lastPageFocus);
        pageState.tabs = PageState.captureTabs(dialog);
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
        // Id страницы — до создания окна, как штатно: PreferencesUtil сразу вызывает create(), а выбор
        // страницы в уже созданном окне сдвигает дерево страниц по горизонтали.
        String nextPageId = sameKind ? pageId : null;
        if (!sameKind && target == null)
            nextPageId = findByLabelPath(PlatformUI.getWorkbench().getPreferenceManager(), labelPath);
        else if (!sameKind)
        {
            // Узлы свойств проекта известны только окну свойств — пробное окно не открывается.
            PreferenceDialog probe = PreferencesUtil.createPropertyDialogOn(owner, replacement, null, null, null,
                PreferencesUtil.OPTION_NONE);
            if (probe != null)
            {
                nextPageId = findByLabelPath(probe.getPreferenceManager(), labelPath);
                probe.close();
            }
        }
        PreferenceDialog next = target == null
            ? PreferencesUtil.createPreferenceDialogOn(owner, nextPageId, null, null)
            : PreferencesUtil.createPropertyDialogOn(owner, replacement, nextPageId, null, null,
                PreferencesUtil.OPTION_NONE);
        if (next != null)
        {
            next.getShell().getDisplay().asyncExec(() -> pageState.restoreWhenReady(next, 0));
            next.open();
        }
    }

    /** Подписи узлов от корня до выбранной страницы. */
    private static List<String> labelPath(PreferenceDialog dialog)
    {
        List<String> path = new ArrayList<>();
        TreeViewer tree = dialog.getTreeViewer();
        if (tree == null || tree.getControl().isDisposed() || !(tree.getControl() instanceof Tree swtTree)
                || swtTree.getSelectionCount() == 0)
            return path;
        for (TreeItem item = swtTree.getSelection()[0]; item != null; item = item.getParentItem())
            path.add(0, item.getText());
        return path;
    }

    /** Id узла с теми же подписями; если такого нет — самого глубокого совпавшего предка. */
    private static String findByLabelPath(PreferenceManager manager, List<String> path)
    {
        if (manager == null)
            return null;
        IPreferenceNode[] level = manager.getRootSubNodes();
        String found = null;
        for (String label : path)
        {
            IPreferenceNode matched = null;
            for (IPreferenceNode node : level)
                if (label.equals(node.getLabelText()))
                {
                    matched = node;
                    break;
                }
            if (matched == null)
                break;
            found = matched.getId();
            level = matched.getSubNodes();
        }
        return found;
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
        /** Выбранные вкладки страницы: путь к {@code TabFolder} и в последнем элементе — индекс вкладки. */
        List<int[]> tabs = List.of();

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
            if (!restoreTabs(root) && attempt < 30)
            {
                retry(dialog, attempt);
                return;
            }
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

        static List<int[]> captureTabs(PreferenceDialog dialog)
        {
            List<int[]> result = new ArrayList<>();
            if (dialog.getSelectedPage() instanceof IPreferencePage page && page.getControl() != null)
                collectTabs(page.getControl(), page.getControl(), result);
            return result;
        }

        private static void collectTabs(Control root, Control control, List<int[]> result)
        {
            if (control == null || control.isDisposed())
                return;
            if (control instanceof TabFolder folder && folder.getSelectionIndex() >= 0)
            {
                int[] path = path(root, folder);
                int[] entry = Arrays.copyOf(path, path.length + 1);
                entry[path.length] = folder.getSelectionIndex();
                result.add(entry);
            }
            if (control instanceof Composite composite)
                for (Control child : composite.getChildren())
                    collectTabs(root, child, result);
        }

        /** @return {@code false}, если вкладки ещё нет (вкладку Комфорта страница получает позже) */
        private boolean restoreTabs(Control root)
        {
            boolean complete = true;
            for (int[] entry : tabs)
            {
                int index = entry[entry.length - 1];
                if (!(resolve(root, Arrays.copyOf(entry, entry.length - 1)) instanceof TabFolder folder)
                        || index >= folder.getItemCount())
                {
                    complete = false;
                    continue;
                }
                if (folder.getSelectionIndex() != index)
                {
                    folder.setSelection(index);
                    Event event = new Event();
                    event.item = folder.getItem(index);
                    folder.notifyListeners(SWT.Selection, event);
                }
            }
            return complete;
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
