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
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.hover.AbstractCompositeHover;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
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
        BslDocCommentTypeMerge.install();
        Display.getDefault().asyncExec(() ->
        {
            ParamHintHtmlModifier.install(Display.getDefault());
            MethodDefinitionLink.install(Display.getDefault());
            MetadataTypeMenu.install(Display.getDefault());

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
            @Override public void partActivated(IWorkbenchPartReference r) { partOpened(r); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partVisible(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) { partOpened(r); }
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
        FunctionReturnTypes.observeLocalContextEditor(editor);
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        // Глобальный тумблер «Подсказки при наведении» применяется и к новым,
        // и к уже обёрнутым редакторам (повторные вызовы при активации/смене страницы).
        BslHoverHintState.applyToViewer(sourceViewer);
        BslEditorFoldingHook.installCommentHover(sourceViewer);
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
        private volatile ContentHover lastQueryHover;

        AnnotationAwareInformationProvider(IInformationProvider delegate, SourceViewer sourceViewer)
        {
            this.delegate = delegate;
            this.sourceViewer = sourceViewer;
        }

        @Override
        public IRegion getSubject(ITextViewer textViewer, int offset)
        {
            IRegion queryRegion = contentHoverRegion(textViewer, offset);
            return queryRegion != null ? queryRegion : delegate.getSubject(textViewer, offset);
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
            lastQueryHover = null;
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
                BslXtextEditor editor = SmartContentAssistProcessor.resolveEditorForLiteralAudit(textViewer);
                ContentHover queryHover =
                    contentHover(editor, textViewer, subject.getOffset());
                if (queryHover != null)
                {
                    lastQueryHover = queryHover;
                    return queryHover.info();
                }
                // Пусто — значит подсказка подавлена (комментарий, ключевое слово):
                // при наведении мышью здесь тоже ничего не показывается.
                if (info == null)
                    return null;
            }
            Object info = delegate instanceof IInformationProviderExtension ext
                ? ext.getInformation2(textViewer, subject)
                : delegate.getInformation(textViewer, subject);
            if (subject != null && IrBslHoverHtml.isBslBrowserInput(info))
                BslStructureInsertCommentTypes.completePropertyHover(info, textViewer, subject.getOffset());
            return info;
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getInformationPresenterControlCreator()
        {
            ContentHover queryHover = lastQueryHover;
            if (queryHover != null)
                return HoverAffordanceText.wrapCreator(queryHover.creator());
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
                this.delegate = IrBslHoverHtml.wrapCreator(delegate);
            }

            @Override
            public IInformationControl createInformationControl(org.eclipse.swt.widgets.Shell parent)
            {
                IInformationControl control = delegate.createInformationControl(parent);
                IrBslHoverHtml.installHtmlDecoration(control);
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
                if (reuse)
                    IrBslHoverHtml.installHtmlDecoration(control);
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

    /** Описание содержимого редактора и его штатный контрол. */
    record ContentHover(Object info, org.eclipse.jface.text.IInformationControlCreator creator) {}

    private static ContentHover contentHover(BslXtextEditor editor, ITextViewer viewer, int offset)
    {
        ContentHover group = CommonModuleAssistGroups.groupHover(viewer, offset);
        if (group != null)
            return group;
        var query = BslQueryLiteralSupport.queryHover(editor, viewer, offset);
        return query != null ? new ContentHover(query.info(), query.creator())
            : BslLiteralValues.valueHover(viewer, offset);
    }

    private static IRegion contentHoverRegion(ITextViewer viewer, int offset)
    {
        IRegion group = CommonModuleAssistGroups.groupHoverRegion(viewer, offset);
        if (group != null)
            return group;
        IRegion query = BslQueryLiteralSupport.queryHoverRegion(viewer, offset);
        return query != null ? query : BslLiteralValues.valueHoverRegion(viewer, offset);
    }

    /**
     * Единственная точка подключения обработчиков боковой подсказки. Вызывается перед
     * чтением описания в SmartCompletionProposal, независимо от источника строки списка.
     * Источники предложений сами обработчики не устанавливают.
     */
    static void prepareSideHint(ICompletionProposal proposal, ITextViewer viewer)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (!(raw instanceof ConfigurableCompletionProposal configurable))
            return;
        Object hover = Global.getField(configurable, "hover"); //$NON-NLS-1$
        if (hover instanceof SideHintHover installed)
            installed.proposalViewer = viewer;
        else if (hover instanceof IEObjectHover original)
            configurable.setHover(new SideHintHover(original, viewer));
    }

    /** Общая цепочка обработчиков боковой подсказки; новые обработчики добавляются здесь. */
    private static final class SideHintHover implements IEObjectHover, ITextHoverExtension
    {
        private final IEObjectHover delegate;
        private ITextViewer proposalViewer;

        SideHintHover(IEObjectHover delegate, ITextViewer proposalViewer)
        {
            this.delegate = delegate;
            this.proposalViewer = proposalViewer;
        }

        @Override
        public Object getHoverInfo(EObject object, ITextViewer viewer, IRegion region)
        {
            // Провайдер предложения может вернуть EObject только здесь, после первой проверки строки.
            if (!SmartCompletionProposal.isEdtDocumentationObjectReady(object))
                return null;
            Object info = delegate.getHoverInfo(object, viewer, region);
            // EDT не всегда задаёт viewer предложения; используем контекст общего чтения описания.
            ITextViewer context = viewer != null ? viewer : proposalViewer;
            BslStructureInsertCommentTypes.completePropertyHover(info, object, context);
            return decorateSideHint(info, object, context);
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getHoverControlCreator()
        {
            return delegate instanceof ITextHoverExtension extension
                ? IrBslHoverHtml.wrapCreator(extension.getHoverControlCreator()) : null;
        }
    }

    /**
     * Боковая подсказка списка: те же ссылки в описании и блок «: Конструктор()»
     * с меню, что и при наведении. Возвращает исходное {@code info}, если добавлять нечего.
     */
    static Object decorateSideHint(Object info, org.eclipse.emf.ecore.EObject member,
        ITextViewer viewer)
    {
        try
        {
            if (info == null || viewer == null || !IrBslHoverHtml.isBslBrowserInput(info)
                || !(viewer.getDocument() instanceof IXtextDocument document))
                return info;
            var links = BslStructureInsertCommentTypes.seeLinksFor(member, viewer);
            String block = ParamHintHtmlModifier.memberSeeLinksBlock(links, document.getResourceURI(),
                MethodDefinitionLink::registerFormHandler);
            String html = MetadataTypeMenu.decorate(IrBslHoverHtml.readHtml(info), viewer,
                SmartContentAssistProcessor.resolveEditorForLiteralAudit(viewer));
            String injected = ParamHintHtmlModifier.injectMemberSeeLinks(html, block);
            String result = injected != null ? injected : html;
            String descriptionHtml = result;
            result = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
            {
                var descriptionLinks = ParamHintHtmlModifier.methodDescriptionLinksForInput(info,
                    document.getResourceURI(), MethodDefinitionLink::registerFormHandler);
                if (descriptionLinks.isEmpty())
                    descriptionLinks = ParamHintHtmlModifier.methodDescriptionLinks(member,
                        document.getResourceURI(), MethodDefinitionLink::registerFormHandler);
                return ParamHintHtmlModifier.injectMethodDescriptionLinks(descriptionHtml, descriptionLinks);
            });
            if (result == null)
                result = descriptionHtml;
            return result.equals(IrBslHoverHtml.readHtml(info)) ? info : IrBslHoverHtml.withHtml(info, result);
        }
        catch (Throwable ex)
        {
            return info;
        }
    }

    /** Мост клика без навигации браузера, как в подсказке параметров. */
    private static final class MethodDefinitionLink
    {
        private static final String FUNCTION = "comfortOpenMethodDefinition";
        private static final Map<String, Runnable> formHandlerActions =
            new java.util.concurrent.ConcurrentHashMap<>();

        /** Действие ссылки на элемент формы, подписанный на событие методом (issue 671). */
        static String registerFormHandler(Runnable action)
        {
            if (formHandlerActions.size() > 200)
                formHandlerActions.clear();
            String token = "formhandler:" + java.util.UUID.randomUUID();
            formHandlerActions.put(token, action);
            return token;
        }

        static void install(Display display)
        {
            display.addFilter(org.eclipse.swt.SWT.Show, event -> {
                if (event.widget instanceof org.eclipse.swt.widgets.Shell shell)
                    installBrowser(IrBslHoverHtml.findControlBrowser(shell));
            });
        }

        static void installBrowser(Browser browser)
        {
            if (browser == null || browser.isDisposed() || browser.getData(FUNCTION) != null)
                return;
            Display display = browser.getDisplay();
            org.eclipse.swt.widgets.Shell shell = browser.getShell();
            org.eclipse.swt.browser.BrowserFunction function =
                new org.eclipse.swt.browser.BrowserFunction(browser, FUNCTION)
                {
                    @Override
                    public Object function(Object[] arguments)
                    {
                        if (arguments == null || arguments.length < 1 || arguments.length > 2
                            || !(arguments[0] instanceof String encoded))
                            return Boolean.FALSE;
                        boolean closeWindow = arguments.length == 1 || !Boolean.FALSE.equals(arguments[1]);
                        if (encoded.startsWith("formhandler:"))
                        {
                            Runnable action = formHandlerActions.get(encoded);
                            if (action == null)
                                return Boolean.FALSE;
                            display.asyncExec(() -> {
                                if (closeWindow && !shell.isDisposed())
                                    shell.setVisible(false);
                                action.run();
                            });
                            return Boolean.TRUE;
                        }
                        org.eclipse.emf.common.util.URI uri = org.eclipse.emf.common.util.URI.createURI(
                            java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8));
                        display.asyncExec(() -> {
                            org.eclipse.xtext.resource.IResourceServiceProvider provider =
                                org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                                    .getResourceServiceProvider(org.eclipse.emf.common.util.URI.createURI("*.bsl"));
                            org.eclipse.xtext.ui.editor.IURIEditorOpener opener = provider == null ? null
                                : provider.get(org.eclipse.xtext.ui.editor.IURIEditorOpener.class);
                            if (opener != null)
                            {
                                if (!shell.isDisposed())
                                    shell.setVisible(false);
                                opener.open(uri, true);
                            }
                        });
                        return Boolean.TRUE;
                    }
                };
            browser.setData(FUNCTION, function);
        }
    }

    /** Мост {@code comfortOpenMethodDefinition} для браузера подсказки параметров. */
    static void installMethodDefinitionFunction(Browser browser)
    {
        MethodDefinitionLink.installBrowser(browser);
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

    /** Подмена страницы ошибки описания члена — общая для наведения и кнопки аннотации. */
    static Object recoverMemberDocumentation(ITextViewer textViewer, int offset, Object info)
    {
        if (!ParamHintHtmlModifier.needsMemberDocumentationRecovery(info)
            || !(textViewer.getDocument() instanceof IXtextDocument document))
            return info;
        Object recovered = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
        {
            if (resource == null)
                return info;
            EObject source = new EObjectAtOffsetHelper().resolveContainedElementAt(resource, offset);
            return ParamHintHtmlModifier.recoverMemberDocumentation(info, source, resource);
        });
        return recovered != null ? recovered : info;
    }

    /** Доработки описания из кнопки аннотации — те же, что при наведении. */
    static java.util.function.Consumer<IInformationControl> createIdentifierDocDecorator(
        ISourceViewer viewer, IRegion region, Object info)
    {
        if (viewer instanceof SourceViewer sourceViewer)
        {
            @SuppressWarnings("unchecked")
            Map<Object, ITextHover> hovers =
                (Map<Object, ITextHover>)Global.getField(sourceViewer, "fTextHovers"); //$NON-NLS-1$
            if (hovers != null)
                for (ITextHover hover : hovers.values())
                    if (hover instanceof IrBslTextHoverWrapper existing)
                    {
                        IrBslTextHoverWrapper wrapper =
                            new IrBslTextHoverWrapper(existing.delegate, existing.editor, false);
                        wrapper.prepareHoverDecorations(viewer, region, info);
                        return control -> {
                            String html = IrBslHoverHtml.readHtml(info);
                            String decorated = wrapper.applyHoverDecorations(html);
                            if (!decorated.equals(html))
                            {
                                IrBslHoverHtml.withHtml(info, decorated);
                                IrBslHoverHtml.applyHtmlToControl(control, decorated);
                            }
                        };
                    }
        }
        return control -> {};
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
        private final String platformAvailabilityColor;
        private final AtomicInteger fetchGeneration = new AtomicInteger();
        private volatile int lastScheduledOffset = -1;
        private volatile String lastIrHtml;
        private volatile String lastBaseHtml;
        private volatile String lastDirective;
        private volatile Map<String, DefinedTypeLinks.Link> lastDefinedTypeLinks = Map.of();
        private volatile String lastFormHandlersHtml;
        /** «: Конструктор()» с меню для свойства или параметра под указателем (issue 828). */
        private volatile String lastMemberSeeLinksHtml;
        private volatile Map<String, String> lastMethodDescriptionLinks = Map.of();
        private volatile String lastPlatformAvailabilityHtml;
        /** «Приемник.Тип: …» места слова под указателем — как в боковой подсказке списка. */
        private volatile String lastReceiverTypeLine;
        private volatile boolean lastCreationSite;
        private volatile HtmlIntegrityWatcher activeWatcher;
        private volatile ContentHover lastQueryHover;
        /** Функция конфигурации под указателем — её тип в заголовке ({@link FunctionReturnTypes}). */
        private volatile FunctionReturnTypes.Target lastFunctionTarget;
        /** Штатная подсказка последнего наведения — основа перерисовки после расчёта типа функции. */
        private volatile Object lastHoverBaseInput;
        private volatile int lastHoverOffset = -1;
        /** Последний применённый заголовок с типом функции и его основа. UI-поток. */
        private Object lastFunctionTypeBase;
        private String lastFunctionTypeHtml;

        IrBslTextHoverWrapper(ITextHover delegate, BslXtextEditor editor, boolean suppressPointerHover)
        {
            this.delegate = delegate;
            this.delegateExt = delegate instanceof ITextHoverExtension ext ? ext : null;
            this.delegateExt2 = delegate instanceof ITextHoverExtension2 ext2 ? ext2 : null;
            this.editor = editor;
            this.suppressPointerHover = suppressPointerHover;
            // Обёртка создаётся при подключении редактора в UI-потоке,
            // но EDT вызывает getHoverInfo2 из фонового TextViewerHoverManager.
            this.platformAvailabilityColor = PlatformWordAvailability.recentColor();
            // Слабая ссылка: слушатель не держит обёртку закрытого редактора.
            java.lang.ref.WeakReference<IrBslTextHoverWrapper> self = new java.lang.ref.WeakReference<>(this);
            FunctionReturnTypes.addListener(() ->
            {
                IrBslTextHoverWrapper wrapper = self.get();
                if (wrapper == null)
                    return false;
                wrapper.refreshFunctionType();
                return true;
            });
        }

        @Override
        public String getHoverInfo(ITextViewer textViewer, IRegion hoverRegion)
        {
            Object info = getHoverInfo2(textViewer, hoverRegion);
            if (info == null)
                return null;
            if (IrBslHoverHtml.isBslBrowserInput(info))
                return IrBslHoverHtml.readDisplayHtml(info);
            return info instanceof String text ? text : info.toString();
        }

        @Override
        public IRegion getHoverRegion(ITextViewer textViewer, int offset)
        {
            IRegion region = delegate.getHoverRegion(textViewer, offset);
            IRegion queryRegion = contentHoverRegion(textViewer, offset);
            return queryRegion != null ? queryRegion : region;
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getHoverControlCreator()
        {
            ContentHover queryHover = lastQueryHover;
            if (queryHover != null)
                return HoverAffordanceText.wrapCreator(queryHover.creator());
            return delegateExt == null ? null
                : HoverAffordanceText.wrapCreator(delegateExt.getHoverControlCreator());
        }

        // #region temp #877: подсказка в редакторе перестала открываться — вход, ранние выходы, исключения
        @Override
        public Object getHoverInfo2(ITextViewer textViewer, IRegion hoverRegion)
        {
            int entryOffset = hoverRegion == null ? -1 : hoverRegion.getOffset();
            functionTypeLog("enter offset=" + entryOffset + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                Object info = hoverInfo2(textViewer, hoverRegion);
                functionTypeLog("exit offset=" + entryOffset + " info=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (info == null ? "null" : info.getClass().getSimpleName()) //$NON-NLS-1$
                    + (info instanceof String text ? " text=" + text.substring(0, Math.min(400, text.length())) : "") //$NON-NLS-1$ //$NON-NLS-2$
                    + " queryHover=" + (lastQueryHover != null) //$NON-NLS-1$
                    + " delegate=" + delegate.getClass().getName() //$NON-NLS-1$
                    + " current=" + hoverClass(Global.getField(delegate, "currentHover")) //$NON-NLS-1$ //$NON-NLS-2$
                    + " hovers=" + hoverClasses(Global.getField(delegate, "hovers"))); //$NON-NLS-1$ //$NON-NLS-2$
                return info;
            }
            catch (Throwable error)
            {
                java.io.StringWriter trace = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(trace));
                functionTypeLog("error offset=" + entryOffset + " " + trace); //$NON-NLS-1$ //$NON-NLS-2$
                throw error;
            }
        }
        /** Состояние модели документа в точке — тем же доступом, что у штатной подсказки. */
        private static String describeDocumentModel(ITextViewer textViewer, int offset)
        {
            try
            {
                if (!(textViewer.getDocument() instanceof IXtextDocument document))
                    return "noXtextDocument"; //$NON-NLS-1$
                String reconcile = String.valueOf(Global.invoke(document, "needsReconciling")); //$NON-NLS-1$
                int docLength = document.getLength();
                return "needsReconciling=" + reconcile + " docLength=" + docLength + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + document.readOnly((IUnitOfWork<String, XtextResource>) resource ->
                    {
                        if (resource == null)
                            return "resource=null"; //$NON-NLS-1$
                        StringBuilder sb = new StringBuilder();
                        sb.append("uri=").append(resource.getURI()) //$NON-NLS-1$
                            .append(" loaded=").append(resource.isLoaded()) //$NON-NLS-1$
                            .append(" contents=").append(resource.getContents().size()); //$NON-NLS-1$
                        IParseResult parse = resource.getParseResult();
                        sb.append(" parse=").append(parse != null); //$NON-NLS-1$
                        if (parse != null && parse.getRootNode() != null)
                        {
                            sb.append(" rootLength=").append(parse.getRootNode().getTotalLength()) //$NON-NLS-1$
                                .append(" syntaxErrors=").append(parse.hasSyntaxErrors()); //$NON-NLS-1$
                            ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(parse.getRootNode(), offset);
                            sb.append(" leaf=").append(leaf == null ? null : "'" + leaf.getText() + "' hidden=" + leaf.isHidden()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        }
                        EObject element = new EObjectAtOffsetHelper().resolveElementAt(resource, offset);
                        sb.append(" element=").append(element == null ? null : element.eClass().getName() //$NON-NLS-1$
                            + (element.eIsProxy() ? "(proxy)" : "")); //$NON-NLS-1$ //$NON-NLS-2$
                        EObject crossRef = new EObjectAtOffsetHelper().resolveCrossReferencedElementAt(resource, offset);
                        sb.append(" crossRef=").append(crossRef == null ? null : crossRef.eClass().getName() //$NON-NLS-1$
                            + (crossRef.eIsProxy() ? "(proxy " + ((org.eclipse.emf.ecore.InternalEObject)crossRef).eProxyURI() + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        return sb.toString();
                    });
            }
            catch (Throwable error)
            {
                return "describeError=" + error; //$NON-NLS-1$
            }
        }

        private static String hoverClass(Object hover)
        {
            if (hover == null)
                return "null"; //$NON-NLS-1$
            Object inner = Global.getField(hover, "delegate"); //$NON-NLS-1$
            return hover.getClass().getName() + (inner != null ? "(" + inner.getClass().getName() + ")" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private static String hoverClasses(Object hovers)
        {
            if (!(hovers instanceof Iterable<?> list))
                return String.valueOf(hovers);
            StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
            for (Object hover : list)
                sb.append(hoverClass(hover)).append(' ');
            return sb.append(']').toString();
        }
        // #endregion

        private Object hoverInfo2(ITextViewer textViewer, IRegion hoverRegion)
        {
            lastQueryHover = null;
            lastFunctionTarget = null;
            lastHoverBaseInput = null;
            lastHoverOffset = -1;
            if (hoverRegion != null && annotationTextAt(textViewer, hoverRegion) == null)
            {
                ContentHover queryHover =
                    contentHover(editor, textViewer, hoverRegion.getOffset());
                if (queryHover != null)
                {
                    cancelIrEnrichment();
                    lastQueryHover = queryHover;
                    functionTypeLog("return queryHover"); //$NON-NLS-1$
                    return queryHover.info();
                }
            }
            if (suppressPointerHover)
            {
                String reason = hoverSuppressionReason(textViewer, hoverRegion);
                if (reason != null)
                {
                    IrBslHoverDebug.step("hover", "suppressed reason=" + reason //$NON-NLS-1$ //$NON-NLS-2$
                        + " offset=" + (hoverRegion == null ? -1 : hoverRegion.getOffset())); //$NON-NLS-1$
                    functionTypeLog("return suppressed reason=" + reason); //$NON-NLS-1$
                    return null;
                }
            }
            else
            {
            }
            // #region temp timing #877: долгое первое открытие подсказки
            long t0 = System.nanoTime();
            // Подсказка читает готовые типы; пересчёт цепочки зависимостей здесь не запускается — только
            // план результатов запросов метода (поля выборки), как перед списком.
            refreshQueryTypesBeforeHover(textViewer, hoverRegion);
            long t1 = System.nanoTime();
            Object info = delegateExt2 != null
                ? delegateExt2.getHoverInfo2(textViewer, hoverRegion)
                : delegate.getHoverInfo(textViewer, hoverRegion);
            long t2 = System.nanoTime();
            if (info == null || hoverRegion == null || editor == null)
            {
                functionTypeLog("timing noInfo staleMs=" + (t1 - t0) / 1_000_000L //$NON-NLS-1$
                    + " edtMs=" + (t2 - t1) / 1_000_000L); //$NON-NLS-1$
                return info;
            }
            if (!IrBslHoverHtml.isBslBrowserInput(info))
            {
                // #region temp #877: EDT не нашла объект под указателем (BslDispatchingEObjectTextHover → "")
                if ("".equals(info)) //$NON-NLS-1$
                    functionTypeLog("emptyInfo " + describeDocumentModel(textViewer, hoverRegion.getOffset())); //$NON-NLS-1$
                // #endregion
                return info;
            }
            final int offset = hoverRegion.getOffset();
            info = recoverMemberDocumentation(textViewer, offset, info);
            long t3 = System.nanoTime();
            BslStructureInsertCommentTypes.completePropertyHover(info, textViewer, offset);
            long t4 = System.nanoTime();
            prepareHoverDecorations(textViewer, hoverRegion, info);
            long t5 = System.nanoTime();
            functionTypeLog("timing offset=" + offset + " staleMs=" + (t1 - t0) / 1_000_000L //$NON-NLS-1$ //$NON-NLS-2$
                + " edtMs=" + (t2 - t1) / 1_000_000L + " recoverMs=" + (t3 - t2) / 1_000_000L //$NON-NLS-1$ //$NON-NLS-2$
                + " propertyMs=" + (t4 - t3) / 1_000_000L + " decorationsMs=" + (t5 - t4) / 1_000_000L); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            functionTypeLog("hover offset=" + offset + " target=" + lastFunctionTarget); //$NON-NLS-1$ //$NON-NLS-2$
            scheduleFunctionTypeSync(info, offset);
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

        private void prepareHoverDecorations(ITextViewer textViewer, IRegion region, Object info)
        {
            String directive = resolveHoverDirective(region.getOffset());
            lastDirective = (directive != null && !directive.isBlank()) ? directive : null;
            lastCreationSite = isImplicitVariableCreationAt(region);
            resolveMemberTargets(region);
            if (editor.getDocument() instanceof IXtextDocument document)
            {
                var descriptionLinks = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                    ParamHintHtmlModifier.methodDescriptionLinksForInput(info, document.getResourceURI(),
                        MethodDefinitionLink::registerFormHandler));
                if (descriptionLinks != null && !descriptionLinks.isEmpty())
                    lastMethodDescriptionLinks = descriptionLinks;
            }
            lastDefinedTypeLinks = resolveDefinedTypeLinks(IrBslHoverHtml.readHtml(info));
            lastFormHandlersHtml = resolveFormHandlersHtml(region);
            lastMemberSeeLinksHtml = resolveMemberSeeLinksHtml(textViewer, region);
            lastPlatformAvailabilityHtml = PlatformWordAvailability.hoverBlock(info, platformAvailabilityColor);
            // При ИР описание слова строит ИР — как и в боковой подсказке списка.
            lastReceiverTypeLine = IrBslExpressionHtmlSupport.resolveConnectedSession(editor) != null ? null
                : BslExpectedTypePremiums.receiverTypeLineAt(textViewer.getDocument(),
                    region.getOffset() + region.getLength());
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
            if (!caret && KeyStateProbe.isCtrlPressed())
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

        /**
         * Функция конфигурации под указателем: в UI-потоке её тип становится первым в расчёте
         * ({@link FunctionReturnTypes#activate}) и подставляется в заголовок открытой подсказки;
         * после расчёта заголовок обновляет {@link #refreshFunctionType}.
         */
        private void scheduleFunctionTypeSync(Object baseInput, int offset)
        {
            if (lastFunctionTarget == null)
                return;
            lastHoverBaseInput = baseInput;
            lastHoverOffset = offset;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (offset != lastHoverOffset)
                {
                    functionTypeLog("sync skip offset=" + offset + " now=" + lastHoverOffset); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                FunctionReturnTypes.activate(lastFunctionTarget);
                refreshFunctionType();
            });
        }

        /** Заголовок открытой подсказки — с текущим типом функции (вместе с дополнением ИР). UI-поток. */
        private void refreshFunctionType()
        {
            Object base = lastHoverBaseInput;
            if (lastFunctionTarget == null || base == null)
            {
                functionTypeLog("refresh skip target=" + lastFunctionTarget + " base=" + (base != null)); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            String baseHtml = lastBaseHtml;
            String irHtml = lastIrHtml;
            String html = baseHtml != null && irHtml != null
                ? IrBslHoverHtml.mergeHtml(baseHtml, irHtml) : IrBslHoverHtml.readHtml(base);
            if (html == null)
            {
                functionTypeLog("refresh skip noHtml"); //$NON-NLS-1$
                return;
            }
            // Поколение типов могло смениться без правки текста (досчитан результат запроса).
            // Повторное чтение — только для показанной подсказки; модель не пересчитывается.
            if (IrBslHoverControlAccess.resolve(editor) != null)
                FunctionReturnTypes.activate(lastFunctionTarget);
            String decorated = applyHoverDecorations(html);
            // Публикации идут по всем модулям; перерисовка без изменений сбивала бы прокрутку.
            if (base == lastFunctionTypeBase && decorated.equals(lastFunctionTypeHtml))
            {
                functionTypeLog("refresh skip unchanged"); //$NON-NLS-1$
                return;
            }
            boolean applied = tryApplyToCurrentControl(decorated);
            functionTypeLog("refresh applied=" + applied + " ir=" + (irHtml != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " heading=" + headingSnippet(decorated)); //$NON-NLS-1$
            if (applied)
            {
                lastFunctionTypeBase = base;
                lastFunctionTypeHtml = decorated;
            }
        }

        /** Временный лог типа функции в подсказке (#877; снять после подтверждения). */
        private static void functionTypeLog(String text)
        {
            Global.tempLog("hover-function-type", text); //$NON-NLS-1$
        }

        private static String headingSnippet(String html)
        {
            if (html == null)
                return null;
            int heading = html.indexOf("infocontrol-heading"); //$NON-NLS-1$
            if (heading < 0)
                return "noHeading len=" + html.length() //$NON-NLS-1$
                    + " start=" + html.substring(0, Math.min(400, html.length())); //$NON-NLS-1$
            int end = html.indexOf("</div>", heading); //$NON-NLS-1$
            return html.substring(heading, end > heading ? Math.min(end + 6, heading + 1200)
                : Math.min(html.length(), heading + 600));
        }

        /**
         * Тип в заголовке функции ({@code Функция <Тип> Имя()}) — как в строке списка
         * автодополнения: итоговый рассчитанный; пока пересчёт ждёт или идёт — последний известный
         * с {@code ?} первым элементом; без пересчёта и итога — штатный. И в фоновом потоке подсказки: тогда тип есть уже в HTML, который получает
         * EDT, и её повторные выставления исходного HTML не возвращают штатный тип (лог 09:23).
         */
        private String injectFunctionType(String html)
        {
            FunctionReturnTypes.Target target = lastFunctionTarget;
            if (target == null || html == null)
                return html;
            return (String)FunctionReturnTypes.withFunctionReturnType(html, target,
                editor.getInternalSourceViewer());
        }

        private Object maybeDecorateHoverInfo(Object info)
        {
            String html = IrBslHoverHtml.readHtml(info);
            String modified = applyHoverDecorations(html);
            return modified.equals(html) ? info : IrBslHoverHtml.withHtml(info, modified);
        }

        private String applyHoverDecorations(String html)
        {
            if (html == null)
                return html;
            String result = injectDefinedTypeLinks(injectFunctionType(html));
            if (lastDirective != null && !lastDirective.isBlank())
                result = IrBslHoverHtml.injectDirectiveIntoHtml(result, lastDirective);
            if (lastCreationSite)
                result = IrBslHoverHtml.injectNewVariablePrefix(result);
            String handlersHtml = lastFormHandlersHtml;
            if (handlersHtml != null && !result.contains("comfort-form-handlers"))
            {
                // Сразу под заголовком: низ подсказки обрезается высотой окна (штатная сноска
                // тоже не видна), блок в конце тела оставался за краем.
                int insertAt = result.indexOf("<div class=\"hover-content\">");
                if (insertAt < 0)
                    insertAt = result.toLowerCase().lastIndexOf("</body>");
                result = insertAt >= 0
                    ? result.substring(0, insertAt) + handlersHtml + result.substring(insertAt)
                    : result + handlersHtml;
            }
            String withSeeLinks = ParamHintHtmlModifier.injectMemberSeeLinks(result, lastMemberSeeLinksHtml);
            if (withSeeLinks != null)
                result = withSeeLinks;
            result = ParamHintHtmlModifier.injectMethodDescriptionLinks(result, lastMethodDescriptionLinks);
            result = BslExpectedTypePremiums.prependReceiverType(result, lastReceiverTypeLine);
            return MetadataTypeMenu.decorate(
                PlatformWordAvailability.injectHoverBlock(result, lastPlatformAvailabilityHtml),
                editor.getInternalSourceViewer(), editor);
        }

        private String resolveMemberSeeLinksHtml(ITextViewer textViewer, IRegion region)
        {
            try
            {
                var links = BslStructureInsertCommentTypes.seeLinksAt(textViewer, region.getOffset());
                if (links.isEmpty() || !(editor.getDocument() instanceof IXtextDocument document))
                    return null;
                return ParamHintHtmlModifier.memberSeeLinksBlock(links, document.getResourceURI(),
                    MethodDefinitionLink::registerFormHandler);
            }
            catch (Throwable ex)
            {
                return null;
            }
        }

        private Map<String, DefinedTypeLinks.Link> resolveDefinedTypeLinks(String html)
        {
            Set<String> names = DefinedTypeLinks.names(html);
            if (names.isEmpty() || !(editor.getDocument() instanceof IXtextDocument document))
                return Map.of();
            return document.readOnly((IUnitOfWork<Map<String, DefinedTypeLinks.Link>, XtextResource>) resource ->
                DefinedTypeLinks.resolve(names, resource));
        }

        private String injectDefinedTypeLinks(String html)
        {
            return DefinedTypeLinks.inject(html, lastDefinedTypeLinks);
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
                    Object decoratedInput = IrBslHoverHtml.withHtml(baseInput, modified);
                    IrBslHoverHtml.applyHtmlToControl(control, modified);
                    if (control.hasDelayedInputChangeListener())
                        control.notifyDelayedInputChange(decoratedInput);
                    return;
                }
                if (control.hasDelayedInputChangeListener())
                    control.notifyDelayedInputChange(baseInput);
            });
        }

        /** {@link BslStructureInsertCommentTypes#refreshQueryTypesForAssist} для места подсказки; в UI-потоке — нет. */
        private static void refreshQueryTypesBeforeHover(ITextViewer textViewer, IRegion hoverRegion)
        {
            if (hoverRegion == null || org.eclipse.swt.widgets.Display.getCurrent() != null
                || !(textViewer != null && textViewer.getDocument() instanceof IXtextDocument document))
                return;
            try
            {
                int offset = hoverRegion.getOffset();
                document.readOnly(resource ->
                {
                    BslStructureInsertCommentTypes.refreshQueryTypesForAssist(resource, offset, status -> {});
                    // Сеансы менеджера временных таблиц метода под курсором (issue 889)
                    BslTempTableSessions.refreshDocument(document, resource, offset);
                    return null;
                });
            }
            catch (RuntimeException ignored)
            {
                // Досчёт не удался — подсказка покажет то, что есть в модели.
            }
        }

        private void resolveMemberTargets(IRegion region)
        {
            lastFunctionTarget = null;
            lastMethodDescriptionLinks = Map.of();
            if (!(editor.getDocument() instanceof IXtextDocument document))
                return;
            document.readOnly((IUnitOfWork<Void, XtextResource>) resource -> {
                EObject object = resource == null ? null
                    : new EObjectAtOffsetHelper().resolveContainedElementAt(resource, region.getOffset());
                com._1c.g5.v8.dt.bsl.model.FeatureAccess access = null;
                for (EObject current = object; current != null && access == null; current = current.eContainer())
                {
                    if (!(current instanceof com._1c.g5.v8.dt.bsl.model.FeatureAccess candidate))
                        continue;
                    for (INode node : NodeModelUtils.findNodesForFeature(candidate,
                        BslPackage.Literals.FEATURE_ACCESS__NAME))
                        if (region.getOffset() < node.getEndOffset()
                            && region.getOffset() + Math.max(1, region.getLength()) > node.getOffset())
                        {
                            access = candidate;
                            break;
                        }
                }
                org.eclipse.xtext.resource.IResourceServiceProvider provider = resource == null ? null
                    : org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                        .getResourceServiceProvider(resource.getURI());
                if (access == null)
                    return null;
                com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer computer = provider == null
                    ? null : provider.get(com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer.class);
                List<com._1c.g5.v8.dt.bsl.model.FeatureEntry> entries =
                    access instanceof StaticFeatureAccess staticAccess ? staticAccess.getFeatureEntries()
                    : computer == null ? List.of()
                        : com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer
                            .resolveDynamicFeatureAccess(access, computer);
                for (com._1c.g5.v8.dt.bsl.model.FeatureEntry entry : entries)
                {
                    EObject feature = entry.getFeature();
                    if (lastMethodDescriptionLinks.isEmpty())
                        lastMethodDescriptionLinks = ParamHintHtmlModifier.methodDescriptionLinks(feature,
                            document.getResourceURI(), MethodDefinitionLink::registerFormHandler);
                    if (lastFunctionTarget == null)
                        lastFunctionTarget = FunctionReturnTypes.targetOf(feature, 0);
                }
                return null;
            });
        }

        /**
         * Наведение на имя метода в модуле формы: ссылки на элементы формы (и саму форму),
         * чьи события подписаны на этот метод (issue 671). Клик — как двойной клик по колонке
         * обработчиков дерева элементов формы ({@link FormEditorHook#openFormEventHandler}).
         *
         * @return HTML-блок или {@code null}, если это не имя метода формы или подписок нет
         */
        private String resolveFormHandlersHtml(IRegion region)
        {
            if (!(editor.getDocument() instanceof IXtextDocument document))
                return null;
            try
            {
                // До блокировки документа: определение темы уходит в UI-поток.
                String color = formHandlersTextColor();
                return document.readOnly((IUnitOfWork<String, XtextResource>) resource -> {
                    if (resource == null)
                        return null;
                    EObject object = new EObjectAtOffsetHelper().resolveContainedElementAt(resource,
                        region.getOffset());
                    com._1c.g5.v8.dt.bsl.model.Method method = null;
                    for (EObject current = object; current != null && method == null; current = current.eContainer())
                        if (current instanceof com._1c.g5.v8.dt.bsl.model.Method candidate)
                            method = candidate;
                    if (method == null || method.getName() == null)
                        return null;
                    ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(),
                        region.getOffset());
                    if (leaf == null || !method.getName().equalsIgnoreCase(leaf.getText()))
                        return null;
                    com._1c.g5.v8.dt.bsl.model.Module module =
                        org.eclipse.emf.ecore.util.EcoreUtil.getRootContainer(method)
                            instanceof com._1c.g5.v8.dt.bsl.model.Module m ? m : null;
                    EObject owner = module == null ? null : module.getOwner();
                    // Владелец модуля формы — содержимое формы (form.model.Form), а не BasicForm.
                    if (!(owner instanceof com._1c.g5.v8.dt.form.model.Form form))
                        return null;
                    org.eclipse.emf.common.util.URI moduleUri = resource.getURI();
                    List<String> links = new ArrayList<>();
                    collectFormHandlerLinks(moduleUri, form, "Форма", form, method.getName(), links);
                    collectFormItemHandlerLinks(moduleUri, form.getItems(), method.getName(), links);
                    for (com._1c.g5.v8.dt.form.model.FormCommand command : form.getFormCommands())
                    {
                        if (!(command.getAction() instanceof com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer
                            action) || action.getHandler() == null
                            || !method.getName().equalsIgnoreCase(action.getHandler().getName()))
                            continue;
                        // Как строка «Найти ссылки»: команда формы и её поле «Действие» в «Свойствах».
                        String token = MethodDefinitionLink.registerFormHandler(() -> {
                            org.eclipse.ui.IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                            org.eclipse.ui.IWorkbenchPage page = window == null ? null : window.getActivePage();
                            ConfigSearchResultsHook.PropertyFieldFocus.scheduleExact(page, command,
                                com._1c.g5.v8.dt.form.model.FormPackage.Literals.FORM_COMMAND__ACTION);
                            FormEditorHook.openFormElement(page, command);
                        });
                        links.add("<a href=\"#\" onclick=\"comfortOpenMethodDefinition('" + token
                            + "');return false;\">" + escapeHtml(command.getName() == null ? "" : command.getName())
                            + "</a> (Команда формы)");
                    }
                    if (links.isEmpty())
                        return null;
                    return "<div class=\"comfort-form-handlers\" style=\"color:" + color + ";\">Обработчик для: "
                        + String.join(", ", links) + "</div>";
                });
            }
            catch (Exception | LinkageError ex)
            {
                return null;
            }
        }

        /** Цвет строки «Обработчик для» в светлой теме; для тёмной пересчитывается. */
        private static final org.eclipse.swt.graphics.RGB FORM_HANDLERS_LIGHT_RGB =
            new org.eclipse.swt.graphics.RGB(0x50, 0x50, 0x00);

        /** Определяется один раз в UI-потоке (подсказка считается в фоновом). */
        private static volatile String formHandlersTextColor;

        /**
         * Цвет текста строки «Обработчик для»: свой оттенок (бирюзовый), а не приглушение —
         * строка должна выделяться среди штатного текста. Задан для светлой темы, для тёмной —
         * {@link ThemeAwareColors#toEffectiveRgb}.
         */
        private static String formHandlersTextColor()
        {
            String color = formHandlersTextColor;
            if (color == null)
            {
                org.eclipse.swt.graphics.RGB[] result = new org.eclipse.swt.graphics.RGB[1];
                Runnable probe = () -> result[0] = ThemeAwareColors.toEffectiveRgb(FORM_HANDLERS_LIGHT_RGB);
                if (Display.getCurrent() != null)
                    probe.run();
                else
                    Display.getDefault().syncExec(probe);
                org.eclipse.swt.graphics.RGB rgb = result[0];
                formHandlersTextColor = color = String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
                    Integer.valueOf(rgb.red), Integer.valueOf(rgb.green), Integer.valueOf(rgb.blue));
            }
            return color;
        }

        /**
         * Форма объекта метаданных по файлу её модуля. Содержимое формы ({@code form.model.Form})
         * обратной ссылки на неё не имеет. Полное имя файла модуля длиннее имени формы (хвост —
         * содержимое формы и модуль), а спуск по парам «тип.имя» на лишней паре даёт {@code null},
         * поэтому укорачиваем по паре, пока не получим саму форму. Звать вне блокировки документа.
         */
        private static com._1c.g5.v8.dt.metadata.mdclass.BasicForm resolveBasicForm(
            org.eclipse.emf.common.util.URI moduleUri)
        {
            if (moduleUri == null || !moduleUri.isPlatformResource())
                return null;
            org.eclipse.core.resources.IFile file =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getFile(
                    new org.eclipse.core.runtime.Path(moduleUri.toPlatformString(true)));
            com._1c.g5.v8.dt.core.platform.IV8Project v8project =
                Global.getServiceByClass(com._1c.g5.v8.dt.core.platform.IV8ProjectManager.class)
                    instanceof com._1c.g5.v8.dt.core.platform.IV8ProjectManager pm
                        ? pm.getProject(file.getProject()) : null;
            String fullName = v8project == null ? null : GoToDefinition.fullNameFromFile(file);
            String[] parts = fullName == null ? new String[0] : fullName.split("\\.", -1);
            EObject resolved = null;
            for (int count = parts.length - parts.length % 2; count >= 2
                && !(resolved instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicForm); count -= 2)
                resolved = GoToDefinition.resolveEObjectByQualifiedName(
                    String.join(".", java.util.Arrays.copyOf(parts, count)), v8project);
            return resolved instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicForm basicForm ? basicForm : null;
        }

        private static void collectFormItemHandlerLinks(org.eclipse.emf.common.util.URI moduleUri,
            List<com._1c.g5.v8.dt.form.model.FormItem> items, String methodName, List<String> links)
        {
            if (items == null)
                return;
            for (com._1c.g5.v8.dt.form.model.FormItem item : items)
            {
                collectFormHandlerLinks(moduleUri, item, item.getName(), item, methodName, links);
                if (item instanceof com._1c.g5.v8.dt.form.model.FormItemContainer nested)
                    collectFormItemHandlerLinks(moduleUri, nested.getItems(), methodName, links);
            }
        }

        private static void collectFormHandlerLinks(org.eclipse.emf.common.util.URI moduleUri,
            EObject target, String ownerName, Object owner, String methodName, List<String> links)
        {
            List<com._1c.g5.v8.dt.form.model.EventHandler> handlers = new ArrayList<>();
            if (owner instanceof com._1c.g5.v8.dt.form.model.EventHandlerContainer container)
                handlers.addAll(container.getHandlers());
            // Обработчики элемента лежат и в extInfo (как в BslReferenceSearchTableHook.matchEventHandlers).
            if (Global.invoke(owner, "getExtInfo") instanceof com._1c.g5.v8.dt.form.model.EventHandlerContainer extInfo)
                handlers.addAll(extInfo.getHandlers());
            for (com._1c.g5.v8.dt.form.model.EventHandler handler : handlers)
            {
                if (handler.getName() == null || !methodName.equalsIgnoreCase(handler.getName()))
                    continue;
                com._1c.g5.v8.dt.mcore.Event event = handler.getEvent();
                String eventName = event == null ? ""
                    : event.getNameRu() != null && !event.getNameRu().isEmpty() ? event.getNameRu()
                    : event.getName() != null ? event.getName() : "";
                String token = MethodDefinitionLink.registerFormHandler(() -> {
                    org.eclipse.ui.IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null
                        ? null : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    FormEditorHook.openFormEventHandler(page, resolveBasicForm(moduleUri), target, handler);
                });
                links.add("<a href=\"#\" onclick=\"comfortOpenMethodDefinition('" + token + "');return false;\">"
                    + escapeHtml(ownerName == null ? "" : ownerName) + "</a>"
                    + (eventName.isEmpty() ? "" : " (" + escapeHtml(eventName) + ")"));
            }
        }

        private static String escapeHtml(String text)
        {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
            Object decoratedInput = IrBslHoverHtml.withHtml(control.getInput(), mergedHtml);
            if (!IrBslHoverHtml.applyHtmlToControl(control, mergedHtml))
                return false;
            if (control.hasDelayedInputChangeListener())
                control.notifyDelayedInputChange(decoratedInput);
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
                        IrBslHoverHtml.withHtml(control.getInput(), merged);
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
