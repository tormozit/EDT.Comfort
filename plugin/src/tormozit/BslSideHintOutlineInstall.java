package tormozit;

import java.util.function.Consumer;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.util.OpenStrategy;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import org.eclipse.jface.internal.text.InformationControlReplacer;
import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IInformationControlExtension2;
import org.eclipse.jface.text.IInformationControlExtension3;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.TreeItem;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.ui.hover.BslDispatchingEObjectTextHover;
import com._1c.g5.v8.dt.mcore.Event;
import java.util.Iterator;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.handly.model.ISourceElementExtension;
import org.eclipse.handly.model.ISourceElementInfo;
import org.eclipse.handly.util.TextRange;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

/**
 * Боковая подсказка для BSL outline-popup (Quick Outline и аналоги). ContentOutline — вне scope.
 */
public final class BslSideHintOutlineInstall
{

    static final String ROW_HOVER_HINT_KEY = "tormozit.outlineRowHoverHint"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.bslSideHintInstalled"; //$NON-NLS-1$
    private static final String PRESENTER_KEY = "tormozit.bslSideHintPresenter"; //$NON-NLS-1$
    private static final String PENDING_KEY = "tormozit.bslSideHintPending"; //$NON-NLS-1$
    static final String SUPPRESS_SELECTION_KEY = "tormozit.bslSideHintSuppressSelection"; //$NON-NLS-1$
    private static final String LAST_HINT_ELEMENT_KEY = "tormozit.bslSideHintLastElement"; //$NON-NLS-1$
    private static final String WORDS_TABLE_READY_KEY = "tormozit.bslSideHintWordsTableReady"; //$NON-NLS-1$
    private static final String CONTEXT_HOST_KEY = "tormozit.bslSideHintContextHost"; //$NON-NLS-1$
    private static final String LAST_BASE_HINT_KEY = "tormozit.bslSideHintLastBaseHint"; //$NON-NLS-1$
    private static final String METHOD_IMPL_CLASS = "com._1c.g5.v8.dt.internal.bsl.core.MethodImpl"; //$NON-NLS-1$
    private static final String I_EXTENSION_ELEMENT = "com._1c.g5.v8.dt.bsl.core.IExtensionElement"; //$NON-NLS-1$
    /** EDT offset 0 → позиция каретки 1 в ИР при sync перед {@code ЗаполнитьТаблицуСлов}. */
    private static final int WORDS_TABLE_SYNC_CARET_OFFSET = 0;
    private static final int DEBOUNCE_MS = OpenStrategy.getPostSelectionDelay();
    private BslSideHintOutlineInstall() {}

