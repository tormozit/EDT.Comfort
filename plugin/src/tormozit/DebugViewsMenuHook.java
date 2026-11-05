package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.SelectionProviderAction;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Команды значений в контекстном меню панелей «Переменные», «Выражения» и инспектора.
 * Только {@link SWT#MenuDetect} при правом клике — без partListener и без getViewer() на активации view.
 */
public final class DebugViewsMenuHook implements IStartup
{
    private static final String HOOK_MARKER_PREFIX = "tormozit.debugViewsMenuHooked."; //$NON-NLS-1$
    private static final String VARIABLE_VIEW_ID = "org.eclipse.debug.ui.VariableView"; //$NON-NLS-1$
    private static final String BSL_EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Отладить объект ИР"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
        "Текущее выражение передается в отладочный инструмент в окно приложения ИР или предмета отладки" //$NON-NLS-1$
        + Global.pluginSignForTooltip();

    private enum ViewKind
    {
        VARIABLES,
        EXPRESSIONS,
        INSPECTOR
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MenuDetect, DebugViewsMenuHook::handleMenuDetect);
        WatchExpressionVariables.install(display);
        UndefinedValueEditor.install();
        DebugViewsDebug.log("install MenuDetect filter"); //$NON-NLS-1$
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Tree tree))
            return;

        ActiveView active = activeDebugView(tree);
        if (active == null)
            return;

        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed())
            return;

        String hookMarker = HOOK_MARKER_PREFIX + active.kind.name();
        if (Boolean.TRUE.equals(menu.getData(hookMarker)))
            return;

        menu.setData(hookMarker, Boolean.TRUE);
        menu.addMenuListener(buildMenuListener(active.view, active.kind, tree));
        DebugViewsDebug.log("MenuDetect " + active.kind + ": MenuAdapter attached"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static MenuAdapter buildMenuListener(AbstractDebugView view, ViewKind kind, Tree tree)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                boolean suspended = DebugSessionHelper.isDebugSuspended(null);
                ISelection selection = selectionOf(view, kind, tree);
                DebugViewsDebug.log("menuShown " + kind + " " + DebugViewsDebug.selectionBrief(selection)); //$NON-NLS-1$ //$NON-NLS-2$

                boolean supported = selection instanceof IStructuredSelection structured
                    && structured.size() == 1 && isSupportedSelection(kind, structured.getFirstElement());
                Menu menu = (Menu) e.widget;
                addChangeValueItem(menu, selection, tree, suspended, addedItems);
                MenuItem item = new MenuItem(menu, SWT.PUSH);
                item.setText(ITEM_TEXT);
                item.setEnabled(supported && suspended);
                item.setToolTipText(ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        runCommand(view, kind, tree);
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(addedItems);
                addedItems.clear();
                ((Menu) e.widget).getDisplay().asyncExec(() ->
                {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
    }

    /** Корневое выражение не реализует IVariable, необходимый штатной команде EDT. */
    private static void addChangeValueItem(Menu menu, ISelection selection, Tree tree,
        boolean suspended, List<MenuItem> addedItems)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled()
            || !(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return;
        for (MenuItem existing : menu.getItems())
        {
            if (existing.getData() instanceof ActionContributionItem contribution
                && contribution.getAction() instanceof SelectionProviderAction stockAction
                && stockAction.getClass().getSimpleName().equals("ChangeVariableValueAction")) //$NON-NLS-1$
            {
                adaptChangeValueAction(stockAction);
                existing.setEnabled(suspended && stockAction.isEnabled());
                return;
            }
            String label = existing.getText().replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (existing.getData() instanceof SelectionProviderAction action
                    && action.getClass().getSimpleName().equals("ChangeVariableValueAction") //$NON-NLS-1$
                || label.startsWith("Изменить значение") //$NON-NLS-1$
                || label.startsWith("Change Value") || label.startsWith("Change value")) //$NON-NLS-1$ //$NON-NLS-2$
                return;
        }
        ISelection adapted = adaptVariableSelection(selection);
        if (!(adapted instanceof IStructuredSelection variables)
            || !(variables.getFirstElement() instanceof IBslVariable variable))
            return;

        IStructuredSelection variableSelection = new StructuredSelection(variable);
        SelectionProviderAction action = createChangeValueAction(
            new FixedVariableSelection(variableSelection), tree.getShell());
        if (action == null)
            return;
        action.selectionChanged(variableSelection);
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(action.getText());
        item.setData(action);
        if (action.getImageDescriptor() != null)
        {
            Image image = action.getImageDescriptor().createImage();
            item.setImage(image);
            item.addListener(SWT.Dispose, event -> image.dispose());
        }
        item.setEnabled(suspended && action.isEnabled());
        ComfortSubmenuHelper.setMenuItemTooltip(item, action.getDescription());
        item.addListener(SWT.Selection, event ->
        {
            if (variable.getDebugTarget().isSuspended() && variable.supportsValueModification())
                action.run();
        });
        addedItems.add(item);
    }

    /** Класс и конструктор штатной команды подтверждены в debug-ui/ChangeVariableValueAction.javap.txt. */
    private static SelectionProviderAction createChangeValueAction(ISelectionProvider provider, Shell shell)
    {
        try
        {
            Class<?> type = Platform.getBundle("com._1c.g5.v8.dt.debug.ui").loadClass( //$NON-NLS-1$
                "com._1c.g5.v8.dt.internal.debug.ui.actions.ChangeVariableValueAction"); //$NON-NLS-1$
            return (SelectionProviderAction) type.getConstructor(ISelectionProvider.class, Shell.class)
                .newInstance(provider, shell);
        }
        catch (ReflectiveOperationException e)
        {
            DebugViewsDebug.logError("create stock change-value action", e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /** Поле provider подтверждено исходниками Eclipse SelectionProviderAction. */
    private static void adaptChangeValueAction(SelectionProviderAction action)
    {
        ISelectionProvider original = action.getSelectionProvider();
        if (!(original instanceof VariableSelectionProvider))
        {
            VariableSelectionProvider adapted = new VariableSelectionProvider(original);
            if (!Global.setFieldForce(action, "provider", adapted)) //$NON-NLS-1$
            {
                return;
            }
            original.removeSelectionChangedListener(action);
            adapted.addSelectionChangedListener(action);
        }
        ISelection selection = action.getSelectionProvider().getSelection();
        action.selectionChanged(new SelectionChangedEvent(action.getSelectionProvider(), selection));
    }

    private static ISelection adaptVariableSelection(ISelection selection)
    {
        if (selection instanceof IStructuredSelection structured && structured.size() == 1
            && structured.getFirstElement() instanceof IWatchExpression watch
            && watch.isEnabled() && !watch.isPending() && !watch.hasErrors()
            && watch.getValue() instanceof IBslValue value && value.getStackFrame() != null
            && value.getParentUuid() != null && value.isEvaluated()
            && !value.isPending() && !value.isUnreadable())
            return new StructuredSelection(WatchExpressionVariables.create(watch, value));
        return selection;
    }

    /** Адаптация выделения только для команды, без подмены модели дерева. */
    private static final class VariableSelectionProvider implements ISelectionProvider
    {
        private final ISelectionProvider original;
        private final Map<ISelectionChangedListener, ISelectionChangedListener> listeners = new HashMap<>();

        VariableSelectionProvider(ISelectionProvider original)
        {
            this.original = original;
        }

        @Override
        public ISelection getSelection()
        {
            return adaptVariableSelection(original.getSelection());
        }

        @Override
        public void setSelection(ISelection selection)
        {
            // Штатная команда повторно выбирает IVariable; корневое выражение оставляем в дереве.
            ISelection current = original.getSelection();
            if (!(current instanceof IStructuredSelection structured)
                || !(structured.getFirstElement() instanceof IWatchExpression))
                original.setSelection(selection);
        }

        @Override
        public void addSelectionChangedListener(ISelectionChangedListener listener)
        {
            if (listeners.containsKey(listener))
                return;
            ISelectionChangedListener forwarding = event -> listener.selectionChanged(
                new SelectionChangedEvent(this, getSelection()));
            listeners.put(listener, forwarding);
            original.addSelectionChangedListener(forwarding);
        }

        @Override
        public void removeSelectionChangedListener(ISelectionChangedListener listener)
        {
            ISelectionChangedListener forwarding = listeners.remove(listener);
            if (forwarding != null)
                original.removeSelectionChangedListener(forwarding);
        }
    }

    /** Сохраняет выделенное выражение в дереве при работе штатной команды с его переменной. */
    private static final class FixedVariableSelection implements ISelectionProvider
    {
        private final ISelection selection;

        FixedVariableSelection(ISelection selection)
        {
            this.selection = selection;
        }

        @Override
        public ISelection getSelection()
        {
            return selection;
        }

        @Override
        public void setSelection(ISelection selection) { }

        @Override
        public void addSelectionChangedListener(ISelectionChangedListener listener) { }

        @Override
        public void removeSelectionChangedListener(ISelectionChangedListener listener) { }
    }

    /**
     * Штатная фабрика сохраняет UUID и путь значения. BslVariable.fireVariableModifiedEvent
     * посылает MODEL_SPECIFIC/10001 после успешного setValue/setTypedValue
     * (debug-core/BslVariable.javap.txt); только тогда пересчитываем выражение.
     */
    private static final class UndefinedValueEditor
    {
        private static final String KEY = "tormozit.debugValueEditor.dialog"; //$NON-NLS-1$

        static void install()
        {
            System.getProperties().put(KEY, (java.util.function.Consumer<Object[]>) event ->
            {
                String phase = (String) event[0];
                Object dialog = event[1];

                try
                {
                    if ("types".equals(phase)) //$NON-NLS-1$
                    {
                        Class<?> api = dialog.getClass().getClassLoader().loadClass(
                            "com._1c.g5.v8.dt.internal.debug.ui.values.modify.IBslValueTypeEditor"); //$NON-NLS-1$
                        Object editor = java.lang.reflect.Proxy.newProxyInstance(api.getClassLoader(),
                            new Class<?>[] { api }, (proxy, method, args) -> switch (method.getName())
                            {
                                case "getLabel", "toString" -> "Неопределено"; //$NON-NLS-1$ //$NON-NLS-2$
                                case "getModificationType" -> Void.class; //$NON-NLS-1$
                                case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                                case "equals" -> proxy == args[0]; //$NON-NLS-1$
                                default -> null; // У Неопределено нет поля ввода и типизированного результата.
                            });
                        Object original = Global.getField(dialog, "delegates"); //$NON-NLS-1$
                        if (original instanceof java.util.Collection<?> delegates)
                        {
                            List<Object> types = new ArrayList<>(delegates);
                            types.add(0, editor);
                            Global.setField(dialog, "delegates", types); //$NON-NLS-1$
                        }
                    }
                    else if ("default".equals(phase)) //$NON-NLS-1$
                    {
                        Object types = Global.getField(dialog, "delegates"); //$NON-NLS-1$
                        Object viewer = Global.getField(dialog, "typeViewer"); //$NON-NLS-1$
                        if (types instanceof List<?> list && !list.isEmpty()
                            && viewer instanceof org.eclipse.jface.viewers.ComboViewer combo)
                            combo.setSelection(new StructuredSelection(list.get(0)));
                    }
                    else if ("ok".equals(phase)) //$NON-NLS-1$
                    {
                        Object viewer = Global.getField(dialog, "typeViewer"); //$NON-NLS-1$
                        Object mode = Global.getField(dialog, "resultType"); //$NON-NLS-1$
                        Object types = Global.getField(dialog, "delegates"); //$NON-NLS-1$
                        if (viewer instanceof org.eclipse.jface.viewers.ComboViewer combo
                            && types instanceof List<?> list && !list.isEmpty()
                            && combo.getStructuredSelection().getFirstElement() == list.get(0)
                            && mode instanceof Enum<?> current && "TYPED_MODIFICATION".equals(current.name())) //$NON-NLS-1$
                        {
                            for (Object constant : current.getDeclaringClass().getEnumConstants())
                                if (constant instanceof Enum<?> value && "EXPRESSION".equals(value.name())) //$NON-NLS-1$
                                    Global.setField(dialog, "resultType", constant); //$NON-NLS-1$
                            Global.setField(dialog, "resultExpression", "Неопределено"); //$NON-NLS-1$
                        }
                    }
                }
                catch (Exception failure)
                {
                    DebugViewsDebug.logError("value editor dialog " + phase, failure); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            });
        }
    }

    private static final class WatchExpressionVariables
    {
        // BslVariable.equals сравнивает UUID/путь: WeakHashMap сохраняла первый из равных
        // ключей, который мог исчезнуть ещё до события от реально редактируемого экземпляра.
        private static final List<PendingWatchVariable> expressions = new ArrayList<>();
        private static boolean installed;

        private static final class PendingWatchVariable
        {
            final java.lang.ref.WeakReference<IBslVariable> variable;
            final IWatchExpression watch;

            PendingWatchVariable(IBslVariable variable, IWatchExpression watch)
            {
                this.variable = new java.lang.ref.WeakReference<>(variable);
                this.watch = watch;
            }
        }

        static IBslVariable create(IWatchExpression watch, IBslValue value)
        {
            IBslStackFrame frame = value.getStackFrame();
            IBslVariable variable = frame.getDebugTarget().getVariablesFactory()
                .createVariable(frame, watch.getExpressionText(), value);
            synchronized (expressions)
            {
                expressions.removeIf(entry -> entry.variable.get() == null);
                expressions.add(new PendingWatchVariable(variable, watch));
            }

            return variable;
        }

        private static IWatchExpression takeWatch(IBslVariable variable)
        {
            IWatchExpression result = null;
            synchronized (expressions)
            {
                var iterator = expressions.iterator();
                while (iterator.hasNext())
                {
                    PendingWatchVariable entry = iterator.next();
                    IBslVariable candidate = entry.variable.get();
                    if (candidate == variable)
                        result = entry.watch;
                    if (candidate == null || candidate == variable)
                        iterator.remove();
                }
            }
            return result;
        }

        static void install(Display display)
        {
            if (installed)
                return;
            installed = true;
            DebugPlugin.getDefault().addDebugEventListener(events ->
            {
                for (DebugEvent event : events)
                {
                    if (event.getKind() != DebugEvent.MODEL_SPECIFIC || event.getDetail() != 10001
                        || !(event.getSource() instanceof IBslVariable variable))
                        continue;
                    IWatchExpression watch = takeWatch(variable);

                    if (watch == null)
                        continue;

                    if (!display.isDisposed())
                        display.asyncExec(() ->
                        {
                            if (watch.isEnabled() && variable.getDebugTarget().isSuspended())
                                watch.evaluate();
                        });
                }
            });
        }
    }

    private static void runCommand(AbstractDebugView view, ViewKind kind, Tree tree)
    {
        ISelection selection = selectionOf(view, kind, tree);
        DebugViewsDebug.log("command run " + kind + " " + DebugViewsDebug.selectionBrief(selection)); //$NON-NLS-1$ //$NON-NLS-2$
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return;

        Object element = structured.getFirstElement();
        if ((kind == ViewKind.VARIABLES || kind == ViewKind.INSPECTOR)
            && element instanceof IBslVariable variable)
        {
            DebugIRHandler.debugVariable(variable);
            return;
        }
        if ((kind == ViewKind.EXPRESSIONS || kind == ViewKind.INSPECTOR)
            && element instanceof IWatchExpression watchExpr)
        {
            IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(null);
            IProject project = DebugIRHandler.getProjectFromStackFrame(frame);
            DebugIRHandler.debugWatchExpression(watchExpr, frame, project);
        }
    }

    private static boolean isSupportedSelection(ViewKind kind, Object element)
    {
        return (kind == ViewKind.VARIABLES || kind == ViewKind.INSPECTOR) && element instanceof IBslVariable
            || (kind == ViewKind.EXPRESSIONS || kind == ViewKind.INSPECTOR)
                && element instanceof IWatchExpression;
    }

    private static ISelection selectionOf(AbstractDebugView view, ViewKind kind, Tree tree)
    {
        if (kind == ViewKind.INSPECTOR)
        {
            TreeItem[] items = tree.getSelection();
            return items.length == 1 && items[0].getData() != null
                ? new StructuredSelection(items[0].getData()) : StructuredSelection.EMPTY;
        }
        ISelectionProvider provider = view.getSite().getSelectionProvider();
        return provider != null ? provider.getSelection() : null;
    }

    private record ActiveView(AbstractDebugView view, ViewKind kind) {}

    /**
     * Без {@code getViewer()} — только activePart + viewId, чтобы не трогать дерево VariableView
     * при переключении вкладок (гонка с EDT ChildrenCountUpdate).
     */
    private static ActiveView activeDebugView(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            if (tree.getShell() != window.getShell()
                && DebugInspectorHook.isInspectorShell(tree.getShell())
                && DebugInspectorTreeEnhancement.findInspectorTreeOnShell(tree.getShell()) == tree)
                return new ActiveView(null, ViewKind.INSPECTOR);
            IWorkbenchPart part = page.getActivePart();
            if (!(part instanceof AbstractDebugView debugView))
                return null;

            ViewKind kind = viewKind(part);
            if (kind == null)
                return null;

            return new ActiveView(debugView, kind);
        }
        catch (Exception e)
        {
            DebugViewsDebug.logError("activeDebugView", e); //$NON-NLS-1$
            return null;
        }
    }

    private static ViewKind viewKind(IWorkbenchPart part)
    {
        if (part == null)
            return null;
        String id = part.getSite().getId();
        if (VARIABLE_VIEW_ID.equals(id))
            return ViewKind.VARIABLES;
        if (BSL_EXPRESSIONS_VIEW_ID.equals(id) || IDebugUIConstants.ID_EXPRESSION_VIEW.equals(id))
            return ViewKind.EXPRESSIONS;
        return null;
    }
}
