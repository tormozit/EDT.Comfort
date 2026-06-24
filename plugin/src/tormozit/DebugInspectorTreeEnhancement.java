package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.text.IFindReplaceTarget;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.debug.core.model.IValueModification;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.texteditor.FindReplaceAction;
import org.osgi.framework.FrameworkUtil;

/**
 * Улучшения дерева инспектора отладки: выбор строки по клику в любой колонке,
 * подсветка активной ячейки, копирование и поиск по всем колонкам.
 */
final class DebugInspectorTreeEnhancement
{
    private static final String ENHANCED_KEY = "tormozit.debugInspectorTreeEnhanced"; //$NON-NLS-1$
    private static final String COPY_HOOKED_KEY = "tormozit.debugInspectorCopyHooked"; //$NON-NLS-1$
    private static final String FIND_TEXT_KEY = "tormozit.debugInspectorFindText"; //$NON-NLS-1$
    private static final String DETAIL_FIND_TEXT_KEY = "tormozit.debugInspectorDetailFindText"; //$NON-NLS-1$
    private static final String DETAIL_FIND_OFFSET_KEY = "tormozit.debugInspectorDetailFindOffset"; //$NON-NLS-1$
    private static final String COPY_ACTION_SUFFIX = ".VirtualCopyToClipboardAction"; //$NON-NLS-1$
    private static final String COLUMN_MARKER_RU = "Фактический тип"; //$NON-NLS-1$
    private static final String COLUMN_MARKER_EN = "Actual type"; //$NON-NLS-1$
    private static final String CLASS_STANDALONE_INSPECTOR_DIALOG =
        "com._1c.g5.v8.dt.internal.debug.ui.hover.DebugElementDialog"; //$NON-NLS-1$
    private static final String CLASS_INSPECT_POPUP =
        "com._1c.g5.v8.dt.internal.debug.ui.dialogs.PendingAwareInspectPopupDialog"; //$NON-NLS-1$
    private static final String EDITOR_CANCEL_LISTENER_SUFFIX = ".DebugElementDialog$6"; //$NON-NLS-1$

    private final Tree tree;
    private final Object viewer;
    private final Object dialog;

    private TreeItem selectedItem;
    private int activeColumn;
    private String findText = ""; //$NON-NLS-1$
    private int findGeneration;
    private int pendingFocusGeneration;
    private String pendingPropertyName;
    private long pendingFocusStartedAt;
    private long pendingFocusSessionStart;
    private Object viewerUpdateListener;

    private static final int[] PENDING_FOCUS_DELAYS_MS =
        { 0, 50, 100, 200, 400, 800, 1500, 2500, 4000 };

    private Listener eraseItemListener;
    private Listener paintItemListener;
    private Listener focusListener;
    private Listener selectionListener;
    private Listener keyFilter;
    private Listener treeKeyListener;
    private Listener menuDetectListener;
    private Listener doubleClickListener;
    private Listener changeValueSelectionFilter;
    private Listener changeValueInspectorDisposeListener;
    private FindDialogDeactivateGuard changeValueModalGuard;
    private int changeValueGuardGeneration;
    private MouseAdapter mouseListener;
    private Color ownedRowBg;
    private Color ownedActiveCellBg;

    private DebugInspectorTreeEnhancement(Tree tree, Object viewer, Object dialog)
    {
        this.tree = tree;
        this.viewer = viewer;
        this.dialog = dialog;
    }

    boolean isAttached()
    {
        return tree != null && !tree.isDisposed() && tree.getData(ENHANCED_KEY) == this;
    }

    static DebugInspectorTreeEnhancement install(Object dialog, Shell shell)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return null;
        Tree tree = resolveInspectorTree(dialog, shell);
        if (tree == null || tree.isDisposed())
        {
            DebugInspectorDebug.step("tree", "no tree dialog=" + DebugInspectorDebug.cn(dialog)); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        if (tree.getData(ENHANCED_KEY) != null)
            return (DebugInspectorTreeEnhancement) tree.getData(ENHANCED_KEY);

        Object viewer = resolveViewerForDialog(dialog);
        DebugInspectorTreeEnhancement enhancement = new DebugInspectorTreeEnhancement(tree, viewer, dialog);
        if (!enhancement.installHooks())
        {
            DebugInspectorDebug.step("tree", "hooks failed columns=" + tree.getColumnCount()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        enhancement.scheduleCopyActionHook();
        tree.setData(ENHANCED_KEY, enhancement);
        tree.addDisposeListener(e -> enhancement.dispose());
        DebugInspectorDebug.step("tree", "OK columns=" + tree.getColumnCount() //$NON-NLS-1$ //$NON-NLS-2$
            + " dialog=" + DebugInspectorDebug.cn(dialog));
        return enhancement;
    }

    static void schedulePendingFocusForShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Tree inspectorTree = findInspectorTreeOnShell(shell);
        if (inspectorTree == null || inspectorTree.isDisposed())
            return;
        Object data = inspectorTree.getData(ENHANCED_KEY);
        if (data instanceof DebugInspectorTreeEnhancement enhancement)
            enhancement.schedulePendingPropertyFocus();
    }

    static Tree findInspectorTreeOnShell(Shell shell)
    {
        return findTreeWithInspectorColumns(shell);
    }

    static Object viewerForTree(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        Object data = tree.getData(ENHANCED_KEY);
        if (data instanceof DebugInspectorTreeEnhancement enhancement)
            return enhancement.viewer;
        Shell shell = tree.getShell();
        if (shell == null || shell.isDisposed())
            return null;
        return resolveViewer(shell.getData());
    }

    private static Tree resolveInspectorTree(Object dialog, Shell shell)
    {
        Object host = unwrapInspectorDialogHost(dialog);
        if (host != null)
        {
            Object treeObj = Global.invoke(host, "getTree"); //$NON-NLS-1$
            if (treeObj instanceof Tree tree && !tree.isDisposed())
                return tree;
            treeObj = Global.getField(host, "tree"); //$NON-NLS-1$
            if (treeObj instanceof Tree tree && !tree.isDisposed())
                return tree;
        }
        return findInspectorDebugTree(shell);
    }

    static Tree findInspectorDebugTree(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        return findTreeInControl(root);
    }

    static void hookInspectorPrefixTree(Shell shell, Object dialog)
    {
        Tree tree = resolveInspectorTree(dialog, shell);
        Object viewer = resolveViewerForDialog(dialog);
        if (viewer instanceof Viewer treeViewer && tree != null)
            DebugVariablePresentationHook.hookInspectorTreeViewer(treeViewer, tree);
    }

    static Object unwrapInspectorDialogHost(Object dialog)
    {
        if (dialog == null)
            return null;
        String name = dialog.getClass().getName();
        if (name.contains("ExpressionInformationControl") //$NON-NLS-1$
            && !name.endsWith("DebugExpressionInformationControl")) //$NON-NLS-1$
        {
            Object inner = Global.getField(dialog, "debugElementDialog"); //$NON-NLS-1$
            if (inner != null)
                return inner;
            Object got = Global.invoke(dialog, "getDebugElementDialog"); //$NON-NLS-1$
            if (got != null)
                return got;
        }
        return dialog;
    }

    static Object resolveViewerForDialog(Object dialog)
    {
        return resolveViewer(unwrapInspectorDialogHost(dialog));
    }

    private static Object resolveViewer(Object dialog)
    {
        if (dialog == null)
            return null;
        Object viewer = Global.getField(dialog, "viewer"); //$NON-NLS-1$
        if (viewer != null)
            return viewer;
        return Global.invoke(dialog, "getViewer"); //$NON-NLS-1$
    }

    private static Tree findTreeWithInspectorColumns(Shell shell)
    {
        return findInspectorDebugTree(shell);
    }

    private static Tree findTreeInControl(org.eclipse.swt.widgets.Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Tree tree && treeHasInspectorColumns(tree))
            return tree;
        if (root instanceof org.eclipse.swt.widgets.Composite composite)
        {
            for (org.eclipse.swt.widgets.Control child : composite.getChildren())
            {
                Tree found = findTreeInControl(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean treeHasInspectorColumns(Tree tree)
    {
        org.eclipse.swt.widgets.TreeColumn[] columns = tree.getColumns();
        if (columns == null || columns.length < 2)
            return false;
        boolean hasValue = false;
        boolean hasName = false;
        boolean hasType = false;
        for (org.eclipse.swt.widgets.TreeColumn column : columns)
        {
            if (column == null || column.isDisposed())
                continue;
            String text = column.getText();
            if (text == null)
                continue;
            String header = text.trim();
            if ("Имя".equalsIgnoreCase(header) || "Name".equalsIgnoreCase(header)) //$NON-NLS-1$ //$NON-NLS-2$
                hasName = true;
            if ("Значение".equalsIgnoreCase(header) || "Value".equalsIgnoreCase(header)) //$NON-NLS-1$ //$NON-NLS-2$
                hasValue = true;
            if (header.contains(COLUMN_MARKER_RU) || header.contains(COLUMN_MARKER_EN)
                || header.startsWith("Фактический")) //$NON-NLS-1$
                hasType = true;
        }
        return hasValue && (hasName || hasType || columns.length >= 2);
    }

    private boolean installHooks()
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return false;
        if (tree.isDisposed())
            return false;

        ListSelectionThemeColors.markOptOut(tree);

        syncFromTreeSelection();

        doubleClickListener = this::onTreeDoubleClick;
        tree.addListener(SWT.MouseDoubleClick, doubleClickListener);

        mouseListener = new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1)
                    return;
                TreeItem item = itemAt(tree, e.x, e.y);
                if (item == null)
                    return;
                int column = columnAt(tree, e.x, e.y, item);
                if (column < 0)
                    column = 0;
                selectCell(item, column);
            }
        };
        tree.addMouseListener(mouseListener);

        eraseItemListener = this::onEraseItem;
        tree.addListener(SWT.EraseItem, eraseItemListener);

        paintItemListener = this::onPaintItem;
        tree.addListener(SWT.PaintItem, paintItemListener);

        focusListener = e ->
        {
            invalidateHighlightColor();
            tree.redraw();
        };
        tree.addListener(SWT.FocusIn, focusListener);
        tree.addListener(SWT.FocusOut, focusListener);

        selectionListener = e ->
        {
            syncFromTreeSelection();
            invalidateHighlightColor();
            tree.redraw();
        };
        tree.addListener(SWT.Selection, selectionListener);

        keyFilter = this::onKeyFilter;
        tree.getDisplay().addFilter(SWT.KeyDown, keyFilter);

        treeKeyListener = this::onTreeKeyDown;
        tree.addListener(SWT.KeyDown, treeKeyListener);

        DebugInspectorCollectionMenuHook.install(tree, viewer);

        if (viewer instanceof Viewer treeViewer)
            DebugVariablePresentationHook.hookInspectorTreeViewer(treeViewer, tree);

        menuDetectListener = this::onMenuDetect;
        tree.addListener(SWT.MenuDetect, menuDetectListener);

        installChangeValueModalGuard();

        return true;
    }

