package tormozit;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
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
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.bsl.ui.hover.BslDispatchingEObjectTextHover;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IInformationControlExtension5;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.xtext.ui.editor.hover.html.IXtextBrowserInformationControl;

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
        Display.getDefault().asyncExec(() ->
        {
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
        if (Boolean.TRUE.equals(sourceViewer.getData(HOOK_MARKER)))
            return;
        @SuppressWarnings("unchecked")
        Map<Object, ITextHover> hovers =
            (Map<Object, ITextHover>) Global.getField(sourceViewer, "fTextHovers"); //$NON-NLS-1$
        if (hovers == null || hovers.isEmpty())
            return;
        boolean wrapped = false;
        for (Map.Entry<Object, ITextHover> entry : hovers.entrySet())
        {
            ITextHover hover = entry.getValue();
            if (hover instanceof IrBslTextHoverWrapper)
                continue;
            if (hover instanceof BslDispatchingEObjectTextHover)
            {
                entry.setValue(new IrBslTextHoverWrapper(hover, editor));
                wrapped = true;
            }
        }
        if (wrapped)
        {
            sourceViewer.setData(HOOK_MARKER, Boolean.TRUE);
            IrBslHoverDebug.log("wrapped hover editor=" + editor.getTitle()); //$NON-NLS-1$
        }
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
            return delegate.getHoverInfo(textViewer, hoverRegion);
        }

        @Override
        public IRegion getHoverRegion(ITextViewer textViewer, int offset)
        {
            return delegate.getHoverRegion(textViewer, offset);
        }

        @Override
        public IInformationControlCreator getHoverControlCreator()
        {
            if (delegateExt != null)
                return delegateExt.getHoverControlCreator();
            return null;
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
            IRSession session = IrBslHoverSupport.resolveConnectedSession(editor);
            if (session == null)
                return info;
            final int offset = hoverRegion.getOffset();
            final int gen = fetchGeneration.incrementAndGet();
            lastScheduledOffset = offset;
            final Object baseInput = info;
            IRSession.CodeEditorSyncPayload payload = session.prepareCodeEditorSyncForHover(editor, offset);
            if (payload == null)
                return info;
            session.executor.submit(() -> scheduleIrEnrichment(session, baseInput, payload, offset, gen));
            return info;
        }

        private void scheduleIrEnrichment(
            IRSession session, Object baseInput,
            IRSession.CodeEditorSyncPayload payload, int offset, int gen)
        {
            String irHtml = IrBslHoverSupport.fetchExpressionHtml(session, editor, payload);
            if (irHtml == null || irHtml.isBlank())
                return;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> {
                if (gen != fetchGeneration.get() || offset != lastScheduledOffset)
                {
                    IrBslHoverDebug.step("skip", "stale gen=" + gen + " offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return;
                }
                if (editor.getSite() == null)
                    return;
                IXtextBrowserInformationControl control = IrBslHoverControlAccess.resolve(editor);
                if (control == null)
                {
                    IrBslHoverDebug.step("skip", "control null offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                if (!(control instanceof IInformationControlExtension5 ext5) || !ext5.isVisible())
                {
                    IrBslHoverDebug.step("skip", "control hidden offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                String merged = IrBslHoverHtml.mergedHtml(baseInput, irHtml);
                control.notifyDelayedInputChange(merged);
                IrBslHoverDebug.log("enriched offset=" + offset); //$NON-NLS-1$
            });
        }

        /** Доступ к активному browser-hover control BSL-редактора. */
        private static final class IrBslHoverControlAccess
        {
            private IrBslHoverControlAccess() {}

            static IXtextBrowserInformationControl resolve(BslXtextEditor editor)
            {
                if (editor == null)
                    return null;
                ISourceViewer viewer = editor.getInternalSourceViewer();
                if (!(viewer instanceof SourceViewer sourceViewer))
                    return null;
                Object textHoverManager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
                if (textHoverManager == null)
                    return null;
                Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
                if (replacer != null)
                {
                    Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
                    IXtextBrowserInformationControl ic = asBrowserControl(replacerControl);
                    if (ic != null)
                        return ic;
                }
                Object infoControl = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
                return asBrowserControl(infoControl);
            }

            private static IXtextBrowserInformationControl asBrowserControl(Object control)
            {
                return control instanceof IXtextBrowserInformationControl browser ? browser : null;
            }
        }


        /**
         * Слияние HTML doc-hover без ссылок на internal API {@code BslBrowserInformationControlInput}.
         */
        private static final class IrBslHoverHtml
        {
            private static final String BSL_BROWSER_INPUT_CLASS =
                "com._1c.g5.v8.dt.internal.bsl.ui.browserscommon.BslBrowserInformationControlInput"; //$NON-NLS-1$

            private IrBslHoverHtml() {}

            static boolean isBslBrowserInput(Object info)
            {
                if (info == null)
                    return false;
                for (Class<?> type = info.getClass(); type != null; type = type.getSuperclass())
                {
                    if (BSL_BROWSER_INPUT_CLASS.equals(type.getName()))
                        return true;
                }
                return false;
            }

            static String readHtml(Object browserInput)
            {
                if (browserInput == null)
                    return ""; //$NON-NLS-1$
                Object html = Global.invoke(browserInput, "getHtml"); //$NON-NLS-1$
                return html != null ? html.toString() : ""; //$NON-NLS-1$
            }

            static String mergeHtml(String baseHtml, String irFragment)
            {
                if (irFragment == null || irFragment.isEmpty())
                    return baseHtml != null ? baseHtml : ""; //$NON-NLS-1$
                if (baseHtml == null || baseHtml.isEmpty())
                    return irFragment;
                String lower = baseHtml.toLowerCase();
                int bodyEnd = lower.lastIndexOf("</body>"); //$NON-NLS-1$
                if (bodyEnd >= 0)
                    return baseHtml.substring(0, bodyEnd) + irFragment + baseHtml.substring(bodyEnd);
                return baseHtml + irFragment;
            }

            static String mergedHtml(Object baseInput, String irHtml)
            {
                return mergeHtml(readHtml(baseInput), irHtml);
            }
        }


        /**
         * COM-цепочка ИР для doc-hover: sync → {@code РазобратьТекущийКонтекст} → {@code ОписаниеХТМЛВыражения}.
         */
        private static final class IrBslHoverSupport
        {
            private IrBslHoverSupport() {}

            /**
             * @return HTML-фрагмент ИР или {@code null}, если дополнение не нужно
             */
            static String fetchExpressionHtml(
                IRSession session, BslXtextEditor editor, IRSession.CodeEditorSyncPayload payload)
            {
                if (session == null || editor == null || payload == null)
                    return null;
                try
                {
                    session.applyPreparedCodeEditorSync(payload);
                    ensureCodeEditor(session);
                    ComBridge.invoke(session.codeEditor, "РазобратьТекущийКонтекст"); //$NON-NLS-1$
                    Object raw = ComBridge.invoke(session.codeEditor, "ОписаниеХТМЛВыражения"); //$NON-NLS-1$
                    String html = ComBridge.toString(raw);
                    if (html == null || html.isBlank())
                    {
                        IrBslHoverDebug.step("fetch", "пустой ответ offset=" + payload.offset); //$NON-NLS-1$ //$NON-NLS-2$
                        return null;
                    }
                    IrBslHoverDebug.log("fetch offset=" + payload.offset + " len=" + html.length()); //$NON-NLS-1$ //$NON-NLS-2$
                    return html;
                }
                catch (Exception e)
                {
                    IrBslHoverDebug.problem("fetch offset=" + payload.offset + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    return null;
                }
            }

            static IRSession resolveConnectedSession(BslXtextEditor editor)
            {
                IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
                if (dtProject == null || !IRApplication.hasConnectedSessionForKeys(dtProject))
                    return null;
                IRSession session = IRApplication.getSession(dtProject);
                if (session == null || session.executor == null || session.executor.isShutdown())
                    return null;
                return session;
            }

            private static void ensureCodeEditor(IRSession session)
            {
                if (session.codeEditor != null)
                    return;
                Object irCache = session.getModule("ирКэш"); //$NON-NLS-1$
                session.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
            }
        }

    }

}
