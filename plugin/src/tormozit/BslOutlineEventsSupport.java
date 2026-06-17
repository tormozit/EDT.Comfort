package tormozit;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;
import com._1c.g5.v8.dt.bsl.common.IBslModuleTextInsertInfo;
import com._1c.g5.v8.dt.bsl.common.IModuleExtensionService;
import com._1c.g5.v8.dt.bsl.common.IModuleExtensionServiceProvider;
import com._1c.g5.v8.dt.bsl.contextdef.IBslModuleContextDefService;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.bsl.ui.event.BslModuleEventData;
import com._1c.g5.v8.dt.bsl.ui.event.BslModuleRegionsInfoServiceProvider;
import com._1c.g5.v8.dt.bsl.ui.event.IBslModuleEventsLookup;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.google.common.base.Predicates;
import com._1c.g5.v8.dt.bsl.ui.event.ProcedureDirective;
import com._1c.g5.v8.dt.bsl.ui.event.ProcedureParameters;
import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.ParamSet;

/**
 * Быстрая и полная схема BSL (только при «Заменять фильтры в списках»):
 * события видны по умолчанию в Quick Outline, подписи обработчиков подписок
 * {@code ИмяМодуля.ИмяОбработчика} в дереве; двойной клик / Enter по событию без
 * обработчика создаёт обработчик.
 */
public final class BslOutlineEventsSupport
{
    private static final String TAG = "BslOutlineEvents"; //$NON-NLS-1$
    private static final String CREATE_HANDLER_KEY = "tormozit.bslOutlineCreateHandler"; //$NON-NLS-1$
    private static final String KEY_FILTER_KEY = "tormozit.bslOutlineKeyFilter"; //$NON-NLS-1$
    private static final String BSL_QUICK_OUTLINE_POPUP =
            "com._1c.g5.v8.dt.bsl.ui.outline.BslQuickOutlinePopup"; //$NON-NLS-1$
    private static final String BSL_OUTLINE_LABEL_PROVIDER =
            "com._1c.g5.v8.dt.bsl.ui.outline.BslOutlineLabelProvider"; //$NON-NLS-1$
    private static final String CONTENT_OUTLINE_PART =
            "org.eclipse.ui.views.contentoutline.ContentOutline"; //$NON-NLS-1$
    private static final String MULTI_PAGE_DELEGATING_OUTLINE_PAGE =
            "com._1c.g5.v8.dt.common.ui.outline.MultiPageDelegatingContentOutlinePage"; //$NON-NLS-1$
    private static final String I_EVENT = "com._1c.g5.v8.dt.bsl.core.IEvent"; //$NON-NLS-1$
    private static final String I_EVENT_HANDLER = "com._1c.g5.v8.dt.bsl.core.IEventHandler"; //$NON-NLS-1$
    private static final String METHOD_IMPL = "com._1c.g5.v8.dt.internal.bsl.core.MethodImpl"; //$NON-NLS-1$
    private static final String I_EVENT_SECTION = "com._1c.g5.v8.dt.bsl.core.IEventSection"; //$NON-NLS-1$
    private static final String I_EXTENSION_ELEMENT = "com._1c.g5.v8.dt.bsl.core.IExtensionElement"; //$NON-NLS-1$
    private static final String HANDLY_ELEMENTS = "org.eclipse.handly.model.Elements"; //$NON-NLS-1$
    private static final String CREATE_EVENT_HANDLER_ACTION =
            "com._1c.g5.v8.dt.internal.bsl.ui.outline.action.BslOutlineCreateEventHandlerAction"; //$NON-NLS-1$
    private static final String COMMON_OUTLINE_PAGE =
            "org.eclipse.handly.ui.outline.ICommonOutlinePage"; //$NON-NLS-1$
    private static final String OUTLINE_MESSAGES =
            "com._1c.g5.v8.dt.bsl.ui.outline.Messages"; //$NON-NLS-1$
    private static final String DECORATED_LP_KEY = "tormozit.bslQuickOutlineDecoratedLp"; //$NON-NLS-1$
    private static final String SUBSCRIPTION_TREE_LABELS_KEY = "tormozit.bslOutlineSubscriptionTreeLabels"; //$NON-NLS-1$
    private static final String SUBSCRIPTION_TREE_LABELS_PATCHED_KEY =
            "tormozit.bslOutlineSubscriptionTreeLabelsPatched"; //$NON-NLS-1$
    private static final String TYPE_NAMES_USER_DATA = "typeNames"; //$NON-NLS-1$
    private static volatile boolean outlinePageListenerInstalled;
    private BslOutlineEventsSupport() {}
    static boolean isEnabled()
    {
        return ComfortSettings.isReplaceListFiltersEnabled();
    }