    private void scheduleCopyActionHook()
    {
        Display display = tree.getDisplay();
        for (int delay : new int[] { 0, 50, 150, 400, 800, 1500 })
        {
            display.timerExec(delay, () ->
            {
                if (!tree.isDisposed())
                    hookGlobalCopyAction();
            });
        }
    }

    private void hookGlobalCopyAction()
    {
        if (dialog == null || Boolean.TRUE.equals(tree.getData(COPY_HOOKED_KEY)))
            return;

        Object globalActions = Global.getField(dialog, "globalActions"); //$NON-NLS-1$
        if (!(globalActions instanceof List<?> actions))
            return;

        for (int i = 0; i < actions.size(); i++)
        {
            Object item = actions.get(i);
            if (!(item instanceof IAction original))
                continue;
            String className = item.getClass().getName();
            if (!className.endsWith(COPY_ACTION_SUFFIX))
                continue;

            IAction replacement = new Action()
            {
                {
                    setText(original.getText());
                    setToolTipText(original.getToolTipText());
                    setImageDescriptor(original.getImageDescriptor());
                    setAccelerator(original.getAccelerator());
                    setActionDefinitionId(original.getActionDefinitionId());
                }

                @Override
                public void run()
                {
                    copyActiveCellToClipboard();
                }
            };
            @SuppressWarnings("unchecked")
            List<IAction> mutable = new ArrayList<>((List<IAction>) globalActions);
            mutable.set(i, replacement);
            Global.setField(dialog, "globalActions", mutable); //$NON-NLS-1$
            tree.setData(COPY_HOOKED_KEY, Boolean.TRUE);
            DebugInspectorDebug.log("copy action hooked"); //$NON-NLS-1$
            return;
        }
    }

    private void onEraseItem(Event e)
    {
        if (!(e.item instanceof TreeItem item))
            return;
        TreeItem row = currentSelectedRow();
        if (row == null || item != row)
            return;

        Color rowBg = rowSelectionBackground();
        Color bg = e.index == activeColumn ? activeCellBackground(rowBg) : rowBg;
        e.gc.setBackground(bg);
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.BACKGROUND;
    }

    private TreeItem currentSelectedRow()
    {
        TreeItem[] selection = tree.getSelection();
        if (selection.length > 0)
            return selection[0];
        return selectedItem;
    }

    private Color rowSelectionBackground()
    {
        if (ownedRowBg != null && !ownedRowBg.isDisposed())
            return ownedRowBg;
        if (ListSelectionThemeColors.isDarkList(tree))
        {
            ownedRowBg = ListSelectionThemeColors.listSelectionBackground(tree, tree.isFocusControl());
            return ownedRowBg;
        }
        Display display = tree.getDisplay();
        Color base = tree.getBackground();
        if (base == null || base.isDisposed())
            base = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        double factor = tree.isFocusControl() ? 0.12 : 0.08;
        ownedRowBg = slightlyDarker(base, factor);
        return ownedRowBg;
    }

    private Color activeCellBackground(Color rowBg)
    {
        if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
            return ownedActiveCellBg;
        if (ListSelectionThemeColors.isDarkList(tree))
        {
            ownedActiveCellBg = ListSelectionThemeColors.activeCellBackground(tree, rowBg);
            return ownedActiveCellBg;
        }
        ownedActiveCellBg = slightlyDarker(rowBg, tree.isFocusControl() ? 0.08 : 0.06);
        return ownedActiveCellBg;
    }

    private static Color slightlyDarker(Color base, double factor)
    {
        Device device = base.getDevice();
        RGB rgb = base.getRGB();
        int r = clampChannel((int) (rgb.red * (1.0 - factor)));
        int g = clampChannel((int) (rgb.green * (1.0 - factor)));
        int b = clampChannel((int) (rgb.blue * (1.0 - factor)));
        return new Color(device, r, g, b);
    }

    private static int clampChannel(int value)
    {
        return Math.max(0, Math.min(255, value));
    }

    private void invalidateHighlightColor()
    {
        if (ownedRowBg != null && !ownedRowBg.isDisposed())
            ownedRowBg.dispose();
        if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
            ownedActiveCellBg.dispose();
        ownedRowBg = null;
        ownedActiveCellBg = null;
    }

