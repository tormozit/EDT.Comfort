package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.inject.Injector;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerLabel;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;

import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Префикс {@code [N]} в колонке «Значение» штатных деревьев «Переменные» / «Выражения».
 * Только при {@link ComfortSettings#isImproveDebuggerWindowsEnabled()}.
 */
public final class DebugVariablePresentationHook implements IStartup
{
    private static final String FACTORY_MARKER = "BslDebugElementAdapterFactory"; //$NON-NLS-1$
    private static final String FIELD_VARIABLE_LABEL_PROVIDER = "variableLabelProvider"; //$NON-NLS-1$
    private static final String FIELD_WATCH_EXPRESSION_LABEL_PROVIDER = "watchExpressionLabelProvider"; //$NON-NLS-1$

    private static final String EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$
    private static final String BUNDLE_DEBUG_UI = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$
    private static final String LISTENER_VIEW_KEY = "tormozit.variableLabelEnhanceView"; //$NON-NLS-1$
    private static final String INSPECTOR_LISTENER_HOOK_KEY = "tormozit.inspectorLabelEnhanceListener"; //$NON-NLS-1$
    private static final String INSPECTOR_VIEWER_FLAG_KEY = "tormozit.inspectorLabelEnhanceViewer"; //$NON-NLS-1$
    private static final String INSPECTOR_TREE_KEY = "tormozit.inspectorLabelEnhanceTree"; //$NON-NLS-1$
    private static final String INSPECTOR_VISIBLE_SYNC_KEY = "tormozit.inspectorVisibleSync"; //$NON-NLS-1$
    private static final int[] INSPECTOR_VISIBLE_SYNC_DELAYS_MS = { 150, 500, 1000, 2000 };
    private static final String STRING_RELABEL_DEDUPE_KEY = "tormozit.stringRelabelDedupe"; //$NON-NLS-1$
    private static final String COL_VAR_VALUE =
        "org.eclipse.debug.ui.VARIALBE_COLUMN_PRESENTATION.COL_VAR_VALUE"; //$NON-NLS-1$

    private static final Set<ILabelUpdate> WRAPPED_LABEL_UPDATES =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final String LABEL_PROVIDER_CLASS =
        "org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider"; //$NON-NLS-1$

    private static volatile Object patchedFactory;
    private static volatile Object stockLabelProvider;
    private static volatile boolean partListenersInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            scheduleInstall(0);
            installDebugTreeLabelListeners();
            installDebugLaunchRepatch();
        });
    }

    private static void installDebugLaunchRepatch()
    {
        try
        {
            DebugPlugin.getDefault().getLaunchManager().addLaunchListener(new ILaunchListener()
            {
                @Override
                public void launchAdded(org.eclipse.debug.core.ILaunch launch)
                {
                    scheduleInstall(0);
                }

                @Override
                public void launchChanged(org.eclipse.debug.core.ILaunch launch) { }

                @Override
                public void launchRemoved(org.eclipse.debug.core.ILaunch launch) { }
            });
        }
        catch (Exception ignored)
        {
            // отладчик ещё не инициализирован
        }
    }

    private static void scheduleInstall(int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
        {
            restoreStockLabelProvider();
            return;
        }
        if (tryInstallLabelProvider())
            return;
        if (attempt < 48)
            display.timerExec(attempt < 8 ? 50 : 100, () -> scheduleInstall(attempt + 1));
    }

    static boolean tryInstallLabelProvider()
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return false;
        int guicePatches = patchViaGuiceInjector();
        if (guicePatches > 0)
            return true;
        int managerPatches = patchAllLabelProviderHostsFromManager();
        if (managerPatches > 0)
            return true;
        if (hasPatchedLabelProviderHost())
            return true;
        return false;
    }

    private static int patchViaGuiceInjector()
    {
        try
        {
            Class<?> pluginClass = loadDebugUiClass(
                "com._1c.g5.v8.dt.internal.debug.ui.DebugUiPlugin"); //$NON-NLS-1$
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin == null)
                return 0;
            Method getInjectorMethod = pluginClass.getDeclaredMethod("getInjector"); //$NON-NLS-1$
            getInjectorMethod.setAccessible(true);
            Injector injector = (Injector) getInjectorMethod.invoke(plugin);
            if (injector == null)
                return 0;
            Class<?> factoryClass = loadDebugUiClass(
                "com._1c.g5.v8.dt.internal.debug.ui.BslDebugElementAdapterFactory"); //$NON-NLS-1$
            @SuppressWarnings("unchecked") //$NON-NLS-1$
            Object factory = injector.getInstance((Class<Object>) factoryClass);
            if (factory == null)
                return 0;
            return patchLabelProviderHost(factory) ? 1 : 0;
        }
        catch (Exception e)
        {
             //$NON-NLS-1$ //$NON-NLS-2$
            return 0;
        }
    }

    private static Class<?> loadDebugUiClass(String className) throws ClassNotFoundException
    {
        Bundle bundle = Platform.getBundle(BUNDLE_DEBUG_UI);
        if (bundle == null)
            throw new ClassNotFoundException(className + " (bundle missing)"); //$NON-NLS-1$
        if (bundle.getState() != Bundle.ACTIVE)
        {
            try
            {
                bundle.start(Bundle.START_TRANSIENT);
            }
            catch (Exception ignored)
            {
                // activation optional
            }
        }
        return bundle.loadClass(className);
    }

    private static void forceLoadLabelAdapter(Object sample)
    {
        if (sample == null)
            return;
        try
        {
            Platform.getAdapterManager().loadAdapter(sample, LABEL_PROVIDER_CLASS);
        }
        catch (Exception ignored)
        {
            // адаптер ещё не зарегистрирован
        }
    }

    private static int patchForDebugView(AbstractDebugView debugView)
    {
        Object sample = findAdapterSample(debugView);
        forceLoadLabelAdapter(sample);
        int patched = patchAllLabelProviderHostsFromManager();
        if (patched > 0)
            return patched;
        patched = patchViaGuiceInjector();
        if (patched > 0)
            return patched;
        return patchLabelProviderHostByRuntimeProvider(sample) ? 1 : 0;
    }

    private static boolean patchLabelProviderHostByRuntimeProvider(Object sample)
    {
        if (sample == null)
            return false;
        IElementLabelProvider runtime = Platform.getAdapterManager().getAdapter(sample,
            IElementLabelProvider.class);
        if (runtime == null || runtime instanceof VariableLabelEnhancer)
            return runtime instanceof VariableLabelEnhancer;
        try
        {
            Class<?> managerClass = Class.forName("org.eclipse.core.internal.runtime.AdapterManager"); //$NON-NLS-1$
            Object manager = managerClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            Object factoriesMap = managerClass.getMethod("getFactories").invoke(manager); //$NON-NLS-1$
            if (!(factoriesMap instanceof Map<?, ?> allFactories))
                return false;
            for (Object listObj : allFactories.values())
            {
                if (!(listObj instanceof List<?> list))
                    continue;
                for (Object factory : list)
                {
                    if (!hasLabelProviderHostField(factory))
                        continue;
                    Object current = Global.getField(factory, FIELD_VARIABLE_LABEL_PROVIDER);
                    if (current == runtime)
                        return patchLabelProviderHost(factory);
                }
            }
        }
        catch (Exception ignored)
        {
            // identity match недоступен
        }
        return false;
    }

    private static Object findAdapterSample(AbstractDebugView debugView)
    {
        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return null;
        if (viewer.getSelection() instanceof IStructuredSelection selection && !selection.isEmpty())
        {
            Object first = selection.getFirstElement();
            if (first instanceof IBslVariable variable)
                return variable;
        }
        Object input = viewer.getInput();
        if (input instanceof IBslVariable variable)
            return variable;
        if (input instanceof IBslStackFrame frame)
        {
            try
            {
                IBslVariable[] vars = frame.getVariables();
                if (vars != null && vars.length > 0)
                    return vars[0];
            }
            catch (DebugException ignored)
            {
                // кадр ещё без переменных
            }
        }
        return input;
    }

    private static int patchAllLabelProviderHostsFromManager()
    {
        int patched = 0;
        try
        {
            Class<?> managerClass = Class.forName("org.eclipse.core.internal.runtime.AdapterManager"); //$NON-NLS-1$
            Object manager = managerClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            Object factoriesMap = managerClass.getMethod("getFactories").invoke(manager); //$NON-NLS-1$
            if (!(factoriesMap instanceof Map<?, ?> allFactories))
            {
                 //$NON-NLS-1$ //$NON-NLS-2$
                return 0;
            }
             //$NON-NLS-1$
            for (Object listObj : allFactories.values())
            {
                if (!(listObj instanceof List<?> list))
                    continue;
                for (Object factory : list)
                {
                    if (patchLabelProviderHost(factory))
                        patched++;
                }
            }
        }
        catch (Exception e)
        {
        }
        return patched;
    }

    private static boolean hasPatchedLabelProviderHost()
    {
        try
        {
            Class<?> managerClass = Class.forName("org.eclipse.core.internal.runtime.AdapterManager"); //$NON-NLS-1$
            Object manager = managerClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            Object factoriesMap = managerClass.getMethod("getFactories").invoke(manager); //$NON-NLS-1$
            if (!(factoriesMap instanceof Map<?, ?> allFactories))
                return false;
            for (Object listObj : allFactories.values())
            {
                if (!(listObj instanceof List<?> list))
                    continue;
                for (Object factory : list)
                {
                    if (factory == null || !hasLabelProviderHostField(factory))
                        continue;
                    Object current = Global.getField(factory, FIELD_VARIABLE_LABEL_PROVIDER);
                    if (current instanceof VariableLabelEnhancer)
                        return true;
                }
            }
        }
        catch (Exception ignored)
        {
            // manager ещё не готов
        }
        return false;
    }

    private static boolean patchLabelProviderHost(Object factory)
    {
        if (factory == null || !hasLabelProviderHostField(factory))
            return false;
        Object current = Global.getField(factory, FIELD_VARIABLE_LABEL_PROVIDER);
        if (current instanceof VariableLabelEnhancer)
        {
            patchedFactory = factory;
            return true;
        }
        if (!(current instanceof IElementLabelProvider delegate))
        {
            return false;
        }
        if (stockLabelProvider == null)
            stockLabelProvider = current;
        VariableLabelEnhancer enhancer = new VariableLabelEnhancer(delegate);
        boolean ok = Global.setFieldForce(factory, FIELD_VARIABLE_LABEL_PROVIDER, enhancer);
        if (ok)
        {
            patchedFactory = factory;
            patchWatchExpressionLabelProvider(factory);
        }
        return ok;
    }

    private static boolean hasLabelProviderHostField(Object factory)
    {
        if (factory.getClass().getName().contains(FACTORY_MARKER))
            return true;
        try
        {
            factory.getClass().getDeclaredField(FIELD_VARIABLE_LABEL_PROVIDER);
            return true;
        }
        catch (NoSuchFieldException e)
        {
            return false;
        }
    }

    private static void patchWatchExpressionLabelProvider(Object factory)
    {
        Object current = Global.getField(factory, FIELD_WATCH_EXPRESSION_LABEL_PROVIDER);
        if (current instanceof VariableLabelEnhancer)
            return;
        if (!(current instanceof IElementLabelProvider delegate))
            return;
        Global.setFieldForce(factory, FIELD_WATCH_EXPRESSION_LABEL_PROVIDER, new VariableLabelEnhancer(delegate));
    }

    private static void restoreStockLabelProvider()
    {
        Object factory = patchedFactory;
        if (factory == null)
        {
            patchAllLabelProviderHostsFromManager();
            factory = patchedFactory;
        }
        if (factory == null)
            return;
        Object current = Global.getField(factory, FIELD_VARIABLE_LABEL_PROVIDER);
        if (!(current instanceof VariableLabelEnhancer))
            return;
        if (stockLabelProvider != null)
            Global.setFieldForce(factory, FIELD_VARIABLE_LABEL_PROVIDER, stockLabelProvider);
        patchedFactory = null;
    }

    private static void installDebugTreeLabelListeners()
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        if (!partListenersInstalled)
        {
            partListenersInstalled = true;
            workbench.addWorkbenchListener(new org.eclipse.ui.IWorkbenchListener()
            {
                @Override
                public boolean preShutdown(IWorkbench workbench1, boolean forced)
                {
                    return true;
                }

                @Override
                public void postShutdown(IWorkbench workbench1)
                {
                    partListenersInstalled = false;
                }
            });
            workbench.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window)
                {
                    hookWindow(window);
                }

                @Override
                public void windowClosed(IWorkbenchWindow window) { }

                @Override
                public void windowActivated(IWorkbenchWindow window) { }

                @Override
                public void windowDeactivated(IWorkbenchWindow window) { }
            });
        }
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                tryHookDebugView(ref.getPart(false));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                tryHookDebugView(ref.getPart(false));
            }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IViewReference ref : page.getViewReferences())
            {
                if (ref == null)
                    continue;
                tryHookDebugView(ref.getPart(false));
            }
        }
    }

    private static void tryHookDebugView(IWorkbenchPart part)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (!(part instanceof AbstractDebugView debugView))
            return;
        if (!isVariableTreeView(debugView))
            return;
        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return;
        if (viewer.getData(LISTENER_VIEW_KEY) == null)
            viewer.setData(LISTENER_VIEW_KEY, debugView);
        patchForDebugView(debugView);
        patchTreePendingProviders(viewer);
    }

    private static boolean isVariableTreeView(AbstractDebugView view)
    {
        String id = view.getViewSite() != null ? view.getViewSite().getId() : null;
        return EXPRESSIONS_VIEW_ID.equals(id) || IDebugUIConstants.ID_VARIABLE_VIEW.equals(id);
    }

    private static boolean isComfortVariablesViewer(Viewer viewer)
    {
        if (viewer == null)
            return false;
        Object viewKey = viewer.getData(LISTENER_VIEW_KEY);
        return viewKey instanceof AbstractDebugView debugView && isVariableTreeView(debugView);
    }

    private static boolean isComfortEnhanceViewer(Viewer viewer)
    {
        if (viewer == null)
            return false;
        if (Boolean.TRUE.equals(viewer.getData(INSPECTOR_VIEWER_FLAG_KEY)))
            return true;
        return isComfortVariablesViewer(viewer);
    }

    /** Подключить префикс {@code [N]} к TreeModelViewer инспектора (отдельно от «Переменные»). */
    static void hookInspectorTreeViewer(Viewer viewer, Tree tree)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || viewer == null)
            return;
        if (tree != null && !tree.isDisposed())
            viewer.setData(INSPECTOR_TREE_KEY, tree);
        if (viewer.getData(INSPECTOR_LISTENER_HOOK_KEY) != null)
        {
            scheduleVisibleInspectorSync(viewer);
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> syncVisibleInspectorLabelsFromTree(viewer));
            return;
        }
        Object listener = createInspectorLabelUpdateListener();
        if (listener == null)
            return;
        try
        {
            Global.invoke(viewer, "addLabelUpdateListener", listener); //$NON-NLS-1$
            viewer.setData(INSPECTOR_LISTENER_HOOK_KEY, listener);
            viewer.setData(INSPECTOR_VIEWER_FLAG_KEY, Boolean.TRUE);
            patchTreePendingProviders(viewer);
            tryInstallLabelProvider();
            int colCount = tree != null && !tree.isDisposed() ? tree.getColumnCount() : -1;
             //$NON-NLS-1$ //$NON-NLS-2$
            scheduleVisibleInspectorSync(viewer);
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> syncVisibleInspectorLabelsFromTree(viewer));
        }
        catch (Exception e)
        {
        }
    }

    /** Синхронизация префикса только по уже созданным SWT-строкам — без обхода модели viewer. */
    private static void scheduleVisibleInspectorSync(Viewer viewer)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || viewer == null)
            return;
        if (Boolean.TRUE.equals(viewer.getData(INSPECTOR_VISIBLE_SYNC_KEY)))
            return;
        viewer.setData(INSPECTOR_VISIBLE_SYNC_KEY, Boolean.TRUE);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        for (int delay : INSPECTOR_VISIBLE_SYNC_DELAYS_MS)
        {
            display.timerExec(delay, () ->
            {
                if (!Boolean.TRUE.equals(viewer.getData(INSPECTOR_VIEWER_FLAG_KEY)))
                    return;
                syncVisibleInspectorLabelsFromTree(viewer);
            });
        }
        int clearDelay = INSPECTOR_VISIBLE_SYNC_DELAYS_MS[INSPECTOR_VISIBLE_SYNC_DELAYS_MS.length - 1] + 100;
        display.timerExec(clearDelay, () -> viewer.setData(INSPECTOR_VISIBLE_SYNC_KEY, null));
    }

    private static void syncVisibleInspectorLabelsFromTree(Viewer viewer)
    {
        Object treeObj = viewer.getData(INSPECTOR_TREE_KEY);
        if (!(treeObj instanceof Tree tree) || tree.isDisposed())
            return;
        int valueCol = resolveInspectorTreeValueColumn(tree);
        if (valueCol < 0)
            return;
        syncVisibleInspectorLabelsFromItems(viewer, tree.getItems(), valueCol);
    }

    private static void syncVisibleInspectorLabelsFromItems(Viewer viewer, TreeItem[] items, int valueCol)
    {
        if (items == null)
            return;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            tryFormatVisibleInspectorItem(item, valueCol);
            if (item.getExpanded() && item.getItemCount() > 0)
                syncVisibleInspectorLabelsFromItems(viewer, item.getItems(), valueCol);
        }
    }

    private static void tryFormatVisibleInspectorItem(TreeItem item, int valueCol)
    {
        Object data = item.getData();
        if (!(data instanceof IBslVariable variable))
            return;
        IValue value = variable.getValue();
        if (!(value instanceof IBslValue bsl) || !DebugStringValueFormat.isStringValue(bsl))
            return;
        String displayed = item.getText(valueCol);
        if (displayed == null)
            displayed = ""; //$NON-NLS-1$
        DebugStringValueFormat.tryForceStringDetail(variable, bsl,
            DebugStringValueFormat.looksLikeEdtTruncated(displayed));
        String formatted = DebugStringValueFormat.formatForStockTree(displayed, variable, bsl);
        if (formatted.equals(displayed))
            return;
        item.setText(valueCol, formatted);
    }

    private static Object createInspectorLabelUpdateListener()
    {
        try
        {
            Class<?> iface = Class.forName(
                "org.eclipse.debug.internal.ui.viewers.model.ILabelUpdateListener"); //$NON-NLS-1$
            return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[] { iface },
                (proxy, method, args) ->
                {
                    if (args != null && args.length == 1 && args[0] instanceof ILabelUpdate update)
                    {
                        if ("labelUpdateComplete".equals(method.getName()) //$NON-NLS-1$
                            && update.getElement() instanceof IBslVariable)
                            enhanceAndApplyStockValueColumn(update);
                    }
                    return defaultProxyReturn(method.getReturnType());
                });
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Object defaultProxyReturn(Class<?> returnType)
    {
        if (returnType == null || returnType == void.class || returnType == Void.class)
            return null;
        if (returnType == boolean.class || returnType == Boolean.class)
            return Boolean.FALSE;
        return null;
    }

    private static void enhanceAndApplyStockValueColumn(ILabelUpdate update)
    {
        enhanceAndApplyStockValueColumn(update, 0);
    }

    private static void enhanceAndApplyStockValueColumn(ILabelUpdate update, int attempt)
    {
        String[] columns = update.getColumnIds();
        Object labelsBeforeObj = Global.getField(update, "fLabels"); //$NON-NLS-1$
        if (!(labelsBeforeObj instanceof String[] labelsBefore) || columns == null)
            return;
        int valueIndex = -1;
        for (int i = 0; i < columns.length && i < labelsBefore.length; i++)
        {
            if (COL_VAR_VALUE.equals(columns[i]))
            {
                valueIndex = i;
                break;
            }
        }
        if (valueIndex < 0)
            return;
        String before = labelsBefore[valueIndex] != null ? labelsBefore[valueIndex] : ""; //$NON-NLS-1$
        enhanceStockValueColumn(update);
        Object labelsAfterObj = Global.getField(update, "fLabels"); //$NON-NLS-1$
        if (!(labelsAfterObj instanceof String[] labelsAfter) || valueIndex >= labelsAfter.length)
            return;
        String after = labelsAfter[valueIndex] != null ? labelsAfter[valueIndex] : ""; //$NON-NLS-1$
        if (!before.equals(after))
        {
            applyLabelUpdateToViewer(update);
            return;
        }
        if (shouldRetryStringRelabel(update, valueIndex, after) && attempt < 5)
            scheduleStringRelabel(update, attempt);
    }

    private static boolean shouldRetryStringRelabel(ILabelUpdate update, int valueIndex, String displayed)
    {
        Object element = update.getElement();
        if (!(element instanceof IBslVariable variable))
            return false;
        IValue value = variable.getValue();
        if (!(value instanceof IBslValue bsl) || !DebugStringValueFormat.isStringValue(bsl))
            return false;
        if (!DebugStringValueFormat.looksLikeEdtTruncated(displayed))
            return false;
        return DebugStringValueFormat.resolveFullStringCharLength(bsl) < 0;
    }

    private static void scheduleStringRelabel(ILabelUpdate update, int attempt)
    {
        if (attempt >= 5 || update.isCanceled())
            return;
        Object element = update.getElement();
        if (!(element instanceof IBslVariable variable))
            return;
        IValue value = variable.getValue();
        if (!(value instanceof IBslValue bsl))
            return;
        int valueIndex = indexOfColumn(update.getColumnIds(), COL_VAR_VALUE);
        Object provider = Global.getField(update, "fProvider"); //$NON-NLS-1$
        Object viewerObj = provider != null ? Global.getField(provider, "fViewer") : null; //$NON-NLS-1$
        Viewer viewer = viewerObj instanceof Viewer v ? v : null;
        String dedupeKey = STRING_RELABEL_DEDUPE_KEY + ':' + System.identityHashCode(variable); //$NON-NLS-1$
        if (attempt == 0 && viewer != null && Boolean.TRUE.equals(viewer.getData(dedupeKey)))
            return;
        if (attempt == 0 && viewer != null)
            viewer.setData(dedupeKey, Boolean.TRUE);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 100 : attempt == 1 ? 300 : attempt == 2 ? 700 : 1500;
        final int nextAttempt = attempt + 1;
        display.timerExec(delay, () ->
        {
            if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || update.isCanceled())
                return;
            DebugStringValueFormat.tryForceStringDetail(variable, bsl,
                DebugStringValueFormat.looksLikeEdtTruncated(
                    afterLabelText(update, valueIndex)));
            String displayed = afterLabelText(update, valueIndex);
            String formatted = DebugStringValueFormat.formatForStockTree(displayed, variable, bsl);
            if (!formatted.equals(displayed) && viewer != null)
                paintStockValueLabel(viewer, update.getElementPath(), formatted, 0);
            if (shouldRetryStringRelabel(update, valueIndex, formatted) && nextAttempt < 5)
                scheduleStringRelabel(update, nextAttempt);
            else if (nextAttempt >= 5 && viewer != null)
                viewer.setData(dedupeKey, null);
        });
    }

    private static String afterLabelText(ILabelUpdate update, int valueIndex)
    {
        Object labelsObj = Global.getField(update, "fLabels"); //$NON-NLS-1$
        if (!(labelsObj instanceof String[] labels) || valueIndex < 0 || valueIndex >= labels.length)
            return ""; //$NON-NLS-1$
        return labels[valueIndex] != null ? labels[valueIndex] : ""; //$NON-NLS-1$
    }

    private static void applyLabelUpdateToViewer(ILabelUpdate update)
    {
        Object provider = Global.getField(update, "fProvider"); //$NON-NLS-1$
        if (provider == null)
            return;
        TreePath path = update.getElementPath();
        if (path == null)
            return;
        Object labelsObj = Global.getField(update, "fLabels"); //$NON-NLS-1$
        if (!(labelsObj instanceof String[] labels))
            return;
        Object viewerObj = Global.getField(provider, "fViewer"); //$NON-NLS-1$
        Viewer viewer = viewerObj instanceof Viewer v ? v : null;
        if (viewer == null || !Boolean.TRUE.equals(viewer.getData(INSPECTOR_VIEWER_FLAG_KEY)))
            return;
        Object imagesObj = Global.getField(update, "fImageDescriptors"); //$NON-NLS-1$
        Object fontsObj = Global.getField(update, "fFontDatas"); //$NON-NLS-1$
        Object fgObj = Global.getField(update, "fForegrounds"); //$NON-NLS-1$
        Object bgObj = Global.getField(update, "fBackgrounds"); //$NON-NLS-1$
        Object checkedObj = Global.getField(update, "fChecked"); //$NON-NLS-1$
        Object grayedObj = Global.getField(update, "fGrayed"); //$NON-NLS-1$
        Object numColsObj = Global.getField(update, "fNumColumns"); //$NON-NLS-1$
        int numCols = numColsObj instanceof Integer integer ? integer.intValue() : labels.length; //$NON-NLS-1$
        boolean checked = checkedObj instanceof Boolean b && b.booleanValue();
        boolean grayed = grayedObj instanceof Boolean b && b.booleanValue();
        try
        {
            Global.invoke(provider, "setElementData", path, numCols, labels, imagesObj, fontsObj, fgObj, bgObj, //$NON-NLS-1$
                checked, grayed);
            int valueIndex = indexOfColumn(update.getColumnIds(), COL_VAR_VALUE);
            paintInspectorValueLabel(viewer, provider, path, labels, valueIndex);
        }
        catch (Exception e)
        {
        }
    }

    private static void paintStockValueLabel(Viewer viewer, TreePath path, String text, int paintAttempt)
    {
        if (viewer == null || path == null || text == null)
            return;
        if (Boolean.TRUE.equals(viewer.getData(INSPECTOR_VIEWER_FLAG_KEY)))
            return;
        Object treeObj;
        try
        {
            treeObj = Global.invoke(viewer, "getTree"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return;
        }
        if (!(treeObj instanceof Tree tree) || tree.isDisposed())
            return;
        TreeItem item = findTreeItemForPath(tree, path);
        if (item == null || item.isDisposed())
        {
            Object segment = path.getLastSegment();
            item = findTreeItemForData(tree.getItems(), segment);
        }
        if (item == null || item.isDisposed())
        {
            scheduleStockPaintRetry(viewer, path, text, paintAttempt);
            return;
        }
        int treeCol = resolveInspectorTreeValueColumn(tree);
        if (treeCol < 0)
            return;
        item.setText(treeCol, text);
        tree.redraw();
    }

    private static void scheduleStockPaintRetry(Viewer viewer, TreePath path, String text, int paintAttempt)
    {
        if (paintAttempt >= 3 || viewer == null || path == null || text == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = paintAttempt == 0 ? 80 : paintAttempt == 1 ? 200 : 400;
        display.timerExec(delay, () ->
        {
            if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
                return;
            paintStockValueLabel(viewer, path, text, paintAttempt + 1);
        });
    }

    private static void paintInspectorValueLabel(Viewer viewer, Object provider, TreePath path, String[] labels,
        int valueIndex)
    {
        paintInspectorValueLabel(viewer, provider, path, labels, valueIndex, 0);
    }

    private static void paintInspectorValueLabel(Viewer viewer, Object provider, TreePath path, String[] labels,
        int valueIndex, int paintAttempt)
    {
        if (viewer == null || path == null || labels == null || valueIndex < 0 || valueIndex >= labels.length)
            return;
        if (!Boolean.TRUE.equals(viewer.getData(INSPECTOR_VIEWER_FLAG_KEY)))
            return;
        String text = labels[valueIndex];
        try
        {
            if (provider != null)
                Global.invoke(provider, "update", path); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // repaint optional
        }
        Object treeObj = viewer.getData(INSPECTOR_TREE_KEY);
        if (!(treeObj instanceof Tree tree) || tree.isDisposed())
        {
             //$NON-NLS-1$
            return;
        }
        TreeItem item = findTreeItemForPath(tree, path);
        if (item == null || item.isDisposed())
        {
            Object segment = path.getLastSegment();
            item = findTreeItemForData(tree.getItems(), segment);
        }
        if (item == null || item.isDisposed())
        {
             //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            scheduleInspectorPaintRetry(viewer, provider, path, labels, valueIndex, paintAttempt);
            return;
        }
        int treeCol = resolveInspectorTreeValueColumn(tree);
        if (treeCol < 0)
            return;
        item.setText(treeCol, text);
        tree.redraw();
         //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void scheduleInspectorPaintRetry(Viewer viewer, Object provider, TreePath path, String[] labels,
        int valueIndex, int paintAttempt)
    {
        if (paintAttempt >= 3 || viewer == null || path == null || labels == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = paintAttempt == 0 ? 80 : paintAttempt == 1 ? 200 : 400;
        display.timerExec(delay, () ->
        {
            if (!Boolean.TRUE.equals(viewer.getData(INSPECTOR_VIEWER_FLAG_KEY)))
                return;
            paintInspectorValueLabel(viewer, provider, path, labels, valueIndex, paintAttempt + 1);
        });
    }

    private static int resolveInspectorTreeValueColumn(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return -1;
        int columns = tree.getColumnCount();
        for (int i = 0; i < columns; i++)
        {
            org.eclipse.swt.widgets.TreeColumn column = tree.getColumn(i);
            if (column == null || column.isDisposed())
                continue;
            String header = column.getText();
            if (header == null)
                continue;
            header = header.trim();
            if ("Значение".equalsIgnoreCase(header) || "Value".equalsIgnoreCase(header)) //$NON-NLS-1$ //$NON-NLS-2$
                return i;
        }
        return columns > 2 ? 2 : columns > 1 ? 1 : 0;
    }

    private static TreeItem findTreeItemForPath(Tree tree, TreePath path)
    {
        if (tree == null || tree.isDisposed() || path == null || path.getSegmentCount() == 0)
            return null;
        TreeItem[] level = tree.getItems();
        TreeItem current = null;
        for (int i = 0; i < path.getSegmentCount(); i++)
        {
            Object segment = path.getSegment(i);
            current = findTreeItemAmongSiblings(level, segment);
            if (current == null)
                return null;
            level = current.getItems();
        }
        return current;
    }

    private static TreeItem findTreeItemAmongSiblings(TreeItem[] items, Object data)
    {
        if (items == null || data == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (data.equals(item.getData()))
                return item;
        }
        return null;
    }

    private static TreeItem findTreeItemForData(TreeItem[] items, Object data)
    {
        if (items == null || data == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (data.equals(item.getData()))
                return item;
            TreeItem nested = findTreeItemForData(item.getItems(), data);
            if (nested != null)
                return nested;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static int patchTreePendingProviders(Viewer viewer)
    {
        Object labelProvider = null;
        if (viewer instanceof ColumnViewer columnViewer)
            labelProvider = columnViewer.getLabelProvider();
        if (labelProvider == null)
            return 0;
        Object pendingObj = Global.getField(labelProvider, "fPendingUpdates"); //$NON-NLS-1$
        if (!(pendingObj instanceof Map<?, ?>))
            return 0;
        Map<Object, Object> pending = (Map<Object, Object>) pendingObj;
        int replaced = 0;
        List<Object> keys = new ArrayList<>(pending.keySet());
        for (Object key : keys)
        {
            if (key instanceof VariableLabelEnhancer)
                continue;
            if (!(key instanceof IElementLabelProvider delegate))
                continue;
            String className = key.getClass().getName();
            if (!className.contains("BslVariableLabelProvider") //$NON-NLS-1$
                && !className.contains("BslWatchExpressionLabelProvider")) //$NON-NLS-1$
                continue;
            Object value = pending.remove(key);
            VariableLabelEnhancer enhancer = new VariableLabelEnhancer(delegate);
            pending.put(enhancer, value);
            replaced++;
        }
        return replaced;
    }

    private static boolean needsStringPrefixEnhance(ILabelUpdate update)
    {
        if (update == null || update.isCanceled())
            return false;
        if (!(update.getElement() instanceof IBslVariable variable))
            return false;
        IValue value = variable.getValue();
        if (!(value instanceof IBslValue bsl) || !DebugStringValueFormat.isStringValue(bsl))
            return false;
        String[] columns = update.getColumnIds();
        if (columns == null)
            return false;
        for (String column : columns)
        {
            if (COL_VAR_VALUE.equals(column))
                return true;
        }
        return false;
    }

    private static void enhanceStockValueColumn(ILabelUpdate update)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled() || update.isCanceled())
            return;
        Object element = update.getElement();
        if (!(element instanceof IBslVariable variable))
            return;
        IValue value = variable.getValue();
        if (!(value instanceof IBslValue bsl) || !DebugStringValueFormat.isStringValue(bsl))
            return;
        String[] columns = update.getColumnIds();
        if (columns == null)
            return;
        Object labelsObj = Global.getField(update, "fLabels"); //$NON-NLS-1$
        if (!(labelsObj instanceof String[] labels))
            return;
        for (int i = 0; i < columns.length && i < labels.length; i++)
        {
            if (!COL_VAR_VALUE.equals(columns[i]))
                continue;
            String text = labels[i] != null ? labels[i] : ""; //$NON-NLS-1$
            if (DebugStringValueFormat.looksLikeEdtTruncated(text))
                DebugStringValueFormat.tryForceStringDetail(variable, bsl, true);
            String formatted = DebugStringValueFormat.formatForStockTreeQuick(text, variable, bsl);
            if (!text.equals(formatted))
                update.setLabel(formatted, i);
            if (DebugStringValueFormat.looksLikeEdtTruncated(text)
                && DebugStringValueFormat.resolveFullStringCharLength(bsl) < 0)
                scheduleStringRelabel(update, 0);
            return;
        }
    }

    private static int indexOfColumn(String[] columns, String columnId)
    {
        if (columns == null)
            return -1;
        for (int i = 0; i < columns.length; i++)
        {
            if (columnId.equals(columns[i]))
                return i;
        }
        return -1;
    }

    private static ILabelUpdate[] wrapLabelUpdates(ILabelUpdate[] updates)
    {
        if (updates == null || updates.length == 0)
            return updates;
        ILabelUpdate[] wrapped = new ILabelUpdate[updates.length];
        for (int i = 0; i < updates.length; i++)
            wrapped[i] = wrapLabelUpdate(updates[i]);
        return wrapped;
    }

    private static ILabelUpdate wrapLabelUpdate(ILabelUpdate update)
    {
        if (update == null || WRAPPED_LABEL_UPDATES.contains(update))
            return update;
        if (!needsStringPrefixEnhance(update))
            return update;
        List<Class<?>> interfaces = new ArrayList<>();
        interfaces.add(ILabelUpdate.class);
        try
        {
            Class<?> checkUpdate = Class.forName(
                "org.eclipse.debug.internal.ui.viewers.model.provisional.ICheckUpdate"); //$NON-NLS-1$
            if (checkUpdate.isInstance(update))
                interfaces.add(checkUpdate);
        }
        catch (ClassNotFoundException ignored)
        {
            // optional check column
        }
        WRAPPED_LABEL_UPDATES.add(update);
        ClassLoader loader = update.getClass().getClassLoader();
        return (ILabelUpdate) java.lang.reflect.Proxy.newProxyInstance(loader,
            interfaces.toArray(Class<?>[]::new),
            (proxy, method, args) ->
            {
                if ("done".equals(method.getName()) && (args == null || args.length == 0) //$NON-NLS-1$
                    && ComfortSettings.isImproveDebuggerWindowsEnabled())
                {
                    enhanceStockValueColumn(update);
                }
                return method.invoke(update, args);
            });
    }

    private static final class VariableLabelEnhancer implements IElementLabelProvider
    {
        private final IElementLabelProvider delegate;

        VariableLabelEnhancer(IElementLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void update(ILabelUpdate[] updates)
        {
            ILabelUpdate[] batch = ComfortSettings.isImproveDebuggerWindowsEnabled()
                ? wrapLabelUpdates(updates) : updates;
            delegate.update(batch);
        }
    }
}
