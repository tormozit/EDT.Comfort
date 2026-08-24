// IrBslEditorHoverHook.java
package tormozit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.bsl.ui.hover.BslDispatchingEObjectTextHover;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import org.eclipse.jface.text.IInformationControlExtension5;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.hover.html.IXtextBrowserInformationControl;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.ImplicitVariable;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

/**
 * Дополняет doc-hover BSL описанием из ИР при подключённой сессии.
 */
public final class IrBslEditorHoverHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.irHoverWrapped"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        ContentAssistDebug.logLiteralAssistBuildStamp();
        BslDocCommentDescriptionFix.install();
        Display.getDefault().asyncExec(() ->
        {
            ParamHintHtmlModifier.install(Display.getDefault());

            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }
            @Override public void partActivated(IWorkbenchPartReference r) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partVisible(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            wrapHoverIfNeeded(bsl);
        else if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private void hookGranularEditor(DtGranularEditor<?> editor)
    {
        org.eclipse.ui.forms.editor.IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
        {
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor bsl)
                wrapHoverIfNeeded(bsl);
        }
        if (!hookedGranularEditors.contains(editor))
        {
            IPageChangedListener listener = new IPageChangedListener()
            {
                @Override
                public void pageChanged(PageChangedEvent event)
                {
                    Object page = event.getSelectedPage();
                    if (page instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
                    {
                        IEditorPart embedded = xtextPage.getEmbeddedEditor();
                        if (embedded instanceof BslXtextEditor bsl)
                            wrapHoverIfNeeded(bsl);
                    }
                }
            };
            editor.addPageChangedListener(listener);
            hookedGranularEditors.add(editor);
        }
    }

    static void wrapHoverIfNeeded(BslXtextEditor editor)
    {
        if (editor == null)
            return;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        // Глобальный тумблер «Подсказки при наведении» применяется и к новым,
        // и к уже обёрнутым редакторам (повторные вызовы при активации/смене страницы).
        BslHoverHintState.applyToViewer(sourceViewer);
        if (Boolean.TRUE.equals(sourceViewer.getData(HOOK_MARKER)))
            return;
        boolean wrappedText = wrapTextHovers(sourceViewer, editor);
        boolean wrappedInfo = wrapInformationProviderHover(editor);
        boolean wrapped = wrappedText || wrappedInfo;
        if (wrapped)
        {
            sourceViewer.setData(HOOK_MARKER, Boolean.TRUE);
            IrBslHoverDebug.log("wrapped hover editor=" + editor.getTitle()); //$NON-NLS-1$
        }
    }

    private static boolean wrapTextHovers(SourceViewer sourceViewer, BslXtextEditor editor)
    {
        @SuppressWarnings("unchecked")
        Map<Object, ITextHover> hovers =
            (Map<Object, ITextHover>) Global.getField(sourceViewer, "fTextHovers"); //$NON-NLS-1$
        if (hovers == null || hovers.isEmpty())
            return false;
        boolean wrapped = false;
        for (Map.Entry<Object, ITextHover> entry : hovers.entrySet())
        {
            IrBslTextHoverWrapper wrapper = wrapHoverDelegate(entry.getValue(), editor);
            if (wrapper != null)
            {
                entry.setValue(wrapper);
                wrapped = true;
            }
        }
        return wrapped;
    }

    /** Ctrl+F2 (INFORMATION_PROPOSAL) — {@code XtextInformationProvider.hover}, не из {@code fTextHovers}. */
    private static boolean wrapInformationProviderHover(BslXtextEditor editor)
    {
        Object config = Global.invoke(editor, "getSourceViewerConfiguration"); //$NON-NLS-1$
        if (config == null)
            return false;
        Object provider = Global.getField(config, "informationProvider"); //$NON-NLS-1$
        if (provider == null)
            return false;
        Object hover = Global.getField(provider, "hover"); //$NON-NLS-1$
        if (!(hover instanceof ITextHover textHover) || textHover instanceof IrBslTextHoverWrapper)
            return false;
        IrBslTextHoverWrapper wrapper = wrapHoverDelegate(textHover, editor);
        if (wrapper == null)
            return false;
        Global.setField(provider, "hover", wrapper); //$NON-NLS-1$
        return true;
    }

    private static IrBslTextHoverWrapper wrapHoverDelegate(ITextHover hover, BslXtextEditor editor)
    {
        if (hover instanceof IrBslTextHoverWrapper existing)
            return existing;
        if (!isWrappableBslHover(hover))
            return null;
        return new IrBslTextHoverWrapper(hover, editor);
    }

    private static boolean isWrappableBslHover(ITextHover hover)
    {
        if (hover == null)
            return false;
        if (hover instanceof BslDispatchingEObjectTextHover)
            return true;
        if ("com._1c.g5.v8.dt.lcore.ui.hover.BestMatchEObjectTextHover".equals(hover.getClass().getName())) //$NON-NLS-1$
            return true;
        Object htmlHover = Global.getField(hover, "htmlHover"); //$NON-NLS-1$
        return htmlHover instanceof BslDispatchingEObjectTextHover;
    }

    /**
     * Обёртка штатного {@link ITextHover}: асинхронно дополняет HTML описанием из ИР.
     */
    private static final class IrBslTextHoverWrapper implements ITextHover, ITextHoverExtension, ITextHoverExtension2
    {
        private final ITextHover delegate;
        private final ITextHoverExtension delegateExt;
        private final ITextHoverExtension2 delegateExt2;
        private final BslXtextEditor editor;
        private final AtomicInteger fetchGeneration = new AtomicInteger();
        private volatile int lastScheduledOffset = -1;
        private volatile String lastIrHtml;
        private volatile String lastBaseHtml;
        private volatile String lastDirective;
        private volatile boolean lastCreationSite;
        private volatile HtmlIntegrityWatcher activeWatcher;

        IrBslTextHoverWrapper(ITextHover delegate, BslXtextEditor editor)
        {
            this.delegate = delegate;
            this.delegateExt = delegate instanceof ITextHoverExtension ext ? ext : null;
            this.delegateExt2 = delegate instanceof ITextHoverExtension2 ext2 ? ext2 : null;
            this.editor = editor;
        }

        @Override
        public String getHoverInfo(ITextViewer textViewer, IRegion hoverRegion)
        {
            Object info = getHoverInfo2(textViewer, hoverRegion);
            if (info == null)
                return null;
            if (IrBslHoverHtml.isBslBrowserInput(info))
                return IrBslHoverHtml.readHtml(info);
            return info instanceof String text ? text : info.toString();
        }

        @Override
        public IRegion getHoverRegion(ITextViewer textViewer, int offset)
        {
            return delegate.getHoverRegion(textViewer, offset);
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getHoverControlCreator()
        {
            return delegateExt != null ? delegateExt.getHoverControlCreator() : null;
        }

        @Override
        public Object getHoverInfo2(ITextViewer textViewer, IRegion hoverRegion)
        {
            Object info = delegateExt2 != null
                ? delegateExt2.getHoverInfo2(textViewer, hoverRegion)
                : delegate.getHoverInfo(textViewer, hoverRegion);
            if (info == null || hoverRegion == null || editor == null)
                return info;
            if (!IrBslHoverHtml.isBslBrowserInput(info))
                return info;
            final int offset = hoverRegion.getOffset();
            String directive = resolveHoverDirective(offset);
            lastDirective = (directive != null && !directive.isBlank()) ? directive : null;
            lastCreationSite = isImplicitVariableCreationAt(hoverRegion);
            IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(editor);
            if (session == null)
            {
                cancelIrEnrichment();
                IrBslHoverDebug.step("skip", "no session"); //$NON-NLS-1$ //$NON-NLS-2$
                return maybeDecorateHoverInfo(info);
            }
            IRSession.cancelActiveEvaluation(session);
            final int gen = cancelIrEnrichment();
            lastScheduledOffset = offset;
            final Object baseInput = info;
            IRSession.CodeEditorSyncPayload payload = session.prepareCodeEditorSyncForHover(editor, offset);
            if (payload == null)
            {
                scheduleNativeInputSync(baseInput, offset, gen);
                return maybeDecorateHoverInfo(info);
            }
            scheduleNativeInputSync(baseInput, offset, gen);
            session.executor.submit(() -> scheduleIrEnrichment(session, baseInput, payload, offset, gen));
            return maybeDecorateHoverInfo(info);
        }

        private Object maybeDecorateHoverInfo(Object info)
        {
            String html = IrBslHoverHtml.readHtml(info);
            String modified = applyHoverDecorations(html);
            return modified.equals(html) ? info : modified;
        }

        private String applyHoverDecorations(String html)
        {
            if (html == null)
                return html;
            String result = html;
            if (lastDirective != null && !lastDirective.isBlank())
                result = IrBslHoverHtml.injectDirectiveIntoHtml(result, lastDirective);
            if (lastCreationSite)
                result = IrBslHoverHtml.injectNewVariablePrefix(result);
            return result;
        }

        /** Сбрасывает delayed input на штатный base, чтобы родный HTML обновлялся при смене слова. */
        private void scheduleNativeInputSync(Object baseInput, int offset, int gen)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> {
                if (gen != fetchGeneration.get() || offset != lastScheduledOffset)
                    return;
                IXtextBrowserInformationControl control = IrBslHoverControlAccess.resolve(editor);
                if (control == null)
                    return;
                String html = IrBslHoverHtml.readHtml(baseInput);
                String modified = applyHoverDecorations(html);
                if (!modified.equals(html))
                {
                    IrBslHoverHtml.applyHtmlToControl(control, modified);
                    if (control.hasDelayedInputChangeListener())
                        control.notifyDelayedInputChange(modified);
                    return;
                }
                if (control.hasDelayedInputChangeListener())
                    control.notifyDelayedInputChange(baseInput);
            });
        }

        private String resolveHoverDirective(int offset)
        {
            if (editor == null)
                return null;
            org.eclipse.jface.text.IDocument document = editor.getDocument();
            if (!(document instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument xtextDoc))
                return null;
            try
            {
                return xtextDoc.readOnly(
                    (org.eclipse.xtext.util.concurrent.IUnitOfWork<String, org.eclipse.xtext.resource.XtextResource>) resource -> {
                        if (resource == null)
                            return null;
                        org.eclipse.emf.ecore.EObject obj = findInvocationLikeAt(resource, offset);
                        if (obj instanceof com._1c.g5.v8.dt.bsl.model.Invocation invocation)
                            return ParamHintHtmlModifier.extractMethodDirective(resource, invocation);
                        return null;
                    });
            }
            catch (Exception e)
            {
                return null;
            }
        }

        /**
         * {@code true}, если регион наведения пересекает имя {@link StaticFeatureAccess} с
         * непустым {@code getImplicitVariable()} — место создания неявной переменной.
         */
        private boolean isImplicitVariableCreationAt(IRegion hoverRegion)
        {
            if (editor == null || hoverRegion == null)
                return false;
            org.eclipse.jface.text.IDocument document = editor.getDocument();
            if (!(document instanceof IXtextDocument xtextDoc))
                return false;
            try
            {
                Boolean found = xtextDoc.readOnly((IUnitOfWork<Boolean, XtextResource>) resource -> {
                    if (resource == null)
                        return Boolean.FALSE;
                    return Boolean.valueOf(isImplicitVariableCreationAt(resource, hoverRegion));
                });
                return Boolean.TRUE.equals(found);
            }
            catch (Exception e)
            {
                return false;
            }
        }

        private static boolean isImplicitVariableCreationAt(XtextResource resource, IRegion hoverRegion)
        {
            int hoverStart = hoverRegion.getOffset();
            int hoverEnd = hoverStart + hoverRegion.getLength();
            EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
            EObject obj = helper.resolveContainedElementAt(resource, hoverStart);
            for (EObject cur = obj; cur != null; cur = cur.eContainer())
            {
                StaticFeatureAccess access = asCreatingAccess(cur);
                if (access == null)
                    continue;
                List<INode> nodes = NodeModelUtils.findNodesForFeature(access,
                    BslPackage.Literals.FEATURE_ACCESS__NAME);
                for (INode node : nodes)
                {
                    int start = node.getOffset();
                    int end = start + node.getLength();
                    if (node.getLength() > 0 && hoverStart < end && hoverEnd > start)
                        return true;
                }
                return false;
            }
            return false;
        }

        private static StaticFeatureAccess asCreatingAccess(EObject element)
        {
            if (element instanceof StaticFeatureAccess access && access.getImplicitVariable() != null)
                return access;
            if (element instanceof ImplicitVariable variable
                && variable.eContainer() instanceof StaticFeatureAccess access
                && access.getImplicitVariable() == variable)
                return access;
            return null;
        }

        private static org.eclipse.emf.ecore.EObject findInvocationLikeAt(
            org.eclipse.xtext.resource.XtextResource resource, int caret)
        {
            org.eclipse.xtext.resource.EObjectAtOffsetHelper helper =
                new org.eclipse.xtext.resource.EObjectAtOffsetHelper();
            org.eclipse.emf.ecore.EObject obj = helper.resolveContainedElementAt(resource, caret);
            for (org.eclipse.emf.ecore.EObject cur = obj; cur != null; cur = cur.eContainer())
            {
                if (cur instanceof com._1c.g5.v8.dt.bsl.model.Invocation
                    || cur instanceof com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator)
                    return cur;
            }
            return null;
        }

        /** Отменяет watcher, async-обогащение и кэш последнего ИР-фрагмента. */
        private int cancelIrEnrichment()
        {
            int gen = fetchGeneration.incrementAndGet();
            HtmlIntegrityWatcher oldWatcher = activeWatcher;
            if (oldWatcher != null)
                oldWatcher.cancel();
            activeWatcher = null;
            lastScheduledOffset = -1;
            lastIrHtml = null;
            lastBaseHtml = null;
            return gen;
        }

        private static final int APPLY_MAX_ATTEMPTS = 10;
        private static final int APPLY_RETRY_MS = 50;

        private void scheduleIrEnrichment(
            IRSession session, Object baseInput,
            IRSession.CodeEditorSyncPayload payload, int offset, int gen)
        {
            String irHtml = IrBslExpressionHtmlSupport.fetchDescriptionHtmlForHover(session, payload);
            if (irHtml == null || irHtml.isBlank())
                return;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyIrEnrichmentOnUi(baseInput, irHtml, offset, gen, 0));
        }

        private void applyIrEnrichmentOnUi(Object baseInput, String irHtml, int offset, int gen, int attempt)
        {
            if (gen != fetchGeneration.get() || offset != lastScheduledOffset)
            {
                IrBslHoverDebug.step("skip", "stale gen=" + gen + " offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return;
            }
            if (editor.getSite() == null)
                return;

            lastIrHtml = irHtml;
            String baseHtml = IrBslHoverHtml.readHtml(baseInput);
            lastBaseHtml = baseHtml;
            String merged = applyHoverDecorations(IrBslHoverHtml.mergeHtml(baseHtml, irHtml));

            if (tryApplyToCurrentControl(merged))
            {
                IrBslHoverDebug.log("enriched inline offset=" + offset); //$NON-NLS-1$
                HtmlIntegrityWatcher watcher = new HtmlIntegrityWatcher(gen, lastBaseHtml, lastIrHtml, lastScheduledOffset);
                activeWatcher = watcher;
                watcher.start();
                return;
            }
            if (attempt < APPLY_MAX_ATTEMPTS)
            {
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                {
                    display.timerExec(APPLY_RETRY_MS, () -> applyIrEnrichmentOnUi(baseInput, irHtml, offset, gen, attempt + 1));
                    return;
                }
            }
            IrBslHoverDebug.log("enriched inline FAILED offset=" + offset); //$NON-NLS-1$
            HtmlIntegrityWatcher watcher = new HtmlIntegrityWatcher(gen, lastBaseHtml, lastIrHtml, lastScheduledOffset);
            activeWatcher = watcher;
            watcher.start();
        }

        private boolean tryApplyToCurrentControl(String mergedHtml)
        {
            ResolveResult resolved = IrBslHoverControlAccess.resolveDetailed(editor);
            IXtextBrowserInformationControl control = resolved.control;
            if (control == null || !resolved.visible)
                return false;
            if (!IrBslHoverHtml.applyHtmlToControl(control, mergedHtml))
                return false;
            if (control.hasDelayedInputChangeListener())
                control.notifyDelayedInputChange(mergedHtml);
            return true;
        }

        private static final class ResolveResult
        {
            final IXtextBrowserInformationControl control;
            final boolean visible;

            ResolveResult(IXtextBrowserInformationControl control, boolean visible)
            {
                this.control = control;
                this.visible = visible;
            }
        }

        /** Доступ к активному browser-hover control BSL-редактора. */
        private static final class IrBslHoverControlAccess
        {
            private IrBslHoverControlAccess() {}

            static IXtextBrowserInformationControl resolve(BslXtextEditor editor)
            {
                ResolveResult result = resolveDetailed(editor);
                return result.visible ? result.control : null;
            }

            static ResolveResult resolveDetailed(BslXtextEditor editor)
            {
                if (editor == null)
                    return new ResolveResult(null, false);
                ISourceViewer viewer = editor.getInternalSourceViewer();
                if (!(viewer instanceof SourceViewer sourceViewer))
                    return new ResolveResult(null, false);
                IXtextBrowserInformationControl fromHoverManager = resolveVisibleFromTextHoverManager(sourceViewer);
                if (fromHoverManager != null)
                    return new ResolveResult(fromHoverManager, true);
                IXtextBrowserInformationControl fromEditorPresenter =
                    resolveVisibleFromPresenter(Global.getField(editor, "fInformationPresenter")); //$NON-NLS-1$
                if (fromEditorPresenter != null)
                    return new ResolveResult(fromEditorPresenter, true);
                IXtextBrowserInformationControl fromViewerPresenter = resolveVisibleFromPresenter(
                    Global.getField(sourceViewer, "fInformationPresenter")); //$NON-NLS-1$
                if (fromViewerPresenter != null)
                    return new ResolveResult(fromViewerPresenter, true);
                return new ResolveResult(null, false);
            }

            private static IXtextBrowserInformationControl resolveVisibleFromTextHoverManager(SourceViewer sourceViewer)
            {
                Object textHoverManager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
                if (textHoverManager == null)
                    return null;
                Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
                Object infoControl = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
                IXtextBrowserInformationControl replacerIc = null;
                if (replacer != null)
                {
                    Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
                    replacerIc = asBrowserControl(replacerControl);
                }
                IXtextBrowserInformationControl infoIc = asBrowserControl(infoControl);
                if (replacerIc instanceof IInformationControlExtension5 ext5 && ext5.isVisible())
                    return replacerIc;
                if (infoIc instanceof IInformationControlExtension5 ext5 && ext5.isVisible())
                    return infoIc;
                return null;
            }

            private static IXtextBrowserInformationControl resolveVisibleFromPresenter(Object presenter)
            {
                if (!(presenter instanceof AbstractInformationControlManager manager))
                    return null;
                IInformationControl control = manager.getInternalAccessor().getCurrentInformationControl();
                return asVisibleBrowserControl(control);
            }

            private static IXtextBrowserInformationControl asVisibleBrowserControl(Object control)
            {
                IXtextBrowserInformationControl browser = asBrowserControl(control);
                if (browser == null)
                    return null;
                if (browser instanceof IInformationControlExtension5 ext5)
                    return ext5.isVisible() ? browser : null;
                return browser;
            }

            private static IXtextBrowserInformationControl asBrowserControl(Object control)
            {
                return control instanceof IXtextBrowserInformationControl browser ? browser : null;
            }
        }

        /**
         * Watcher: проверяет HTML в браузере и восстанавливает ИР-фрагмент при сбросе.
         * Останавливается по cancel() или если контрол 10 раз подряд null.
         */
        private final class HtmlIntegrityWatcher
        {
            final int gen;
            private final String baseHtml;
            private final String irHtml;
            private final int offset;
            private final AtomicInteger activeGen;
            private final Runnable checkTask;
            private int nullCount = 0;
            private static final int MAX_NULL_RETRIES = 10;

            HtmlIntegrityWatcher(int gen, String baseHtml, String irHtml, int offset)
            {
                this.gen = gen;
                this.baseHtml = baseHtml;
                this.irHtml = irHtml;
                this.offset = offset;
                this.activeGen = new AtomicInteger(gen);
                this.checkTask = this::doCheck;
            }

            void start()
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.timerExec(100, checkTask);
            }

            void cancel()
            {
                activeGen.incrementAndGet();
            }

            private boolean shouldContinueWatching()
            {
                return activeGen.get() == gen
                    && fetchGeneration.get() == gen
                    && lastScheduledOffset == offset
                    && IrBslExpressionHtmlSupport.resolveConnectedSession(editor) != null;
            }

            private void doCheck()
            {
                if (activeGen.get() != gen)
                    return;
                if (IrBslExpressionHtmlSupport.resolveConnectedSession(editor) == null)
                    return;
                if (fetchGeneration.get() != gen || lastScheduledOffset != offset)
                    return;

                IXtextBrowserInformationControl control = IrBslHoverControlAccess.resolve(editor);
                if (control == null)
                {
                    nullCount++;
                    if (nullCount >= MAX_NULL_RETRIES)
                        return;
                    if (shouldContinueWatching())
                    {
                        Display display = Display.getDefault();
                        if (display != null && !display.isDisposed())
                            display.timerExec(100, checkTask);
                    }
                    return;
                }
                nullCount = 0;

                if (!(control instanceof IInformationControlExtension5 ext5) || !ext5.isVisible())
                {
                    if (shouldContinueWatching())
                    {
                        Display display = Display.getDefault();
                        if (display != null && !display.isDisposed())
                            display.timerExec(100, checkTask);
                    }
                    return;
                }

                Browser browser = IrBslHoverHtml.findControlBrowser(control);
                if (browser == null || browser.isDisposed())
                {
                    if (shouldContinueWatching())
                    {
                        Display display = Display.getDefault();
                        if (display != null && !display.isDisposed())
                            display.timerExec(100, checkTask);
                    }
                    return;
                }

                String currentText = browser.getText();
                boolean hasMarker = currentText != null && currentText.contains("comfort-ir-hover"); //$NON-NLS-1$

                if (currentText != null && !currentText.isEmpty()
                    && irHtml != null && !irHtml.isEmpty()
                    && !hasMarker)
                {
                    String merged = applyHoverDecorations(IrBslHoverHtml.mergeHtml(baseHtml, irHtml));
                    boolean baseReset = IrBslHoverHtml.looksLikeBaseHtmlReset(currentText, baseHtml, merged);
                    boolean backEnabled = browser.isBackEnabled();
                    if (backEnabled && !baseReset)
                    {
                        IrBslHoverDebug.step("watcher", //$NON-NLS-1$
                            "skip navigation back=" + backEnabled); //$NON-NLS-1$
                    }
                    else if (baseReset)
                    {
                        IrBslHoverHtml.applyHtmlToControl(control, merged);
                        IrBslHoverDebug.log("html restored offset=" + offset); //$NON-NLS-1$
                    }
                    else
                    {
                        IrBslHoverDebug.step("watcher", //$NON-NLS-1$
                            "skip restore back=" + backEnabled + " baseReset=" + baseReset); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }

                if (shouldContinueWatching())
                {
                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                        display.timerExec(300, checkTask);
                }
            }
        }
    }
}