    private void onPaintItem(Event e)
    {
        if (!(e.item instanceof TreeItem item) || item != currentSelectedRow() || e.index != activeColumn)
            return;
        Rectangle bounds = item.getBounds(e.index);
        if (bounds == null || bounds.isEmpty())
            return;
        Color rowBg = rowSelectionBackground();
        Color base = activeCellBackground(rowBg);
        Color frame = slightlyDarker(base, 0.12);
        try
        {
            e.gc.setForeground(frame);
            e.gc.drawRectangle(bounds.x, bounds.y, Math.max(0, bounds.width - 1), Math.max(0, bounds.height - 1));
        }
        finally
        {
            if (!frame.isDisposed())
                frame.dispose();
        }
    }

    private void onTreeKeyDown(Event e)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (handleCopyKey(e))
            e.doit = false;
        if (e.keyCode == SWT.F3 && e.doit)
        {
            e.doit = false;
            findNext((e.stateMask & SWT.SHIFT) == 0);
        }
    }

    private void onKeyFilter(Event e)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (tree.isDisposed() || !tree.isVisible() || !isInspectorKeyContext())
            return;
        if (isFocusInInspectorNonTreeText())
        {
            if ((e.stateMask & SWT.CTRL) != 0 && (e.keyCode == 'f' || e.keyCode == 'F'))
            {
                if (openDetailPaneFindReplace())
                {
                    e.doit = false;
                    return;
                }
            }
            if (e.keyCode == SWT.F3)
            {
                e.doit = false;
                findNextInDetailPane((e.stateMask & SWT.SHIFT) == 0);
                return;
            }
            if (isComfortTreeListKey(e))
                return;
        }
        if (handleCopyKey(e))
        {
            e.doit = false;
            return;
        }
        if ((e.stateMask & SWT.CTRL) != 0 && (e.keyCode == 'f' || e.keyCode == 'F'))
        {
            e.doit = false;
            promptFind();
            return;
        }
        if (e.keyCode == SWT.F3)
        {
            e.doit = false;
            findNext((e.stateMask & SWT.SHIFT) == 0);
        }
        if (e.keyCode == SWT.F2 && (e.stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4)) == 0)
        {
            e.doit = false;
            DebugInspectorCollectionMenuHook.tryOpenCollectionFromTree(tree, viewer);
        }
    }

    private boolean isInspectorKeyContext()
    {
        if (tree.isDisposed() || !tree.isVisible())
            return false;
        Shell inspector = tree.getShell();
        if (inspector == null || inspector.isDisposed())
            return false;
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return false;

        Shell focusShell = resolveFocusShell(display);
        if (focusShell != null)
            return isKeyContextForShell(focusShell, inspector);

        Shell active = display.getActiveShell();
        if (active != null && !active.isDisposed())
            return isKeyContextForShell(active, inspector);
        return false;
    }

    private static Shell resolveFocusShell(Display display)
    {
        Control focus = display.getFocusControl();
        if (focus == null || focus.isDisposed())
            return null;
        Shell focusShell = focus.getShell();
        if (focusShell == null || focusShell.isDisposed())
            return null;
        return focusShell;
    }

    /** Focus/active shell belongs to this inspector, not a sibling popup or nested dialog. */
    private static boolean isKeyContextForShell(Shell contextShell, Shell inspector)
    {
        if (contextShell == inspector)
            return true;
        if (isNestedInspectorShell(contextShell, inspector))
            return false;
        if (DebugInspectorHook.isInspectorShell(contextShell))
            return false;
        return false;
    }

    /** Detail / expression {@link Text} or {@link StyledText} in inspector shell — not the tree. */
    private boolean isFocusInInspectorNonTreeText()
    {
        if (tree == null || tree.isDisposed())
            return false;
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return false;
        Control focus = display.getFocusControl();
        if (focus == null || focus.isDisposed())
            return false;
        if (focus == tree || isDescendantOf(focus, tree))
            return false;
        if (!(focus instanceof StyledText) && !(focus instanceof Text))
            return false;
        Shell inspector = tree.getShell();
        if (inspector == null || inspector.isDisposed())
            return false;
        return isDescendantOf(focus, inspector);
    }

    private static boolean isDescendantOf(Control control, Control ancestor)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (c == ancestor)
                return true;
        }
        return false;
    }

    private static boolean isComfortTreeListKey(Event e)
    {
        if ((e.stateMask & SWT.CTRL) != 0
            && (e.keyCode == 'f' || e.keyCode == 'F' || e.keyCode == 'c' || e.keyCode == 'C'))
            return true;
        return e.keyCode == SWT.F2
            && (e.stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4)) == 0;
    }

    /** Штатный «Найти/Заменить» для detail pane (как в «Выражениях»). */
    private boolean openDetailPaneFindReplace()
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return false;
        Control focus = display.getFocusControl();
        if (focus == null || focus.isDisposed())
            return false;
        IFindReplaceTarget target = resolveDetailPaneFindTarget(focus);
        if (target == null)
        {
            DebugInspectorDebug.step("detailFind", "no IFindReplaceTarget focus=" + focus.getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        Shell shell = focus.getShell();
        if (shell == null || shell.isDisposed())
            return false;
        ResourceBundle bundle = ResourceBundle.getBundle("org.eclipse.ui.texteditor.EditorMessages"); //$NON-NLS-1$
        new FindReplaceAction(bundle, "FindReplace_", shell, target).run(); //$NON-NLS-1$
        tree.setData(DETAIL_FIND_OFFSET_KEY, null);
        String selection = target.getSelectionText();
        if (selection != null && !selection.isBlank())
            writeDetailFindText(selection);
        DebugInspectorDebug.step("detailFind", "FindReplaceAction.run"); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    private void findNextInDetailPane(boolean forward)
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Control focus = display.getFocusControl();
        IFindReplaceTarget target = resolveDetailPaneFindTarget(focus);
        if (target == null || !target.canPerformFind())
            return;
        String needle = resolveDetailFindNeedle(target);
        if (needle.isBlank())
            return;
        writeDetailFindText(needle);
        DetailFindOptions options = readDetailFindOptions();
        int searchStart = resolveDetailSearchStart(target, forward);
        int foundOffset = target.findAndSelect(
            searchStart, needle, !forward, options.caseSensitive, options.wholeWord);
        if (foundOffset < 0)
        {
            int wrapStart = forward ? 0 : 0;
            foundOffset = target.findAndSelect(
                wrapStart, needle, !forward, options.caseSensitive, options.wholeWord);
        }
        if (foundOffset >= 0)
            tree.setData(DETAIL_FIND_OFFSET_KEY, forward ? foundOffset + needle.length() : foundOffset);
        else
            tree.setData(DETAIL_FIND_OFFSET_KEY, null);
        revealDetailFindSelection(focus);
    }

    private int resolveDetailSearchStart(IFindReplaceTarget target, boolean forward)
    {
        Object stored = tree.getData(DETAIL_FIND_OFFSET_KEY);
        if (stored instanceof Integer offset && offset >= 0)
            return offset;
        Point sel = target.getSelection();
        if (sel == null)
            return 0;
        return forward ? sel.x + sel.y : sel.x;
    }

    private static void revealDetailFindSelection(Control focus)
    {
        if (focus instanceof StyledText styledText && !styledText.isDisposed())
        {
            styledText.showSelection();
            styledText.setFocus();
        }
    }

    private String resolveDetailFindNeedle(IFindReplaceTarget target)
    {
        String stored = readDetailFindText(target);
        if (!stored.isBlank())
            return stored;
        String fromDialog = readFindReplaceDialogNeedle();
        if (!fromDialog.isBlank())
            return fromDialog;
        return ""; //$NON-NLS-1$
    }

    private static final String FIND_REPLACE_DIALOG_SETTINGS =
        "org.eclipse.ui.texteditor.FindReplaceDialog"; //$NON-NLS-1$

    private static String readFindReplaceDialogNeedle()
    {
        try
        {
            IDialogSettings root = PlatformUI.getDialogSettingsProvider(
                FrameworkUtil.getBundle(FindReplaceAction.class)).getDialogSettings();
            IDialogSettings section = root.getSection(FIND_REPLACE_DIALOG_SETTINGS);
            if (section == null)
                return ""; //$NON-NLS-1$
            String selection = section.get("selection"); //$NON-NLS-1$
            if (selection != null && !selection.isBlank())
                return selection.trim();
            String[] history = section.getArray("findhistory"); //$NON-NLS-1$
            if (history != null)
            {
                for (String entry : history)
                {
                    if (entry != null && !entry.isBlank())
                        return entry.trim();
                }
            }
        }
        catch (Exception ignored)
        {
            // fallback
        }
        return ""; //$NON-NLS-1$
    }

    private static DetailFindOptions readDetailFindOptions()
    {
        boolean caseSensitive = false;
        boolean wholeWord = false;
        try
        {
            IDialogSettings root = PlatformUI.getDialogSettingsProvider(
                FrameworkUtil.getBundle(FindReplaceAction.class)).getDialogSettings();
            IDialogSettings section = root.getSection(FIND_REPLACE_DIALOG_SETTINGS);
            if (section != null)
            {
                caseSensitive = section.getBoolean("casesensitive"); //$NON-NLS-1$
                wholeWord = section.getBoolean("wholeword"); //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
            // defaults
        }
        return new DetailFindOptions(caseSensitive, wholeWord);
    }

    private record DetailFindOptions(boolean caseSensitive, boolean wholeWord) {}

    private String readDetailFindText(IFindReplaceTarget target)
    {
        Object stored = tree.getData(DETAIL_FIND_TEXT_KEY);
        if (stored instanceof String s && !s.isBlank())
            return s;
        if (target != null)
        {
            String sel = target.getSelectionText();
            if (sel != null && !sel.isBlank())
                return sel;
        }
        return ""; //$NON-NLS-1$
    }

    private void writeDetailFindText(String text)
    {
        if (text == null || text.isBlank())
            return;
        tree.setData(DETAIL_FIND_TEXT_KEY, text);
    }

    /** {@link DebugElementDialog} внутри {@code ExpressionInformationControl}. */
    private Object resolveElementDialogHost()
    {
        if (dialog == null)
            return null;
        if (isElementDialogHost(dialog))
            return dialog;
        Object inner = Global.getField(dialog, "debugElementDialog"); //$NON-NLS-1$
        if (isElementDialogDeactivateHost(inner))
            return inner;
        inner = Global.invoke(dialog, "getDebugElementDialog"); //$NON-NLS-1$
        if (isElementDialogDeactivateHost(inner))
            return inner;
        return null;
    }

    private static boolean isElementDialogHost(Object host)
    {
        if (host == null)
            return false;
        for (Class<?> c = host.getClass(); c != null; c = c.getSuperclass())
        {
            String name = c.getName();
            if (CLASS_INSPECT_POPUP.equals(name) || CLASS_STANDALONE_INSPECTOR_DIALOG.equals(name))
                return true;
        }
        return false;
    }

    private static boolean isElementDialogDeactivateHost(Object host)
    {
        return isElementDialogHost(host) || hasListenToDeactivateField(host);
    }

    private static boolean hasListenToDeactivateField(Object host)
    {
        if (host == null)
            return false;
        for (Class<?> c = host.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                c.getDeclaredField("listenToDeactivate"); //$NON-NLS-1$
                return true;
            }
            catch (NoSuchFieldException ignored)
            {
                // next
            }
        }
        return false;
    }

    private boolean isExpressionInformationControl()
    {
        return dialog != null && dialog.getClass().getName().contains("ExpressionInformationControl"); //$NON-NLS-1$
    }

    private boolean shouldKeepDeactivateOff()
    {
        return isPopupInspectorDialog() || isExpressionInformationControl() || isIndependentElementDialog();
    }

    private boolean needsPinSuspendForFind()
    {
        if (isExpressionInformationControl())
            return true;
        // Standalone F9: InputDialog на workbench — pin не трогаем (без мигания при закрытии).
        if (isIndependentElementDialog())
            return false;
        return isPopupInspectorDialog();
    }

    /**
     * {@link SourceViewer} detail pane не адаптируется из цепочки {@link StyledText} —
     * берём через {@code dialog.detailPane} (DefaultDetailPane в EDT).
     */
    private IFindReplaceTarget resolveDetailPaneFindTarget(Control focus)
    {
        IFindReplaceTarget fromDialog = findTargetFromInspectorDetailPane(focus);
        if (fromDialog != null)
            return fromDialog;
        ISourceViewer viewer = resolveSourceViewerFromHierarchy(focus);
        if (viewer instanceof SourceViewer sourceViewer)
            return sourceViewer.getFindReplaceTarget();
        return null;
    }

    private IFindReplaceTarget findTargetFromInspectorDetailPane(Control focus)
    {
        if (focus == null || focus.isDisposed())
            return null;
        Object host = resolveElementDialogHost();
        if (host == null)
            host = dialog;
        if (host == null)
            return null;
        Object detailPaneProxy = Global.getField(host, "detailPane"); //$NON-NLS-1$
        if (detailPaneProxy == null)
            return null;
        Object detailControl = Global.invoke(detailPaneProxy, "getCurrentControl"); //$NON-NLS-1$
        if (detailControl instanceof Control dc
            && focus != dc
            && !isDescendantOf(focus, dc))
            return null;
        Object currentPane = Global.getField(detailPaneProxy, "fCurrentPane"); //$NON-NLS-1$
        if (currentPane == null)
            return null;
        Object adapted = Global.invoke(currentPane, "getAdapter", IFindReplaceTarget.class); //$NON-NLS-1$
        if (adapted instanceof IFindReplaceTarget target)
            return target;
        Object sourceViewer = Global.getField(currentPane, "fSourceViewer"); //$NON-NLS-1$
        if (sourceViewer instanceof SourceViewer sv)
        {
            StyledText widget = sv.getTextWidget();
            if (widget != null && !widget.isDisposed()
                && (focus == widget || isDescendantOf(focus, widget)))
                return sv.getFindReplaceTarget();
        }
        return null;
    }

    private static ISourceViewer resolveSourceViewerFromHierarchy(Control focus)
    {
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            ISourceViewer adapted = Adapters.adapt(c, ISourceViewer.class);
            if (adapted != null)
                return adapted;
            for (String method : new String[] {
                "getViewer", "getSourceViewer", "getTextViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object viewerObj = Global.invoke(c, method);
                if (viewerObj instanceof ISourceViewer sourceViewer)
                    return sourceViewer;
            }
        }
        return null;
    }

    /** Modal/child shell opened over the inspector (Set Value, InputDialog, …). */
    private static boolean isNestedInspectorShell(Shell shell, Shell inspector)
    {
        for (Shell s = shell; s != null; s = parentShell(s))
        {
            if (s == inspector)
                return true;
        }
        return false;
    }

    private static Shell parentShell(Shell shell)
    {
        Control parent = shell.getParent();
        if (parent instanceof Shell sh)
            return sh;
        return parent != null ? parent.getShell() : null;
    }

    private boolean handleCopyKey(Event e)
    {
        if ((e.stateMask & SWT.CTRL) == 0 || (e.keyCode != 'c' && e.keyCode != 'C'))
            return false;
        copyActiveCellToClipboard();
        return true;
    }

    private void selectCell(TreeItem item, int column)
    {
        selectedItem = item;
        activeColumn = column;
        invalidateHighlightColor();
        tree.setSelection(item);
        applyViewerSelection(item);
        fireSelectionEvent(item);
        tree.redraw();
    }

    private void onMenuDetect(Event e)
    {
        if (e.widget != tree || tree.isDisposed())
            return;
        Point loc = tree.toControl(e.x, e.y);
        TreeItem item = itemAt(tree, loc.x, loc.y);
        if (item == null)
            return;
        int column = columnAt(tree, loc.x, loc.y, item);
        if (column < 0)
            column = 0;
        selectCell(item, column);
    }

    private void onTreeDoubleClick(Event e)
    {
        if (e.button != 1 || tree.isDisposed())
            return;

        TreeItem item = itemAt(tree, e.x, e.y);
        if (item == null)
            return;

        int column = columnAt(tree, e.x, e.y, item);
        if (column < 0)
            column = 0;
        selectCell(item, column);

        Object element = item.getData();
        if (DebugSessionHelper.isDebugSuspended(null)
            && DebugCollectionShowSupport.canOpenFrom(element))
        {
            DebugCollectionShowSupport.openFromElement(element);
            DebugCollectionDebug.step("doubleClick", "inspector"); //$NON-NLS-1$ //$NON-NLS-2$
            e.doit = false;
            return;
        }

        if (!isIndependentElementDialog())
            return;

        int valueColumn = resolveInspectorValueColumn();
        if (column != valueColumn)
            return;

        if (!isEditableVariable(item))
            return;

        tree.setFocus();
        if (!activateInlineValueEditor(new MouseEvent(e)))
            logValueEditFailure("activate failed"); //$NON-NLS-1$
    }

    /** Независимое окно инспектора: {@link DebugElementDialog} или наследник (попап F9/Инспектировать), не hover. */
    private boolean isIndependentElementDialog()
    {
        if (dialog == null)
            return false;
        for (Class<?> c = dialog.getClass(); c != null; c = c.getSuperclass())
        {
            if (CLASS_STANDALONE_INSPECTOR_DIALOG.equals(c.getName()))
                return true;
        }
        return false;
    }

    private static boolean isEditableVariable(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        Object data = item.getData();
        if (!(data instanceof IValueModification modification))
            return false;
        return modification.supportsValueModification();
    }

    private boolean activateInlineValueEditor(MouseEvent e)
    {
        if (viewer == null || tree.isDisposed())
            return false;

        Object cellObj = Global.invoke(viewer, "getCell", new Point(e.x, e.y)); //$NON-NLS-1$
        if (!(cellObj instanceof ViewerCell cell))
            return false;

        Object columnEditor = Global.invoke(viewer, "getColumnViewerEditor"); //$NON-NLS-1$
        if (columnEditor == null)
            return false;

        removeEditorActivationCancelListeners(columnEditor);

        ColumnViewerEditorActivationEvent activationEvent = new ColumnViewerEditorActivationEvent(cell, e);
        if (!Global.invokeVoid(columnEditor, "handleEditorActivationEvent", activationEvent)) //$NON-NLS-1$
            return false;

        Object active = Global.invoke(columnEditor, "isCellEditorActive"); //$NON-NLS-1$
        return Boolean.TRUE.equals(active);
    }

    /**
     * {@code PendingAwareInspectPopupDialog} вызывает {@code disableTreeElementsEditing()} —
     * listener {@code DebugElementDialog$6} отменяет inline-активацию.
     */
    private static int removeEditorActivationCancelListeners(Object columnEditor)
    {
        if (columnEditor == null)
            return 0;
        Object listenerList = Global.getField(columnEditor, "editorActivationListener"); //$NON-NLS-1$
        if (listenerList == null)
            return 0;
        Object[] listeners = null;
        Object raw = Global.invoke(listenerList, "getListeners"); //$NON-NLS-1$
        if (raw instanceof Object[] array)
            listeners = array;
        if (listeners == null || listeners.length == 0)
            return 0;
        int removed = 0;
        for (Object listener : listeners)
        {
            if (listener == null)
                continue;
            String name = listener.getClass().getName();
            if (!name.endsWith(EDITOR_CANCEL_LISTENER_SUFFIX))
                continue;
            if (Global.invokeVoid(columnEditor, "removeEditorActivationListener", listener)) //$NON-NLS-1$
                removed++;
        }
        return removed;
    }

    private void logValueEditFailure(String reason)
    {
        DebugInspectorDebug.step("valueEdit", reason //$NON-NLS-1$
            + " viewer=" + (viewer != null) //$NON-NLS-1$
            + " dialog=" + DebugInspectorDebug.cn(dialog)); //$NON-NLS-1$
    }

    private void syncFromTreeSelection()
    {
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        selectedItem = selection[0];
        if (activeColumn < 0 || activeColumn >= tree.getColumnCount())
            activeColumn = 0;
    }

    private void applyViewerSelection(TreeItem item)
    {
        if (viewer == null || item == null)
            return;
        Object data = item.getData();
        if (data == null)
            return;
        List<Object> path = new ArrayList<>();
        TreeItem current = item;
        while (current != null)
        {
            Object element = current.getData();
            if (element != null)
                path.add(0, element);
            current = current.getParentItem();
        }
        if (path.isEmpty())
            return;
        TreeSelection selection = new TreeSelection(new TreePath(path.toArray()));
        Global.invoke(viewer, "setSelection", selection); //$NON-NLS-1$
    }

    private void fireSelectionEvent(TreeItem item)
    {
        Event event = new Event();
        event.widget = tree;
        event.item = item;
        tree.notifyListeners(SWT.Selection, event);
    }

    private void copyActiveCellToClipboard()
    {
        TreeItem[] selection = tree.getSelection();
        if (selection.length > 0)
            selectedItem = selection[0];
        if (selectedItem == null || selectedItem.isDisposed())
            return;

        int column = activeColumn;
        if (column < 0 || column >= tree.getColumnCount())
            column = 0;

        String text = resolveCellText(selectedItem, column);
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        DebugInspectorDebug.step("copy", "column=" + column + " len=" + text.length()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void promptFind()
    {
        suppressInspectorAutoClose();
        String currentFindText = readFindText();
        Shell parentShell = resolveFindPromptParentShell();
        InputDialog inputDialog = new InputDialog(parentShell,
            "Поиск в инспекторе", //$NON-NLS-1$
            "Текст в любой колонке" + Global.pluginSignForTooltip(), //$NON-NLS-1$
            currentFindText,
            null);
        try (FindDialogDeactivateGuard guard = new FindDialogDeactivateGuard(shouldKeepDeactivateOff()))
        {
            if (inputDialog.open() != InputDialog.OK)
                return;
            String value = inputDialog.getValue();
            if (value == null || value.isBlank())
                return;
            writeFindText(value);
            findGeneration++;
            findNext(true);
        }
        finally
        {
            if (!tree.isDisposed())
                tree.setFocus();
        }
    }

    private Shell resolveFindPromptParentShell()
    {
        Shell inspectorShell = tree.getShell();
        if (isIndependentElementDialog())
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                Shell workbenchShell = window != null ? window.getShell() : null;
                if (workbenchShell != null && !workbenchShell.isDisposed())
                    return workbenchShell;
            }
            catch (RuntimeException ignored)
            {
                // fallback below
            }
        }
        if (inspectorShell != null && !inspectorShell.isDisposed())
            return inspectorShell;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null && window.getShell() != null && !window.getShell().isDisposed())
                return window.getShell();
        }
        catch (Exception ignored)
        {
            // fallback below
        }
        return inspectorShell;
    }

    private void suppressInspectorAutoClose()
    {
        Object host = resolveElementDialogHost();
        if (host != null)
        {
            Global.setField(host, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
            Global.setField(host, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
        }
    }

    private boolean isPopupInspectorDialog()
    {
        return isElementDialogHost(dialog) && CLASS_INSPECT_POPUP.equals(dialog.getClass().getName());
    }

    private String readFindText()
    {
        Object stored = tree.getData(FIND_TEXT_KEY);
        if (stored instanceof String s && !s.isBlank())
        {
            findText = s;
            return s;
        }
        return findText != null ? findText : ""; //$NON-NLS-1$
    }

    private void writeFindText(String text)
    {
        findText = text != null ? text : ""; //$NON-NLS-1$
        tree.setData(FIND_TEXT_KEY, findText);
    }

    /**
     * {@code InputDialog} modal deactivates popup inspector — suppress {@code listenToDeactivate}
     * on {@link DebugElementDialog} и {@code deactivationListener} у {@code ExpressionInformationControl}.
     */
    private final class FindDialogDeactivateGuard implements AutoCloseable
    {
        private static final String LISTEN_TO_DEACTIVATE = "listenToDeactivate"; //$NON-NLS-1$
        private static final String LISTEN_TO_PARENT_DEACTIVATE = "listenToParentDeactivate"; //$NON-NLS-1$

        private final Object elementDialog;
        private final Boolean prevDeactivate;
        private final Boolean prevParentDeactivate;
        private final boolean keepDeactivateOff;
        private final Shell infoShell;
        private final Listener deactivateListener;
        private final boolean removedDeactivateListener;
        private final Listener activationListener;
        private final boolean removedActivationListener;
        private final Object hoverControlManager;
        private final Object prevInformationControlCloser;
        private final Runnable restoreShellPin;

        FindDialogDeactivateGuard(boolean keepDeactivateOff)
        {
            this.keepDeactivateOff = keepDeactivateOff;
            elementDialog = resolveElementDialogHost();
            prevDeactivate = readBooleanField(elementDialog, LISTEN_TO_DEACTIVATE);
            prevParentDeactivate = readBooleanField(elementDialog, LISTEN_TO_PARENT_DEACTIVATE);
            suppressDeactivate(elementDialog);
            if (isExpressionInformationControl())
                suppressDeactivate(dialog);

            Shell shell = tree.getShell();
            restoreShellPin = needsPinSuspendForFind()
                ? DebugInspectorHook.suspendInspectorShellPin(shell)
                : () -> { };
            Listener deactivate = null;
            Listener activate = null;
            boolean removedDeactivate = false;
            boolean removedActivate = false;
            Object icManager = null;
            Object prevCloser = null;
            if (isExpressionInformationControl() && shell != null && !shell.isDisposed())
            {
                Object deactivateObj = Global.getField(dialog, "deactivationListener"); //$NON-NLS-1$
                if (deactivateObj instanceof Listener l)
                {
                    deactivate = l;
                    shell.removeListener(SWT.Deactivate, l);
                    removedDeactivate = true;
                }
                Object activateObj = Global.getField(dialog, "activationListener"); //$NON-NLS-1$
                if (activateObj instanceof Listener a)
                {
                    activate = a;
                    shell.removeListener(SWT.Activate, a);
                    removedActivate = true;
                }
                icManager = DebugInspectorHook.resolveHoverInformationControlManager(shell, dialog);
                if (icManager != null)
                {
                    prevCloser = Global.getField(icManager, "fInformationControlCloser"); //$NON-NLS-1$
                    if (prevCloser != null)
                        Global.invokeVoid(prevCloser, "stop"); //$NON-NLS-1$
                    Global.setField(icManager, "fInformationControlCloser", null); //$NON-NLS-1$
                }
            }
            infoShell = shell;
            deactivateListener = deactivate;
            removedDeactivateListener = removedDeactivate;
            activationListener = activate;
            removedActivationListener = removedActivate;
            hoverControlManager = icManager;
            prevInformationControlCloser = prevCloser;
        }

        @Override
        public void close()
        {
            if (restoreShellPin != null)
                restoreShellPin.run();
            if (removedDeactivateListener && infoShell != null && !infoShell.isDisposed()
                && deactivateListener != null)
                infoShell.addListener(SWT.Deactivate, deactivateListener);
            if (removedActivationListener && infoShell != null && !infoShell.isDisposed()
                && activationListener != null)
                infoShell.addListener(SWT.Activate, activationListener);
            if (hoverControlManager != null)
                Global.setField(hoverControlManager, "fInformationControlCloser", prevInformationControlCloser); //$NON-NLS-1$
            if (elementDialog == null)
                return;
            if (keepDeactivateOff)
            {
                suppressDeactivate(elementDialog);
                if (isExpressionInformationControl())
                    suppressDeactivate(dialog);
                return;
            }
            if (prevDeactivate != null)
                Global.setField(elementDialog, LISTEN_TO_DEACTIVATE, prevDeactivate);
            if (prevParentDeactivate != null)
                Global.setField(elementDialog, LISTEN_TO_PARENT_DEACTIVATE, prevParentDeactivate);
        }

        private void suppressDeactivate(Object target)
        {
            if (target == null)
                return;
            Global.setField(target, LISTEN_TO_DEACTIVATE, Boolean.FALSE);
            Global.setField(target, LISTEN_TO_PARENT_DEACTIVATE, Boolean.FALSE);
        }

        private Boolean readBooleanField(Object target, String fieldName)
        {
            if (target == null)
                return null;
            Object value = Global.getField(target, fieldName);
            if (value instanceof Boolean b)
                return b;
            return null;
        }
    }

    private void findNext(boolean forward)
    {
        String needleText = readFindText();
        if (needleText.isBlank())
        {
            promptFind();
            return;
        }

        int generation = findGeneration;
        BusyIndicator.showWhile(tree.getDisplay(), () ->
        {
            if (generation != findGeneration)
                return;
            List<TreeItem> items = new ArrayList<>();
            collectItems(tree.getItems(), items);
            if (items.isEmpty())
                return;

            String needle = needleText.toLowerCase();
            int start = 0;
            if (selectedItem != null)
            {
                int idx = items.indexOf(selectedItem);
                if (idx >= 0)
                    start = forward ? idx + 1 : idx - 1;
            }

            TreeItem found = null;
            for (int pass = 0; pass < 2 && found == null; pass++)
            {
                if (forward)
                {
                    for (int i = start; i < items.size(); i++)
                    {
                        if (itemMatches(items.get(i), needle))
                        {
                            found = items.get(i);
                            break;
                        }
                    }
                }
                else
                {
                    for (int i = start; i >= 0; i--)
                    {
                        if (itemMatches(items.get(i), needle))
                        {
                            found = items.get(i);
                            break;
                        }
                    }
                }
                start = forward ? 0 : items.size() - 1;
            }

            if (found == null)
            {
                DebugInspectorDebug.step("find", "no match for \"" + needleText + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return;
            }

            int column = firstMatchingColumn(found, needle);
            if (column < 0)
                column = 0;
            expandTo(found);
            selectCell(found, column);
            tree.showItem(found);
            DebugInspectorDebug.step("find", "match column=" + column); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    private static void collectItems(TreeItem[] roots, List<TreeItem> out)
    {
        if (roots == null)
            return;
        for (TreeItem item : roots)
        {
            if (item == null || item.isDisposed())
                continue;
            out.add(item);
            collectItems(item.getItems(), out);
        }
    }

    private boolean itemMatches(TreeItem item, String needle)
    {
        return firstMatchingColumn(item, needle) >= 0;
    }

    private int firstMatchingColumn(TreeItem item, String needle)
    {
        int columns = Math.max(1, tree.getColumnCount());
        for (int c = 0; c < columns; c++)
        {
            String text = resolveCellText(item, c);
            if (!text.isEmpty() && text.toLowerCase().contains(needle))
                return c;
        }
        return -1;
    }

    private String resolveCellText(TreeItem item, int column)
    {
        if (item == null || item.isDisposed())
            return ""; //$NON-NLS-1$

        String text = item.getText(column);
        if (text != null && !text.isEmpty())
            return text;

        Object element = item.getData();
        if (viewer != null && element != null)
        {
            Object label = Global.invoke(viewer, "getColumnText", element, Integer.valueOf(column)); //$NON-NLS-1$
            if (label instanceof String s && !s.isEmpty())
                return s;
            label = Global.invoke(viewer, "getLabelText", element, Integer.valueOf(column)); //$NON-NLS-1$
            if (label instanceof String s && !s.isEmpty())
                return s;
        }

        return text != null ? text : ""; //$NON-NLS-1$
    }

    private static String cellText(TreeItem item, int column)
    {
        if (item == null || item.isDisposed())
            return ""; //$NON-NLS-1$
        String text = item.getText(column);
        return text != null ? text : ""; //$NON-NLS-1$
    }

    private void expandTo(TreeItem item)
    {
        TreeItem parent = item.getParentItem();
        while (parent != null)
        {
            parent.setExpanded(true);
            parent = parent.getParentItem();
        }
    }

    private static int columnAt(Tree tree, int x, int y, TreeItem item)
    {
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            Rectangle bounds = item.getBounds(i);
            if (bounds != null && bounds.contains(x, y))
                return i;
        }
        return 0;
    }

    private static TreeItem itemAt(Tree tree, int x, int y)
    {
        TreeItem item = tree.getItem(new Point(x, y));
        if (item != null)
            return item;
        for (TreeItem root : tree.getItems())
        {
            TreeItem found = findInItem(root, x, y);
            if (found != null)
                return found;
        }
        return null;
    }

    private static TreeItem findInItem(TreeItem item, int x, int y)
    {
        for (int i = 0; i < item.getParent().getColumnCount(); i++)
        {
            Rectangle bounds = item.getBounds(i);
            if (bounds != null && bounds.contains(x, y))
                return item;
        }
        for (TreeItem child : item.getItems())
        {
            TreeItem found = findInItem(child, x, y);
            if (found != null)
                return found;
        }
        return null;
    }

    void dispose()
    {
        clearPendingFocusSession();
        if (tree != null && !tree.isDisposed())
        {
            if (mouseListener != null)
                tree.removeMouseListener(mouseListener);
            if (eraseItemListener != null)
                tree.removeListener(SWT.EraseItem, eraseItemListener);
            if (paintItemListener != null)
                tree.removeListener(SWT.PaintItem, paintItemListener);
            if (focusListener != null)
            {
                tree.removeListener(SWT.FocusIn, focusListener);
                tree.removeListener(SWT.FocusOut, focusListener);
            }
            if (selectionListener != null)
                tree.removeListener(SWT.Selection, selectionListener);
            if (treeKeyListener != null)
                tree.removeListener(SWT.KeyDown, treeKeyListener);
            if (menuDetectListener != null)
                tree.removeListener(SWT.MenuDetect, menuDetectListener);
            if (doubleClickListener != null)
                tree.removeListener(SWT.MouseDoubleClick, doubleClickListener);
            Shell inspectorShell = tree.getShell();
            if (inspectorShell != null && !inspectorShell.isDisposed()
                && changeValueInspectorDisposeListener != null)
            {
                inspectorShell.removeListener(SWT.Dispose, changeValueInspectorDisposeListener);
                changeValueInspectorDisposeListener = null;
            }
            DebugInspectorCollectionMenuHook.uninstall(tree);
            tree.setData(ENHANCED_KEY, null);
            tree.setData(COPY_HOOKED_KEY, null);
            doubleClickListener = null;
        }
        invalidateHighlightColor();
        if (keyFilter != null)
        {
            Display display = Display.getCurrent();
            if (display != null && !display.isDisposed())
                display.removeFilter(SWT.KeyDown, keyFilter);
            keyFilter = null;
        }
        closeChangeValueModalGuard();
        if (changeValueSelectionFilter != null)
        {
            Display display = Display.getCurrent();
            if (display != null && !display.isDisposed())
                display.removeFilter(SWT.Selection, changeValueSelectionFilter);
            changeValueSelectionFilter = null;
        }
        findGeneration++;
        pendingFocusGeneration++;
    }

    void schedulePendingPropertyFocus()
    {
        String requested = InspectorPendingFocus.peek();
        if (requested == null || requested.isBlank())
            return;

        if (requested.equals(pendingPropertyName) && pendingFocusStartedAt > 0
            && System.currentTimeMillis() - pendingFocusStartedAt < InspectorPendingFocus.TTL_MS)
        {
            tryApplyPendingPropertyFocus(-1, pendingFocusSessionStart, pendingFocusGeneration);
            return;
        }

        clearPendingFocusSession();
        pendingPropertyName = requested;
        pendingFocusStartedAt = System.currentTimeMillis();
        pendingFocusSessionStart = DebugValuesDebug.begin();
        final int generation = ++pendingFocusGeneration;

        installViewerUpdateListener(generation, pendingFocusSessionStart);

        Display display = tree.getDisplay();
        for (int attempt = 0; attempt < PENDING_FOCUS_DELAYS_MS.length; attempt++)
        {
            final int delay = PENDING_FOCUS_DELAYS_MS[attempt];
            final int attemptNo = attempt;
            display.timerExec(delay, () ->
                tryApplyPendingPropertyFocus(attemptNo, pendingFocusSessionStart, generation));
        }
    }

    private void tryApplyPendingPropertyFocus(int attemptNo, long sessionStart, int generation)
    {
        if (!isAttached() || generation != pendingFocusGeneration)
            return;
        if (pendingPropertyName == null || pendingPropertyName.isBlank())
            return;

        long elapsed = System.currentTimeMillis() - pendingFocusStartedAt;
        if (pendingFocusStartedAt <= 0 || elapsed > InspectorPendingFocus.TTL_MS)
        {
            DebugValuesDebug.perfSlow("pendingFocus", sessionStart, //$NON-NLS-1$
                "fail ttl property=" + DebugValuesDebug.quote(pendingPropertyName)); //$NON-NLS-1$
            clearPendingFocusSession();
            pendingFocusGeneration++;
            return;
        }

        long attemptStart = DebugValuesDebug.begin();
        TreeItem[] roots = tree.getItems();
        if (roots.length == 0)
        {
            String tag = attemptNo < 0 ? "update" : "attempt=" + attemptNo; //$NON-NLS-1$ //$NON-NLS-2$
            DebugValuesDebug.step("pendingFocus", tag + " no roots elapsed=" + elapsed + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (attemptNo == PENDING_FOCUS_DELAYS_MS.length - 1)
            {
                DebugValuesDebug.perfSlow("pendingFocus", sessionStart, "fail no roots"); //$NON-NLS-1$ //$NON-NLS-2$
                clearPendingFocusSession();
                pendingFocusGeneration++;
            }
            return;
        }

        expandRootForPendingFocus(roots[0]);

        TreeItem match = findChildProperty(roots[0], pendingPropertyName);
        if (match == null)
        {
            String tag = attemptNo < 0 ? "update" : "attempt=" + attemptNo; //$NON-NLS-1$ //$NON-NLS-2$
            DebugValuesDebug.step("pendingFocus", tag //$NON-NLS-1$
                + " miss children=" + roots[0].getItemCount() //$NON-NLS-1$
                + " elapsed=" + elapsed + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
            if (attemptNo == PENDING_FOCUS_DELAYS_MS.length - 1)
            {
                DebugValuesDebug.perfSlow("pendingFocus", sessionStart, //$NON-NLS-1$
                    "fail property=" + DebugValuesDebug.quote(pendingPropertyName)); //$NON-NLS-1$
                clearPendingFocusSession();
                pendingFocusGeneration++;
            }
            return;
        }

        pendingFocusGeneration++;
        clearPendingFocusSession();
        InspectorPendingFocus.complete();
        int valueColumn = resolveInspectorValueColumn();
        expandTo(match);
        selectCell(match, valueColumn);
        String okTag = attemptNo < 0 ? "update" : "attempt=" + attemptNo; //$NON-NLS-1$ //$NON-NLS-2$
        DebugValuesDebug.perf("pendingFocus.attempt", attemptStart, //$NON-NLS-1$
            "ok " + okTag + " col=" + valueColumn); //$NON-NLS-1$ //$NON-NLS-2$
        DebugValuesDebug.perfSlow("pendingFocus", sessionStart, //$NON-NLS-1$
            "ok property=" + DebugValuesDebug.quote(pendingPropertyName)); //$NON-NLS-1$
    }

    private void expandRootForPendingFocus(TreeItem root)
    {
        if (root == null || root.isDisposed())
            return;
        root.setExpanded(true);
        Object element = root.getData();
        if (viewer == null || element == null)
            return;
        try
        {
            Global.invoke(viewer, "setExpandedState", element, Boolean.TRUE); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // опционально
        }
    }

    private void installViewerUpdateListener(int generation, long sessionStart)
    {
        removeViewerUpdateListener();
        if (viewer == null)
            return;
        try
        {
            Class<?> iface = Class.forName(
                "org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdateListener"); //$NON-NLS-1$
            DebugInspectorTreeEnhancement self = this;
            viewerUpdateListener = java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[] { iface },
                (proxy, method, args) ->
                {
                    String methodName = method.getName();
                    if ("viewerUpdatesComplete".equals(methodName) //$NON-NLS-1$
                        || "updateComplete".equals(methodName)) //$NON-NLS-1$
                    {
                        Display display = tree.getDisplay();
                        if (display != null && !display.isDisposed())
                            display.asyncExec(() ->
                            {
                                self.tryApplyPendingPropertyFocus(-1, sessionStart, generation);
                                if (viewer instanceof Viewer treeViewer)
                                    DebugVariablePresentationHook.hookInspectorTreeViewer(treeViewer, self.tree);
                            });
                    }
                    return proxyDefaultReturn(method.getReturnType());
                });
            Global.invoke(viewer, "addViewerUpdateListener", viewerUpdateListener); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("pendingFocus", "listener failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            viewerUpdateListener = null;
        }
    }

    private void removeViewerUpdateListener()
    {
        if (viewerUpdateListener == null || viewer == null)
            return;
        try
        {
            Global.invoke(viewer, "removeViewerUpdateListener", viewerUpdateListener); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // опционально
        }
        viewerUpdateListener = null;
    }

    private static Object proxyDefaultReturn(Class<?> returnType)
    {
        if (returnType == null || returnType == void.class || returnType == Void.class)
            return null;
        if (returnType == boolean.class || returnType == Boolean.class)
            return Boolean.FALSE;
        if (returnType == int.class || returnType == Integer.class)
            return Integer.valueOf(0);
        if (returnType == long.class || returnType == Long.class)
            return Long.valueOf(0L);
        return null;
    }

    private void clearPendingFocusSession()
    {
        removeViewerUpdateListener();
        pendingPropertyName = null;
        pendingFocusStartedAt = 0L;
        pendingFocusSessionStart = 0L;
    }

    private TreeItem findChildProperty(TreeItem root, String propertyName)
    {
        if (root == null || root.isDisposed() || propertyName == null || propertyName.isBlank())
            return null;
        String needle = propertyName.trim();
        for (TreeItem child : root.getItems())
        {
            if (child == null || child.isDisposed())
                continue;
            String name = child.getText(0);
            if (InspectorPendingFocus.matchesPropertyLabel(name, needle))
                return child;
        }
        return null;
    }

    private int resolveInspectorValueColumn()
    {
        int columns = tree.getColumnCount();
        for (int i = 0; i < columns; i++)
        {
            org.eclipse.swt.widgets.TreeColumn column = tree.getColumn(i);
            if (column == null || column.isDisposed())
                continue;
            String text = column.getText();
            if (text == null)
                continue;
            String header = text.trim();
            if ("Значение".equalsIgnoreCase(header) //$NON-NLS-1$
                || "Value".equalsIgnoreCase(header)) //$NON-NLS-1$
                return i;
        }
        return columns > 1 ? 1 : 0;
    }

    private void installChangeValueModalGuard()
    {
        Shell inspectorShell = tree.getShell();
        if (inspectorShell == null || inspectorShell.isDisposed())
            return;

        if (changeValueSelectionFilter == null)
        {
            changeValueSelectionFilter = this::onChangeValueSelectionFilter;
            tree.getDisplay().addFilter(SWT.Selection, changeValueSelectionFilter);
        }

        if (changeValueInspectorDisposeListener == null)
        {
            changeValueInspectorDisposeListener = e -> closeChangeValueModalGuard();
            inspectorShell.addListener(SWT.Dispose, changeValueInspectorDisposeListener);
        }
    }

    private void onChangeValueSelectionFilter(Event e)
    {
        if (!(e.widget instanceof MenuItem item) || item.isDisposed())
            return;
        if (!isChangeValueMenuItem(item))
            return;
        Menu menu = item.getParent();
        if (menu == null || menu.isDisposed())
            return;
        Shell menuShell = menu.getShell();
        Shell inspectorShell = tree.getShell();
        if (menuShell == null || inspectorShell == null || menuShell != inspectorShell)
            return;
        if (!shouldKeepDeactivateOff())
            return;

        beginChangeValueModalGuard();
    }

    private void beginChangeValueModalGuard()
    {
        suppressInspectorAutoClose();
        closeChangeValueModalGuard();
        changeValueModalGuard = new FindDialogDeactivateGuard(shouldKeepDeactivateOff());
        changeValueGuardGeneration++;
        scheduleChangeValueGuardRelease(changeValueGuardGeneration);
    }

    private void scheduleChangeValueGuardRelease(int generation)
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> attachChangeValueGuardRelease(generation, 0));
    }

    private void attachChangeValueGuardRelease(int generation, int attempt)
    {
        if (tree.isDisposed() || generation != changeValueGuardGeneration)
            return;
        Shell inspectorShell = tree.getShell();
        Display display = tree.getDisplay();
        if (inspectorShell == null || inspectorShell.isDisposed() || display == null || display.isDisposed())
        {
            closeChangeValueModalGuard();
            return;
        }

        Shell active = display.getActiveShell();
        if (active != null && !active.isDisposed() && active != inspectorShell
            && isNestedInspectorShell(active, inspectorShell))
        {
            active.addListener(SWT.Dispose, e ->
            {
                if (generation == changeValueGuardGeneration)
                    closeChangeValueModalGuard();
            });
            return;
        }

        if (attempt < 24)
            display.timerExec(50, () -> attachChangeValueGuardRelease(generation, attempt + 1));
        else
            closeChangeValueModalGuard();
    }

    private void closeChangeValueModalGuard()
    {
        changeValueGuardGeneration++;
        if (changeValueModalGuard != null)
        {
            changeValueModalGuard.close();
            changeValueModalGuard = null;
        }
        if (tree != null && !tree.isDisposed())
            tree.setFocus();
    }

    private static boolean isChangeValueMenuItem(MenuItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        String label = item.getText();
        if (label != null)
        {
            String trimmed = label.trim();
            if (trimmed.contains("Изменить значение") //$NON-NLS-1$
                || trimmed.contains("Change value") //$NON-NLS-1$
                || trimmed.contains("Change Value")) //$NON-NLS-1$
                return true;
        }
        Object data = item.getData();
        return data != null && data.getClass().getName().contains("ChangeVariableValueAction"); //$NON-NLS-1$
    }
}
