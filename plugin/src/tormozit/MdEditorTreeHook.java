package tormozit;

import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;

/**
 * Доработки деревьев ({@code DtTreeView}) в редакторах объектов метаданных.
 * <p>
 * Сейчас доработка одна — текущая строка не должна уезжать на верхнюю видимую при промахе
 * программного выделения. Двойной клик по проблеме реквизита в панели проблем даёт штатную
 * цепочку {@code BmMarkerUiHandler.showMarker} → {@code OpenHelper.openEditor} →
 * {@code DtGranularEditor.gotoSelection}, в которой в дерево уходят ДВА выделения: сперва сам
 * реквизит (строка есть — выделяется верно), затем объект маркера, например
 * {@code TypeDescription} для проверки составных типов. Строки для него в дереве нет, маппер
 * отдаёт пустую {@code TreeItemViewModel}, {@code DtTreeView} пытается выделить её в JFace,
 * элемент не находится — и выделение становится пустым. Штатный
 * {@code DtTreeView$SelectionListener} трактует это как «пользователь снял выделение» и выделяет
 * {@code tree.getTopItem()}, то есть верхнюю ВИДИМУЮ строку. Пользователь видит, как активной
 * становится посторонняя строка.
 * <p>
 * Перехват: штатный слушатель выделения снимается с {@code TreeViewer} и вызывается из нашей
 * обёртки. Пустое выделение, пришедшее из программного применения события (в стеке есть кадр
 * {@code DtTreeView}), штатному слушателю не отдаётся — вместо этого восстанавливается прежняя
 * строка. Пустое выделение от действий пользователя обрабатывается штатно.
 * <p>
 * Почему не «доводка» (выделить нужную строку после того, как штатная цепочка отработает):
 * промах приходит примерно через 170 мс после верного выделения, и повторное выделение было бы
 * видно пользователю как скачок текущей строки туда и обратно.
 */
public final class MdEditorTreeHook
    implements IStartup
{
    /** Ключ, под которым {@code DtTreeView} кладёт свой {@code TreeViewer} в данные контрола. */
    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String STOCK_LISTENER_CLASS =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView$SelectionListener"; //$NON-NLS-1$

    /** Кадр стека, по которому видно, что выделение применяет сам {@code DtTreeView}. */
    private static final String DT_TREE_VIEW_CLASS =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView"; //$NON-NLS-1$

    /** Страницы редактора создаются не сразу — повторяем обход контролов. */
    private static final int[] RETRY_DELAYS = { 0, 150, 400, 900, 1800, 3500 };

    /** События активации приходят пачкой — обход контролов повторяем не чаще раза в секунду. */
    private static final long RESCHEDULE_PAUSE_MS = 1000;

    private static final Map<IEditorPart, Long> lastScheduled = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench workbench = PlatformUI.getWorkbench();
            if (workbench == null)
                return;
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
            if (page != null)
                for (org.eclipse.ui.IEditorReference ref : page.getEditorReferences())
                    scheduleInstall(ref != null ? ref.getEditor(false) : null);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partActivated(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partVisible(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partInputChanged(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
        });
    }

    private static IEditorPart editorOf(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        return part instanceof IEditorPart editor ? editor : null;
    }

    private static void scheduleInstall(IEditorPart editor)
    {
        if (editor == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        long now = System.currentTimeMillis();
        Long previous = lastScheduled.get(editor);
        if (previous != null && now - previous.longValue() < RESCHEDULE_PAUSE_MS)
            return;
        lastScheduled.put(editor, Long.valueOf(now));
        for (int delay : RETRY_DELAYS)
            display.timerExec(delay, () -> install(editor));
    }

    private static void install(IEditorPart editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof Composite composite) || composite.isDisposed())
            return;
        installInChildren(composite, 0);
    }

    private static void installInChildren(Composite composite, int depth)
    {
        if (composite.isDisposed() || depth > 25)
            return;
        if (composite.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
            installOnViewer(viewer);
        for (Control child : composite.getChildren())
            if (child instanceof Composite childComposite)
                installInChildren(childComposite, depth + 1);
    }

    /**
     * Ставится заново, если {@code DtTreeView} перепривязался к тому же дереву и добавил свой
     * слушатель снова: признаком служит не отметка на виджете, а наличие штатного слушателя в
     * списке.
     */
    private static void installOnViewer(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        ISelectionChangedListener stock = findListener(viewer, STOCK_LISTENER_CLASS);
        if (stock == null)
            return;
        ISelectionChangedListener previous = findListener(viewer, KeepSelectionListener.class.getName());
        if (previous != null)
            viewer.removeSelectionChangedListener(previous);
        viewer.removeSelectionChangedListener(stock);
        viewer.addSelectionChangedListener(new KeepSelectionListener(viewer, stock));
        Debug.log("перехват выделения установлен"); //$NON-NLS-1$
    }

    private static ISelectionChangedListener findListener(TreeViewer viewer, String className)
    {
        Object listenerList = Global.getField(viewer, "selectionChangedListeners"); //$NON-NLS-1$
        Object raw = listenerList != null ? Global.invoke(listenerList, "getListeners") : null; //$NON-NLS-1$
        if (!(raw instanceof Object[] listeners))
            return null;
        for (Object listener : listeners)
            if (listener instanceof ISelectionChangedListener selectionListener
                && className.equals(listener.getClass().getName()))
                return selectionListener;
        return null;
    }

    /**
     * Обёртка штатного {@code DtTreeView$SelectionListener}: пустое выделение, пришедшее от
     * программного применения события, не пропускается — вместо подстановки верхней видимой
     * строки возвращается прежняя.
     */
    private static final class KeepSelectionListener
        implements ISelectionChangedListener
    {
        private final TreeViewer viewer;
        private final ISelectionChangedListener stock;
        private ISelection lastNonEmpty;
        private boolean restoring;

        KeepSelectionListener(TreeViewer viewer, ISelectionChangedListener stock)
        {
            this.viewer = viewer;
            this.stock = stock;
        }

        @Override
        public void selectionChanged(SelectionChangedEvent event)
        {
            ISelection selection = event != null ? event.getSelection() : null;
            if (selection != null && !selection.isEmpty())
            {
                lastNonEmpty = selection;
                restoring = false;
                stock.selectionChanged(event);
                return;
            }
            // Повторный промах при восстановлении (строка устарела после обновления дерева) —
            // отдаём штатному, иначе выделение зациклится.
            if (restoring || lastNonEmpty == null || !appliedByTreeView())
            {
                restoring = false;
                stock.selectionChanged(event);
                return;
            }
            restoring = true;
            try
            {
                viewer.setSelection(lastNonEmpty, false);
            }
            finally
            {
                restoring = false;
            }
            if (viewer.getSelection().isEmpty())
            {
                stock.selectionChanged(event);
                return;
            }
            Debug.log("промах программного выделения — текущая строка сохранена"); //$NON-NLS-1$
        }

        /**
         * Пустое выделение пришло из применения события дерева ({@code DtTreeView}), а не от
         * действий пользователя: у пользовательского клика в стеке только рассылка SWT/JFace.
         */
        private static boolean appliedByTreeView()
        {
            for (StackTraceElement frame : new Throwable().getStackTrace())
                if (frame.getClassName().startsWith(DT_TREE_VIEW_CLASS))
                    return true;
            return false;
        }
    }

    private static final class Debug
    {
        private static final String TAG = "MdEditorTree"; //$NON-NLS-1$

        private Debug() {}

        static void log(String message)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, message);
        }
    }
}
