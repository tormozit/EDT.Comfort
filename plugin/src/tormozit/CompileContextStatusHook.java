package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.StatusLineLayoutData;
import org.eclipse.jface.action.StatusLineManager;
import org.eclipse.jface.action.SubContributionManager;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.IXtextModelListener;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.IfPreprocessor;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.PragmaTarget;
import com._1c.g5.v8.dt.bsl.model.PreprocessorConditional;
import com._1c.g5.v8.dt.bsl.model.PreprocessorExpression;
import com._1c.g5.v8.dt.bsl.model.PreprocessorIfConditional;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Индикатор условий компиляции в строке состояния (issue #683): для позиции каретки в модуле
 * показывает цепочку вложенных условий препроцессора и директиву компиляции метода —
 * {@code (Не ТонкийКлиент И Не ВебКлиент)/Клиент/&НаКлиенте/ТолстыйКлиентУправляемоеПриложение}.
 * При наведении открывается окно с теми же условиями в виде ссылок: щелчок переходит к строке
 * условия.
 *
 * <p>Поле одно на окно и живёт в общей строке состояния окна, а не в полях редактора: его
 * видимость переключается при смене активного редактора. Пока активен редактор модуля, поле
 * видно всегда (пустое, если условий нет) — иначе соседние поля прыгали бы при движении каретки.
 *
 * <p>Ветка {@code #Если} определяется по вложенности в модели: операторы после
 * {@code #КонецЕсли} лежат в {@code itemAfter} той же директивы, а не в её ветке, поэтому
 * условием считается только предок-{@link PreprocessorConditional}. Для пробелов и комментариев
 * берётся предыдущая значащая лексема: скрытые листья Xtext относит к следующей лексеме, и пустая
 * строка в конце ветки иначе попадала бы в следующую ветку.
 */
public final class CompileContextStatusHook implements IStartup
{
    private static final String ITEM_ID = "tormozit.comfort.compileContext"; //$NON-NLS-1$
    private static final String SEPARATOR = "/"; //$NON-NLS-1$
    /** Коалесцирует серию движений каретки/правок в один расчёт. */
    private static final int COMPUTE_DELAY_MS = 200;
    /** Задержка страховочного расчёта, пока модель не обновлена после правки. */
    private static final int STALE_COMPUTE_DELAY_MS = 2000;
    private static final int POPUP_WATCH_MS = 200;
    /** Страница модуля granular-редактора создаётся асинхронно — ограниченное число повторов. */
    private static final int MAX_BIND_ATTEMPTS = 5;
    private static final int RETRY_DELAY_MS = 300;

    /** Директивы компиляции (без «&», в нижнем регистре); прочие аннотации в индикатор не идут. */
    private static final Set<String> COMPILE_DIRECTIVES = Set.of(
        "наклиенте", "насервере", "насерверебезконтекста", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "наклиентенасерверебезконтекста", "наклиентенасервере", //$NON-NLS-1$ //$NON-NLS-2$
        "atclient", "atserver", "atservernocontext", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "atclientatservernocontext", "atclientatserver"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Map<IWorkbenchWindow, WindowState> states = new HashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(CompileContextStatusHook::install);
    }

    private static void install()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench() == null)
            return;

        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override
            public void windowClosed(IWorkbenchWindow w)
            {
                WindowState state = states.remove(w);
                if (state != null)
                    state.unbind();
            }
        });

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);

        Activator.getDefault().getPreferenceStore().addPropertyChangeListener(event -> {
            if (ComfortSettings.PREF_COMPILE_CONTEXT_STATUS_WIDTH.equals(event.getProperty()))
                Display.getDefault().asyncExec(() -> {
                    for (WindowState state : new ArrayList<>(states.values()))
                        state.rebind(0);
                });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (states.containsKey(window))
            return;
        WindowState state = new WindowState(window);
        states.put(window, state);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partActivated(IWorkbenchPartReference ref)     { state.scheduleRebind(); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref)  { state.scheduleRebind(); }
            @Override public void partOpened(IWorkbenchPartReference ref)        { state.scheduleRebind(); }
            @Override public void partClosed(IWorkbenchPartReference ref)        { state.scheduleRebind(); }
            @Override public void partDeactivated(IWorkbenchPartReference ref)   {}
            @Override public void partHidden(IWorkbenchPartReference ref)        {}
            @Override public void partVisible(IWorkbenchPartReference ref)       {}
            @Override public void partInputChanged(IWorkbenchPartReference ref)  { state.scheduleRebind(); }
        });
        // Редактор, восстановленный при старте, активируется до установки слушателя.
        state.scheduleRebind();
    }

    // =========================================================================
    // Расчёт цепочки условий
    // =========================================================================

    /**
     * Одно звено индикатора: текст для поля (составное условие — в скобках, иначе звенья через
     * «/» не читаются), текст для окна со ссылками (со спецсимволом «#»/«&», без внешних скобок —
     * там каждое условие на своей строке) и позиция в модуле, к которой ведёт ссылка.
     */
    private record Segment(String text, String linkText, int offset) {}

    /** Вызывается из фонового потока, без блокировки документа. */
    private static List<Segment> computeSegments(XtextResource resource, int offset)
    {
        if (resource == null || resource.getParseResult() == null)
            return Collections.emptyList();
        ICompositeNode root = resource.getParseResult().getRootNode();
        if (root == null)
            return Collections.emptyList();
        ILeafNode leaf = significantLeafAt(root, offset);
        if (leaf == null)
            return Collections.emptyList();

        List<Segment> result = new ArrayList<>();
        for (EObject object = NodeModelUtils.findActualSemanticObjectFor(leaf); object != null;
            object = object.eContainer())
        {
            Segment segment = null;
            if (object instanceof PreprocessorConditional conditional
                && conditional.eContainer() instanceof IfPreprocessor preprocessor)
                segment = conditionSegment(preprocessor, conditional);
            else if (object instanceof PragmaTarget target)
                segment = directiveSegment(target, offset);
            if (segment != null)
                result.add(segment);
        }
        Collections.reverse(result); // обход шёл изнутри наружу
        return result;
    }

    /**
     * {@code BslXtextDocument.readOnlyDataModelWithoutSync} — через рефлексию: сам класс
     * наследует {@code HandlyXtextDocument} из бандла, которого нет в зависимостях плагина, и
     * прямая ссылка на него не компилируется. Документ другого типа читается обычным
     * {@code readOnly}.
     */
    @SuppressWarnings("unchecked")
    private static List<Segment> readWithoutSync(IXtextDocument document,
        IUnitOfWork<List<Segment>, XtextResource> work) throws Exception
    {
        java.lang.reflect.Method method;
        try
        {
            method = document.getClass().getMethod("readOnlyDataModelWithoutSync", IUnitOfWork.class); //$NON-NLS-1$
        }
        catch (NoSuchMethodException e)
        {
            return document.readOnly(work);
        }
        Object result = method.invoke(document, work);
        return result instanceof List<?> ? (List<Segment>)result : Collections.emptyList();
    }

    /** Значащая лексема в позиции; для пробела/комментария — ближайшая значащая слева. */
    private static ILeafNode significantLeafAt(ICompositeNode root, int offset)
    {
        int probe = Math.min(offset, root.getTotalEndOffset() - 1);
        while (probe >= 0)
        {
            ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(root, probe);
            if (leaf == null)
                return null;
            if (!leaf.isHidden())
                return leaf;
            probe = Math.min(leaf.getTotalOffset(), probe) - 1;
        }
        return null;
    }

    /**
     * Условие, при котором компилируется ветка: своё условие плюс отрицания условий всех
     * предыдущих веток той же директивы ({@code #ИначеЕсли}, {@code #Иначе}).
     */
    private static Segment conditionSegment(IfPreprocessor preprocessor, PreprocessorConditional branch)
    {
        List<PreprocessorIfConditional> conditionals = new ArrayList<>();
        if (preprocessor.getIfPart() != null)
            conditionals.add(preprocessor.getIfPart());
        conditionals.addAll(preprocessor.getElsIfParts());

        int index = branch == preprocessor.getElseElement() ? conditionals.size() : conditionals.indexOf(branch);
        if (index < 0)
            return null;

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < index; i++)
        {
            String previous = predicateText(conditionals.get(i));
            if (previous != null)
                parts.add("Не " + parenthesize(previous)); //$NON-NLS-1$
        }
        if (index < conditionals.size())
        {
            String own = predicateText(conditionals.get(index));
            if (own == null)
                return null;
            parts.add(parts.isEmpty() ? own : parenthesize(own));
        }
        if (parts.isEmpty())
            return null;

        String plain = String.join(" И ", parts); //$NON-NLS-1$

        // Узел первой ветки начинается с условия, а не с «#Если» — берём узел всей директивы.
        ICompositeNode node = NodeModelUtils.getNode(index == 0 ? preprocessor : branch);
        return node == null ? null : new Segment(parenthesize(plain), "# " + plain, node.getOffset()); //$NON-NLS-1$
    }

    private static String predicateText(PreprocessorIfConditional conditional)
    {
        PreprocessorExpression predicate = conditional.getPredicate();
        ICompositeNode node = predicate == null ? null : NodeModelUtils.getNode(predicate);
        if (node == null)
            return null;
        String text = NodeModelUtils.getTokenText(node).replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
        return text.isEmpty() ? null : text;
    }

    /** Составное условие — в скобки, чтобы звенья индикатора читались однозначно. */
    private static String parenthesize(String text)
    {
        return text.indexOf(' ') < 0 ? text : "(" + text + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Segment directiveSegment(PragmaTarget target, int offset)
    {
        if (target instanceof Method)
        {
            // Пробел между методами приводит к последней лексеме предыдущего метода.
            ICompositeNode methodNode = NodeModelUtils.getNode(target);
            if (methodNode == null || offset < methodNode.getOffset() || offset > methodNode.getEndOffset())
                return null;
        }
        for (Pragma pragma : target.getPragmas())
        {
            String symbol = pragma.getSymbol();
            if (symbol == null || !COMPILE_DIRECTIVES.contains(symbol.toLowerCase(Locale.ROOT)))
                continue;
            ICompositeNode node = NodeModelUtils.getNode(pragma);
            if (node != null)
                return new Segment("&" + symbol, "&" + symbol, node.getOffset()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    // =========================================================================
    // Привязка к активному редактору окна
    // =========================================================================

    private static final class WindowState
    {
        final IWorkbenchWindow window;
        final StatusItem item;
        IStatusLineManager manager;

        DtGranularEditor<?> granular;
        final IPageChangedListener pageListener = event -> scheduleRebind();

        BslXtextEditor editor;
        IEditorPart outerEditor;
        XtextSourceViewer viewer;
        StyledText widget;
        IXtextDocument document;
        final CaretListener caretListener;
        final IXtextModelListener modelListener;
        final IDocumentListener documentListener;
        /**
         * Документ изменён, а Xtext модель ещё не обновил: позиции дерева разбора не совпадают с
         * текстом, считать бессмысленно. Расчёт ждёт {@code modelChanged}. Только UI-поток.
         */
        boolean modelStale;

        final Job job;
        volatile IXtextDocument jobDocument;
        volatile int jobOffset;
        /** Меняется только в UI-потоке; устаревший результат расчёта отбрасывается. */
        volatile int generation;

        WindowState(IWorkbenchWindow window)
        {
            this.window = window;
            this.item = new StatusItem(this::navigate);
            this.caretListener = event -> scheduleCompute(
                SmartContentAssistProcessor.widgetToModelOffset(viewer, event.caretOffset));
            this.modelListener = resource -> {
                Runnable refresh = () -> {
                    modelStale = false;
                    scheduleComputeAtCaret();
                };
                // Xtext зовёт слушателей модели в UI-потоке; asyncExec — страховка на обратный случай.
                if (Display.getCurrent() != null)
                    refresh.run();
                else
                    PlatformUI.getWorkbench().getDisplay().asyncExec(refresh);
            };
            this.documentListener = new IDocumentListener()
            {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event)
                {
                    modelStale = true;
                    job.cancel();
                }

                @Override
                public void documentChanged(DocumentEvent event)
                {
                    // всё сделано до изменения — каретка сдвигается уже при устаревшей модели
                }
            };
            this.job = new Job("Comfort: условия компиляции позиции модуля") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    IXtextDocument doc = jobDocument;
                    int offset = jobOffset;
                    int gen = generation;
                    if (doc == null)
                        return Status.OK_STATUS;
                    List<Segment> result;
                    try
                    {
                        // Тот же способ чтения, что у штатного поля с именем метода: без
                        // синхронизации модели с текстом, то есть никогда не запускает разбор
                        // модуля. Блокировки при этом нет — если разбор идёт прямо сейчас, расчёт
                        // может упасть или дать неточный результат; следующий его исправит.
                        result = readWithoutSync(doc, resource -> computeSegments(resource, offset));
                    }
                    catch (Exception e)
                    {
                        // чтение без блокировки попало на разбор модуля — исправит следующий расчёт
                        return Status.OK_STATUS;
                    }
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    Display display = PlatformUI.getWorkbench().getDisplay();
                    if (display != null && !display.isDisposed())
                        display.asyncExec(() -> {
                            if (gen == generation && doc == document)
                                item.setSegments(result);
                        });
                    return Status.OK_STATUS;
                }
            };
            this.job.setSystem(true);
            this.job.setPriority(Job.DECORATE);
        }

        void scheduleRebind()
        {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> rebind(0));
        }

        void rebind(int attempt)
        {
            Shell shell = window.getShell();
            if (shell == null || shell.isDisposed())
                return;

            IWorkbenchPage page = window.getActivePage();
            IEditorPart active = page == null ? null : page.getActiveEditor();
            trackGranular(active instanceof DtGranularEditor<?> g ? g : null);
            ensureItem(active);
            if (manager == null)
                return;

            int width = ComfortSettings.getCompileContextStatusWidth();
            // Фокус в другой панели (Git, навигатор...) — поле скрыто, хотя редактор остаётся активным.
            boolean editorFocused = page != null && active != null && page.getActivePart() == active;
            BslXtextEditor bsl = width > 0 && editorFocused ? GetRef.getActiveBslEditor(active) : null;
            XtextSourceViewer newViewer = null;
            StyledText newWidget = null;
            IXtextDocument newDocument = null;
            if (bsl != null)
            {
                ISourceViewer sourceViewer = bsl.getInternalSourceViewer();
                if (sourceViewer instanceof XtextSourceViewer xtextViewer)
                {
                    newViewer = xtextViewer;
                    newWidget = xtextViewer.getTextWidget();
                    newDocument = xtextViewer.getXtextDocument();
                }
                if (newWidget == null || newWidget.isDisposed() || newDocument == null)
                    bsl = null;
            }
            if (bsl == null && width > 0 && editorFocused && active instanceof DtGranularEditor<?>
                && attempt < MAX_BIND_ATTEMPTS)
                shell.getDisplay().timerExec(RETRY_DELAY_MS, () -> rebind(attempt + 1));

            if (bsl == null)
                unbind();
            else if (newWidget != widget || newDocument != document)
            {
                unbind();
                bind(bsl, active, newViewer, newWidget, newDocument);
            }
            updateItem(bsl != null, width);
        }

        private void trackGranular(DtGranularEditor<?> current)
        {
            if (current == granular)
                return;
            if (granular != null)
            {
                try
                {
                    granular.removePageChangedListener(pageListener);
                }
                catch (Exception ignored)
                {
                    // редактор уже закрыт
                }
            }
            granular = current;
            if (granular != null)
                granular.addPageChangedListener(pageListener);
        }

        private void ensureItem(IEditorPart active)
        {
            if (manager != null)
                return;
            Object global = Global.call(window, "getStatusLineManager"); //$NON-NLS-1$
            if (global instanceof IStatusLineManager statusLine)
                manager = statusLine;
            else if (active != null && active.getEditorSite() != null
                && active.getEditorSite().getActionBars()
                    .getStatusLineManager() instanceof SubContributionManager sub
                && sub.getParent() instanceof IStatusLineManager statusLine)
                manager = statusLine; // строка состояния редактора — обёртка над общей
            if (manager == null)
                return;

            item.setVisible(false);
            try
            {
                manager.appendToGroup(StatusLineManager.BEGIN_GROUP, item);
            }
            catch (IllegalArgumentException e)
            {
                manager.add(item);
            }
        }

        private void updateItem(boolean visible, int width)
        {
            boolean changed = item.isVisible() != visible;
            if (visible && item.widthChars != width)
            {
                item.widthChars = width;
                changed = true;
            }
            if (!changed)
                return;
            item.setVisible(visible);
            manager.markDirty();
            manager.update(false);
        }

        private void bind(BslXtextEditor bsl, IEditorPart outer, XtextSourceViewer newViewer, StyledText newWidget,
            IXtextDocument newDocument)
        {
            editor = bsl;
            outerEditor = outer;
            viewer = newViewer;
            widget = newWidget;
            document = newDocument;
            widget.addCaretListener(caretListener);
            widget.addDisposeListener(event -> scheduleRebind());
            document.addModelListener(modelListener);
            document.addDocumentListener(documentListener);
            modelStale = false;
            scheduleComputeAtCaret();
        }

        void unbind()
        {
            if (widget == null)
                return;
            if (!widget.isDisposed())
                widget.removeCaretListener(caretListener);
            try
            {
                document.removeModelListener(modelListener);
                document.removeDocumentListener(documentListener);
            }
            catch (Exception ignored)
            {
                // документ уже закрыт
            }
            job.cancel();
            generation++;
            jobDocument = null;
            editor = null;
            outerEditor = null;
            viewer = null;
            widget = null;
            document = null;
            item.setSegments(Collections.emptyList());
        }

        private void scheduleComputeAtCaret()
        {
            if (widget == null || widget.isDisposed())
                return;
            scheduleCompute(SmartContentAssistProcessor.resolveWidgetCaret(viewer));
        }

        private void scheduleCompute(int modelOffset)
        {
            if (document == null || modelOffset < 0)
                return;
            generation++;
            jobDocument = document;
            jobOffset = modelOffset;
            job.cancel();
            // Модель устарела — основной расчёт придёт по modelChanged; этот — страховка на случай,
            // если Xtext события не пришлёт, поэтому с большой задержкой.
            job.schedule(modelStale ? STALE_COMPUTE_DELAY_MS : COMPUTE_DELAY_MS);
        }

        private void navigate(Segment segment)
        {
            if (editor == null || outerEditor == null)
                return;
            IWorkbenchPage page = window.getActivePage();
            if (page != null)
                page.activate(outerEditor);
            editor.selectAndReveal(segment.offset(), 0);
        }
    }

    // =========================================================================
    // Поле строки состояния
    // =========================================================================

    private static final class StatusItem extends ContributionItem
    {
        private final Consumer<Segment> navigator;
        private List<Segment> segments = Collections.emptyList();
        private CLabel label;
        private Shell popup;
        int widthChars = ComfortSettings.DEFAULT_COMPILE_CONTEXT_STATUS_WIDTH;

        StatusItem(Consumer<Segment> navigator)
        {
            super(ITEM_ID);
            this.navigator = navigator;
        }

        /** Вызывается заново при каждой перестройке строки состояния — состояние хранит сам элемент. */
        @Override
        public void fill(Composite parent)
        {
            Label separator = new Label(parent, SWT.SEPARATOR);
            label = new CLabel(parent, SWT.SHADOW_NONE);

            GC gc = new GC(parent);
            gc.setFont(parent.getFont());
            FontMetrics metrics = gc.getFontMetrics();
            int width = Dialog.convertWidthInCharsToPixels(metrics, widthChars);
            int height = metrics.getHeight();
            gc.dispose();

            StatusLineLayoutData labelData = new StatusLineLayoutData();
            labelData.widthHint = width;
            label.setLayoutData(labelData);
            StatusLineLayoutData separatorData = new StatusLineLayoutData();
            separatorData.heightHint = height;
            separator.setLayoutData(separatorData);

            // CLabel при обрезке текста сам ставит нативную подсказку с полным текстом, если
            // своя подсказка не задана (null). Пустая строка — «задана», и подсказки нет:
            // полный текст показывает наше окно со ссылками.
            label.setToolTipText(""); //$NON-NLS-1$
            label.setText(labelText());
            label.addListener(SWT.MouseHover, event -> showPopup());
            label.addListener(SWT.MouseDown, event -> showPopup());
            label.addListener(SWT.Dispose, event -> closePopup());
        }

        void setSegments(List<Segment> newSegments)
        {
            if (segments.equals(newSegments))
                return;
            segments = newSegments;
            closePopup();
            if (label != null && !label.isDisposed())
                label.setText(labelText());
        }

        private String labelText()
        {
            if (segments.isEmpty())
                return ""; //$NON-NLS-1$
            // Счётчик спереди: поле узкое, и число звеньев видно даже при обрезанном тексте.
            StringBuilder sb = new StringBuilder().append(segments.size()).append(':');
            boolean first = true;
            for (Segment segment : segments)
            {
                if (!first)
                    sb.append(SEPARATOR);
                first = false;
                sb.append(escapeMnemonic(segment.text()));
            }
            return sb.toString();
        }

        /** «&» в {@link CLabel} и {@link Link} — признак мнемоники, а у нас это «&НаКлиенте». */
        private static String escapeMnemonic(String text)
        {
            return text.replace("&", "&&"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Подсказка со ссылками. Нативная подсказка ссылок не умеет, поэтому своё окно без
         * фокуса: оно стоит вплотную над полем и закрывается, когда указатель ушёл и с поля, и
         * с окна.
         */
        private void showPopup()
        {
            if (segments.isEmpty() || label == null || label.isDisposed() || (popup != null && !popup.isDisposed()))
                return;

            Shell shell = new Shell(label.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 6;
            layout.marginHeight = 4;
            layout.verticalSpacing = 2;
            shell.setLayout(layout);

            Label title = new Label(shell, SWT.NONE);
            title.setText("Условия компиляции в позиции каретки" + Global.pluginSignForTooltip() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            for (Segment segment : segments)
            {
                Link link = new Link(shell, SWT.NONE);
                link.setText("<a>" + escapeMnemonic(segment.linkText()) + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
                link.addListener(SWT.Selection, event -> {
                    closePopup();
                    navigator.accept(segment);
                });
            }

            shell.pack();
            Point size = shell.getSize();
            Point origin = label.toDisplay(0, 0);
            Rectangle area = label.getMonitor().getClientArea();
            int x = Math.max(area.x, Math.min(origin.x, area.x + area.width - size.x));
            int y = origin.y - size.y;
            if (y < area.y)
                y = origin.y + label.getSize().y;
            shell.setLocation(x, y);
            shell.setVisible(true);
            popup = shell;
            label.getDisplay().timerExec(POPUP_WATCH_MS, this::watchPopup);
        }

        private void watchPopup()
        {
            if (popup == null || popup.isDisposed())
                return;
            if (label == null || label.isDisposed())
            {
                closePopup();
                return;
            }
            Display display = popup.getDisplay();
            Point cursor = display.getCursorLocation();
            Point labelOrigin = label.toDisplay(0, 0);
            Point labelSize = label.getSize();
            Rectangle labelArea = new Rectangle(labelOrigin.x, labelOrigin.y, labelSize.x, labelSize.y);
            if (popup.getBounds().contains(cursor) || labelArea.contains(cursor))
                display.timerExec(POPUP_WATCH_MS, this::watchPopup);
            else
                closePopup();
        }

        private void closePopup()
        {
            if (popup != null && !popup.isDisposed())
                popup.dispose();
            popup = null;
        }
    }
}
