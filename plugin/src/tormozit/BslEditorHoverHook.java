// BslEditorHoverHook.java
package tormozit;

import java.util.ArrayList;
import java.util.Collection;
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
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.information.IInformationProvider;
import org.eclipse.jface.text.information.IInformationProviderExtension;
import org.eclipse.jface.text.information.IInformationProviderExtension2;
import org.eclipse.jface.text.information.InformationPresenter;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
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
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.xtext.ui.editor.hover.AbstractCompositeHover;
import org.eclipse.xtext.ui.editor.hover.AbstractProblemHover;
import org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover;
import org.eclipse.xtext.ui.editor.hover.html.IXtextBrowserInformationControl;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.ImplicitVariable;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

/**
 * Дополняет doc-hover BSL описанием из ИР при подключённой сессии.
 * Подавляет подсказку при наведении без Ctrl на ключевых словах языка, строковых
 * литералах, в комментариях и на пустом месте; аннотация проблемы в позиции
 * подавление отменяет. Вызов от каретки (Ctrl+F2) приравнен к наведению мышью:
 * сначала аннотация, потом описание, но справка по ключевому слову и литералу
 * при нём показывается.
 */
public final class BslEditorHoverHook implements IStartup
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
        // Презентер создаётся позже обёртки hover-ов, поэтому прокси ставим при каждом
        // проходе (сам по себе повторно не навешивается).
        installAnnotationAwareInformationProvider(sourceViewer);
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
            IrBslTextHoverWrapper wrapper = wrapHoverDelegate(entry.getValue(), editor, true);
            if (wrapper != null)
            {
                entry.setValue(wrapper);
                wrapped = true;
            }
        }
        return wrapped;
    }

    /**
     * Ctrl+F2 от каретки должен вести себя как наведение мышью: сначала аннотация
     * (орфография, ошибка, предупреждение) с исправлениями, и только потом описание
     * объекта. Штатный {@code XtextInformationProvider.getInformation2} зовёт лишь
     * {@code IEObjectHover}, про аннотации не знает вовсе, а нужный контрол попапа
     * выбирает {@code getInformationPresenterControlCreator} — поэтому подменяем
     * провайдер целиком, а не поле {@code hover} внутри него.
     */
    private static void installAnnotationAwareInformationProvider(SourceViewer sourceViewer)
    {
        Object presenter = Global.getField(sourceViewer, "fInformationPresenter"); //$NON-NLS-1$
        if (!(presenter instanceof InformationPresenter informationPresenter))
            return;
        @SuppressWarnings("unchecked")
        Map<String, IInformationProvider> providers =
            (Map<String, IInformationProvider>)Global.getField(informationPresenter, "fProviders"); //$NON-NLS-1$
        if (providers == null || providers.isEmpty())
            return;
        for (Map.Entry<String, IInformationProvider> entry : new ArrayList<>(providers.entrySet()))
        {
            IInformationProvider stock = entry.getValue();
            if (stock == null || stock instanceof AnnotationAwareInformationProvider)
                continue;
            informationPresenter.setInformationProvider(
                new AnnotationAwareInformationProvider(stock, sourceViewer), entry.getKey());
        }
    }

    /**
     * Идёт вызов подсказки от каретки (Ctrl+F2). Подавление наведения мышью на
     * ключевых словах и комментариях при этом не применяется — вызов осознанный.
     */
    private static final ThreadLocal<Boolean> caretInvocation = new ThreadLocal<>();

    /**
     * Прокси штатного {@code XtextInformationProvider}: при наличии аннотации в позиции
     * отдаёт её подсказку (с исправлениями), иначе — штатное описание объекта.
     * Контрол попапа берётся у того же источника, что и содержимое, иначе информация
     * аннотации попала бы в браузерный контрол описания.
     */
    private static final class AnnotationAwareInformationProvider
        implements IInformationProvider, IInformationProviderExtension, IInformationProviderExtension2
    {
        private final IInformationProvider delegate;
        private final SourceViewer sourceViewer;
        private volatile ITextHover lastAnnotationHover;

        AnnotationAwareInformationProvider(IInformationProvider delegate, SourceViewer sourceViewer)
        {
            this.delegate = delegate;
            this.sourceViewer = sourceViewer;
        }

        @Override
        public IRegion getSubject(ITextViewer textViewer, int offset)
        {
            return delegate.getSubject(textViewer, offset);
        }

        @Override
        @Deprecated
        public String getInformation(ITextViewer textViewer, IRegion subject)
        {
            Object info = getInformation2(textViewer, subject);
            return info == null ? null : info.toString();
        }

        @Override
        public Object getInformation2(ITextViewer textViewer, IRegion subject)
        {
            lastAnnotationHover = null;
            // Содержимое берём у того же hover-а, что работает под мышью: он уже
            // содержит и композит (аннотация раньше описания), и наш фильтр
            // предложений орфографии. Своя обёртка пропускается — её подавление
            // на ключевых словах и комментариях к осознанному Ctrl+F2 не относится.
            ITextHover pointerHover = resolvePointerHover();
            if (pointerHover instanceof ITextHoverExtension2 pointerExt)
            {
                Object info;
                // Флаг говорит нашей обёртке внутри цепочки, что это вызов от каретки:
                // зажатый Ctrl не считать «Ctrl+наведением», всё остальное — как у мыши.
                caretInvocation.set(Boolean.TRUE);
                try
                {
                    // BestMatchEObjectTextHover выбирает currentHover именно в
                    // getHoverRegion, а getHoverInfo2 лишь делегирует выбранному.
                    // Без этого вызова достаётся hover от прошлого наведения мышью.
                    IRegion region = pointerHover.getHoverRegion(textViewer, subject.getOffset());
                    info = region == null ? null : pointerExt.getHoverInfo2(textViewer, region);
                }
                finally
                {
                    caretInvocation.remove();
                }
                ITextHover annotationHover = info instanceof AnnotationWithQuickFixesHover.AnnotationInfo
                    ? resolveAnnotationHover() : null;
                // Информацию аннотации отдаём только вместе с её контролом, иначе
                // она попадёт в браузерный контрол описания и не отрисуется.
                if (annotationHover != null)
                {
                    lastAnnotationHover = annotationHover;
                    return info;
                }
                // Пусто — значит подсказка подавлена (комментарий, ключевое слово):
                // при наведении мышью здесь тоже ничего не показывается.
                if (info == null)
                    return null;
            }
            return delegate instanceof IInformationProviderExtension ext
                ? ext.getInformation2(textViewer, subject)
                : delegate.getInformation(textViewer, subject);
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getInformationPresenterControlCreator()
        {
            ITextHover annotationHover = lastAnnotationHover;
            if (annotationHover != null)
            {
                Object creator = Global.invoke(annotationHover, "getInformationPresenterControlCreator"); //$NON-NLS-1$
                if (creator instanceof org.eclipse.jface.text.IInformationControlCreator controlCreator)
                    return HoverAffordanceText.wrapCreator(controlCreator);
            }
            return delegate instanceof IInformationProviderExtension2 ext
                ? HoverAffordanceText.wrapCreator(ext.getInformationPresenterControlCreator()) : null;
        }

        /** Hover наведения мышью того же редактора, без нашей внешней обёртки. */
        private ITextHover resolvePointerHover()
        {
            for (ITextHover hover : pointerHovers())
                return hover instanceof IrBslTextHoverWrapper wrapper ? wrapper.delegate : hover;
            return null;
        }

        /**
         * Hover аннотаций: он спрятан в цепочке обёрток ({@code IrBslTextHoverWrapper},
         * {@code AnnotationHoverProposalFilter}) поверх композита, поэтому цепочку
         * разворачиваем, а не смотрим один слой.
         */
        private ITextHover resolveAnnotationHover()
        {
            for (ITextHover hover : pointerHovers())
            {
                ITextHover found = findAnnotationHover(unwrapHover(hover));
                if (found != null)
                    return found;
            }
            return null;
        }

        private Collection<ITextHover> pointerHovers()
        {
            @SuppressWarnings("unchecked")
            Map<Object, ITextHover> hovers =
                (Map<Object, ITextHover>)Global.getField(sourceViewer, "fTextHovers"); //$NON-NLS-1$
            return hovers == null ? List.of() : hovers.values();
        }

        /** Разворот цепочки обёрток до композита или hover-а аннотаций. */
        private static Object unwrapHover(Object hover)
        {
            Object current = hover;
            for (int depth = 0; depth < 8 && current != null; depth++)
            {
                if (current instanceof AbstractProblemHover || current instanceof AbstractCompositeHover)
                    return current;
                Object next = Global.getField(current, "delegate"); //$NON-NLS-1$
                if (next == null)
                    next = Global.getField(current, "annotationHover"); //$NON-NLS-1$
                if (next == null || next == current)
                    return current;
                current = next;
            }
            return current;
        }

        /**
         * Композиты бывают двух видов: {@link AbstractCompositeHover} у Xtext и
         * {@code BestMatchEObjectTextHover} у EDT со своими {@code hovers} /
         * {@code currentHover}. Последний уже отработавший hover ({@code currentHover})
         * и есть источник только что полученной информации.
         */
        private static ITextHover findAnnotationHover(Object candidate)
        {
            if (candidate instanceof AbstractProblemHover problemHover)
                return problemHover;
            if (candidate == null)
                return null;
            if (candidate instanceof AbstractCompositeHover composite)
                return firstProblemHover(composite.getHovers());
            if (Global.getField(candidate, "currentHover") instanceof AbstractProblemHover current) //$NON-NLS-1$
                return current;
            if (Global.getField(candidate, "annotationHover") instanceof AbstractProblemHover annotation) //$NON-NLS-1$
                return annotation;
            return Global.getField(candidate, "hovers") instanceof Iterable<?> nested //$NON-NLS-1$
                ? firstProblemHover(nested) : null;
        }

        private static ITextHover firstProblemHover(Iterable<?> hovers)
        {
            for (Object nested : hovers)
            {
                if (nested instanceof AbstractProblemHover problemHover)
                    return problemHover;
            }
            return null;
        }
    }

    /**
     * Строка внизу попапа. Штатная («Нажмите CTRL+F2 для фокусировки») вводит в
     * заблуждение: фокусировка недостижима, потому что {@code Closer.keyPressed} в
     * JFace закрывает попап на любое нажатие клавиши, включая одиночный Ctrl. По
     * этому сочетанию подсказка открывается от каретки — так и пишем.
     */
    private static final class HoverAffordanceText
    {
        private static final String SHOW_INFORMATION_COMMAND = "org.eclipse.ui.edit.text.showInformation"; //$NON-NLS-1$

        static org.eclipse.jface.text.IInformationControlCreator wrapCreator(
            org.eclipse.jface.text.IInformationControlCreator creator)
        {
            if (creator == null || creator instanceof AffordanceCreator)
                return creator;
            return new AffordanceCreator(creator);
        }

        static String text()
        {
            String binding = binding();
            return binding == null ? null : "Нажмите " + binding + " для открытия от каретки"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String binding()
        {
            try
            {
                org.eclipse.ui.keys.IBindingService service =
                    PlatformUI.getWorkbench().getService(org.eclipse.ui.keys.IBindingService.class);
                String formatted = service == null ? null
                    : service.getBestActiveBindingFormattedFor(SHOW_INFORMATION_COMMAND);
                return formatted == null || formatted.isBlank() ? null : formatted;
            }
            catch (Exception e)
            {
                return null;
            }
        }

        private static final class AffordanceCreator
            implements org.eclipse.jface.text.IInformationControlCreator,
            org.eclipse.jface.text.IInformationControlCreatorExtension
        {
            private final org.eclipse.jface.text.IInformationControlCreator delegate;

            AffordanceCreator(org.eclipse.jface.text.IInformationControlCreator delegate)
            {
                this.delegate = delegate;
            }

            @Override
            public IInformationControl createInformationControl(org.eclipse.swt.widgets.Shell parent)
            {
                IInformationControl control = delegate.createInformationControl(parent);
                String text = text();
                if (text != null && control instanceof org.eclipse.jface.text.IInformationControlExtension4 ext)
                    ext.setStatusText(text);
                return control;
            }

            @Override
            public boolean canReuse(IInformationControl control)
            {
                boolean reuse = delegate instanceof org.eclipse.jface.text.IInformationControlCreatorExtension ext
                    && ext.canReuse(control);
                String text = text();
                if (reuse && text != null
                    && control instanceof org.eclipse.jface.text.IInformationControlExtension4 ext4)
                    ext4.setStatusText(text);
                return reuse;
            }

            @Override
            public boolean canReplace(org.eclipse.jface.text.IInformationControlCreator creator)
            {
                org.eclipse.jface.text.IInformationControlCreator other =
                    creator instanceof AffordanceCreator affordance ? affordance.delegate : creator;
                return delegate instanceof org.eclipse.jface.text.IInformationControlCreatorExtension ext
                    && ext.canReplace(other);
            }
        }
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
        IrBslTextHoverWrapper wrapper = wrapHoverDelegate(textHover, editor, false);
        if (wrapper == null)
            return false;
        Global.setField(provider, "hover", wrapper); //$NON-NLS-1$
        return true;
    }

    private static IrBslTextHoverWrapper wrapHoverDelegate(
        ITextHover hover, BslXtextEditor editor, boolean suppressPointerHover)
    {
        if (hover instanceof IrBslTextHoverWrapper existing)
            return existing;
        if (!isWrappableBslHover(hover))
            return null;
        return new IrBslTextHoverWrapper(hover, editor, suppressPointerHover);
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
     * Вариант для наведения мышью ({@code suppressPointerHover}) подавляет подсказку
     * на ключевых словах языка (EDT показывает по ним синтаксическую справку всей
     * конструкции) и на пустом месте (подсказка соседнего слова); Ctrl+наведение
     * и Ctrl+F2 (осознанный вызов) не подавляются.
     */
    private static final class IrBslTextHoverWrapper implements ITextHover, ITextHoverExtension, ITextHoverExtension2
    {
        private final ITextHover delegate;
        private final ITextHoverExtension delegateExt;
        private final ITextHoverExtension2 delegateExt2;
        private final BslXtextEditor editor;
        private final boolean suppressPointerHover;
        private final AtomicInteger fetchGeneration = new AtomicInteger();
        private volatile int lastScheduledOffset = -1;
        private volatile String lastIrHtml;
        private volatile String lastBaseHtml;
        private volatile String lastDirective;
        private volatile boolean lastCreationSite;
        private volatile HtmlIntegrityWatcher activeWatcher;

        IrBslTextHoverWrapper(ITextHover delegate, BslXtextEditor editor, boolean suppressPointerHover)
        {
            this.delegate = delegate;
            this.delegateExt = delegate instanceof ITextHoverExtension ext ? ext : null;
            this.delegateExt2 = delegate instanceof ITextHoverExtension2 ext2 ? ext2 : null;
            this.editor = editor;
            this.suppressPointerHover = suppressPointerHover;
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
            return delegateExt == null ? null
                : HoverAffordanceText.wrapCreator(delegateExt.getHoverControlCreator());
        }

        @Override
        public Object getHoverInfo2(ITextViewer textViewer, IRegion hoverRegion)
        {
            if (suppressPointerHover)
            {
                String reason = hoverSuppressionReason(textViewer, hoverRegion);
                if (reason != null)
                {
                    IrBslHoverDebug.step("hover", "suppressed reason=" + reason //$NON-NLS-1$ //$NON-NLS-2$
                        + " offset=" + (hoverRegion == null ? -1 : hoverRegion.getOffset())); //$NON-NLS-1$
                    return null;
                }
            }
            else
            {
            }
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

        /**
         * Слово, чаще употребляемое как имя метода ({@code Запрос.Выполнить()}),
         * чем как оператор {@code Выполнить <строка кода>}: попап на нём не подавляем.
         */
        private static final Set<String> KEYWORD_HOVER_WHITELIST =
            Set.of("Выполнить", "Execute"); //$NON-NLS-1$ //$NON-NLS-2$

        /**
         * Причина подавления подсказки при обычном наведении (без Ctrl) или {@code null}:
         * на ключевом слове языка EDT показывает синтаксическую справку всей
         * конструкции, на строковом литерале — справку по строковому литералу,
         * а на скрытом узле (пустое место, комментарий) — подсказку
         * ближайшего семантического элемента, то есть чужого слова. Зажатый Ctrl —
         * осознанный вызов, подсказка остаётся (как и у Ctrl+F2). Если в позиции есть
         * аннотация с текстом (маркер проблемы, орфография), подсказка не подавляется
         * никогда: подчёркивание обещает пояснение, и прятать его нельзя.
         */
        /**
         * Диагностика: {@code OS.GetKeyState} — Win32-специфичный внутренний API SWT
         * ({@code org.eclipse.swt.internal.win32.OS}). На платформах, где SWT собран не
         * под win32 (Linux GTK и т.п.), вызов может завершиться {@link LinkageError}
         * (класс/метод недоступен в фрагменте SWT этой платформы). Временно логируем
         * такой сбой в журнал «Комфорт» и пробрасываем исключение дальше без изменения
         * поведения — чтобы подтвердить гипотезу по логу пользователя, прежде чем менять
         * логику подавления подсказки.
         */
        private static boolean isCtrlPhysicallyHeld()
        {
            try
            {
                return (OS.GetKeyState(OS.VK_CONTROL) & 0x8000) != 0;
            }
            catch (LinkageError | RuntimeException e)
            {
                IrBslHoverDebug.problem("OS.GetKeyState failed: " + e.getClass().getName() //$NON-NLS-1$
                    + ": " + e.getMessage() + "; os.name=" + System.getProperty("os.name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                throw e;
            }
        }

        private String hoverSuppressionReason(ITextViewer textViewer, IRegion hoverRegion)
        {
            if (hoverRegion == null)
                return null;
            int offset = hoverRegion.getOffset();
            // При Ctrl+F2 от каретки Ctrl зажат физически, но это не «Ctrl+наведение».
            // Подавления при таком вызове действуют не все: на ключевом слове EDT даёт
            // осмысленную справку по конструкции, и осознанный вызов её показывает,
            // а на комментарии и пустом месте подсказка соседнего слова бесполезна
            // в любом режиме.
            boolean caret = Boolean.TRUE.equals(caretInvocation.get());
            if (!caret && isCtrlPhysicallyHeld())
                return null;
            String annotation = annotationTextAt(textViewer, hoverRegion);
            if (annotation != null)
                return null;
            if (editor == null || offset < 0)
                return null;
            org.eclipse.jface.text.IDocument document = editor.getDocument();
            if (!(document instanceof IXtextDocument xtextDoc))
                return null;
            try
            {
                return xtextDoc.readOnly(
                    (IUnitOfWork<String, XtextResource>) resource -> {
                        IParseResult parseResult = resource != null ? resource.getParseResult() : null;
                        if (parseResult == null)
                            return null;
                        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(parseResult.getRootNode(), offset);
                        if (leaf == null)
                            return null;
                        String token = leaf.getText();
                        String keywordValue = leaf.getGrammarElement() instanceof Keyword grammarKeyword
                            ? grammarKeyword.getValue() : null;
                        boolean whitelisted = keywordValue != null
                            && KEYWORD_HOVER_WHITELIST.contains(keywordValue);
                        String ruleName = grammarRuleName(leaf);
                        boolean stringLiteral = ruleName != null
                            && ruleName.toUpperCase(java.util.Locale.ROOT).contains("STRING"); //$NON-NLS-1$
                        String reason;
                        if (leaf.isHidden())
                            reason = token != null && token.isBlank() ? "blank" : "hidden"; //$NON-NLS-1$ //$NON-NLS-2$
                        else if (!caret && keywordValue != null && !keywordValue.isEmpty()
                            && Character.isLetter(keywordValue.charAt(0)) && !whitelisted)
                            reason = "keyword"; //$NON-NLS-1$
                        else if (!caret && stringLiteral)
                            reason = "string"; //$NON-NLS-1$
                        else
                            reason = null;
                        return reason;
                    });
            }
            catch (Exception ignored)
            {
                return null;
            }
        }

        /**
         * Текст первой аннотации с сообщением, перекрывающей область подсказки,
         * или {@code null}. Именно её показывает штатный hover, поэтому при её
         * наличии подавлять подсказку нельзя.
         */
        private String annotationTextAt(ITextViewer textViewer, IRegion hoverRegion)
        {
            IAnnotationModel model = resolveAnnotationModel(textViewer);
            if (model == null)
                return null;
            int start = hoverRegion.getOffset();
            int end = start + Math.max(hoverRegion.getLength(), 0);
            try
            {
                for (java.util.Iterator<?> it = model.getAnnotationIterator(); it.hasNext();)
                {
                    if (!(it.next() instanceof Annotation annotation) || annotation.isMarkedDeleted())
                        continue;
                    String text = annotation.getText();
                    if (text == null || text.isBlank() || !isProblemAnnotationType(annotation.getType()))
                        continue;
                    Position position = model.getPosition(annotation);
                    if (position == null || position.isDeleted())
                        continue;
                    int posStart = position.getOffset();
                    int posEnd = posStart + Math.max(position.getLength(), 1);
                    if (posEnd > start && posStart < Math.max(end, start + 1))
                        return text;
                }
            }
            catch (Exception ignored)
            {
                // модель аннотаций могла измениться во время обхода
            }
            return null;
        }

        /**
         * Тип аннотации, для которой штатный hover показывает сообщение
         * (как {@code AbstractProblemHover.isHandled}): ошибка, предупреждение,
         * информация, орфография. Аннотации Quick Diff («изменено: N строк»),
         * свёртки, вхождений и т.п. сюда не попадают — они подсказку не дают.
         */
        private static boolean isProblemAnnotationType(String type)
        {
            if (type == null)
                return false;
            return type.contains("error") || type.contains("warning") //$NON-NLS-1$ //$NON-NLS-2$
                || type.contains("info") || type.contains("spelling"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private IAnnotationModel resolveAnnotationModel(ITextViewer textViewer)
        {
            if (textViewer instanceof ISourceViewer sourceViewer)
            {
                IAnnotationModel model = sourceViewer.getAnnotationModel();
                if (model != null)
                    return model;
            }
            if (editor == null)
                return null;
            org.eclipse.ui.texteditor.IDocumentProvider provider = editor.getDocumentProvider();
            return provider == null ? null : provider.getAnnotationModel(editor.getEditorInput());
        }

        /** Имя правила грамматики листа ({@code STRING} и т.п.) или {@code null}. */
        private static String grammarRuleName(ILeafNode leaf)
        {
            Object grammarElement = leaf.getGrammarElement();
            if (grammarElement instanceof org.eclipse.xtext.RuleCall ruleCall && ruleCall.getRule() != null)
                return ruleCall.getRule().getName();
            if (grammarElement instanceof org.eclipse.xtext.AbstractRule rule)
                return rule.getName();
            return null;
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
            if (element instanceof StaticFeatureAccess access
                && BslEditorHighlighting.isImplicitVariableCreationSite(access))
                return access;
            if (element instanceof ImplicitVariable variable
                && variable.eContainer() instanceof StaticFeatureAccess access
                && BslEditorHighlighting.isImplicitVariableCreationSite(access))
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