    public static void installIfBsl(TreeViewer viewer, Object contextHost, String dialogName)
    {

        if (!ComfortSettings.isReplaceListFiltersEnabled() || viewer == null)
            return;
        if (!isBslOutlinePopup(dialogName, viewer))
        {

            return;
        }

        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(INSTALLED_KEY)))
            return;
        tree.setData(INSTALLED_KEY, Boolean.TRUE);
        final BslSideHintPresenter presenter = new BslSideHintPresenter();
        presenter.install(tree);
        tree.setData(PRESENTER_KEY, presenter);
        tree.setData(CONTEXT_HOST_KEY, contextHost);
        final int[] generation = new int[1];
        tree.setData("tormozit.bslSideHintGeneration", generation); //$NON-NLS-1$

        final Shell outlineShell = tree.getShell();
        final Object popupDialog = outlineShell.getData();
        final java.lang.reflect.Field[] listenToDeactivateField = new java.lang.reflect.Field[1];

        if (popupDialog != null) {
            listenToDeactivateField[0] = findField(popupDialog, "listenToDeactivate"); //$NON-NLS-1$
        }

        // Глобальный фильтр: клик вне outline + подсказки → закрываем outline
        final Listener globalMouseFilter = event -> {
            if (event.type != SWT.MouseDown || event.button != 1) return;
            if (!(event.widget instanceof Control clicked)) return;

            Shell clickedShell = clicked.getShell();
            boolean inOutline = clickedShell == outlineShell;
            boolean inHint = presenter.isClickInsideInformationControl(clicked);

            if (!inOutline && !inHint) {
                if (!outlineShell.isDisposed()) {
                    outlineShell.close();
                }
            }
        };
        tree.getDisplay().addFilter(SWT.MouseDown, globalMouseFilter);

        ISelectionChangedListener listener = new ISelectionChangedListener() {

            @Override
            public void selectionChanged(SelectionChangedEvent event)
            {

                if (Boolean.TRUE.equals(tree.getData(SUPPRESS_SELECTION_KEY)))
                    return;
                if (!(event.getSelection() instanceof IStructuredSelection))
                {

                    presenter.clearHint();
                    return;
                }

                IStructuredSelection sel = (IStructuredSelection) event.getSelection();
                if (sel.isEmpty())
                {

                    presenter.clearHint();
                    return;
                }

                final Object element = sel.getFirstElement();
                scheduleHintUpdate(tree, presenter, contextHost, element, ++generation[0]);
            }

        };
        viewer.addSelectionChangedListener(listener);
        tree.setData(ROW_HOVER_HINT_KEY, (Consumer<Object>) element -> scheduleHintUpdate(tree, presenter,
            contextHost, element, ++generation[0]));
        FilterListMouseCurrentSync.installForOutlineTree(viewer,
            () -> Boolean.TRUE.equals(tree.getData(SUPPRESS_SELECTION_KEY)));

        // Блокируем закрытие outline по Deactivate, пока показана боковая подсказка
        final Listener outlineDeactivateFilter = event -> {
            if (!(event.widget instanceof Shell)) return;
            if (event.widget != outlineShell) return;

            if (presenter.hasActiveInformationControl()) {
                if (listenToDeactivateField[0] != null && popupDialog != null) {
                    try {
                        listenToDeactivateField[0].setBoolean(popupDialog, false);
                    } catch (Exception ignored) {}
                }
            }
        };
        outlineShell.getDisplay().addFilter(SWT.Deactivate, outlineDeactivateFilter);

        tree.addDisposeListener(e -> {

            cancelPendingHintUpdate(tree);
            FilterListMouseCurrentSync.uninstall(tree);
            if (!outlineShell.isDisposed()) {
                outlineShell.getDisplay().removeFilter(SWT.Deactivate, outlineDeactivateFilter);
                outlineShell.getDisplay().removeFilter(SWT.MouseDown, globalMouseFilter);
            }
            presenter.dispose();
            tree.setData(PRESENTER_KEY, null);
            tree.setData(INSTALLED_KEY, null);
            tree.setData(PENDING_KEY, null);
            tree.setData("tormozit.bslSideHintGeneration", null); //$NON-NLS-1$
            tree.setData(ROW_HOVER_HINT_KEY, null);
            tree.setData(WORDS_TABLE_READY_KEY, null);
            tree.setData(CONTEXT_HOST_KEY, null);
            tree.setData(LAST_BASE_HINT_KEY, null);
        });
        ensureWordsTablePreparation(tree, contextHost);
        if (viewer.getSelection() instanceof IStructuredSelection sel && !sel.isEmpty())
            listener.selectionChanged(new SelectionChangedEvent(viewer, sel));
        BslSideHintDebug.log("installed on " + dialogName); //$NON-NLS-1$
    }

    private static java.lang.reflect.Field findField(Object obj, String fieldName) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /** После refresh фильтра — одно обновление подсказки без лишних selection-событий. */
    public static void refreshAfterFilter(TreeViewer viewer, Object contextHost)
    {

        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        BslSideHintPresenter presenter = (BslSideHintPresenter) tree.getData(PRESENTER_KEY);
        if (presenter == null)
            return;
        if (!(viewer.getSelection() instanceof IStructuredSelection sel) || sel.isEmpty())
        {

            presenter.clearHint();
            return;
        }

        applyHintForElement(tree, presenter, contextHost, sel.getFirstElement(), -1);
    }

    static void cancelPendingHintUpdate(Tree tree)
    {

        if (tree == null || tree.isDisposed())
            return;
        Runnable pending = (Runnable) tree.getData(PENDING_KEY);
        if (pending != null)
            tree.getDisplay().timerExec(-1, pending);
        tree.setData(PENDING_KEY, null);
    }

    private static void scheduleHintUpdate(Tree tree, BslSideHintPresenter presenter, Object contextHost,
            Object element, int gen)
    {
        BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(editor);
        if (session != null)
            IRSession.cancelActiveEvaluation(session);
        int peekOffset = BslSideHintResolver.peekSourceOffset(element);
        if (shouldSkipHintForElement(tree, presenter, element, peekOffset))
            return;
        Display display = tree.getDisplay();
        cancelPendingHintUpdate(tree);
        Runnable task = () -> {

            tree.setData(PENDING_KEY, null);
            applyHintForElement(tree, presenter, contextHost, element, gen);
        };
        tree.setData(PENDING_KEY, task);
        display.timerExec(DEBOUNCE_MS, task);
    }

    private static void applyHintForElement(Tree tree, BslSideHintPresenter presenter, Object contextHost,
            Object element, int gen)
    {

        if (tree.isDisposed())
            return;
        if (gen >= 0 && !isCurrentGeneration(tree, gen))
            return;
        int peekOffset = BslSideHintResolver.peekSourceOffset(element);
        if (shouldSkipHintForElement(tree, presenter, element, peekOffset))
            return;
        ITextViewer textViewer = resolveTextViewer(contextHost);
        if (textViewer == null)
        {

            presenter.clearHint();
            BslSideHintDebug.log("selection: no BSL text viewer"); //$NON-NLS-1$
            return;
        }

        BslItemSideHint hint = BslSideHintResolver.fromOutlineElement(element, textViewer);
        if (gen >= 0 && !isCurrentGeneration(tree, gen))
            return;
        if (hint == null || hint.isEmpty())
        {
            presenter.clearHint();
            tree.setData(LAST_HINT_ELEMENT_KEY, null);
            return;
        }

        presenter.updateHint(hint);
        tree.setData(LAST_HINT_ELEMENT_KEY, element);
        tree.setData(LAST_BASE_HINT_KEY, hint);
        scheduleIrMethodEnrichment(tree, presenter, contextHost, element, hint, gen);
    }

    private static void retryIrMethodEnrichmentIfNeeded(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (!Boolean.TRUE.equals(tree.getData(WORDS_TABLE_READY_KEY)))
            return;
        BslSideHintPresenter presenter = (BslSideHintPresenter) tree.getData(PRESENTER_KEY);
        Object contextHost = tree.getData(CONTEXT_HOST_KEY);
        Object element = tree.getData(LAST_HINT_ELEMENT_KEY);
        BslItemSideHint hint = (BslItemSideHint) tree.getData(LAST_BASE_HINT_KEY);
        if (presenter == null || contextHost == null || element == null || hint == null || hint.isEmpty())
            return;
        scheduleIrMethodEnrichment(tree, presenter, contextHost, element, hint, resolveGeneration(tree, -1));
    }

    private static void ensureWordsTablePreparation(Tree tree, Object contextHost)
    {
        if (tree == null || tree.isDisposed())
            return;
        Object state = tree.getData(WORDS_TABLE_READY_KEY);
        if (Boolean.TRUE.equals(state) || Boolean.FALSE.equals(state))
            return;
        scheduleWordsTablePreparation(tree, contextHost);
    }

    private static void scheduleWordsTablePreparation(Tree tree, Object contextHost)
    {
        BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
        if (editor == null)
            return;
        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null || !IRApplication.hasConnectedSessionForKeys(dtProject))
            return;
        IRSession session = IRApplication.getSession(dtProject);
        if (session == null || session.executor == null || session.executor.isShutdown())
            return;
        tree.setData(WORDS_TABLE_READY_KEY, Boolean.FALSE);
        IrBslExpressionHtmlSupport.prepareWordsTableAsync(session, editor, WORDS_TABLE_SYNC_CARET_OFFSET, () -> {
            if (tree.isDisposed())
                return;
            tree.setData(WORDS_TABLE_READY_KEY, Boolean.TRUE);
            BslSideHintDebug.log("wordsTable ready"); //$NON-NLS-1$
            retryIrMethodEnrichmentIfNeeded(tree);
        });
    }

    private static void scheduleIrMethodEnrichment(Tree tree, BslSideHintPresenter presenter,
            Object contextHost, Object element, BslItemSideHint baseHint, int gen)
    {
        if (element == null || baseHint == null || baseHint.isEmpty())
            return;
        String methodName = IrOutlineSideHintSupport.resolveOutlineMethodName(element);
        if (methodName == null || methodName.isEmpty())
            return;
        if (!Boolean.TRUE.equals(tree.getData(WORDS_TABLE_READY_KEY)))
        {
            ensureWordsTablePreparation(tree, contextHost);
            return;
        }
        BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(editor);
        if (session == null)
            return;
        final int irGen = resolveGeneration(tree, gen);
        final Object baseInput = baseHint.getControlInput();
        final IInformationControlCreator creator = baseHint.getControlCreator();
        final int sourceOffset = baseHint.getSourceOffset();
        session.executor.submit(() -> {
            String irHtml = IrBslExpressionHtmlSupport.fetchDescriptionHtml(session, methodName,
                IrBslExpressionHtmlSupport.KIND_METHOD);
            if (irHtml == null || irHtml.isBlank())
                return;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> {
                if (tree.isDisposed())
                    return;
                if (irGen >= 0 && !isCurrentGeneration(tree, irGen))
                {
                    BslSideHintDebug.step("ir skip", "stale gen=" + irGen + " method=" + methodName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return;
                }
                Object lastElement = tree.getData(LAST_HINT_ELEMENT_KEY);
                if (lastElement != null && !lastElement.equals(element))
                {
                    BslSideHintDebug.step("ir skip", "element changed method=" + methodName); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                String baseHtml = IrBslHoverHtml.readHtml(baseInput);
                String merged = IrBslHoverHtml.mergeHtml(baseHtml, irHtml);
                presenter.updateHint(new BslItemSideHint(merged, creator, sourceOffset));
                BslSideHintDebug.log("ir enriched method=" + methodName + " len=" + irHtml.length()); //$NON-NLS-1$ //$NON-NLS-2$
            });
        });
    }

    private static int resolveGeneration(Tree tree, int gen)
    {
        if (gen >= 0)
            return gen;
        Object g = tree.getData("tormozit.bslSideHintGeneration"); //$NON-NLS-1$
        return g instanceof int[] generation ? generation[0] : -1;
    }

    private static boolean shouldSkipHintForElement(Tree tree, BslSideHintPresenter presenter,
            Object element, int peekOffset)
    {

        if (peekOffset < 0 || !presenter.isShowingSameOffset(peekOffset))
            return false;
        Object last = tree.getData(LAST_HINT_ELEMENT_KEY);
        return element != null && element.equals(last);
    }

    private static boolean isCurrentGeneration(Tree tree, int gen)
    {

        Object g = tree.getData("tormozit.bslSideHintGeneration"); //$NON-NLS-1$
        return g instanceof int[] generation && generation[0] == gen;
    }

    static boolean isBslOutlinePopup(String dialogName, TreeViewer viewer)
    {

        if (dialogName != null)
        {

            if (dialogName.contains("BslQuickOutlinePopup")) //$NON-NLS-1$
                return true;
            String lower = dialogName.toLowerCase();
            if (lower.contains("bsl") && lower.contains("outline")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }

        if (viewer == null)
            return false;
        Object cp = viewer.getContentProvider();
        if (cp != null)
        {

            String cpName = cp.getClass().getName().toLowerCase();
            if (cpName.contains("bsl") && cpName.contains("outline")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }

        Object lp = viewer.getLabelProvider();
        if (lp != null)
        {

            String lpName = lp.getClass().getName().toLowerCase();
            if (lpName.contains("bsl") && lpName.contains("outline")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }

        return false;
    }

    private static ITextViewer resolveTextViewer(Object contextHost)
    {

        BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(contextHost);
        if (editor == null)
            return null;
        return editor.getInternalSourceViewer();
    }


    /**
     * Боковая подсказка у дерева outline-popup — тот же {@link IInformationControlCreator}, что у автодополнения.
     */
    private static final class BslSideHintPresenter extends AbstractInformationControlManager
    {

        private static final IInformationControlCreator PLACEHOLDER_CREATOR = new IInformationControlCreator() {

            @Override
            public IInformationControl createInformationControl(Shell parent)
            {

                return new DefaultInformationControl(parent);
            }

        };
        private volatile BslItemSideHint pendingHint;
        private int shownSourceOffset = -1;
        private int shownAreaX = Integer.MIN_VALUE;
        private int shownAreaY = Integer.MIN_VALUE;
        private Rectangle lastKnownArea;

        public BslSideHintPresenter()
        {

            super(PLACEHOLDER_CREATOR);
            setAnchor(ANCHOR_RIGHT);
            setFallbackAnchors(new Anchor[] { ANCHOR_LEFT, ANCHOR_TOP, ANCHOR_BOTTOM });
            setSizeConstraints(280, 120, true, true);
            takesFocusWhenVisible(false);
            InformationControlReplacer replacer = new InformationControlReplacer(PLACEHOLDER_CREATOR);
            getInternalAccessor().setInformationControlReplacer(replacer);
        }

        @Override
        public void install(Control subject)
        {

            super.install(subject);
        }

        public void updateHint(BslItemSideHint hint)
        {

            pendingHint = hint;

            if (hint == null || hint.isEmpty())
            {

                hideWithoutReset();
                return;
            }

            Control subject = getSubjectControl();
            Rectangle area = getSubjectArea();
            int offset = hint.getSourceOffset();
            if (hasReuseableControl() && offset >= 0 && offset == shownSourceOffset && area != null)
            {

                if (area.x == shownAreaX && area.y == shownAreaY)
                {

                    refreshContentInPlace(hint, area);
                    return;
                }

                rememberArea(area);
                repositionHint(area);
                shownAreaX = area.x;
                shownAreaY = area.y;
                return;
            }

            if (subject == null || subject.isDisposed())
                return;
            if (area == null)
                area = fallbackSubjectArea();
            if (area == null)
                return;
            rememberArea(area);
            if (hasReuseableControl())
            {

                refreshContentInPlace(hint, area);
                return;
            }

            shownSourceOffset = offset;
            shownAreaX = area.x;
            shownAreaY = area.y;
            showInformation();
        }

        public boolean isShowingSameOffset(int sourceOffset)
        {

            return hasReuseableControl() && sourceOffset >= 0 && sourceOffset == shownSourceOffset;
        }

        /** Скрыть панель, сохранив контрол для обновления на месте (без full show). */
        public void hideWithoutReset()
        {

            pendingHint = null;
            if (!hasReuseableControl())
                hideInformationControl();
        }

        public void clearHint()
        {

            pendingHint = null;
            shownSourceOffset = -1;
            shownAreaX = Integer.MIN_VALUE;
            shownAreaY = Integer.MIN_VALUE;
            lastKnownArea = null;
            hideInformationControl();
        }

        /**
         * Убрать дочерний shell подсказки до обработки SWT.Deactivate у {@link org.eclipse.jface.dialogs.PopupDialog}:
         * пока у outline-popup есть child shells, клик вне окна его не закрывает.
         */
        public void releaseInformationControlShell()
        {

            if (!hasReuseableControl())
                return;
            pendingHint = null;
            shownSourceOffset = -1;
            shownAreaX = Integer.MIN_VALUE;
            shownAreaY = Integer.MIN_VALUE;
            lastKnownArea = null;
            disposeInformationControl();
        }

        public boolean isClickInsideInformationControl(Control clicked)
        {
            IInformationControl current = getInternalAccessor().getCurrentInformationControl();
            if (current == null) return false;

            Shell clickedShell = clicked.getShell();

            try {
                Class<?> cls = current.getClass();
                while (cls != null) {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object val = f.get(current);
                        if (val instanceof Shell) {
                            Shell s = (Shell) val;
                            if (s == clickedShell) return true;
                        }
                    }
                    cls = cls.getSuperclass();
                }
            } catch (Exception ignored) {}

            return false;
        }

        public boolean hasActiveInformationControl() {
            return getInternalAccessor().getCurrentInformationControl() != null;
        }

        private void refreshContentInPlace(BslItemSideHint hint, Rectangle area)
        {

            pendingHint = hint;
            setCustomInformationControlCreator(hint.getControlCreator());
            IInformationControl control = getInternalAccessor().getCurrentInformationControl();
            if (control == null)
            {

                shownSourceOffset = hint.getSourceOffset();
                shownAreaX = area.x;
                shownAreaY = area.y;
                showInformation();
                return;
            }

            Object input = hint.getControlInput();
            if (input instanceof String html)
            {
                // Обогащённый HTML-строкой: setInput(String) бросает InstanceOf-ассерт в Xtext-контроле
                IrBslHoverHtml.applyHtmlToControl(control, html);
            }
            else if (control instanceof IInformationControlExtension2 ext2)
                ext2.setInput(input);
            else
                control.setInformation(input != null ? input.toString() : ""); //$NON-NLS-1$
            shownSourceOffset = hint.getSourceOffset();
            shownAreaX = area.x;
            shownAreaY = area.y;
            repositionHint(area);
            control.setVisible(true);
        }

        private void rememberArea(Rectangle area)
        {

            if (area == null)
                return;
            lastKnownArea = new Rectangle(area.x, area.y, area.width, area.height);
        }

        private Rectangle fallbackSubjectArea()
        {

            if (lastKnownArea != null)
            {

                return new Rectangle(lastKnownArea.x, lastKnownArea.y, lastKnownArea.width, lastKnownArea.height);
            }

            return null;
        }

        /**
         * Якорь ANCHOR_RIGHT: правый край видимой области дерева, на строку ниже выбранного элемента
         * (не по полной ширине текста — иначе подсказка уезжает за окно; сдвиг вниз — без пересечения с тултипом).
         */
        private static Rectangle outlineTreeAnchorArea(Tree tree, TreeItem item)
        {

            Rectangle itemBounds = item.getBounds();
            int clientW = tree.getClientArea().width;
            int lineH = tree.getItemHeight();
            return new Rectangle(0, itemBounds.y + lineH, clientW, itemBounds.height);
        }

        private boolean hasReuseableControl()
        {

            return getInternalAccessor().getCurrentInformationControl() != null;
        }

        private void repositionHint(Rectangle area)
        {

            IInformationControl control = getInternalAccessor().getCurrentInformationControl();
            if (control == null || area == null)
                return;
            Point size;
            if (control instanceof IInformationControlExtension3 ext3)
            {

                Rectangle bounds = ext3.getBounds();
                size = new Point(bounds.width, bounds.height);
            }

            else
            {

                size = control.computeSizeHint();
            }

            if (size == null)
                return;
            Point location = computeInformationControlLocation(area, size);
            control.setLocation(location);
            control.setVisible(true);
        }

        @Override
        protected void doShowInformation()
        {

            if (hasReuseableControl())
            {

                computeInformation();
                return;
            }

            super.doShowInformation();
        }

        @Override
        protected Rectangle getSubjectArea()
        {

            // Не super.getSubjectArea(): fSubjectArea менеджера залипает после первого show (canClearDataOnHide=false).
            Control subject = getSubjectControl();
            if (subject == null || subject.isDisposed())
                return null;
            if (subject instanceof Tree tree)
            {

                TreeItem[] selection = tree.getSelection();
                if (selection != null && selection.length > 0)
                {

                    TreeItem item = selection[0];
                    if (item != null && !item.isDisposed())
                    {

                        Rectangle area = outlineTreeAnchorArea(tree, item);
                        rememberArea(area);
                        return area;
                    }

                }

            }

            if (!hasReuseableControl() && subject != null)
            {

                Point size = subject.getSize();
                return new Rectangle(0, 0, size.x, size.y);
            }

            return fallbackSubjectArea();
        }

        @Override
        protected void computeInformation()
        {

            BslItemSideHint hint = pendingHint;
            Rectangle area = getSubjectArea();
            if (hint == null || hint.isEmpty())
            {

                if (!hasReuseableControl())
                    setInformation((Object) null, area);
                return;
            }

            if (area == null)
                area = fallbackSubjectArea();
            if (area == null)
                return;
            setCustomInformationControlCreator(hint.getControlCreator());
            setInformation(hint.getControlInput(), area);
        }

        @Override
        protected boolean canClearDataOnHide()
        {

            return false;
        }

    }


    /**
     * Единая генерация боковой подсказки: образец — {@code getAdditionalProposalInfo} автодополнения EDT.
     */
    private static final class BslSideHintResolver
    {

        private BslSideHintResolver() {}

        public static BslItemSideHint fromCompletionProposal(ICompletionProposal proposal, ITextViewer viewer)
        {

            if (proposal == null)
                return null;
            ICompletionProposal delegate = SmartContentAssistProcessor.unwrapProposal(proposal);
            IInformationControlCreator creator = null;
            Object info = null;
            if (delegate instanceof ICompletionProposalExtension3)
                creator = ((ICompletionProposalExtension3) delegate).getInformationControlCreator();
            if (delegate instanceof ICompletionProposalExtension5)
            {

                info = ((ICompletionProposalExtension5) delegate)
                        .getAdditionalProposalInfo(new NullProgressMonitor());
            }

            else
            {

                info = delegate.getAdditionalProposalInfo();
            }

            info = normalizeInfo(info);
            if (info == null || creator == null)
            {

                BslSideHintDebug.log("fromCompletionProposal: empty info=" + (info != null) //$NON-NLS-1$
                        + " creator=" + (creator != null)); //$NON-NLS-1$
                return null;
            }

            return new BslItemSideHint(info, creator);
        }

        public static BslItemSideHint fromOutlineElement(Object element, ITextViewer viewer)
        {

            return fromOutlineElement(element, viewer, new NullProgressMonitor());
        }

        public static BslItemSideHint fromOutlineElement(Object element, ITextViewer viewer,
                IProgressMonitor monitor)
        {

            if (element == null || viewer == null)
                return null;
            BslItemSideHint hint = tryFromSubscriptionHandler(element, viewer, monitor);
            if (hint != null)
                return hint;
            hint = tryFromOutlineEvent(element, viewer, monitor);
            if (hint != null)
                return hint;
            return fromSourceElementRange(element, viewer, monitor);
        }

        private static BslItemSideHint fromSourceElementRange(Object element, ITextViewer viewer,
                IProgressMonitor monitor)
        {

            int[] range = resolveSourceRange(element);
            if (range == null)
            {

                BslSideHintDebug.log("fromOutlineElement: no source range for " //$NON-NLS-1$
                        + element.getClass().getSimpleName());
                return null;
            }

            BslDispatchingEObjectTextHover hover = resolveBslHover(viewer);
            if (hover == null)
                return null;
            try
            {

                if (monitor != null && monitor.isCanceled())
                    return null;
                Region region = new Region(range[0], range[1]);
                Object info = hover.getHoverInfo2(viewer, region);
                info = normalizeInfo(info);
                IInformationControlCreator creator = hover.getHoverControlCreator();
                if (info == null || creator == null)
                {

                    BslSideHintDebug.log("fromOutlineElement: empty hover offset=" + range[0]); //$NON-NLS-1$
                    return null;
                }

                return new BslItemSideHint(info, creator, range[0]);
            }

            catch (Exception e)
            {

                BslSideHintDebug.problem("fromOutlineElement: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }

        }

        private static BslItemSideHint tryFromSubscriptionHandler(Object element, ITextViewer viewer,
                IProgressMonitor monitor)
        {

            if (!BslOutlineEventsSupport.isOutlineEventHandlerElement(element))
                return null;
            URI handlerUri = resolveHandlerUri(element);
            if (handlerUri == null)
                return null;
            if (!(viewer.getDocument() instanceof IXtextDocument contextDoc))
                return null;
            URI currentUri = contextDoc.getResourceURI();
            if (!isExternalHandlerUri(handlerUri, currentUri))
                return null;
            String handlerName = resolveHandlerName(element);
            ResolvedHandlerTarget target = resolveHandlerTarget(contextDoc, handlerUri, handlerName);
            if (target == null || target.eObject == null)
            {

                BslSideHintDebug.log("tryFromSubscriptionHandler: no target for " + handlerUri); //$NON-NLS-1$
                return null;
            }

            URI hoverResourceUri = target.resourceUri != null ? target.resourceUri
                    : trimResourceUri(handlerUri);
            BslDispatchingEObjectTextHover hover = resolveBslHover(hoverResourceUri);
            if (hover == null)
                return null;
            try
            {

                if (monitor != null && monitor.isCanceled())
                    return null;
                Region region = new Region(Math.max(0, target.offset), 1);
                Object info = hover.getHoverInfo(target.eObject, viewer, region);
                if (info == null)
                    info = hover.getHoverInfo2(viewer, region);
                info = normalizeInfo(info);
                IInformationControlCreator creator = hover.getHoverControlCreator();
                if (info == null || creator == null)
                {

                    BslSideHintDebug.log("tryFromSubscriptionHandler: empty hover uri=" + handlerUri); //$NON-NLS-1$
                    return null;
                }

                return new BslItemSideHint(info, creator, target.offset);
            }

            catch (Exception e)
            {

                BslSideHintDebug.problem("tryFromSubscriptionHandler: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }

        }

        private static BslItemSideHint tryFromOutlineEvent(Object element, ITextViewer viewer,
                IProgressMonitor monitor)
        {

            if (!BslOutlineEventsSupport.isOutlineEvent(element))
                return null;
            Object hasHandler = Global.invoke(element, "hasHandler"); //$NON-NLS-1$
            if (Boolean.TRUE.equals(hasHandler))
                return null;
            if (!(viewer.getDocument() instanceof IXtextDocument contextDoc))
                return null;
            String eventName = BslOutlineEventsSupport.resolveOutlineEventName(element);
            if (eventName.isEmpty())
                eventName = BslOutlineEventsSupport.resolveOutlineEventInternalName(element);
            if (eventName.isEmpty())
                return null;
            Event mcoreEvent = BslOutlineEventsSupport.resolveMcoreEvent(contextDoc, eventName);
            if (mcoreEvent == null)
            {

                BslSideHintDebug.log("tryFromOutlineEvent: mcore event not found: " + eventName); //$NON-NLS-1$
                return null;
            }

            BslDispatchingEObjectTextHover hover = resolveBslHover(viewer);
            if (hover == null)
                return null;
            try
            {

                if (monitor != null && monitor.isCanceled())
                    return null;
                Region region = new Region(0, 1);
                Object info = hover.getHoverInfo(mcoreEvent, viewer, region);
                info = normalizeInfo(info);
                IInformationControlCreator creator = hover.getHoverControlCreator();
                if (info == null || creator == null)
                {

                    BslSideHintDebug.log("tryFromOutlineEvent: empty hover for " + eventName); //$NON-NLS-1$
                    return null;
                }

                return new BslItemSideHint(info, creator, -1);
            }

            catch (Exception e)
            {

                BslSideHintDebug.problem("tryFromOutlineEvent: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }

        }

        private static Object normalizeInfo(Object info)
        {

            if (info instanceof StyledString)
                return ((StyledString) info).getString();
            return info;
        }

        public static int peekSourceOffset(Object element)
        {

            int[] range = resolveSourceRange(element);
            return range != null ? range[0] : -1;
        }

        private static int[] resolveSourceRange(Object element)
        {

            if (!(element instanceof ISourceElementExtension))
                return null;
            try
            {

                ISourceElementInfo info = ((ISourceElementExtension) element).getSourceElementInfo();
                if (info == null)
                    return null;
                TextRange range = info.getIdentifyingRange();
                if (range == null)
                    range = info.getFullRange();
                if (range == null)
                    return null;
                int offset = range.getOffset();
                int length = range.getLength();
                if (length <= 0)
                    length = 1;
                return new int[] { offset, length };
            }

            catch (CoreException e)
            {

                BslSideHintDebug.problem("resolveSourceRange: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }

        }

        private static URI resolveHandlerUri(Object element)
        {

            if (element == null)
                return null;
            Object uriValue = Global.invoke(element, "getHandlerURI"); //$NON-NLS-1$
            if (uriValue instanceof URI)
                return (URI) uriValue;
            uriValue = Global.getField(element, "handlerURI"); //$NON-NLS-1$
            if (uriValue instanceof URI)
                return (URI) uriValue;
            if (uriValue != null)
            {

                String text = String.valueOf(uriValue);
                if (!text.isEmpty())
                {

                    try
                    {

                        return URI.createURI(text);
                    }

                    catch (Exception ignored)
                    {

                    }

                }

            }

            return null;
        }

        private static String resolveHandlerName(Object element)
        {

            if (element == null)
                return ""; //$NON-NLS-1$
            Object fromGetName = Global.invoke(element, "getName"); //$NON-NLS-1$
            if (fromGetName instanceof String && !((String) fromGetName).isEmpty())
                return (String) fromGetName;
            Object fromField = Global.getField(element, "name"); //$NON-NLS-1$
            return fromField instanceof String ? (String) fromField : ""; //$NON-NLS-1$
        }

        private static boolean isExternalHandlerUri(URI handlerUri, URI currentModuleUri)
        {

            if (handlerUri == null || currentModuleUri == null)
                return false;
            URI handlerResource = trimResourceUri(handlerUri);
            URI currentResource = trimResourceUri(currentModuleUri);
            return handlerResource != null && currentResource != null
                    && !handlerResource.equals(currentResource);
        }

        private static URI trimResourceUri(URI uri)
        {

            if (uri == null)
                return null;
            return uri.hasFragment() ? uri.trimFragment() : uri;
        }

        private static ResolvedHandlerTarget resolveHandlerTarget(IXtextDocument contextDoc, URI handlerUri,
                String handlerName)
        {

            if (contextDoc == null || handlerUri == null)
                return null;
            try
            {

                return contextDoc.readOnly((IUnitOfWork<ResolvedHandlerTarget, XtextResource>) resource ->
                        resolveHandlerTargetInResourceSet(resource, handlerUri, handlerName));
            }

            catch (Exception e)
            {

                BslSideHintDebug.problem("resolveHandlerTarget: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }

        }

        private static ResolvedHandlerTarget resolveHandlerTargetInResourceSet(XtextResource contextResource,
                URI handlerUri, String handlerName)
        {

            if (contextResource == null || handlerUri == null)
                return null;
            EObject target = contextResource.getResourceSet().getEObject(handlerUri, true);
            URI resourceUri = trimResourceUri(handlerUri);
            if (target == null && resourceUri != null)
            {

                Resource emfResource = contextResource.getResourceSet().getResource(resourceUri, true);
                if (emfResource instanceof XtextResource xtextResource)
                {

                    if (handlerUri.hasFragment())
                    {

                        target = emfResource.getEObject(handlerUri.fragment());
                    }

                    if (target == null && handlerName != null && !handlerName.isEmpty())
                        target = findMethodByName(xtextResource, handlerName);
                    if (target != null)
                        return toResolvedTarget(target, resourceUri);
                }

            }

            if (target != null)
                return toResolvedTarget(target, resourceUri);
            return null;
        }

        private static Method findMethodByName(XtextResource resource, String methodName)
        {

            if (resource == null || methodName == null || methodName.isEmpty())
                return null;
            Iterator<EObject> it = EcoreUtil.getAllContents(resource, false);
            while (it.hasNext())
            {

                EObject obj = it.next();
                if (obj instanceof Method method && methodName.equals(method.getName()))
                    return method;
            }

            return null;
        }

        private static ResolvedHandlerTarget toResolvedTarget(EObject target, URI resourceUri)
        {

            int offset = offsetOfEObject(target);
            return new ResolvedHandlerTarget(target, resourceUri, offset);
        }

        private static int offsetOfEObject(EObject eObject)
        {

            if (eObject == null)
                return -1;
            INode node = NodeModelUtils.findActualNodeFor(eObject);
            if (node == null)
                node = NodeModelUtils.getNode(eObject);
            return node != null ? node.getOffset() : -1;
        }

        private static BslDispatchingEObjectTextHover resolveBslHover(ITextViewer viewer)
        {

            if (viewer == null || viewer.getDocument() == null)
                return null;
            if (!(viewer.getDocument() instanceof IXtextDocument xtextDoc))
                return null;
            return resolveBslHover(xtextDoc.getResourceURI());
        }

        private static BslDispatchingEObjectTextHover resolveBslHover(URI resourceUri)
        {

            if (resourceUri == null)
                return null;
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(resourceUri);
            if (rsp == null)
                return null;
            IEObjectHover hover = rsp.get(IEObjectHover.class);
            if (!(hover instanceof BslDispatchingEObjectTextHover bslHover))
                return null;
            return bslHover;
        }

        private static final class ResolvedHandlerTarget
        {

            final EObject eObject;
            final URI resourceUri;
            final int offset;
            ResolvedHandlerTarget(EObject eObject, URI resourceUri, int offset)
            {

                this.eObject = eObject;
                this.resourceUri = resourceUri;
                this.offset = offset;
            }

        }

    }


    /** COM-цепочка ИР для боковой подсказки Quick Outline по имени метода. */
    private static final class IrOutlineSideHintSupport
    {
        private IrOutlineSideHintSupport() {}

        static String resolveOutlineMethodName(Object element)
        {
            if (element == null)
                return null;
            if (BslOutlineEventsSupport.isOutlineEvent(element)
                    || BslOutlineEventsSupport.isOutlineEventHandlerElement(element))
                return null;
            if (!isOutlineMethodElement(element) && !isExtensionElement(element))
                return null;
            String name = resolveElementName(element);
            if (name == null || name.isEmpty())
                return null;
            int paren = name.indexOf('(');
            if (paren >= 0)
                name = name.substring(0, paren).trim();
            if (name.isEmpty())
                return null;
            if (isOutlineMethodElement(element))
            {
                Object isEvent = Global.invoke(element, "isEvent"); //$NON-NLS-1$
                if (Boolean.TRUE.equals(isEvent))
                    return null;
            }
            Object formItemEvent = Global.invoke(element, "isFormItemEvent"); //$NON-NLS-1$
            if (formItemEvent instanceof Boolean && (Boolean) formItemEvent)
                return null;
            return name;
        }

        private static boolean isOutlineMethodElement(Object element)
        {
            if (element == null)
                return false;
            String name = element.getClass().getName();
            return METHOD_IMPL_CLASS.equals(name) || name.contains("MethodImpl"); //$NON-NLS-1$
        }

        private static boolean isExtensionElement(Object element)
        {
            if (element == null)
                return false;
            try
            {
                Class<?> type = Class.forName(I_EXTENSION_ELEMENT, false,
                        BslSideHintOutlineInstall.class.getClassLoader());
                if (type.isInstance(element))
                    return true;
            }
            catch (ClassNotFoundException ignored)
            {
            }
            return element.getClass().getName().contains("ExtensionElement"); //$NON-NLS-1$
        }

        private static String resolveElementName(Object element)
        {
            if (element == null)
                return null;
            Object fromGetName = Global.invoke(element, "getName"); //$NON-NLS-1$
            if (fromGetName instanceof String name && !name.isEmpty())
                return name;
            Object fromField = Global.getField(element, "name"); //$NON-NLS-1$
            return fromField instanceof String fieldName && !fieldName.isEmpty() ? fieldName : null;
        }

    }

}