    /** Слушатель {@code ContentOutline} для полной схемы модуля. */
    public static void installOutlinePageListener()
    {
        if (outlinePageListenerInstalled)
            return;
        outlinePageListenerInstalled = true;
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookOutlineWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window)
                {
                    hookOutlineWindow(window);
                }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    private static void hookOutlineWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                scheduleOutlinePagePatch(ref, 0);
            }
            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                scheduleOutlinePagePatch(ref, 0);
            }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partVisible(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref)
            {
                scheduleOutlinePagePatch(ref, 0);
            }
        });
    }

    private static void scheduleOutlinePagePatch(IWorkbenchPartReference ref, int attempt)
    {
        if (ref == null || !isEnabled())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () -> {
            if (!isEnabled())
                return;
            IWorkbenchPart part = ref.getPart(false);
            if (part == null)
            {
                if (attempt < 8)
                    scheduleOutlinePagePatch(ref, attempt + 1);
                return;
            }
            if (!tryPatchOutlinePage(part) && attempt < 8)
                scheduleOutlinePagePatch(ref, attempt + 1);
        });
    }

    /** @return {@code true}, если патч применён */
    static boolean tryPatchOutlinePage(IWorkbenchPart part)
    {
        if (!isEnabled())
            return false;
        if (part == null || !CONTENT_OUTLINE_PART.equals(part.getClass().getName()))
            return false;
        Object outerPage = Global.invoke(part, "getCurrentPage"); //$NON-NLS-1$
        Object outlinePage = resolveContentOutlineBslPage(outerPage);
        if (outlinePage == null)
            return false;
        Object viewerObj = Global.invoke(outlinePage, "getTreeViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer))
            return false;
        TreeViewer viewer = (TreeViewer) viewerObj;
        installEventHandlerActivation(viewer, outlinePage, null);
        installOutlinePageSubscriptionTreeLabels(viewer, outlinePage);
        return true;
    }

    /** Индекс подписок полной схемы — на {@code Tree}, обновляется при смене модуля. */
    static SubscriptionFlatLabels getOutlinePageSubscriptionFlatLabels(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        Object data = tree.getData(SUBSCRIPTION_TREE_LABELS_KEY);
        return data instanceof SubscriptionFlatLabels ? (SubscriptionFlatLabels) data : null;
    }

    /** Подписи {@code ИмяМодуля.ИмяОбработчика} в дереве {@code ContentOutline}. */
    static void installOutlinePageSubscriptionTreeLabels(TreeViewer viewer, Object outlinePage)
    {
        if (!isEnabled() || viewer == null || outlinePage == null || !isBslOutlinePage(outlinePage))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        IBaseLabelProvider labelSource = resolveBslOutlinePageLabelSource(viewer);
        if (labelSource == null)
            return;
        if (!Boolean.TRUE.equals(tree.getData(SUBSCRIPTION_TREE_LABELS_PATCHED_KEY)))
        {
            if (!wrapOutlinePageSubscriptionTreeLabels(viewer, labelSource, tree))
                return;
            tree.setData(SUBSCRIPTION_TREE_LABELS_PATCHED_KEY, Boolean.TRUE);
        }
        SubscriptionFlatLabels labels = createSubscriptionFlatLabels();
        expandEventSection(viewer);
        enrichSubscriptionFlatLabels(labels, viewer, outlinePage, null, labelSource);
        tree.setData(SUBSCRIPTION_TREE_LABELS_KEY, labels);
        if (!viewer.getControl().isDisposed())
            viewer.refresh();
    }

    private static IBaseLabelProvider resolveBslOutlinePageLabelSource(TreeViewer viewer)
    {
        if (viewer == null)
            return null;
        IBaseLabelProvider rawLp = viewer.getLabelProvider();
        if (rawLp instanceof org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider)
        {
            IStyledLabelProvider inner = ((org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider) rawLp)
                    .getStyledStringProvider();
            if (inner instanceof IBaseLabelProvider)
                return (IBaseLabelProvider) inner;
        }
        return rawLp;
    }

    private static boolean wrapOutlinePageSubscriptionTreeLabels(TreeViewer viewer, IBaseLabelProvider labelSource,
            Tree tree)
    {
        IBaseLabelProvider rawLp = viewer.getLabelProvider();
        if (rawLp instanceof org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider)
        {
            org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider delegating =
                    (org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider) rawLp;
            IStyledLabelProvider inner = delegating.getStyledStringProvider();
            if (inner == null)
                return false;
            IStyledLabelProvider wrapped = new OutlinePageSubscriptionStyledLabelWrapper(inner, labelSource, tree);
            ILabelProvider imageSource = rawLp instanceof ILabelProvider ? (ILabelProvider) rawLp : null;
            SmartOutlineLabelProvider smartLp = new SmartOutlineLabelProvider(wrapped, imageSource);
            return injectDelegatingStyledProvider(delegating, smartLp);
        }
        return false;
    }

    private static boolean injectDelegatingStyledProvider(
            org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider provider, IStyledLabelProvider smartProvider)
    {
        Class<?> cls = provider.getClass();
        while (cls != null)
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (IStyledLabelProvider.class.isAssignableFrom(field.getType()))
                {
                    try
                    {
                        field.setAccessible(true);
                        field.set(provider, smartProvider);
                        return true;
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    /** {@code BslOutlineLabelProvider} из popup — для фильтра и иконок. */
    static IBaseLabelProvider getBslOutlineInnerLabelProvider(Object popup)
    {
        if (popup == null)
            return null;
        Object inner = Global.getField(popup, "labelProvider"); //$NON-NLS-1$
        return inner instanceof IBaseLabelProvider ? (IBaseLabelProvider) inner : null;
    }

    /**
     * Штатный decorated label provider popup + контекст декорации (оверлеи P/F).
     * Нельзя переиспользовать provider с дерева: при {@code setLabelProvider} Eclipse вызывает
     * {@code dispose()} на прежнем экземпляре.
     */
    static StyledCellLabelProvider rebuildBslQuickOutlineLabelProvider(Object popup)
    {
        if (popup == null || !BSL_QUICK_OUTLINE_POPUP.equals(popup.getClass().getName()))
            return null;
        Object viewerObj = Global.invoke(popup, "getTreeViewer"); //$NON-NLS-1$
        if (viewerObj instanceof TreeViewer)
        {
            Control tree = ((TreeViewer) viewerObj).getControl();
            if (tree != null && !tree.isDisposed())
            {
                Object cached = tree.getData(DECORATED_LP_KEY);
                if (cached instanceof StyledCellLabelProvider)
                {
                    return (StyledCellLabelProvider) cached;
                }
            }
        }
        try
        {
            Object lp = Global.invoke(popup, "getLabelProvider"); //$NON-NLS-1$
            if (!(lp instanceof StyledCellLabelProvider))
            {
                return null;
            }
            Global.invoke(popup, "setUpDecorationContextFor", lp); //$NON-NLS-1$
            if (viewerObj instanceof TreeViewer)
            {
                Control tree = ((TreeViewer) viewerObj).getControl();
                if (tree != null && !tree.isDisposed())
                    tree.setData(DECORATED_LP_KEY, lp);
            }
            return (StyledCellLabelProvider) lp;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "rebuild label provider", e); //$NON-NLS-1$
            return null;
        }
    }

    /** Показать секцию «События» при открытии Quick Outline. */
    static void showEventsInQuickOutline(Object popup, TreeViewer viewer)
    {
        if (!isEnabled() || popup == null || viewer == null || viewer.getControl().isDisposed())
            return;
        if (!BSL_QUICK_OUTLINE_POPUP.equals(popup.getClass().getName()))
            return;
        Object withEvents = Global.getField(popup, "customContentProvider"); //$NON-NLS-1$
        if (!(withEvents instanceof ITreeContentProvider))
            return;
        ITreeContentProvider eventsCp = (ITreeContentProvider) withEvents;
        Object current = viewer.getContentProvider();
        if (current instanceof SmartOutlineFlatContentProvider)
            ((SmartOutlineFlatContentProvider) current).setDelegate(eventsCp);
        else
            viewer.setContentProvider(eventsCp);
        expandEventSection(viewer);
        updateQuickOutlineInfoText(popup, true);
    }

    /** Enter в поле фильтра Quick Outline: создать обработчик выбранного события. */
    static boolean tryActivateSelectedEvent(TreeViewer viewer, Object contextHost)
    {
        if (!isEnabled() || viewer == null || contextHost == null)
            return false;
        return tryActivateFromViewer(viewer, resolveOutlineContext(viewer, contextHost), contextHost);
    }

    /** Двойной клик и Enter — создание обработчика события. */
    static void installEventHandlerActivation(TreeViewer viewer, Object contextHost, Control filterControl)
    {
        if (!isEnabled() || viewer == null || contextHost == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(CREATE_HANDLER_KEY)))
            return;
        tree.setData(CREATE_HANDLER_KEY, Boolean.TRUE);
        final Object outlineContext = resolveOutlineContext(viewer, contextHost);
        tree.addListener(SWT.DefaultSelection, event -> {
            if (tryActivateFromViewer(viewer, outlineContext, contextHost))
                event.doit = false;
        });
        installEnterKeyFilter(viewer, contextHost, outlineContext, filterControl, tree);
    }

    private static void installEnterKeyFilter(TreeViewer viewer, Object contextHost, Object outlineContext,
            Control filterControl, Tree tree)
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Object existing = tree.getData(KEY_FILTER_KEY);
        if (existing instanceof Listener)
            display.removeFilter(SWT.KeyDown, (Listener) existing);
        Listener keyFilter = event -> {
            if (!isEnabled())
                return;
            if (event.keyCode != SWT.CR && event.keyCode != SWT.KEYPAD_CR)
                return;
            Control widget = event.widget instanceof Control ? (Control) event.widget : null;
            if (widget != tree && widget != filterControl)
                return;
            if (tryActivateFromViewer(viewer, outlineContext, contextHost))
                event.doit = false;
        };
        tree.setData(KEY_FILTER_KEY, keyFilter);
        display.addFilter(SWT.KeyDown, keyFilter);
        tree.addDisposeListener(e -> {
            Object stored = tree.getData(KEY_FILTER_KEY);
            if (stored instanceof Listener && !display.isDisposed())
                display.removeFilter(SWT.KeyDown, (Listener) stored);
            tree.setData(KEY_FILTER_KEY, null);
        });
        if (filterControl != null && !filterControl.isDisposed())
        {
            filterControl.addListener(SWT.Traverse, event -> {
                if (!isEnabled())
                    return;
                if (event.detail != SWT.TRAVERSE_RETURN)
                    return;
                if (tryActivateFromViewer(viewer, outlineContext, contextHost))
                {
                    event.doit = false;
                    event.detail = SWT.TRAVERSE_NONE;
                }
            });
        }
    }

    private static boolean tryActivateFromViewer(TreeViewer viewer, Object outlineContext, Object contextHost)
    {
        if (!(viewer.getSelection() instanceof IStructuredSelection))
            return false;
        return tryActivateEventHandler(viewer, outlineContext, contextHost,
                ((IStructuredSelection) viewer.getSelection()).getFirstElement());
    }

    private static boolean tryActivateEventHandler(TreeViewer viewer, Object outlineContext, Object contextHost,
            Object element)
    {
        boolean activatable = isActivatableEvent(viewer, element);
        if (!activatable)
        {
            return false;
        }
        long now = System.currentTimeMillis();
        int elementId = System.identityHashCode(element);
        if (elementId == lastEventHandlerActivateElementId && now - lastEventHandlerActivateMs < 300)
            return false;
        lastEventHandlerActivateElementId = elementId;
        lastEventHandlerActivateMs = now;
        return createEventHandler(viewer, outlineContext, contextHost, element);
    }

    private static Object resolveOutlineContext(TreeViewer viewer, Object contextHost)
    {
        if (contextHost == null)
            return null;
        if (isBslOutlinePage(contextHost))
            return contextHost;
        if (BSL_QUICK_OUTLINE_POPUP.equals(contextHost.getClass().getName()))
            return createOutlinePageProxy(viewer, contextHost);
        try
        {
            Class<?> pageClass = Class.forName(COMMON_OUTLINE_PAGE, true,
                    contextHost.getClass().getClassLoader());
            if (pageClass.isInstance(contextHost))
                return contextHost;
        }
        catch (Exception ignored) {}
        return createOutlinePageProxy(viewer, contextHost);
    }

    private static Object createOutlinePageProxy(TreeViewer viewer, Object contextHost)
    {
        try
        {
            ClassLoader cl = contextHost.getClass().getClassLoader();
            Class<?> iface = Class.forName(COMMON_OUTLINE_PAGE, true, cl);
            InvocationHandler handler = new OutlinePageProxyHandler(viewer, contextHost);
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] { iface }, handler);
            return proxy;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "create outline page proxy", e); //$NON-NLS-1$
            return contextHost;
        }
    }

    private static volatile long lastEventHandlerActivateMs;
    private static volatile int lastEventHandlerActivateElementId;
    private static boolean createEventHandler(TreeViewer viewer, Object outlineContext, Object contextHost,
            Object eventElement)
    {
        BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
        try
        {
            prepareEventForHandlerCreation(viewer, eventElement);
            String eventName = resolveOutlineEventName(eventElement);
            if (eventName.isEmpty())
            {
                return false;
            }
            if (createEventHandlerViaEdtAction(viewer, outlineContext, contextHost, eventElement))
            {
                return true;
            }
            if (createEventHandlerDirect(editor, eventElement, viewer, contextHost, eventName))
            {
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "create event handler", e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Плоский Quick Outline (с активным фильтром): подпись внешнего обработчика подписки —
     * {@code ИмяСобытия/ИмяМодуля.ИмяОбработчика}. Без фильтра — {@link #resolveTreeSubscriptionHandlerLabel}.
     */
    static String formatFlatSubscriptionHandlerLabel(Object element, IBaseLabelProvider labelSource,
            SubscriptionFlatLabels flatLabels)
    {
        if (element == null)
            return null;

        if (flatLabels != null)
        {
            String cached = flatLabels.elementFlatLabels.get(element);
            if (cached != null)
                return cached;
            String[] mappedParts = flatLabels.elementSubscriptionParts.get(element);
            if (mappedParts != null)
            {
                String ownerModule = resolveOwnerModuleNameForEventHandler(element,
                        resolveOutlineElementName(element), flatLabels);
                String flat = formatFlatFromTypeNameParts(mappedParts, ownerModule, resolveOutlineElementName(element));
                if (flat != null)
                {
                    flatLabels.elementFlatLabels.put(element, flat);
                    return flat;
                }
            }
        }

        String flatFromEdt = resolveFlatViaEdtOutlineLabel(labelSource, element);
        if (flatFromEdt != null)
        {
            if (flatLabels != null)
                flatLabels.elementFlatLabels.put(element, flatFromEdt);
            return flatFromEdt;
        }

        String flatFromEnv = formatFlatFromEnvironmentNames(resolveEnvironmentNames(element));
        if (flatFromEnv != null)
        {
            if (flatLabels != null)
            {
                flatLabels.elementFlatLabels.put(element, flatFromEnv);
                flatLabels.subscriptionOutlineLeaves.add(element);
            }
            return flatFromEnv;
        }

        if (isOutlineEventHandlerElement(element))
        {
            String flat = formatFlatEventHandlerLabel(element, labelSource, flatLabels);
            if (flat != null)
            {
                if (flatLabels != null)
                {
                    flatLabels.elementFlatLabels.put(element, flat);
                    flatLabels.subscriptionOutlineLeaves.add(element);
                }
                return flat;
            }
        }

        if (isEventSubscriptionExtension(element))
        {
            Iterable<?> subscriptionDescriptions = flatLabels != null
                    ? flatLabels.subscriptionDescriptions : null;
            String[] parts = resolveSubscriptionTypeNamesForOutlineElement(element, subscriptionDescriptions);
            if (parts != null)
            {
                String flat = formatFlatFromTypeNameParts(parts, null, null);
                if (flat != null)
                {
                    if (flatLabels != null)
                        flatLabels.elementFlatLabels.put(element, flat);
                    return flat;
                }
            }
            String eventName = resolveParentOutlineEventName(element);
            if (eventName != null && !eventName.isEmpty())
            {
                String handlerName = resolveExtensionElementName(element);
                if (handlerName.isEmpty())
                    handlerName = SmartTreeElementLabels.resolve(element, labelSource);
                if (!handlerName.isEmpty())
                {
                    String flat = formatFlatSubscriptionDisplay(eventName, handlerName, null);
                    if (flatLabels != null)
                        flatLabels.elementFlatLabels.put(element, flat);
                    return flat;
                }
            }
        }

        return tryFormatFlatSubscriptionByName(element, labelSource, flatLabels);
    }

    private static String tryFormatFlatSubscriptionByName(Object element, IBaseLabelProvider labelSource,
            SubscriptionFlatLabels flatLabels)
    {
        if (!isSubscriptionHandlerCandidate(element, flatLabels))
            return null;

        String glued = resolveOutlineElementName(element);
        String display = SmartTreeElementLabels.resolve(element, labelSource);
        if (glued.isEmpty())
            glued = display;

        String[] parts = lookupSubscriptionPartsByMethodName(flatLabels, glued, display);
        if (parts == null && flatLabels != null && flatLabels.subscriptionDescriptions != null)
            parts = resolveSubscriptionPartsForMethodName(glued, flatLabels);
        if (parts == null && flatLabels != null && !flatLabels.gluedPartsIndex.isEmpty())
        {
            parts = splitSubscriptionGluedName(display, flatLabels.eventNames);
            if (parts == null && !glued.equals(display))
                parts = splitSubscriptionGluedName(glued, flatLabels.eventNames);
        }
        String flat = formatFlatFromTypeNameParts(parts, flatLabels != null ? flatLabels.ownerModuleByElement.get(element) : null,
                resolveOutlineElementName(element));
        if (flat == null)
            return null;
        if (flatLabels != null)
        {
            flatLabels.elementFlatLabels.put(element, flat);
            flatLabels.subscriptionOutlineLeaves.add(element);
        }
        return flat;
    }

    private static boolean isSubscriptionHandlerCandidate(Object element, SubscriptionFlatLabels flatLabels)
    {
        if (element == null)
            return false;
        if (isOutlineEventHandlerElement(element))
            return true;
        if (isEventSubscriptionExtension(element))
            return true;
        if (resolveEnvironmentNames(element) != null)
            return true;
        if (flatLabels != null)
        {
            if (flatLabels.subscriptionOutlineLeaves.contains(element))
                return true;
            if (flatLabels.elementFlatLabels.containsKey(element))
                return true;
            String glued = resolveOutlineElementName(element);
            if (isSubscriptionHandlerProcedureName(glued, flatLabels))
                return true;
            if (!glued.isEmpty() && flatLabels.procedureNameIndex.containsKey(glued))
                return true;
        }
        return false;
    }

    private static String resolveOutlineElementName(Object element)
    {
        String fromExtension = resolveExtensionElementName(element);
        if (!fromExtension.isEmpty())
            return fromExtension;
        Object fromGetName = Global.invoke(element, "getName"); //$NON-NLS-1$
        if (fromGetName instanceof String && !((String) fromGetName).isEmpty())
            return (String) fromGetName;
        Object fromField = Global.getField(element, "name"); //$NON-NLS-1$
        return fromField instanceof String ? (String) fromField : ""; //$NON-NLS-1$
    }

    /** Подпись {@code Событие/Обработчик} для отображения в плоском списке. */
    static String resolveFlatDisplayLabel(Object element, IBaseLabelProvider labelSource,
            SubscriptionFlatLabels flatLabels)
    {
        if (element == null || flatLabels == null)
            return null;
        String cached = flatLabels.elementFlatLabels.get(element);
        if (cached != null)
            return cached;
        String flat = formatFlatSubscriptionHandlerLabel(element, labelSource, flatLabels);
        if (flat != null)
            return flat;
        String filterText = flatLabels.filterTextByElement.get(element);
        if (filterText != null && filterText.indexOf('/') >= 0)
            return filterText;
        return null;
    }

    /**
     * Древовый Quick Outline (без фильтра): подпись обработчика подписки —
     * {@code ИмяМодуля.ИмяОбработчика}. Если модуль не определён — {@code null} (штатная подпись EDT).
     */
    static String resolveTreeSubscriptionHandlerLabel(Object element, IBaseLabelProvider labelSource,
            SubscriptionFlatLabels flatLabels)
    {
        if (element == null || flatLabels == null)
            return null;
        if (!isOutlineEventHandlerElement(element) && !isEventSubscriptionExtension(element))
            return null;

        String handlerName = resolveOutlineElementName(element);
        if (handlerName.isEmpty())
            return null;

        String displayHandler = resolveHandlerDisplayForEventHandler(element, handlerName, flatLabels);
        if (displayHandler != null && !displayHandler.isEmpty())
            handlerName = displayHandler;

        String ownerModuleName = resolveOwnerModuleNameForEventHandler(element, handlerName, flatLabels);
        return formatTreeSubscriptionHandlerDisplay(ownerModuleName, handlerName);
    }

    /** Один проход при открытии outline — тексты для фильтрации без повторного getText на каждый символ. */
    static void populateOutlineFilterTextCache(java.util.List<Object> leaves, IBaseLabelProvider labelSource,
            SubscriptionFlatLabels flatLabels, ILabelProvider fallbackLp)
    {
        if (leaves == null || flatLabels == null)
            return;
        for (Object leaf : leaves)
        {
            if (leaf == null)
                continue;
            String flat = formatFlatSubscriptionHandlerLabel(leaf, labelSource, flatLabels);
            String text;
            if (flat != null)
                text = flat;
            else if (fallbackLp != null)
                text = fallbackLp.getText(leaf);
            else
                text = ""; //$NON-NLS-1$
            if (text == null)
                text = ""; //$NON-NLS-1$
            flatLabels.filterTextByElement.put(leaf, text);
        }
    }

    /** Индекс склеенных подписей подписок и имена событий для плоского Quick Outline. */
    static SubscriptionFlatLabels createSubscriptionFlatLabels()
    {
        return new SubscriptionFlatLabels(new HashMap<>(), new LinkedHashSet<>());
    }

    /**
     * Строит индекс подписок после того, как дерево outline уже развёрнуто
     * (секция событий и листья доступны). Выполняется один раз за сессию outline.
     */
    static void enrichSubscriptionFlatLabels(SubscriptionFlatLabels labels, TreeViewer viewer,
            Object contextHost, java.util.List<Object> leaves, IBaseLabelProvider labelSource)
    {
        if (labels == null)
            return;
        indexMcoreEventDisplayNames(contextHost, labels);
        ITreeContentProvider cp = resolveBslOutlineContentProvider(viewer, contextHost);
        if (labels.leafMappingComplete)
            return;
        if (cp != null)
            labels.outlineContentProvider = cp;
        if (viewer != null && cp != null)
        {
            warmOutlineContentProvider(cp, viewer.getInput());
            Object section = resolveEventSection(viewer, cp);
            if (section != null)
                ensureSubscriptionElementsLoaded(cp, section);
            labels.subscriptionDescriptions = materializeSubscriptionDescriptions(
                    resolveSubscriptionDescriptions(cp, section));
            if (labels.subscriptionDescriptions != null && !labels.descriptionsIndexed)
            {
                indexSubscriptionDescriptions(labels.subscriptionDescriptions, labels.gluedPartsIndex,
                        labels.eventNames, labels.eventDisplayByInternalName);
                labels.descriptionsIndexed = true;
            }
        }
        boolean needScopeIndex = !labels.scopeIndexReady || labels.gluedPartsIndex.isEmpty();
        if (needScopeIndex)
        {
            enrichGluedPartsFromSubscriptionScope(viewer, contextHost, labels.gluedPartsIndex,
                    labels.eventNames);
            if (!labels.gluedPartsIndex.isEmpty())
                labels.scopeIndexReady = true;
        }
        collectOutlineEventNames(viewer, cp, labels);
        if (viewer != null && cp != null && !labels.eventChildrenIndexed)
        {
            indexEventChildrenFromOutlineTree(viewer, cp, labels, labelSource);
            if (!labels.eventNameByChildElement.isEmpty() || !labels.eventNameByHandlerUri.isEmpty())
                labels.eventChildrenIndexed = true;
        }
        if (leaves != null && !leaves.isEmpty())
            collectEventNamesFromOutlineLeaves(leaves, labels.eventNames);
        if (viewer != null && cp != null && !labels.treeSubscriptionsIndexed)
        {
            indexEventSectionSubscriptions(viewer, labels);
            labels.treeSubscriptionsIndexed = true;
        }
        if (leaves != null && !leaves.isEmpty())
        {
            mapSubscriptionLeavesOnce(leaves, labels, labelSource);
            labels.leafMappingComplete = true;
        }
        if (BslOutlineDebug.isEnabled())
        {
            int extLeaves = 0;
            String leafSample = ""; //$NON-NLS-1$
            if (leaves != null)
            {
                for (Object leaf : leaves)
                {
                    if (isEventSubscriptionExtension(leaf))
                        extLeaves++;
                    if (leafSample.isEmpty() && leaf != null)
                        leafSample = leaf.getClass().getSimpleName();
                }
            }
            BslOutlineDebug.step("enrichFlatLabels", //$NON-NLS-1$
                    "index=" + labels.gluedPartsIndex.size() //$NON-NLS-1$
                            + " mapped=" + labels.elementFlatLabels.size() //$NON-NLS-1$
                            + " extLeaves=" + extLeaves //$NON-NLS-1$
                            + " leafSample=" + leafSample); //$NON-NLS-1$
        }
    }

    static final class SubscriptionFlatLabels
    {
        final Map<String, String[]> gluedPartsIndex;
        final Set<String> eventNames;
        final Map<Object, String> filterTextByElement = new IdentityHashMap<>();
        final Map<Object, String> elementFlatLabels = new IdentityHashMap<>();
        final Map<Object, String[]> elementSubscriptionParts = new IdentityHashMap<>();
        final Map<Object, String> ownerModuleByElement = new IdentityHashMap<>();
        final Set<Object> subscriptionOutlineLeaves = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String, String[]> procedureNameIndex = new HashMap<>();
        Iterable<?> subscriptionDescriptions;
        ITreeContentProvider outlineContentProvider;
        final Map<String, String> eventDisplayByInternalName = new HashMap<>();
        /** Дочерний узел outline (обработчик подписки) → отображаемое имя события-родителя. */
        final Map<Object, String> eventNameByChildElement = new IdentityHashMap<>();
        /** {@code handlerURI} обработчика → имя события (устойчивый ключ для плоского списка). */
        final Map<String, String> eventNameByHandlerUri = new HashMap<>();
        /** {@code handlerURI} обработчика → общий модуль-владелец процедуры. */
        final Map<String, String> ownerModuleByHandlerUri = new HashMap<>();
        boolean scopeIndexReady;
        boolean descriptionsIndexed;
        boolean treeSubscriptionsIndexed;
        boolean eventChildrenIndexed;
        boolean mcoreEventNamesIndexed;
        boolean outlineEventNamesCollected;
        boolean leafMappingComplete;

        SubscriptionFlatLabels(Map<String, String[]> gluedPartsIndex, Set<String> eventNames)
        {
            this.gluedPartsIndex = gluedPartsIndex;
            this.eventNames = eventNames;
            this.scopeIndexReady = false;
            this.descriptionsIndexed = false;
            this.treeSubscriptionsIndexed = false;
            this.eventChildrenIndexed = false;
            this.mcoreEventNamesIndexed = false;
            this.outlineEventNamesCollected = false;
            this.leafMappingComplete = false;
        }

        void invalidateLeafMapping()
        {
            leafMappingComplete = false;
            outlineEventNamesCollected = false;
            elementFlatLabels.clear();
            elementSubscriptionParts.clear();
            ownerModuleByElement.clear();
            subscriptionOutlineLeaves.clear();
            procedureNameIndex.clear();
            filterTextByElement.clear();
            eventDisplayByInternalName.clear();
            outlineContentProvider = null;
        }

        void clearFilterTextCaches()
        {
            filterTextByElement.clear();
        }
    }

    private static void collectOutlineEventNames(TreeViewer viewer, ITreeContentProvider cp,
            SubscriptionFlatLabels labels)
    {
        if (viewer == null || cp == null || labels == null || labels.outlineEventNamesCollected)
            return;
        Object input = viewer.getInput();
        if (input != null)
        {
            for (Object root : cp.getElements(input))
                walkOutlineEventNames(root, cp, labels.eventNames, labels);
        }
        Object section = resolveEventSection(viewer, cp);
        if (section != null)
            walkOutlineEventNames(section, cp, labels.eventNames, labels);
        labels.outlineEventNamesCollected = true;
    }

    /**
     * Обходит штатное дерево Quick Outline: для каждого узла-события запоминает
     * все дочерние элементы → имя события (для плоского отфильтрованного списка).
     */
    private static void indexEventChildrenFromOutlineTree(TreeViewer viewer, ITreeContentProvider cp,
            SubscriptionFlatLabels labels, IBaseLabelProvider labelSource)
    {
        if (viewer == null || cp == null || labels == null)
            return;
        expandEventSection(viewer);
        Object input = viewer.getInput();
        if (input == null)
            return;
        warmOutlineContentProvider(cp, input);
        Object section = resolveEventSection(viewer, cp);
        if (section != null)
            ensureSubscriptionElementsLoaded(cp, section);
        java.util.Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        try
        {
            for (Object root : cp.getElements(input))
                walkIndexEventChildren(cp, root, labels, seen, labelSource);
            if (section != null)
                walkIndexEventChildren(cp, section, labels, seen, labelSource);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void walkIndexEventChildren(ITreeContentProvider cp, Object element,
            SubscriptionFlatLabels labels, java.util.Set<Object> seen, IBaseLabelProvider labelSource)
    {
        if (element == null || cp == null || labels == null || !seen.add(element))
            return;
        if (isOutlineEvent(element))
        {
            String eventDisplay = resolveLocalizedEventDisplayName(element, labels, labelSource);
            if (!eventDisplay.isEmpty())
                indexChildrenUnderEventNode(cp, element, eventDisplay, labels);
        }
        if (!cp.hasChildren(element))
            return;
        for (Object child : cp.getChildren(element))
            walkIndexEventChildren(cp, child, labels, seen, labelSource);
    }

    private static void indexChildrenUnderEventNode(ITreeContentProvider cp, Object eventNode, String eventDisplay,
            SubscriptionFlatLabels labels)
    {
        if (!cp.hasChildren(eventNode))
            return;
        for (Object child : cp.getChildren(eventNode))
        {
            if (child == null)
                continue;
            labels.eventNameByChildElement.put(child, eventDisplay);
            String handlerUri = resolveHandlerUriText(child);
            if (!handlerUri.isEmpty())
            {
                labels.eventNameByHandlerUri.put(handlerUri, eventDisplay);
                String ownerModule = extractOwnerModuleNameFromHandlerUri(child);
                if (ownerModule != null && !ownerModule.isEmpty())
                    labels.ownerModuleByHandlerUri.put(handlerUri, ownerModule);
            }
            if (!isOutlineEvent(child))
                indexChildrenUnderEventNode(cp, child, eventDisplay, labels);
        }
    }

    /** Имена заглушек событий из уже проиндексированных листьев outline. */
    private static void collectEventNamesFromOutlineLeaves(java.util.List<Object> leaves, Set<String> eventNames)
    {
        if (leaves == null || eventNames == null)
            return;
        for (Object leaf : leaves)
        {
            if (!isOutlineEventMethodStub(leaf))
                continue;
            String stubName = stripEventSignature(resolveOutlineElementName(leaf));
            if (!stubName.isEmpty())
                eventNames.add(stubName);
        }
    }

    /** Один проход: листья секции событий → плоские подписи {@code Событие/Обработчик}. */
    private static void mapSubscriptionLeavesOnce(java.util.List<Object> leaves, SubscriptionFlatLabels labels,
            IBaseLabelProvider labelSource)
    {
        if (leaves == null || labels == null)
            return;
        ensureProcedureNameIndex(labels);
        java.util.List<Object> targets = collectSubscriptionMappingLeaves(leaves, labels);
        for (Object leaf : targets)
        {
            if (leaf == null || labels.elementFlatLabels.containsKey(leaf))
                continue;
            if (isOutlineEventHandlerElement(leaf))
            {
                String flat = formatFlatEventHandlerLabel(leaf, labelSource, labels);
                if (flat != null)
                {
                    labels.subscriptionOutlineLeaves.add(leaf);
                    labels.elementFlatLabels.put(leaf, flat);
                    String ownerModule = resolveOwnerModuleNameForEventHandler(leaf, resolveOutlineElementName(leaf),
                            labels);
                    if (ownerModule != null && !ownerModule.isEmpty())
                        labels.ownerModuleByElement.put(leaf, ownerModule);
                }
                continue;
            }
            if (!isOutlineMethodElement(leaf))
                continue;
            String methodName = resolveOutlineElementName(leaf);
            if (methodName.isEmpty() || methodName.indexOf('(') >= 0)
                continue;
            if (!isEventSubscriptionExtension(leaf)
                    && !isSubscriptionHandlerProcedureName(methodName, labels))
                continue;
            if (labelSource != null)
            {
                String flatEdt = resolveFlatViaEdtOutlineLabel(labelSource, leaf);
                if (flatEdt != null)
                {
                    labels.subscriptionOutlineLeaves.add(leaf);
                    labels.elementFlatLabels.put(leaf, flatEdt);
                    continue;
                }
            }
            String[] envNames = resolveEnvironmentNames(leaf);
            if (envNames != null)
            {
                putSubscriptionEnvironmentOnLeaf(labels, leaf, envNames);
                continue;
            }
            String[] parts = resolveSubscriptionPartsForMethodName(methodName, labels);
            if (parts == null && labelSource != null)
            {
                String display = SmartTreeElementLabels.resolve(leaf, labelSource);
                parts = lookupSubscriptionTypeNameParts(labels, display);
                if (parts == null && display != null && !display.isEmpty())
                    parts = resolveSubscriptionPartsForMethodName(display, labels);
            }
            if (parts == null)
                continue;
            putSubscriptionPartsOnLeaf(labels, leaf, parts);
            String ownerModule = resolveOwnerModuleNameForEventHandler(leaf, methodName, labels);
            if (ownerModule != null && !ownerModule.isEmpty())
                labels.ownerModuleByElement.put(leaf, ownerModule);
        }
    }

    private static void ensureProcedureNameIndex(SubscriptionFlatLabels labels)
    {
        if (labels == null || labels.procedureNameIndex.isEmpty())
        {
            if (labels.subscriptionDescriptions != null)
            {
                for (Object description : labels.subscriptionDescriptions)
                {
                    String[] parts = parseSubscriptionTypeNames(description);
                    if (parts == null)
                        continue;
                    indexSubscriptionLookupKeys(labels.procedureNameIndex, parts);
                    indexSubscriptionDescriptionNames(description, labels.procedureNameIndex, parts);
                }
            }
            for (Map.Entry<String, String[]> entry : labels.gluedPartsIndex.entrySet())
                labels.procedureNameIndex.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private static java.util.List<Object> collectSubscriptionMappingLeaves(java.util.List<Object> leaves,
            SubscriptionFlatLabels labels)
    {
        if (leaves == null || leaves.isEmpty())
            return leaves;
        java.util.List<Object> handlers = new java.util.ArrayList<>();
        for (Object leaf : leaves)
        {
            if (isEventSubscriptionExtension(leaf) || isOutlineEventHandlerElement(leaf))
            {
                handlers.add(leaf);
                continue;
            }
            if (!isOutlineMethodElement(leaf))
                continue;
            String name = resolveOutlineElementName(leaf);
            if (isSubscriptionHandlerProcedureName(name, labels))
                handlers.add(leaf);
        }
        return handlers;
    }

    /** Имя процедуры внешнего обработчика подписки: {@code ПередЗаписьюОбъекта}, не заглушка {@code ПередЗаписью}. */
    private static boolean isSubscriptionHandlerProcedureName(String name, SubscriptionFlatLabels labels)
    {
        if (name == null || name.isEmpty() || name.indexOf('(') >= 0 || labels == null)
            return false;
        if (labels.eventNames.contains(name))
            return false;
        return longestMatchingEventPrefix(name, labels.eventNames) != null;
    }

    static boolean isOutlineEventHandlerElement(Object element)
    {
        if (element == null)
            return false;
        String name = element.getClass().getName();
        if (name.contains("EventHandlerImpl")) //$NON-NLS-1$
            return true;
        try
        {
            Class<?> type = Class.forName(I_EVENT_HANDLER, false, element.getClass().getClassLoader());
            return type.isInstance(element);
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> materializeSubscriptionDescriptions(Iterable<?> descriptions)
    {
        if (descriptions == null)
            return null;
        if (descriptions instanceof java.util.List)
            return (java.util.List<Object>) descriptions;
        java.util.List<Object> list = new java.util.ArrayList<>();
        for (Object description : descriptions)
            list.add(description);
        return list;
    }

    /** Как {@code BslOutlineLabelProvider.environmentsName} — {@code getEnvironmentNames()} на {@code MethodImpl}. */
    private static String[] resolveEnvironmentNames(Object element)
    {
        Object names = Global.invoke(element, "getEnvironmentNames"); //$NON-NLS-1$
        if (!(names instanceof String[]))
            names = Global.getField(element, "environmentNames"); //$NON-NLS-1$
        if (!(names instanceof String[]))
            return null;
        String[] raw = (String[]) names;
        if (raw.length < 2)
            return null;
        int count = 0;
        for (String part : raw)
        {
            if (part != null && !part.isEmpty())
                count++;
        }
        if (count < 2)
            return null;
        String[] copy = new String[raw.length];
        System.arraycopy(raw, 0, copy, 0, raw.length);
        return copy;
    }

    /** Подпись из {@code environmentNames} — части уже в порядке {@code Событие/Обработчик}. */
    private static String formatFlatFromEnvironmentNames(String[] names)
    {
        if (names == null || names.length < 2)
            return null;
        StringBuilder sb = new StringBuilder();
        for (String part : names)
        {
            if (part == null || part.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append('/');
            sb.append(part);
        }
        return sb.length() > 0 && sb.indexOf("/") >= 0 ? sb.toString() : null; //$NON-NLS-1$
    }

    /**
     * Плоская подпись для {@code EventHandlerImpl} при активном фильтре:
     * {@code ИмяСобытия/ИмяМодуля.ИмяОбработчика} (например {@code ПередЗаписью/ОбщегоНазначения.ПередЗаписьюОбъекта}).
     */
    private static String formatFlatEventHandlerLabel(Object element, IBaseLabelProvider labelSource,
            SubscriptionFlatLabels labels)
    {
        if (element == null)
            return null;

        String handlerName = resolveOutlineElementName(element);
        if (handlerName.isEmpty())
            return null;

        String ownerModuleName = resolveOwnerModuleNameForEventHandler(element, handlerName, labels);
        String eventName = localizeInternalEventName(
                resolveEventNameForEventHandler(element, labels, labelSource), labels);

        if (eventName != null && !eventName.isEmpty())
            return formatFlatSubscriptionDisplay(eventName, handlerName, ownerModuleName);

        return null;
    }

    private static String resolveEventNameForEventHandler(Object element, SubscriptionFlatLabels labels,
            IBaseLabelProvider labelSource)
    {
        if (labels != null)
        {
            String eventFromTreeIndex = labels.eventNameByChildElement.get(element);
            if (eventFromTreeIndex != null && !eventFromTreeIndex.isEmpty())
                return eventFromTreeIndex;
            String handlerUri = resolveHandlerUriText(element);
            if (!handlerUri.isEmpty())
            {
                String eventFromUri = labels.eventNameByHandlerUri.get(handlerUri);
                if (eventFromUri != null && !eventFromUri.isEmpty())
                    return eventFromUri;
            }
        }

        if (labels != null)
        {
            String[] mappedParts = labels.elementSubscriptionParts.get(element);
            if (mappedParts != null)
            {
                String eventFromParts = resolveEventDisplayFromTypeNameParts(mappedParts, labels);
                if (eventFromParts != null && !eventFromParts.isEmpty())
                    return eventFromParts;
            }
        }

        String eventFromEnv = resolveEventNameFromEnvironmentNames(element);
        if (eventFromEnv != null && !eventFromEnv.isEmpty())
            return eventFromEnv;

        String eventFromTree = resolveEventNameFromOutlineTree(element, labels);
        if (eventFromTree != null && !eventFromTree.isEmpty())
            return eventFromTree;

        String eventFromEdt = resolveEventNameFromEdtLabel(labelSource, element);
        if (eventFromEdt != null && !eventFromEdt.isEmpty())
            return eventFromEdt;

        return null;
    }

    private static String resolveEventNameFromEnvironmentNames(Object element)
    {
        String[] env = resolveEnvironmentNames(element);
        if (env == null || env.length == 0)
            return null;
        for (String part : env)
        {
            if (part != null && !part.isEmpty())
                return stripEventSignature(part);
        }
        return null;
    }

    private static String resolveEventNameFromOutlineTree(Object element, SubscriptionFlatLabels labels)
    {
        if (element == null || labels == null)
            return null;
        ITreeContentProvider cp = labels.outlineContentProvider;
        for (Object parent = outlineContentParent(cp, element); parent != null;
                parent = outlineContentParent(cp, parent))
        {
            if (isOutlineEvent(parent))
            {
                String display = resolveOutlineEventDisplayName(parent);
                if (display != null && !display.isEmpty())
                    return stripEventSignature(display);
            }
            if (isOutlineEventMethodStub(parent))
            {
                String display = stripEventSignature(resolveOutlineElementName(parent));
                if (!display.isEmpty())
                    return display;
            }
        }
        if (cp != null)
        {
            String[] parts = resolveSubscriptionPartsFromAncestors(cp, element, labels.subscriptionDescriptions);
            if (parts != null)
                return resolveEventDisplayFromTypeNameParts(parts, labels);
        }
        return null;
    }

    private static String resolveEventDisplayFromTypeNameParts(String[] parts, SubscriptionFlatLabels labels)
    {
        if (parts == null || parts.length < 2 || labels == null)
            return null;
        String internal = parts[parts.length - 1];
        if (internal == null || internal.isEmpty())
            return null;
        String display = labels.eventDisplayByInternalName.get(internal);
        if (display != null && !display.isEmpty())
            return display;
        return stripEventSignature(internal);
    }

    private static String resolveEventNameFromEdtLabel(IBaseLabelProvider labelSource, Object element)
    {
        if (labelSource == null || element == null)
            return null;
        IBaseLabelProvider outlineLp = unwrapBslOutlineLabelProvider(labelSource);
        if (outlineLp == null)
            return null;
        Object raw = Global.invoke(outlineLp, "environmentsName", element); //$NON-NLS-1$
        if (!(raw instanceof String))
            return null;
        String flat = normalizeEdtEnvironmentLabel((String) raw);
        if (flat == null || flat.isEmpty())
            return null;
        int slash = flat.indexOf('/');
        String eventPart = slash > 0 ? flat.substring(0, slash) : flat;
        return stripEventSignature(eventPart);
    }

    private static String localizeInternalEventName(String eventName, SubscriptionFlatLabels labels)
    {
        if (eventName == null || eventName.isEmpty())
            return eventName;
        if (labels != null)
        {
            String mapped = labels.eventDisplayByInternalName.get(eventName);
            if (mapped != null && !mapped.isEmpty())
                return stripEventSignature(mapped);
        }
        return eventName;
    }

    private static String resolveLocalizedEventDisplayName(Object eventElement, SubscriptionFlatLabels labels,
            IBaseLabelProvider labelSource)
    {
        String internal = stripEventSignature(resolveOutlineEventInternalName(eventElement));
        if (labels != null && !internal.isEmpty())
        {
            String mapped = labels.eventDisplayByInternalName.get(internal);
            if (mapped != null && !mapped.isEmpty())
                return stripEventSignature(mapped);
        }
        String display = stripEventSignature(resolveOutlineEventDisplayName(eventElement, labelSource));
        if (!display.isEmpty())
        {
            if (labels != null && !internal.isEmpty() && !display.equals(internal))
                labels.eventDisplayByInternalName.putIfAbsent(internal, display);
            return display;
        }
        if (labelSource != null)
        {
            String fromLabel = resolveEventNodeLabelText(labelSource, eventElement);
            if (!fromLabel.isEmpty())
                return stripEventSignature(fromLabel);
        }
        return internal;
    }

    private static String resolveEventNodeLabelText(IBaseLabelProvider labelSource, Object eventElement)
    {
        IBaseLabelProvider outlineLp = unwrapBslOutlineLabelProvider(labelSource);
        if (outlineLp == null || eventElement == null)
            return ""; //$NON-NLS-1$
        if (outlineLp instanceof ILabelProvider)
        {
            String text = ((ILabelProvider) outlineLp).getText(eventElement);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        Object text = Global.invoke(outlineLp, "getText", eventElement); //$NON-NLS-1$
        return text instanceof String ? (String) text : ""; //$NON-NLS-1$
    }

    private static String stripEventSignature(String name)
    {
        if (name == null)
            return ""; //$NON-NLS-1$
        int paren = name.indexOf('(');
        return paren >= 0 ? name.substring(0, paren).trim() : name.trim();
    }

    private static boolean isOutlineEventMethodStub(Object element)
    {
        if (!isOutlineMethodElement(element))
            return false;
        Object isEvent = Global.invoke(element, "isEvent"); //$NON-NLS-1$
        return isEvent instanceof Boolean && (Boolean) isEvent;
    }

    private static String resolveHandlerDisplayForEventHandler(Object element, String handlerName,
            SubscriptionFlatLabels labels)
    {
        Object boundDescription = resolveSubscriptionDescriptionForElement(element);
        String fromBound = resolveHandlerNameFromSubscriptionDescription(boundDescription);
        if (fromBound != null)
            return fromBound;

        if (labels != null)
        {
            String fromIndex = resolveSubscriptionHandlerDisplayName(handlerName, labels);
            if (fromIndex != null)
                return fromIndex;
        }

        return null;
    }

    private static String resolveOwnerModuleNameForEventHandler(Object element, String handlerName,
            SubscriptionFlatLabels labels)
    {
        String handlerUri = resolveHandlerUriText(element);
        if (!handlerUri.isEmpty() && labels != null)
        {
            String cachedByUri = labels.ownerModuleByHandlerUri.get(handlerUri);
            if (cachedByUri != null && !cachedByUri.isEmpty())
                return cachedByUri;
        }
        if (element != null && labels != null)
        {
            String cached = labels.ownerModuleByElement.get(element);
            if (cached != null && !cached.isEmpty())
                return cached;
        }
        String ownerFromHandlerUri = extractOwnerModuleNameFromHandlerUri(element);
        if (ownerFromHandlerUri != null && !ownerFromHandlerUri.isEmpty())
        {
            if (labels != null)
            {
                if (!handlerUri.isEmpty())
                    labels.ownerModuleByHandlerUri.put(handlerUri, ownerFromHandlerUri);
                if (element != null)
                    labels.ownerModuleByElement.put(element, ownerFromHandlerUri);
            }
            return ownerFromHandlerUri;
        }
        Object boundDescription = resolveSubscriptionDescriptionForElement(element);
        String ownerFromBound = extractOwnerModuleNameFromDescription(boundDescription, handlerName);
        if (ownerFromBound != null && !ownerFromBound.isEmpty())
            return ownerFromBound;
        if (labels != null && labels.subscriptionDescriptions != null)
        {
            for (Object description : labels.subscriptionDescriptions)
            {
                String ownerFromTypeNames = extractOwnerModuleNameFromTypeNames(description);
                if (ownerFromTypeNames != null && !ownerFromTypeNames.isEmpty())
                    return ownerFromTypeNames;
                String owner = extractOwnerModuleNameFromDescription(description, handlerName);
                if (owner != null && !owner.isEmpty())
                    return owner;
            }
        }
        return null;
    }

    private static String resolveHandlerUriText(Object element)
    {
        if (element == null)
            return ""; //$NON-NLS-1$
        Object uriValue = Global.getField(element, "handlerURI"); //$NON-NLS-1$
        String uri = valueAsText(uriValue);
        if (uri.isEmpty())
            uri = valueAsText(Global.invoke(element, "getHandlerURI")); //$NON-NLS-1$
        return uri;
    }

    private static String extractOwnerModuleNameFromHandlerUri(Object element)
    {
        if (element == null)
            return null;
        String uri = resolveHandlerUriText(element);
        if (uri.isEmpty())
            return null;
        return extractOwnerModuleNameFromHandlerUriText(uri);
    }

    private static String extractOwnerModuleNameFromHandlerUriText(String uri)
    {
        if (uri == null || uri.isEmpty())
            return null;
        String marker = "/src/CommonModules/"; //$NON-NLS-1$
        int start = uri.indexOf(marker);
        if (start < 0)
            return null;
        int moduleStart = start + marker.length();
        if (moduleStart >= uri.length())
            return null;
        int moduleEnd = uri.indexOf('/', moduleStart);
        if (moduleEnd < 0)
            return null;
        String moduleName = uri.substring(moduleStart, moduleEnd).trim();
        return moduleName.isEmpty() ? null : moduleName;
    }

    private static String extractOwnerModuleNameFromTypeNames(Object description)
    {
        String[] parts = parseSubscriptionTypeNames(description);
        if (parts == null || parts.length < 2)
            return null;
        for (int i = 0; i < parts.length - 1; i++)
        {
            String p = parts[i];
            if (p == null || p.isEmpty())
                continue;
            if (p.startsWith("ОбщийМодуль.")) //$NON-NLS-1$
            {
                String direct = MdTypeMapping.directModuleName(p);
                if (direct != null && !direct.isEmpty())
                    return direct;
                String tail = p.substring("ОбщийМодуль.".length()); //$NON-NLS-1$
                return tail.isEmpty() ? null : tail;
            }
            if (p.startsWith("CommonModule.")) //$NON-NLS-1$
            {
                String tail = p.substring("CommonModule.".length()); //$NON-NLS-1$
                return tail.isEmpty() ? null : tail;
            }
            if ("CommonModule".equals(p) || "ОбщийМодуль".equals(p)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                if (i + 1 < parts.length - 1)
                {
                    String next = parts[i + 1];
                    if (next != null && !next.isEmpty() && !next.startsWith(".")) //$NON-NLS-1$
                        return next;
                }
            }
        }
        return null;
    }

    private static String extractOwnerModuleNameFromDescription(Object description, String handlerName)
    {
        if (description == null || handlerName == null || handlerName.isEmpty())
            return null;
        String owner = extractOwnerModuleNameFromString(valueAsString(Global.invoke(description, "getName")), handlerName); //$NON-NLS-1$
        if (owner != null)
            return owner;
        return extractOwnerModuleNameFromString(valueAsString(Global.invoke(description, "getQualifiedName")), //$NON-NLS-1$
                handlerName);
    }

    private static String extractOwnerModuleNameFromString(String text, String handlerName)
    {
        if (text == null || text.isEmpty() || handlerName == null || handlerName.isEmpty())
            return null;
        int handlerAt = text.lastIndexOf(handlerName);
        if (handlerAt <= 0)
            return null;
        int dotBefore = text.lastIndexOf('.', handlerAt - 1);
        if (dotBefore <= 0)
            return null;
        String modulePath = text.substring(0, dotBefore);
        if (!modulePath.startsWith("ОбщийМодуль.")) //$NON-NLS-1$
            return null;
        String direct = MdTypeMapping.directModuleName(modulePath);
        return direct != null && !direct.isEmpty() ? direct : null;
    }

    private static String valueAsString(Object value)
    {
        return value instanceof String ? (String) value : ""; //$NON-NLS-1$
    }

    private static String valueAsText(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof String)
            return (String) value;
        String text = String.valueOf(value);
        return text != null ? text : ""; //$NON-NLS-1$
    }

    private static Object resolveSubscriptionDescriptionForElement(Object element)
    {
        if (element == null)
            return null;
        Object desc = Global.invoke(element, "getSubscriptionDescription"); //$NON-NLS-1$
        if (desc != null)
            return desc;
        desc = Global.invoke(element, "getDescription"); //$NON-NLS-1$
        if (desc != null)
            return desc;
        return Global.getField(element, "subscriptionDescription"); //$NON-NLS-1$
    }

    private static String resolveHandlerNameFromSubscriptionDescription(Object description)
    {
        if (description == null)
            return null;
        Object name = Global.invoke(description, "getName"); //$NON-NLS-1$
        if (name instanceof String && !((String) name).isEmpty())
            return (String) name;
        Object qualified = Global.invoke(description, "getQualifiedName"); //$NON-NLS-1$
        if (qualified instanceof String && !((String) qualified).isEmpty())
            return (String) qualified;
        return null;
    }

    /** Имя обработчика из метаданных подписки ({@code getName} / {@code getQualifiedName}). */
    private static String resolveSubscriptionHandlerDisplayName(String handlerName, SubscriptionFlatLabels labels)
    {
        if (handlerName == null || handlerName.isEmpty() || labels == null
                || labels.subscriptionDescriptions == null)
            return null;
        for (Object description : labels.subscriptionDescriptions)
        {
            Object name = Global.invoke(description, "getName"); //$NON-NLS-1$
            if (name instanceof String && matchesHandlerDescriptionName(handlerName, (String) name))
                return (String) name;
            Object qualified = Global.invoke(description, "getQualifiedName"); //$NON-NLS-1$
            if (qualified instanceof String && matchesHandlerDescriptionName(handlerName, (String) qualified))
                return (String) qualified;
            if (matchMethodToDescriptionName(handlerName, description, labels.eventNames) != null)
            {
                String fromDesc = resolveHandlerNameFromSubscriptionDescription(description);
                if (fromDesc != null)
                    return fromDesc;
            }
        }
        return null;
    }

    private static boolean matchesHandlerDescriptionName(String handlerName, String descName)
    {
        if (handlerName == null || descName == null || handlerName.isEmpty() || descName.isEmpty())
            return false;
        if (handlerName.equals(descName))
            return true;
        if (descName.endsWith('.' + handlerName))
            return true;
        if (descName.endsWith(handlerName) && descName.length() > handlerName.length())
            return true;
        int slash = descName.lastIndexOf('/');
        return slash >= 0 && handlerName.equals(descName.substring(slash + 1));
    }

    private static void putSubscriptionEnvironmentOnLeaf(SubscriptionFlatLabels labels, Object leaf,
            String[] envNames)
    {
        String flat = formatFlatFromEnvironmentNames(envNames);
        if (labels == null || leaf == null || flat == null)
            return;
        labels.subscriptionOutlineLeaves.add(leaf);
        labels.elementFlatLabels.put(leaf, flat);
    }

    /** Штатная подпись EDT ({@code environmentsName}) — части через запятую, в плоском списке через {@code /}. */
    private static String resolveFlatViaEdtOutlineLabel(IBaseLabelProvider labelSource, Object element)
    {
        if (labelSource == null || element == null)
            return null;
        IBaseLabelProvider outlineLp = unwrapBslOutlineLabelProvider(labelSource);
        if (outlineLp == null)
            return null;
        Object raw = Global.invoke(outlineLp, "environmentsName", element); //$NON-NLS-1$
        if (raw instanceof String)
        {
            String flat = normalizeEdtEnvironmentLabel((String) raw);
            if (flat != null)
                return flat;
        }
        if (outlineLp instanceof IStyledLabelProvider)
        {
            org.eclipse.jface.viewers.StyledString styled =
                    ((IStyledLabelProvider) outlineLp).getStyledText(element);
            if (styled != null)
                return normalizeEdtEnvironmentLabel(styled.getString());
        }
        return null;
    }

    private static IBaseLabelProvider unwrapBslOutlineLabelProvider(IBaseLabelProvider labelSource)
    {
        if (labelSource == null)
            return null;
        if (BSL_OUTLINE_LABEL_PROVIDER.equals(labelSource.getClass().getName()))
            return labelSource;
        if (labelSource instanceof org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider)
        {
            Object styled = ((org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider) labelSource)
                    .getStyledStringProvider();
            if (styled instanceof IBaseLabelProvider)
                return unwrapBslOutlineLabelProvider((IBaseLabelProvider) styled);
        }
        return labelSource.getClass().getName().contains("BslOutlineLabelProvider") ? labelSource : null; //$NON-NLS-1$
    }

    private static String normalizeEdtEnvironmentLabel(String text)
    {
        if (text == null)
            return null;
        String s = text.trim();
        if (s.isEmpty())
            return null;
        if (s.indexOf('/') >= 0)
            return s;
        if (s.indexOf(',') >= 0)
            return s.replace(", ", "/").replace(",", "/"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return null;
    }

    private static boolean isOutlineMethodElement(Object element)
    {
        if (element == null)
            return false;
        String name = element.getClass().getName();
        return METHOD_IMPL.equals(name) || name.contains("MethodImpl"); //$NON-NLS-1$
    }

    private static String[] lookupSubscriptionPartsByMethodName(SubscriptionFlatLabels flatLabels, String methodName,
            String display)
    {
        if (flatLabels == null || methodName == null || methodName.isEmpty())
            return null;
        String[] parts = flatLabels.procedureNameIndex.get(methodName);
        if (parts == null)
            parts = lookupSubscriptionTypeNameParts(flatLabels, methodName);
        if (parts == null && display != null && !display.isEmpty() && !display.equals(methodName))
            parts = lookupSubscriptionTypeNameParts(flatLabels, display);
        return parts;
    }

    /**
     * Сопоставляет имя процедуры внешнего обработчика с {@code typeNames} подписки.
     * Для двухчастных {@code Объект,Событие} процедура часто {@code Событие+Суффикс}
     * (например {@code ПередЗаписьюОбъекта}); событие в typeNames может быть EN, имя — RU.
     */
    private static String[] resolveSubscriptionPartsForMethodName(String methodName, SubscriptionFlatLabels labels)
    {
        if (methodName == null || methodName.isEmpty() || labels == null)
            return null;
        String[] parts = labels.procedureNameIndex.get(methodName);
        if (parts != null)
            return parts;
        if (labels.subscriptionDescriptions == null)
            return null;
        String[] prefixMatch = null;
        for (Object description : labels.subscriptionDescriptions)
        {
            String[] matchedByName = matchMethodToDescriptionName(methodName, description, labels.eventNames);
            if (matchedByName != null)
                return matchedByName;
            String[] candidate = parseSubscriptionTypeNames(description);
            if (candidate == null)
                continue;
            String[] matched = matchMethodToTypeNameParts(methodName, candidate, labels.eventNames);
            if (matched != null && prefixMatch == null)
                prefixMatch = matched;
        }
        return prefixMatch;
    }

    private static String[] matchMethodToDescriptionName(String methodName, Object description,
            Set<String> eventNames)
    {
        if (methodName == null || description == null)
            return null;
        String[] parts = parseSubscriptionTypeNames(description);
        if (parts == null || parts.length < 2)
            return null;
        Object name = Global.invoke(description, "getName"); //$NON-NLS-1$
        if (!(name instanceof String) || ((String) name).isEmpty())
            return null;
        String descName = (String) name;
        boolean nameMatch = descName.equals(methodName);
        if (!nameMatch)
        {
            int dot = descName.lastIndexOf('.');
            nameMatch = dot >= 0 && methodName.equals(descName.substring(dot + 1));
        }
        if (!nameMatch)
            nameMatch = descName.endsWith(methodName) && descName.length() > methodName.length();
        if (!nameMatch)
            return null;
        return expandSubscriptionPartsForMethod(methodName, parts, eventNames);
    }

    private static String[] expandSubscriptionPartsForMethod(String methodName, String[] parts,
            Set<String> eventNames)
    {
        if (methodName == null || parts == null || parts.length < 2)
            return null;
        String eventRu = longestMatchingEventPrefix(methodName, eventNames);
        if (eventRu != null)
            return new String[] { parts[0], methodName, eventRu };
        String eventPart = parts[parts.length - 1];
        return new String[] { parts[0], methodName, eventPart };
    }

    private static String longestMatchingEventPrefix(String methodName, Set<String> eventNames)
    {
        if (methodName == null || eventNames == null)
            return null;
        String bestEvent = null;
        for (String event : eventNames)
        {
            if (event == null || event.isEmpty() || event.equals(methodName))
                continue;
            if (methodName.startsWith(event) && methodName.length() > event.length()
                    && (bestEvent == null || event.length() > bestEvent.length()))
                bestEvent = event;
            else if (methodName.endsWith(event) && methodName.length() > event.length()
                    && (bestEvent == null || event.length() > bestEvent.length()))
                bestEvent = event;
        }
        return bestEvent;
    }

    private static String[] matchMethodToTypeNameParts(String methodName, String[] parts, Set<String> eventNames)
    {
        if (methodName == null || methodName.isEmpty() || parts == null || parts.length < 2)
            return null;
        String eventPart = parts[parts.length - 1];
        if (eventPart == null || eventPart.isEmpty())
            return null;
        for (int i = 0; i < parts.length - 1; i++)
        {
            String part = parts[i];
            if (part == null || part.isEmpty())
                continue;
            if (part.equals(methodName))
                return parts;
            int dot = part.lastIndexOf('.');
            if (dot >= 0 && methodName.equals(part.substring(dot + 1)))
                return parts;
        }
        String handlerSimple = resolveSubscriptionHandlerSimpleName(parts);
        if (methodName.equals(handlerSimple))
            return parts;
        String handlerOnly = joinSubscriptionTypeNameParts(copyTypeNamePartsWithoutLast(parts));
        if (!handlerOnly.isEmpty() && (handlerOnly.equals(methodName) || handlerOnly.endsWith(methodName)))
            return parts;
        if (parts.length >= 2)
        {
            if (methodName.startsWith(eventPart) && methodName.length() > eventPart.length())
                return new String[] { parts[0], methodName, eventPart };
            if (eventNames != null)
            {
                String bestEvent = longestMatchingEventPrefix(methodName, eventNames);
                if (bestEvent != null)
                    return new String[] { parts[0], methodName, bestEvent };
            }
        }
        return null;
    }

    private static void putSubscriptionPartsOnLeaf(SubscriptionFlatLabels labels, Object leaf, String[] parts)
    {
        if (labels == null || leaf == null || parts == null)
            return;
        labels.elementSubscriptionParts.put(leaf, parts);
        indexSubscriptionLookupKeys(labels.gluedPartsIndex, parts);
        String methodName = resolveOutlineElementName(leaf);
        if (!methodName.isEmpty())
        {
            labels.gluedPartsIndex.putIfAbsent(methodName, parts);
            labels.procedureNameIndex.put(methodName, parts);
        }
        String flat = formatFlatFromTypeNameParts(parts, labels.ownerModuleByElement.get(leaf), methodName);
        if (flat != null)
            labels.elementFlatLabels.put(leaf, flat);
    }

    private static String[] resolveSubscriptionPartsFromAncestors(ITreeContentProvider cp, Object element,
            Iterable<?> subscriptionDescriptions)
    {
        if (element == null)
            return null;
        for (Object cur = element; cur != null; cur = outlineContentParent(cp, cur))
        {
            if (!isExtensionElement(cur) || isFormItemEventExtension(cur))
                continue;
            String[] parts = resolveSubscriptionTypeNamesForOutlineElement(cur, subscriptionDescriptions);
            if (parts != null)
                return parts;
        }
        return null;
    }

    private static Object outlineContentParent(ITreeContentProvider cp, Object element)
    {
        if (cp != null)
        {
            try
            {
                Object fromCp = cp.getParent(element);
                if (fromCp != null)
                    return fromCp;
            }
            catch (Exception ignored)
            {
            }
        }
        return outlineModelParent(element);
    }

    static FlatSubscriptionHandlerLabelProvider createFlatSubscriptionHandlerLabelProvider(
            ILabelProvider delegate, IBaseLabelProvider labelSource)
    {
        return new FlatSubscriptionHandlerLabelProvider(delegate, labelSource);
    }

    private static boolean isEventSubscriptionExtension(Object element)
    {
        if (element == null || !isExtensionElement(element))
            return false;
        return !isFormItemEventExtension(element);
    }

    private static boolean isExtensionElement(Object element)
    {
        if (element == null)
            return false;
        try
        {
            Class<?> type = Class.forName(I_EXTENSION_ELEMENT, false, BslHandlerUtil.class.getClassLoader());
            if (type.isInstance(element))
                return true;
        }
        catch (ClassNotFoundException ignored)
        {
        }
        String name = element.getClass().getName();
        return name.contains("ExtensionElement"); //$NON-NLS-1$
    }

    static boolean isOutlineEvent(Object element)
    {
        try
        {
            Class<?> type = Class.forName(I_EVENT, false, BslHandlerUtil.class.getClassLoader());
            return type.isInstance(element);
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }

    private static String resolveParentOutlineEventName(Object element)
    {
        Object parent = outlineModelParent(element);
        while (parent != null)
        {
            if (isOutlineEvent(parent))
                return resolveOutlineEventDisplayName(parent);
            parent = outlineModelParent(parent);
        }
        return ""; //$NON-NLS-1$
    }

    private static Object outlineModelParent(Object element)
    {
        Object fromHandly = handlyParent(element);
        if (fromHandly != null)
            return fromHandly;
        Object fromGetParent = Global.invoke(element, "getParent"); //$NON-NLS-1$
        return fromGetParent;
    }

    private static String resolveExtensionElementName(Object element)
    {
        if (element == null)
            return ""; //$NON-NLS-1$
        Object fromGetName = Global.invoke(element, "getName"); //$NON-NLS-1$
        if (fromGetName instanceof String && !((String) fromGetName).isEmpty())
            return (String) fromGetName;
        Object fromField = Global.getField(element, "name"); //$NON-NLS-1$
        return fromField instanceof String ? (String) fromField : ""; //$NON-NLS-1$
    }

    private static String[] splitSubscriptionGluedName(String glued, Set<String> moduleEventNames)
    {
        if (glued == null || glued.isEmpty() || moduleEventNames == null || moduleEventNames.isEmpty())
            return null;
        String bestEvent = null;
        for (String event : moduleEventNames)
        {
            if (event == null || event.isEmpty())
                continue;
            if (glued.endsWith(event) && glued.length() > event.length())
            {
                if (bestEvent == null || event.length() > bestEvent.length())
                    bestEvent = event;
            }
        }
        if (bestEvent == null)
            return null;
        return new String[] { glued.substring(0, glued.length() - bestEvent.length()), bestEvent };
    }

    private static void walkOutlineEventNames(Object element, ITreeContentProvider cp, Set<String> names,
            SubscriptionFlatLabels labels)
    {
        if (element == null || cp == null || names == null)
            return;
        if (isOutlineEvent(element))
        {
            String eventName = resolveOutlineEventInternalName(element);
            if (eventName != null && !eventName.isEmpty())
                names.add(eventName);
            String displayName = resolveOutlineEventDisplayName(element, null);
            if (displayName != null && !displayName.isEmpty())
                names.add(displayName);
            if (labels != null && eventName != null && !eventName.isEmpty() && displayName != null
                    && !displayName.isEmpty() && !displayName.equals(eventName))
            {
                labels.eventDisplayByInternalName.putIfAbsent(eventName, stripEventSignature(displayName));
            }
        }
        if (isOutlineEventMethodStub(element))
        {
            String stubName = stripEventSignature(resolveOutlineElementName(element));
            if (!stubName.isEmpty())
                names.add(stubName);
        }
        if (!cp.hasChildren(element))
            return;
        for (Object child : cp.getChildren(element))
            walkOutlineEventNames(child, cp, names, labels);
    }

    private static void enrichGluedPartsFromSubscriptionScope(TreeViewer viewer, Object contextHost,
            Map<String, String[]> index, Set<String> eventNames)
    {
        if (viewer == null || index == null || eventNames == null)
            return;
        try
        {
            ITreeContentProvider cp = resolveBslOutlineContentProvider(viewer, contextHost);
            warmOutlineContentProvider(cp, viewer.getInput());
            Object section = resolveEventSection(viewer, cp);
            if (cp == null)
                return;
            if (section != null)
            {
                ensureSubscriptionElementsLoaded(cp, section);
                Object sectionScope = Global.invoke(section, "getSubscriptionScope"); //$NON-NLS-1$
                if (sectionScope instanceof Iterable)
                {
                    int fromSectionScope = indexSubscriptionDescriptions((Iterable<?>) sectionScope, index,
                            eventNames);
                    if (fromSectionScope > 0)
                        return;
                }
                int fromField = indexSubscriptionDescriptions(
                        resolveSubscriptionDescriptions(cp, section), index, eventNames);
                if (fromField > 0)
                    return;
            }
            IProject project = resolveProjectForOutline(cp, viewer, contextHost);
            Object scopeProvider = Global.getField(cp, "scopeProvider"); //$NON-NLS-1$
            if (project == null || scopeProvider == null)
                return;
            Object reference = MdClassPackage.Literals.CONFIGURATION__EVENT_SUBSCRIPTIONS;
            Object scope = resolveSubscriptionScopeObject(cp, scopeProvider, project, section, reference);
            if (scope == null)
                return;
            Object elements = Global.invoke(scope, "getAllElements"); //$NON-NLS-1$
            if (elements instanceof Iterable)
                indexSubscriptionDescriptions((Iterable<?>) elements, index, eventNames);
        }
        catch (Exception ignored)
        {
        }
    }

    private static IProject resolveProjectForOutline(ITreeContentProvider cp, TreeViewer viewer, Object contextHost)
    {
        if (cp != null)
        {
            Object cpProject = Global.getField(cp, "project"); //$NON-NLS-1$
            if (cpProject instanceof IProject)
                return (IProject) cpProject;
        }
        return resolveProjectFromOutlineViewer(viewer, contextHost);
    }

    private static Object resolveSubscriptionScopeObject(ITreeContentProvider cp, Object scopeProvider,
            IProject project, Object section, Object reference)
    {
        Object scope = Global.getField(cp, "scope"); //$NON-NLS-1$
        if (scope != null)
            return scope;
        boolean predicateFallback = false;
        Object predicate = section != null
                ? Global.invoke(cp, "getSubscriptionScope", section) //$NON-NLS-1$
                : null;
        if (predicate == null)
        {
            predicate = Predicates.alwaysTrue();
            predicateFallback = true;
        }
        scope = Global.invoke(scopeProvider, "getScope", project, reference, predicate); //$NON-NLS-1$
        if (scope == null && !predicateFallback)
            scope = Global.invoke(scopeProvider, "getScope", project, reference, Predicates.alwaysTrue()); //$NON-NLS-1$
        return scope;
    }

    private static ITreeContentProvider resolveBslOutlineContentProvider(TreeViewer viewer, Object contextHost)
    {
        ITreeContentProvider cp = unwrapContentProvider(viewer);
        if (cp != null)
            return cp;
        if (contextHost != null && BSL_QUICK_OUTLINE_POPUP.equals(contextHost.getClass().getName()))
        {
            Object customCp = Global.getField(contextHost, "customContentProvider"); //$NON-NLS-1$
            if (customCp instanceof ITreeContentProvider)
                return (ITreeContentProvider) customCp;
        }
        return null;
    }

    private static void warmOutlineContentProvider(ITreeContentProvider cp, Object input)
    {
        if (cp == null || input == null)
            return;
        java.util.Set<Object> seen = new java.util.IdentityHashMap<Object, Boolean>().keySet();
        try
        {
            for (Object root : cp.getElements(input))
                warmOutlineSubtree(cp, root, seen, 0);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void warmOutlineSubtree(ITreeContentProvider cp, Object element, java.util.Set<Object> seen, int depth)
    {
        if (element == null || cp == null || depth > 64 || !seen.add(element))
            return;
        try
        {
            if (!cp.hasChildren(element))
                return;
            for (Object child : cp.getChildren(element))
                warmOutlineSubtree(cp, child, seen, depth + 1);
        }
        catch (Exception ignored)
        {
        }
    }

    private static Object resolveEventSection(TreeViewer viewer, ITreeContentProvider cp)
    {
        Object fromTree = findEventSectionRoot(viewer);
        if (fromTree != null)
            return fromTree;
        if (cp == null)
            cp = unwrapContentProvider(viewer);
        if (cp == null)
            return null;
        Object fromField = Global.getField(cp, "eventSection"); //$NON-NLS-1$
        if (fromField != null && isEventSectionLike(fromField))
            return fromField;
        return null;
    }

    private static void ensureSubscriptionElementsLoaded(ITreeContentProvider cp, Object section)
    {
        if (cp == null || section == null)
            return;
        try
        {
            cp.getChildren(section);
        }
        catch (Exception ignored)
        {
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterable<?> resolveSubscriptionDescriptions(ITreeContentProvider cp, Object eventSection)
    {
        if (cp == null)
            return null;
        Object subs = Global.getField(cp, "subscriptionElements"); //$NON-NLS-1$
        if (subs instanceof Iterable)
            return (Iterable<?>) subs;
        if (eventSection != null)
        {
            Object sectionScope = Global.invoke(eventSection, "getSubscriptionScope"); //$NON-NLS-1$
            if (sectionScope instanceof Iterable)
                return (Iterable<?>) sectionScope;
        }
        return null;
    }

    private static int indexSubscriptionDescriptions(Iterable<?> descriptions, Map<String, String[]> index,
            Set<String> eventNames)
    {
        return indexSubscriptionDescriptions(descriptions, index, eventNames, null);
    }

    private static int indexSubscriptionDescriptions(Iterable<?> descriptions, Map<String, String[]> index,
            Set<String> eventNames, Map<String, String> eventDisplayByInternalName)
    {
        if (descriptions == null || index == null)
            return 0;
        int added = 0;
        for (Object description : descriptions)
        {
            String[] parts = parseSubscriptionTypeNames(description);
            if (parts == null || parts.length < 2)
                continue;
            if (indexSubscriptionLookupKeys(index, parts))
                added++;
            collectEventNameSuffixes(parts, eventNames);
            indexSubscriptionDescriptionNames(description, index, parts);
            if (eventDisplayByInternalName != null)
            {
                String eventInternal = parts[parts.length - 1];
                String eventDisplay = resolveEventDisplayNameFromSubscriptionDescription(description);
                if (eventInternal != null && !eventInternal.isEmpty() && eventDisplay != null
                        && !eventDisplay.isEmpty())
                    eventDisplayByInternalName.putIfAbsent(eventInternal, eventDisplay);
            }
        }
        return added;
    }

    private static String resolveEventDisplayNameFromSubscriptionDescription(Object description)
    {
        if (description == null)
            return null;
        String[] methods = { "getEventNameRu", "getNameRu", "getEventDisplayName", "getDisplayName" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String method : methods)
        {
            Object value = Global.invoke(description, method);
            if (value instanceof String && !((String) value).isEmpty())
                return stripEventSignature((String) value);
        }
        Object event = Global.invoke(description, "getEvent"); //$NON-NLS-1$
        if (event == null)
            event = Global.getField(description, "event"); //$NON-NLS-1$
        if (event != null)
        {
            String display = resolveOutlineEventDisplayName(event);
            if (display != null && !display.isEmpty())
                return stripEventSignature(display);
        }
        return null;
    }

    private static void indexSubscriptionDescriptionNames(Object description, Map<String, String[]> index,
            String[] parts)
    {
        if (description == null || index == null || parts == null)
            return;
        Object name = Global.invoke(description, "getName"); //$NON-NLS-1$
        if (name instanceof String && !((String) name).isEmpty())
        {
            index.putIfAbsent((String) name, parts);
            indexDescriptionNameAliases(index, (String) name, parts);
        }
        Object qualified = Global.invoke(description, "getQualifiedName"); //$NON-NLS-1$
        if (qualified instanceof String && !((String) qualified).isEmpty())
        {
            index.putIfAbsent((String) qualified, parts);
            indexDescriptionNameAliases(index, (String) qualified, parts);
        }
    }

    private static void indexDescriptionNameAliases(Map<String, String[]> index, String name, String[] parts)
    {
        if (index == null || name == null || name.isEmpty() || parts == null)
            return;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length())
            index.putIfAbsent(name.substring(dot + 1), parts);
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length())
            index.putIfAbsent(name.substring(slash + 1), parts);
    }

    private static boolean indexSubscriptionLookupKeys(Map<String, String[]> index, String[] parts)
    {
        if (index == null || parts == null || parts.length < 2)
            return false;
        boolean added = false;
        String glued = joinSubscriptionTypeNameParts(parts);
        if (!glued.isEmpty() && index.putIfAbsent(glued, parts) == null)
            added = true;
        String handlerOnly = joinSubscriptionTypeNameParts(copyTypeNamePartsWithoutLast(parts));
        if (!handlerOnly.isEmpty() && index.putIfAbsent(handlerOnly, parts) == null)
            added = true;
        String handlerSimple = resolveSubscriptionHandlerSimpleName(parts);
        if (!handlerSimple.isEmpty() && index.putIfAbsent(handlerSimple, parts) == null)
            added = true;
        if (parts.length == 2 && parts[0] != null && !parts[0].isEmpty()
                && index.putIfAbsent(parts[0], parts) == null)
            added = true;
        return added;
    }

    private static String resolveSubscriptionHandlerSimpleName(String[] parts)
    {
        if (parts == null || parts.length < 2)
            return ""; //$NON-NLS-1$
        for (int i = parts.length - 2; i >= 0; i--)
        {
            String part = parts[i];
            if (part == null || part.isEmpty() || part.startsWith(".")) //$NON-NLS-1$
                continue;
            int dot = part.lastIndexOf('.');
            return dot >= 0 ? part.substring(dot + 1) : part;
        }
        return ""; //$NON-NLS-1$
    }

    private static String[] lookupSubscriptionTypeNameParts(SubscriptionFlatLabels flatLabels, String glued)
    {
        if (flatLabels == null || glued == null || glued.isEmpty())
            return null;
        String[] fromProcedure = flatLabels.procedureNameIndex.get(glued);
        if (fromProcedure != null)
            return fromProcedure;
        if (flatLabels.gluedPartsIndex == null)
            return null;
        String[] direct = flatLabels.gluedPartsIndex.get(glued);
        if (direct != null)
            return direct;
        for (Map.Entry<String, String[]> entry : flatLabels.gluedPartsIndex.entrySet())
        {
            String[] parts = entry.getValue();
            if (parts == null || parts.length < 2)
                continue;
            String handlerOnly = joinSubscriptionTypeNameParts(copyTypeNamePartsWithoutLast(parts));
            if (glued.equals(handlerOnly))
                return parts;
            String handlerSimple = resolveSubscriptionHandlerSimpleName(parts);
            if (glued.equals(handlerSimple))
                return parts;
            if (!handlerOnly.isEmpty() && handlerOnly.endsWith(glued))
                return parts;
        }
        return null;
    }

    private static String joinSubscriptionTypeNameParts(String[] parts)
    {
        if (parts == null || parts.length == 0)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
        {
            if (part != null)
                sb.append(part);
        }
        return sb.toString();
    }

    private static String formatFlatFromTypeNameParts(String[] parts, String ownerModuleName, String handlerProcedure)
    {
        if (parts == null || parts.length < 2)
            return null;
        String eventPart = parts[parts.length - 1];
        if (eventPart == null || eventPart.isEmpty())
            return null;
        String handlerPart = handlerProcedure;
        if (handlerPart == null || handlerPart.isEmpty())
            handlerPart = resolveSubscriptionHandlerSimpleName(parts);
        if (handlerPart == null || handlerPart.isEmpty())
            handlerPart = joinSubscriptionTypeNameParts(copyTypeNamePartsWithoutLast(parts));
        if (handlerPart == null || handlerPart.isEmpty())
            return null;
        return formatFlatSubscriptionDisplay(eventPart, handlerPart, ownerModuleName);
    }

    private static String formatFlatSubscriptionDisplay(String eventName, String handlerProcedure,
            String ownerModuleName)
    {
        if (eventName == null || eventName.isEmpty() || handlerProcedure == null || handlerProcedure.isEmpty())
            return null;
        if (ownerModuleName != null && !ownerModuleName.isEmpty())
            return eventName + '/' + ownerModuleName + '.' + handlerProcedure;
        return eventName + '/' + handlerProcedure;
    }

    private static String formatTreeSubscriptionHandlerDisplay(String ownerModuleName, String handlerProcedure)
    {
        if (ownerModuleName == null || ownerModuleName.isEmpty()
                || handlerProcedure == null || handlerProcedure.isEmpty())
            return null;
        return ownerModuleName + '.' + handlerProcedure;
    }

    private static String[] copyTypeNamePartsWithoutLast(String[] parts)
    {
        if (parts == null || parts.length <= 1)
            return new String[0];
        String[] copy = new String[parts.length - 1];
        System.arraycopy(parts, 0, copy, 0, copy.length);
        return copy;
    }

    private static void collectEventNameSuffixes(String[] parts, Set<String> eventNames)
    {
        if (parts == null || eventNames == null || parts.length < 2)
            return;
        String eventPart = parts[parts.length - 1];
        if (eventPart != null && !eventPart.isEmpty() && !eventPart.startsWith(".")) //$NON-NLS-1$
            eventNames.add(eventPart);
    }

    private static void indexEventSectionSubscriptions(TreeViewer viewer, SubscriptionFlatLabels labels)
    {
        if (viewer == null || labels == null)
            return;
        ITreeContentProvider cp = unwrapContentProvider(viewer);
        Object section = resolveEventSection(viewer, cp);
        if (section == null || cp == null)
            return;
        ensureSubscriptionElementsLoaded(cp, section);
        Iterable<?> subscriptionDescriptions = resolveSubscriptionDescriptions(cp, section);
        indexEventSectionSubscriptionsSubtree(cp, section, labels.gluedPartsIndex, labels.eventNames, labels,
                subscriptionDescriptions);
    }

    private static void indexEventSectionSubscriptionsSubtree(ITreeContentProvider cp, Object element,
            Map<String, String[]> index, Set<String> eventNames, SubscriptionFlatLabels labels,
            Iterable<?> subscriptionDescriptions)
    {
        if (element == null || cp == null || index == null || eventNames == null)
            return;
        if (labels != null && isOutlineMethodElement(element)
                && !labels.elementSubscriptionParts.containsKey(element))
        {
            String[] envNames = resolveEnvironmentNames(element);
            if (envNames != null)
                putSubscriptionEnvironmentOnLeaf(labels, element, envNames);
        }
        if (isExtensionElement(element) && !isFormItemEventExtension(element))
        {
            String[] parts = resolveSubscriptionTypeNamesForOutlineElement(element, subscriptionDescriptions);
            if (parts == null && labels != null && !index.isEmpty())
            {
                String extName = resolveExtensionElementName(element);
                if (!extName.isEmpty())
                {
                    parts = lookupSubscriptionTypeNameParts(labels, extName);
                    if (parts == null)
                        parts = splitSubscriptionGluedName(extName, eventNames);
                }
            }
            if (parts != null)
            {
                indexSubscriptionLookupKeys(index, parts);
                collectEventNameSuffixes(parts, eventNames);
                if (labels != null)
                    mapSubscriptionPartsToDescendants(cp, element, parts, labels);
            }
            else
            {
                String glued = resolveExtensionElementName(element);
                if (!glued.isEmpty() && !eventNames.isEmpty())
                {
                    String[] split = splitSubscriptionGluedName(glued, eventNames);
                    if (split != null)
                    {
                        putSubscriptionParts(index, glued, split);
                        if (labels != null)
                            mapSubscriptionPartsToDescendants(cp, element, split, labels);
                    }
                }
            }
        }
        if (!cp.hasChildren(element))
            return;
        for (Object child : cp.getChildren(element))
            indexEventSectionSubscriptionsSubtree(cp, child, index, eventNames, labels, subscriptionDescriptions);
    }

    private static void mapSubscriptionPartsToDescendants(ITreeContentProvider cp, Object element, String[] parts,
            SubscriptionFlatLabels labels)
    {
        if (element == null || cp == null || parts == null || labels == null)
            return;
        if (isExtensionElement(element) || isOutlineEventHandlerElement(element)
                || element.getClass().getName().contains("MethodImpl")) //$NON-NLS-1$
        {
            labels.subscriptionOutlineLeaves.add(element);
            labels.elementSubscriptionParts.put(element, parts);
            String ownerModule = resolveOwnerModuleNameForEventHandler(element, resolveOutlineElementName(element),
                    labels);
            String flat = formatFlatFromTypeNameParts(parts, ownerModule, resolveOutlineElementName(element));
            if (flat != null)
                labels.elementFlatLabels.put(element, flat);
        }
        if (!cp.hasChildren(element))
            return;
        for (Object child : cp.getChildren(element))
            mapSubscriptionPartsToDescendants(cp, child, parts, labels);
    }

    private static boolean isFormItemEventExtension(Object element)
    {
        Object formItem = Global.invoke(element, "isFormItemEvent"); //$NON-NLS-1$
        return formItem instanceof Boolean && (Boolean) formItem;
    }

    private static String[] resolveSubscriptionTypeNamesForOutlineElement(Object element,
            Iterable<?> subscriptionDescriptions)
    {
        if (element == null)
            return null;
        String[] direct = parseSubscriptionTypeNames(element);
        if (direct != null)
            return direct;
        Object desc = Global.getField(element, "description"); //$NON-NLS-1$
        if (desc == null)
            desc = Global.invoke(element, "getDescription"); //$NON-NLS-1$
        if (desc == null)
            desc = Global.getField(element, "eObjectDescription"); //$NON-NLS-1$
        if (desc == null)
            desc = Global.invoke(element, "getEObjectDescription"); //$NON-NLS-1$
        String[] fromDesc = parseSubscriptionTypeNames(desc);
        if (fromDesc != null)
            return fromDesc;
        if (subscriptionDescriptions == null)
            return null;
        Object extUri = Global.invoke(element, "getUri"); //$NON-NLS-1$
        if (extUri != null)
        {
            for (Object subscriptionDescription : subscriptionDescriptions)
            {
                Object descUri = Global.invoke(subscriptionDescription, "getURI"); //$NON-NLS-1$
                if (descUri == null)
                    descUri = Global.invoke(subscriptionDescription, "getEObjectURI"); //$NON-NLS-1$
                if (descUri != null && descUri.equals(extUri))
                {
                    String[] parts = parseSubscriptionTypeNames(subscriptionDescription);
                    if (parts != null)
                        return parts;
                }
            }
        }
        if (!isExtensionElement(element))
            return null;
        String extName = resolveExtensionElementName(element);
        if (extName.isEmpty())
            return null;
        for (Object subscriptionDescription : subscriptionDescriptions)
        {
            String[] parts = parseSubscriptionTypeNames(subscriptionDescription);
            if (parts == null)
                continue;
            if (extName.equals(joinSubscriptionTypeNameParts(parts)))
                return parts;
            if (extName.equals(resolveSubscriptionHandlerSimpleName(parts)))
                return parts;
            String handlerOnly = joinSubscriptionTypeNameParts(copyTypeNamePartsWithoutLast(parts));
            if (!handlerOnly.isEmpty() && extName.endsWith(handlerOnly))
                return parts;
        }
        return null;
    }

    private static void putSubscriptionParts(Map<String, String[]> index, String glued, String[] parts)
    {
        if (index == null || glued == null || glued.isEmpty() || parts == null || parts.length < 2)
            return;
        indexSubscriptionLookupKeys(index, parts);
        if (!index.containsKey(glued))
            index.put(glued, parts);
    }

    private static Object findEventSectionRoot(TreeViewer viewer)
    {
        ITreeContentProvider tcp = unwrapContentProvider(viewer);
        if (tcp == null || viewer == null)
            return null;
        Object input = viewer.getInput();
        if (input == null)
            return null;
        Class<?> sectionClass = null;
        try
        {
            sectionClass = Class.forName(I_EVENT_SECTION, false, tcp.getClass().getClassLoader());
        }
        catch (ClassNotFoundException ignored)
        {
        }
        for (Object root : tcp.getElements(input))
        {
            Object found = findEventSectionInSubtree(tcp, root, sectionClass);
            if (found != null)
                return found;
        }
        return null;
    }

    private static Object findEventSectionInSubtree(ITreeContentProvider tcp, Object element, Class<?> sectionClass)
    {
        if (element == null || tcp == null)
            return null;
        if (isEventSectionLike(element) || (sectionClass != null && sectionClass.isInstance(element)))
            return element;
        if (!tcp.hasChildren(element))
            return null;
        for (Object child : tcp.getChildren(element))
        {
            Object found = findEventSectionInSubtree(tcp, child, sectionClass);
            if (found != null)
                return found;
        }
        return null;
    }

    private static boolean isEventSectionLike(Object element)
    {
        return element != null && element.getClass().getName().contains("EventSection"); //$NON-NLS-1$
    }
    private static String[] parseSubscriptionTypeNames(Object description)
    {
        Object typeNames = Global.invoke(description, "getUserData", TYPE_NAMES_USER_DATA); //$NON-NLS-1$
        if (!(typeNames instanceof String) || ((String) typeNames).isEmpty())
            return null;
        String[] parts = ((String) typeNames).split(",", -1); //$NON-NLS-1$
        return parts.length >= 2 ? parts : null;
    }

    private static IProject resolveProjectFromOutlineViewer(TreeViewer viewer, Object contextHost)
    {
        IProject fromInput = resolveProjectFromOutlineInput(viewer != null ? viewer.getInput() : null);
        if (fromInput != null)
            return fromInput;
        if (contextHost != null)
        {
            BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
            if (editor != null)
            {
                try
                {
                    org.eclipse.ui.IEditorInput input = editor.getEditorInput();
                    if (input != null)
                    {
                        org.eclipse.core.resources.IFile file = input.getAdapter(org.eclipse.core.resources.IFile.class);
                        if (file != null)
                            return file.getProject();
                    }
                }
                catch (Exception ignored) {}
            }
        }
        ITreeContentProvider cp = unwrapContentProvider(viewer);
        if (cp != null)
        {
            ITreeContentProvider delegate = cp instanceof SmartOutlineFlatContentProvider
                    ? ((SmartOutlineFlatContentProvider) cp).getDelegate() : cp;
            Object project = Global.getField(delegate, "project"); //$NON-NLS-1$
            if (project instanceof IProject)
                return (IProject) project;
        }
        return null;
    }

    private static IProject resolveProjectFromOutlineInput(Object input)
    {
        Object sourceFile = resolveSourceFileFromOutlineInput(input);
        if (sourceFile == null)
            return null;
        Object resource = Global.invoke(sourceFile, "getResource"); //$NON-NLS-1$
        if (resource == null)
            return null;
        Object project = Global.invoke(resource, "getProject"); //$NON-NLS-1$
        return project instanceof IProject ? (IProject) project : null;
    }

    private static Object resolveSourceFileFromOutlineInput(Object input)
    {
        try
        {
            Class<?> sourceFileClass = Class.forName("org.eclipse.handly.model.ISourceFile"); //$NON-NLS-1$
            Object cur = input;
            for (int depth = 0; depth < 32 && cur != null; depth++)
            {
                if (sourceFileClass.isInstance(cur))
                    return cur;
                cur = handlyParent(cur);
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    private static Object handlyParent(Object element)
    {
        try
        {
            Class<?> elements = Class.forName(HANDLY_ELEMENTS);
            return Global.invoke(elements, "getParent", element); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    /** Внутренний идентификатор mcore-события ({@code eventName} у {@code EventImpl}). */
    static String resolveOutlineEventInternalName(Object eventElement)
    {
        if (eventElement == null)
            return ""; //$NON-NLS-1$
        Object fromField = Global.getField(eventElement, "eventName"); //$NON-NLS-1$
        if (fromField instanceof String && !((String) fromField).isEmpty())
            return (String) fromField;
        return ""; //$NON-NLS-1$
    }

    /** Имя mcore-события: у {@code EventImpl} внутренний id — поле {@code eventName}. */
    static String resolveOutlineEventName(Object eventElement)
    {
        if (eventElement == null)
            return ""; //$NON-NLS-1$
        String internal = resolveOutlineEventInternalName(eventElement);
        if (!internal.isEmpty())
            return internal;
        Object fromGetName = Global.invoke(eventElement, "getName"); //$NON-NLS-1$
        if (fromGetName instanceof String && !((String) fromGetName).isEmpty())
            return (String) fromGetName;
        Object constructName = Global.getField(eventElement, "name"); //$NON-NLS-1$
        if (constructName instanceof String && !((String) constructName).isEmpty())
            return (String) constructName;
        return ""; //$NON-NLS-1$
    }

    /**
     * Отображаемое имя события — как {@code BslOutlineLabelProvider} для {@code IEvent}:
     * {@code IEvent.getName()}, затем {@code getText} label provider.
     */
    private static String resolveOutlineEventDisplayName(Object eventElement, IBaseLabelProvider labelSource)
    {
        if (eventElement == null)
            return ""; //$NON-NLS-1$
        if (isOutlineEvent(eventElement))
        {
            Object fromGetName = Global.invoke(eventElement, "getName"); //$NON-NLS-1$
            if (fromGetName instanceof String && !((String) fromGetName).isEmpty())
                return (String) fromGetName;
            if (labelSource != null)
            {
                String fromLabel = resolveEventNodeLabelText(labelSource, eventElement);
                if (!fromLabel.isEmpty())
                    return fromLabel;
            }
        }
        Object nameRu = Global.invoke(eventElement, "getNameRu"); //$NON-NLS-1$
        if (nameRu instanceof String && !((String) nameRu).isEmpty())
            return (String) nameRu;
        Object eventNameRu = Global.getField(eventElement, "eventNameRu"); //$NON-NLS-1$
        if (eventNameRu instanceof String && !((String) eventNameRu).isEmpty())
            return (String) eventNameRu;
        Object label = Global.getField(eventElement, "label"); //$NON-NLS-1$
        if (label instanceof String && !((String) label).isEmpty())
            return (String) label;
        return resolveOutlineEventInternalName(eventElement);
    }

    private static String resolveOutlineEventDisplayName(Object eventElement)
    {
        return resolveOutlineEventDisplayName(eventElement, null);
    }

    /** Внутренний класс EDT — только classloader бандла {@code bsl.ui}, не mcore/comfort. */
    private static Class<?> createEventHandlerActionClass() throws ClassNotFoundException
    {
        return Class.forName(CREATE_EVENT_HANDLER_ACTION, true, BslHandlerUtil.class.getClassLoader());
    }

    private static final class HandlerProposal
    {
        final String content;
        final int offset;
        final int clearLength;
        HandlerProposal(String content, int offset, int clearLength)
        {
            this.content = content;
            this.offset = offset;
            this.clearLength = clearLength;
        }
    }

    /** Штатная команда EDT «Создать обработчик события» — без лишних директив вроде {@code &НаСервере}. */
    private static boolean createEventHandlerViaEdtAction(TreeViewer viewer, Object outlineContext,
            Object contextHost, Object eventElement)
    {
        try
        {
            Object outlinePage = resolveOutlinePageForEdtHandler(viewer, outlineContext, contextHost);
            if (outlinePage == null)
            {
                return false;
            }
            Class<?> actionClass = createEventHandlerActionClass();
            Class<?> pageIface = Class.forName(COMMON_OUTLINE_PAGE, true, actionClass.getClassLoader());
            if (!pageIface.isInstance(outlinePage))
            {
                return false;
            }
            Constructor<?> ctor = actionClass.getConstructor(pageIface);
            Object action = ctor.newInstance(outlinePage);
            IStructuredSelection selection = new StructuredSelection(eventElement);
            if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed())
                viewer.setSelection(selection, true);
            Method selectionChanged = actionClass.getMethod("selectionChanged", IStructuredSelection.class); //$NON-NLS-1$
            selectionChanged.invoke(action, selection);
            BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
            int docLenBefore = -1;
            if (editor != null && editor.getDocument() != null)
                docLenBefore = editor.getDocument().getLength();
            boolean actionEnabled = Boolean.TRUE.equals(actionClass.getMethod("isEnabled").invoke(action)); //$NON-NLS-1$
            if (!actionEnabled)
            {
                return false;
            }
            actionClass.getMethod("run").invoke(action); //$NON-NLS-1$
            int docLenAfter = docLenBefore;
            if (editor != null && editor.getDocument() != null)
                docLenAfter = editor.getDocument().getLength();
            boolean docChanged = docLenBefore >= 0 && docLenAfter > docLenBefore;
            Object hasHandlerAfter = Global.invoke(eventElement, "hasHandler"); //$NON-NLS-1$
            boolean ok = Boolean.TRUE.equals(hasHandlerAfter) || docChanged;
            if (ok && !Boolean.TRUE.equals(hasHandlerAfter))
                Global.invoke(eventElement, "setHandler", Boolean.TRUE); //$NON-NLS-1$
            if (!ok)
            {
                return false;
            }
            if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed())
                viewer.refresh(eventElement);
            if (contextHost != null && BSL_QUICK_OUTLINE_POPUP.equals(contextHost.getClass().getName()))
                Global.invoke(contextHost, "close"); //$NON-NLS-1$
            return true;
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Global.logError(TAG, "create event handler via EDT action", //$NON-NLS-1$
                    cause instanceof Exception ? (Exception) cause : e);
            return false;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "create event handler via EDT action", e); //$NON-NLS-1$
            return false;
        }
    }

    private static Object resolveOutlinePageForEdtHandler(TreeViewer viewer, Object outlineContext, Object contextHost)
    {
        if (isCommonOutlinePage(outlineContext))
            return outlineContext;
        if (isCommonOutlinePage(contextHost))
            return contextHost;
        if (isBslOutlinePage(outlineContext))
            return outlineContext;
        if (isBslOutlinePage(contextHost))
            return contextHost;
        return createOutlinePageProxy(viewer, contextHost);
    }

    private static boolean isCommonOutlinePage(Object candidate)
    {
        if (candidate == null)
            return false;
        try
        {
            Class<?> pageIface = Class.forName(COMMON_OUTLINE_PAGE, false, candidate.getClass().getClassLoader());
            return pageIface.isInstance(candidate);
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }

    private static boolean createEventHandlerDirect(BslXtextEditor editor, Object eventElement,
            TreeViewer viewer, Object contextHost, String eventName) throws Exception
    {
        if (editor == null)
        {
            return false;
        }
        if (!(editor.getDocument() instanceof IXtextDocument))
        {
            return false;
        }
        IXtextDocument document = (IXtextDocument) editor.getDocument();
        URI uri = document.getResourceURI();
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (rsp == null)
        {
            return false;
        }
        IBslModuleContextDefService contextDefService = rsp.get(IBslModuleContextDefService.class);
        if (contextDefService == null)
        {
            return false;
        }
        IBslModuleEventsLookup eventsLookup = resolveEventsLookup(rsp);
        if (eventsLookup == null)
        {
            return false;
        }
        IModuleExtensionService extensionService = null;
        IModuleExtensionServiceProvider extProvider = rsp.get(IModuleExtensionServiceProvider.class);
        if (extProvider != null)
            extensionService = extProvider.getModuleExtensionService();
        BslModuleRegionsInfoServiceProvider regionsProvider =
                rsp.get(BslModuleRegionsInfoServiceProvider.class);
        IV8ProjectManager v8projectManager = rsp.get(IV8ProjectManager.class);
        IV8Project project = v8projectManager != null ? v8projectManager.getProject(uri) : null;
        boolean isRussian = project != null && BslUtil.isRussian(project);
        int docLength = document.getLength();
        int caretHint = resolveCaretOffset(BslHandlerUtil.getTextViewer(editor));
        if (caretHint <= 0)
            caretHint = docLength;
        int insertOffset = eventsLookup.getPositionOfInsertionEvent(document, caretHint);
        HandlerProposal proposal = buildHandlerProposal(document, contextDefService, extensionService, isRussian,
                eventsLookup, insertOffset, regionsProvider, eventName);
        if (proposal == null)
        {
            return false;
        }
        applyHandlerProposal(editor, eventElement, proposal);
        viewer.refresh(eventElement);
        if (contextHost != null && BSL_QUICK_OUTLINE_POPUP.equals(contextHost.getClass().getName()))
            Global.invoke(contextHost, "close"); //$NON-NLS-1$
        return true;
    }

    private static IBslModuleEventsLookup resolveEventsLookup(IResourceServiceProvider rsp)
    {
        IBslModuleEventsLookup lookup = rsp.get(IBslModuleEventsLookup.class);
        if (lookup != null)
            return lookup;
        return resolveEventsLookupViaPlugin();
    }

    private static IBslModuleEventsLookup resolveEventsLookupViaPlugin()
    {
        try
        {
            Class<?> pluginClass = Class.forName("com._1c.g5.v8.dt.internal.bsl.ui.BslUiPlugin"); //$NON-NLS-1$
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin == null)
                return null;
            Object injector = plugin.getClass().getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            if (injector == null)
                return null;
            return (IBslModuleEventsLookup) injector.getClass()
                    .getMethod("getInstance", Class.class).invoke(injector, IBslModuleEventsLookup.class); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static int resolveCaretOffset(ITextViewer textViewer)
    {
        if (textViewer == null)
            return 0;
        org.eclipse.swt.graphics.Point point = textViewer.getSelectedRange();
        return point != null ? point.x : 0;
    }

    private static HandlerProposal buildHandlerProposal(IXtextDocument document,
            IBslModuleContextDefService contextDefService, IModuleExtensionService extensionService,
            boolean isRussian, IBslModuleEventsLookup eventsLookup, int insertOffset,
            BslModuleRegionsInfoServiceProvider regionsProvider, String eventName) throws Exception
    {
        return document.readOnly((IUnitOfWork<HandlerProposal, XtextResource>) resource ->
        {
            Module root = (Module) resource.getParseResult().getRootASTElement();
            List<com._1c.g5.v8.dt.mcore.Event> events = contextDefService.getModuleEvents(root);
            com._1c.g5.v8.dt.mcore.Event mcoreEvent = findMcoreEvent(events, eventName);
            if (mcoreEvent == null)
                throw new IllegalStateException("event not found: " + eventName); //$NON-NLS-1$
            ParamSet paramSet = firstParamSet(mcoreEvent);
            boolean isExtension = extensionService != null && extensionService.isExtensionModule(root);
            boolean formModule = isFormModule(root, document.getResourceURI());
            if (formModule)
            {
                ProcedureParameters procedureParameters = new ProcedureParameters(ProcedureDirective.AT_CLIENT);
                if (isExtension)
                    procedureParameters.addPrefix(extensionService.getExtensionPrefix(root));
                procedureParameters.addPrefix(isRussian ? "После" : "After"); //$NON-NLS-1$ //$NON-NLS-2$
                if (isExtension)
                    procedureParameters.addSuffix(procedureParameters.getSuffix(isRussian));
                String content = invokeCreateInsertContentLikeEdt(document, eventsLookup, mcoreEvent, paramSet,
                        procedureParameters, insertOffset, isRussian);
                content = ensureSeparatingBlankLineBeforeHandler(document, insertOffset, content);
                return new HandlerProposal(content, insertOffset, 0);
            }
            ProcedureParameters procedureParameters;
            if (isExtension)
            {
                String extensionAnnotation = (isRussian ? "После" : "After") + eventName; //$NON-NLS-1$ //$NON-NLS-2$
                procedureParameters = new ProcedureParameters(ProcedureDirective.AT_CLIENT, extensionAnnotation);
                procedureParameters.addPrefix(extensionService.getExtensionPrefix(root));
            }
            else
            {
                // Модуль объекта: как в BslOutlineCreateEventHandlerAction — AT_CLIENT, без &НаСервере
                procedureParameters = new ProcedureParameters(ProcedureDirective.AT_CLIENT, ""); //$NON-NLS-1$
            }
            String content = invokeCreateInsertContentLikeEdt(document, eventsLookup, mcoreEvent, paramSet,
                    procedureParameters, insertOffset, isRussian);
            content = stripInvalidEventDirectivePlaceholder(content);
            if (regionsProvider == null)
            {
                content = ensureSeparatingBlankLineBeforeHandler(document, insertOffset, content);
                return new HandlerProposal(content, insertOffset, 0);
            }
            BslModuleEventData eventData = new BslModuleEventData(root);
            IBslModuleTextInsertInfo textInsertInfo = regionsProvider.getEventHandlerTextInsertInfo(document,
                    insertOffset, eventData);
            content = regionsProvider.wrap(textInsertInfo, content);
            content = stripInvalidEventDirectivePlaceholder(content);
            int applyOffset = textInsertInfo.getPosition();
            content = ensureSeparatingBlankLineBeforeHandler(document, applyOffset, content);
            return new HandlerProposal(content, applyOffset, textInsertInfo.getClearLength());
        });
    }

    /** Убрать некорректную директиву-заглушку {@code &<Событие>} из вставляемого текста. */
    private static String stripInvalidEventDirectivePlaceholder(String content)
    {
        if (content == null || content.isEmpty())
            return content;
        String ld = content.contains("\r\n") ? "\r\n" : (content.contains("\r") ? "\r" : "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String[] lines = content.split("\r\n|\r|\n", -1); //$NON-NLS-1$
        StringBuilder out = new StringBuilder(content.length());
        for (String line : lines)
        {
            String trimmed = line.trim();
            if ("&<Событие>".equals(trimmed) || "&<Event>".equals(trimmed)) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            if (out.length() > 0)
                out.append(ld);
            out.append(line);
        }
        return out.toString();
    }

    /** Пустая строка-разделитель перед новым обработчиком, как в конфигураторе. */
    private static String ensureSeparatingBlankLineBeforeHandler(IXtextDocument document, int offset, String content)
            throws BadLocationException
    {
        if (content == null || content.isEmpty() || offset <= 0)
            return content;
        int safeOffset = Math.min(offset, Math.max(0, document.getLength() - 1));
        String ld = document.getLineDelimiter(document.getLineOfOffset(safeOffset));
        if (ld == null || ld.isEmpty())
            ld = "\r\n"; //$NON-NLS-1$

        if (hasBlankLineBefore(document, offset))
        {
            return stripLeadingLineDelimiters(content);
        }

        if (countLeadingLineDelimiters(content) >= 2)
        {
            return content;
        }

        String body = stripLeadingLineDelimiters(content);
        StringBuilder prefix = new StringBuilder();
        char charBefore = document.getChar(offset - 1);
        if (charBefore != '\n' && charBefore != '\r')
            prefix.append(ld);
        prefix.append(ld);
        return prefix.toString() + body;
    }

    private static boolean hasBlankLineBefore(IXtextDocument document, int offset) throws BadLocationException
    {
        if (offset <= 0)
            return true;
        int length = document.getLength();
        if (length <= 0)
            return true;
        int line = document.getLineOfOffset(Math.min(offset, length - 1));
        int lineStart = document.getLineOffset(line);
        if (offset == lineStart && document.getLineLength(line) == 0)
            return true;
        if (offset == lineStart && document.get(lineStart, document.getLineLength(line)).trim().isEmpty())
            return true;
        if (line > 0)
        {
            int prevStart = document.getLineOffset(line - 1);
            if (document.get(prevStart, document.getLineLength(line - 1)).trim().isEmpty())
                return true;
        }
        return false;
    }

    private static String stripLeadingLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return text;
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\r')
            {
                i++;
                if (i < text.length() && text.charAt(i) == '\n')
                    i++;
            }
            else if (c == '\n')
                i++;
            else
                break;
        }
        return text.substring(i);
    }

    private static int countLeadingLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return 0;
        int count = 0;
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\r')
            {
                i++;
                if (i < text.length() && text.charAt(i) == '\n')
                    i++;
                count++;
            }
            else if (c == '\n')
            {
                i++;
                count++;
            }
            else
                break;
        }
        return count;
    }

    /**
     * Как {@code BslOutlineCreateEventHandlerAction.createInsertContentProposals}:
     * {@code eventsLookup.createInsertContentProposals(..., annotation, true, false, ...)}.
     */
    private static String invokeCreateInsertContentLikeEdt(IXtextDocument document,
            IBslModuleEventsLookup eventsLookup, com._1c.g5.v8.dt.mcore.Event mcoreEvent, ParamSet paramSet,
            ProcedureParameters procedureParameters, int insertOffset, boolean isRussian)
    {
        String eventLabel = isRussian ? mcoreEvent.getNameRu() : mcoreEvent.getName();
        String procedureAnnotation = eventLabel + procedureParameters.getSuffix(isRussian);
        return eventsLookup.createInsertContentProposals(document, mcoreEvent, paramSet, procedureParameters,
                procedureAnnotation, true, false, insertOffset, isRussian);
    }

    private static boolean isFormModule(Module root, URI uri)
    {
        if (root != null && root.getModuleType() == ModuleType.FORM_MODULE)
            return true;
        if (uri == null)
            return false;
        String path = uri.toString().replace('\\', '/');
        return path.endsWith("/Module.bsl") //$NON-NLS-1$
                && (path.contains("/Forms/") || path.contains("/CommonForms/")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Русские имена событий модуля из {@code IBslModuleContextDefService} (как в EDT при создании обработчика). */
    private static void indexMcoreEventDisplayNames(Object contextHost, SubscriptionFlatLabels labels)
    {
        if (labels == null || labels.mcoreEventNamesIndexed)
            return;
        labels.mcoreEventNamesIndexed = true;
        BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
        if (editor == null || !(editor.getDocument() instanceof IXtextDocument))
            return;
        IXtextDocument document = (IXtextDocument) editor.getDocument();
        org.eclipse.emf.common.util.URI resourceUri = document.getResourceURI();
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resourceUri);
        if (rsp == null)
            return;
        IBslModuleContextDefService contextDefService = rsp.get(IBslModuleContextDefService.class);
        if (contextDefService == null)
            return;
        try
        {
            document.readOnly((IUnitOfWork<Void, XtextResource>) resource ->
            {
                if (resource.getParseResult() == null)
                    return null;
                Module root = (Module) resource.getParseResult().getRootASTElement();
                if (root == null)
                    return null;
                List<com._1c.g5.v8.dt.mcore.Event> events = contextDefService.getModuleEvents(root);
                if (events == null)
                    return null;
                for (com._1c.g5.v8.dt.mcore.Event event : events)
                {
                    if (event == null)
                        continue;
                    String internal = event.getName();
                    String ru = event.getNameRu();
                    if (internal == null || internal.isEmpty() || ru == null || ru.isEmpty()
                            || internal.equals(ru))
                        continue;
                    labels.eventDisplayByInternalName.putIfAbsent(internal, stripEventSignature(ru));
                }
                return null;
            });
        }
        catch (Exception ignored)
        {
        }
    }

    /** mcore-событие модуля по имени (как при создании обработчика в EDT). */
    static com._1c.g5.v8.dt.mcore.Event resolveMcoreEvent(IXtextDocument document, String eventName)
    {
        if (document == null || eventName == null || eventName.isEmpty())
            return null;
        URI resourceUri = document.getResourceURI();
        if (resourceUri == null)
            return null;
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resourceUri);
        if (rsp == null)
            return null;
        IBslModuleContextDefService contextDefService = rsp.get(IBslModuleContextDefService.class);
        if (contextDefService == null)
            return null;
        try
        {
            return document.readOnly((IUnitOfWork<com._1c.g5.v8.dt.mcore.Event, XtextResource>) resource ->
            {
                Module root = (Module) resource.getParseResult().getRootASTElement();
                List<com._1c.g5.v8.dt.mcore.Event> events = contextDefService.getModuleEvents(root);
                return findMcoreEvent(events, eventName);
            });
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static com._1c.g5.v8.dt.mcore.Event findMcoreEvent(List<com._1c.g5.v8.dt.mcore.Event> events,
            String eventName)
    {
        if (events == null || eventName == null || eventName.isEmpty())
            return null;
        for (com._1c.g5.v8.dt.mcore.Event candidate : events)
        {
            if (candidate == null)
                continue;
            if (eventName.equals(candidate.getName()) || eventName.equals(candidate.getNameRu()))
                return candidate;
        }
        return null;
    }

    private static ParamSet firstParamSet(com._1c.g5.v8.dt.mcore.Event mcoreEvent)
    {
        if (mcoreEvent == null || mcoreEvent.getParamSet().isEmpty())
            return null;
        return mcoreEvent.getParamSet().get(0);
    }

    private static void applyHandlerProposal(BslXtextEditor editor, Object eventElement, HandlerProposal proposal)
            throws BadLocationException
    {
        IXtextDocument document = (IXtextDocument) editor.getDocument();
        String content = proposal.content;
        int offset = proposal.offset;
        int clearLength = proposal.clearLength;
        document.modify(resource ->
        {
            try
            {
                document.replace(offset, clearLength, content);
            }
            catch (BadLocationException e)
            {
                throw new RuntimeException(e);
            }
            return null;
        });
        Global.invoke(eventElement, "setHandler", Boolean.TRUE); //$NON-NLS-1$
        int revealOffset = offset;
        int revealLength = content != null ? content.length() : 0;
        try
        {
            Object info = Global.invoke(eventElement, "getSourceElementInfo"); //$NON-NLS-1$
            if (info != null)
            {
                Object range = Global.invoke(info, "getIdentifyingRange"); //$NON-NLS-1$
                if (range != null)
                {
                    revealOffset = ((Number) Global.invoke(range, "getOffset")).intValue(); //$NON-NLS-1$
                    revealLength = ((Number) Global.invoke(range, "getLength")).intValue(); //$NON-NLS-1$
                }
            }
        }
        catch (Exception ignored) {}
        editor.selectAndReveal(revealOffset, revealLength);
    }

    private static boolean isActivatableEvent(TreeViewer viewer, Object element)
    {
        if (element == null)
            return false;
        try
        {
            ClassLoader cl = element.getClass().getClassLoader();
            Class<?> eventClass = Class.forName(I_EVENT, true, cl);
            if (!eventClass.isInstance(element))
                return false;
            Object hasHandler = Global.invoke(element, "hasHandler"); //$NON-NLS-1$
            if (hasHandler instanceof Boolean && (Boolean) hasHandler)
                return false;
            if (hasHandlerChildInTree(viewer, element))
                return false;
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static boolean hasHandlerChildInTree(TreeViewer viewer, Object element)
    {
        try
        {
            ClassLoader cl = element.getClass().getClassLoader();
            Class<?> handlerClass = Class.forName(I_EVENT_HANDLER, true, cl);
            Class<?> methodClass = Class.forName(METHOD_IMPL, true, cl);
            ITreeContentProvider cp = unwrapContentProvider(viewer);
            if (cp == null)
                return false;
            for (Object child : cp.getChildren(element))
            {
                if (handlerClass.isInstance(child))
                    return true;
                if (methodClass.isInstance(child) && isEventMethodOutlineChild(child))
                    return true;
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    private static boolean isEventMethodOutlineChild(Object child)
    {
        Object isEvent = Global.invoke(child, "isEvent"); //$NON-NLS-1$
        return isEvent instanceof Boolean && (Boolean) isEvent;
    }

    private static boolean isFlatOutlineFiltering(TreeViewer viewer)
    {
        if (viewer == null)
            return false;
        Object cp = viewer.getContentProvider();
        return cp instanceof SmartOutlineFlatContentProvider;
    }

    private static ITreeContentProvider unwrapContentProvider(TreeViewer viewer)
    {
        if (viewer == null)
            return null;
        Object cp = viewer.getContentProvider();
        if (cp instanceof SmartOutlineFlatContentProvider)
            cp = ((SmartOutlineFlatContentProvider) cp).getDelegate();
        return cp instanceof ITreeContentProvider ? (ITreeContentProvider) cp : null;
    }

    /** Не сбрасывать {@code hasHandler} в плоском Quick Outline — дети события в дереве не видны. */
    private static void prepareEventForHandlerCreation(TreeViewer viewer, Object eventElement)
    {
        if (isFlatOutlineFiltering(viewer))
            return;
        Object hasHandler = Global.invoke(eventElement, "hasHandler"); //$NON-NLS-1$
        if (hasHandler instanceof Boolean && (Boolean) hasHandler && !hasHandlerChildInTree(viewer, eventElement))
            Global.invoke(eventElement, "setHandler", Boolean.FALSE); //$NON-NLS-1$
    }

    private static void expandEventSection(TreeViewer viewer)
    {
        ITreeContentProvider cp = unwrapContentProvider(viewer);
        Object section = resolveEventSection(viewer, cp);
        if (section != null)
            viewer.setExpandedState(section, true);
    }

    private static void updateQuickOutlineInfoText(Object popup, boolean eventsVisible)
    {
        String field = eventsVisible
                ? "BslQuickOutlinePopup_Press_to_hide_events_section" //$NON-NLS-1$
                : "BslQuickOutlinePopup_Press_to_show_events_section"; //$NON-NLS-1$
        try
        {
            Class<?> messages = Class.forName(OUTLINE_MESSAGES);
            Object text = Global.getField(messages, field);
            if (text instanceof String && !((String) text).isEmpty())
                Global.invoke(popup, "setInfoText", text); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static boolean isBslOutlinePage(Object page)
    {
        return page != null && page.getClass().getName().contains("BslOutlinePage"); //$NON-NLS-1$
    }

    /** {@code ContentOutline#getCurrentPage()} часто — {@code MultiPageDelegatingContentOutlinePage}. */
    private static Object resolveContentOutlineBslPage(Object outlinePage)
    {
        if (outlinePage == null)
            return null;
        if (isBslOutlinePage(outlinePage))
            return outlinePage;
        if (MULTI_PAGE_DELEGATING_OUTLINE_PAGE.equals(outlinePage.getClass().getName()))
        {
            Object current = Global.getField(outlinePage, "currentPage"); //$NON-NLS-1$
            if (isBslOutlinePage(current))
                return current;
        }
        return null;
    }

    private static final class OutlinePageProxyHandler implements InvocationHandler
    {
        private final TreeViewer viewer;
        private final Object contextHost;
        OutlinePageProxyHandler(TreeViewer viewer, Object contextHost)
        {
            this.viewer = viewer;
            this.contextHost = contextHost;
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            String name = method.getName();
            if ("getTreeViewer".equals(name)) //$NON-NLS-1$
                return viewer;
            if ("getEditor".equals(name)) //$NON-NLS-1$
            {
                if (contextHost != null && isBslOutlinePage(contextHost))
                {
                    Object editor = Global.invoke(contextHost, "getEditor"); //$NON-NLS-1$
                    if (editor != null)
                        return editor;
                }
                BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
                return editor;
            }
            if ("getSelection".equals(name)) //$NON-NLS-1$
            {
                if (viewer != null && !viewer.getControl().isDisposed())
                    return viewer.getSelection();
                return StructuredSelection.EMPTY;
            }
            if ("setSelection".equals(name) && args != null && args.length >= 2 && viewer != null //$NON-NLS-1$
                    && !viewer.getControl().isDisposed())
            {
                viewer.setSelection((ISelection) args[0], ((Boolean) args[1]).booleanValue());
                return null;
            }
            if ("addSelectionChangedListener".equals(name) && args != null && args.length >= 1 //$NON-NLS-1$
                    && viewer != null && !viewer.getControl().isDisposed())
            {
                viewer.addSelectionChangedListener((ISelectionChangedListener) args[0]);
                return null;
            }
            if ("removeSelectionChangedListener".equals(name) && args != null && args.length >= 1 //$NON-NLS-1$
                    && viewer != null && !viewer.getControl().isDisposed())
            {
                viewer.removeSelectionChangedListener((ISelectionChangedListener) args[0]);
                return null;
            }
            if (contextHost != null && !BSL_QUICK_OUTLINE_POPUP.equals(contextHost.getClass().getName()))
            {
                try
                {
                    return method.invoke(contextHost, args);
                }
                catch (Exception ignored) {}
            }
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class)
                return Boolean.FALSE;
            if (ret == int.class)
                return Integer.valueOf(0);
            return null;
        }
    }

    /**
     * Полная схема модуля ({@code ContentOutline}): подпись обработчика подписки
     * {@code ИмяМодуля.ИмяОбработчика}. Индекс читается с {@code Tree#getData} при каждой отрисовке.
     */
    private static final class OutlinePageSubscriptionStyledLabelWrapper extends LabelProvider
            implements IStyledLabelProvider
    {
        private final IStyledLabelProvider delegate;
        private final IBaseLabelProvider labelSource;
        private final Tree tree;

        OutlinePageSubscriptionStyledLabelWrapper(IStyledLabelProvider delegate, IBaseLabelProvider labelSource,
                Tree tree)
        {
            this.delegate = delegate;
            this.labelSource = labelSource;
            this.tree = tree;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = delegate != null ? delegate.getStyledText(element) : null;
            String text = styled != null ? styled.getString() : ""; //$NON-NLS-1$
            SubscriptionFlatLabels labels = getOutlinePageSubscriptionFlatLabels(tree);
            if (labels != null)
            {
                String treeLabel = resolveTreeSubscriptionHandlerLabel(element, labelSource, labels);
                if (treeLabel != null)
                    text = treeLabel;
            }
            if (styled != null && text.equals(styled.getString()))
                return styled;
            return new StyledString(text != null ? text : ""); //$NON-NLS-1$
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate != null ? delegate.getImage(element) : null;
        }

        @Override
        public void dispose()
        {
            if (delegate != null)
                delegate.dispose();
            super.dispose();
        }
    }

    static final class FlatSubscriptionHandlerLabelProvider implements ILabelProvider
    {
        private final ILabelProvider delegate;
        private final IBaseLabelProvider labelSource;
        private SmartOutlineFilter filter;
        private SubscriptionFlatLabels flatLabels;

        FlatSubscriptionHandlerLabelProvider(ILabelProvider delegate, IBaseLabelProvider labelSource)
        {
            this.delegate = delegate;
            this.labelSource = labelSource;
        }

        void bindFilter(SmartOutlineFilter filter)
        {
            this.filter = filter;
        }

        void bindSubscriptionFlatLabels(SubscriptionFlatLabels flatLabels)
        {
            this.flatLabels = flatLabels;
        }

        @Override
        public String getText(Object element)
        {
            if (filter != null && filter.isFlattenWhenFiltered() && filter.isFiltering())
            {
                String flat = BslOutlineEventsSupport.resolveFlatDisplayLabel(element, labelSource, flatLabels);
                if (flat != null)
                    return flat;
            }
            else if (flatLabels != null)
            {
                String tree = BslOutlineEventsSupport.resolveTreeSubscriptionHandlerLabel(element, labelSource,
                        flatLabels);
                if (tree != null)
                    return tree;
            }
            return delegate.getText(element);
        }

        @Override
        public org.eclipse.swt.graphics.Image getImage(Object element)
        {
            return delegate.getImage(element);
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            delegate.addListener(listener);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            delegate.removeListener(listener);
        }
    }

    /** Диагностика подписей подписок в BSL outline (только при «Общем логировании»). */
    private static final class BslOutlineDebug
    {
        private static final String TAG = "BslOutline"; //$NON-NLS-1$

        private BslOutlineDebug() {}

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void step(String phase, String detail)
        {
            if (isEnabled())
                Global.log(TAG, phase + " " + detail); //$NON-NLS-1$
        }
    }

}
