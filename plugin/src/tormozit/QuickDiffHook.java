package tormozit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.information.IInformationProviderExtension2;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationHover;
import org.eclipse.jface.text.source.IAnnotationHoverExtension;
import org.eclipse.jface.text.source.IAnnotationHoverExtension2;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.IChangeRulerColumn;
import org.eclipse.jface.text.source.ILineDiffer;
import org.eclipse.jface.text.source.ILineDifferExtension;
import org.eclipse.jface.text.source.ILineDifferExtension2;
import org.eclipse.jface.text.source.ILineDiffInfo;
import org.eclipse.jface.text.source.ILineRange;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.IVerticalRulerColumn;
import org.eclipse.jface.text.source.IVerticalRulerInfoExtension;
import org.eclipse.jface.text.source.IVerticalRulerListener;
import org.eclipse.jface.text.source.LineChangeHover;
import org.eclipse.jface.text.source.LineNumberChangeRulerColumn;
import org.eclipse.jface.text.source.LineRange;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Quick Diff («Выделение изменений»): скрывает пробельные-only маркеры на полосе
 * номеров и линейке обзора; тултип маркера — интерактивный, снизу кнопки
 * «Сравнить» и «Откатить».
 *
 * <p>На полосе номеров {@code DiffPainter} (и в режиме «Отображать информацию
 * о ревизии» — {@code RevisionPainter} через те же хунки {@link ILineDiffer})
 * получает обёртку и не рисует пробельные добавления/изменения/удаления. На
 * линейке обзора аннотации Quick Diff с той же пробельной разницей не отдаются
 * в отрисовку (на Windows линейка рисует через {@code new GC}, минуя
 * {@code PaintListener}). Содержательные маркеры не трогаем.
 *
 * <p>Тултип Quick Diff — обёртка {@code DiffPainter.fHover} ({@link LineChangeHover}):
 * исходный текст хунка (добавленные — текущий текст, изменённые и удалённые —
 * исходный; фон текста не красится; в начале строки — ячейка цветом маркера
 * Quick Diff, удаление ослабляется), {@code canHandleMouseCursor}, кнопки в нижней
 * панели ({@link DefaultInformationControl} с {@link ToolBarManager}).
 * «Сравнить» открывает двухпанельное сравнение: в Git — как двойной клик
 * в панели «Индексирование Git» (рабочая копия с индексом), иначе локальная
 * история / референс Quick Diff. «Откатить» — {@link ILineDiffer#revertBlock}.
 * В режиме «Отображать информацию о ревизии» штатный тултип ревизии
 * ({@code RevisionPainter}) не подменяется: обёртка срабатывает только на
 * изменённых строках, где ревизионной подсказки нет.
 */
public final class QuickDiffHook implements IStartup
{
    private static final String LINE_NUMBER_KEY = "tormozit.quickDiffWs.lineNumber"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;
    private static final String QD_DELETION = "org.eclipse.ui.workbench.texteditor.quickdiffDeletion"; //$NON-NLS-1$
    private static final String QD_CHANGE = "org.eclipse.ui.workbench.texteditor.quickdiffChange"; //$NON-NLS-1$
    private static final String QD_ADDITION = "org.eclipse.ui.workbench.texteditor.quickdiffAddition"; //$NON-NLS-1$

    private static final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Paint, QuickDiffHook::beforeLineNumberPaint);
        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
        });
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            IEditorPart active = page.getActiveEditor();
            if (active != null)
                hookEditorIfNeeded(active);
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null && ed != active)
                    hookEditorIfNeeded(ed);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partVisible(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void hookFromPartRef(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference editorRef))
            return;
        IEditorPart ed = editorRef.getEditor(false);
        if (ed != null)
            hookEditorIfNeeded(ed);
    }

    private static void hookEditorIfNeeded(IEditorPart editor)
    {
        ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
        if (textEditor != null)
            tryAttach(textEditor, 0);
        if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private static void hookGranularEditor(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
        {
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof ITextEditor textEditor)
                tryAttach(textEditor, 0);
        }
        if (!hookedGranularEditors.add(editor))
            return;
        editor.addPageChangedListener(new IPageChangedListener()
        {
            @Override
            public void pageChanged(PageChangedEvent event)
            {
                Object selected = event.getSelectedPage();
                if (selected instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
                {
                    IEditorPart embedded = xtextPage.getEmbeddedEditor();
                    if (embedded instanceof ITextEditor textEditor)
                        tryAttach(textEditor, 0);
                }
            }
        });
    }

    private static void tryAttach(ITextEditor editor, int attempt)
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        StyledText text = viewer != null ? viewer.getTextWidget() : null;
        LineNumberChangeRulerColumn column = findLineNumberColumn(viewer);
        IOverviewRuler overview = overviewRulerOf(viewer);
        Control lineControl = column != null ? column.getControl() : null;
        Control overviewControl = overview != null ? overview.getControl() : null;
        boolean textReady = text != null && !text.isDisposed();
        boolean lineReady = lineControl != null && !lineControl.isDisposed();
        boolean overviewReady = overviewControl != null && !overviewControl.isDisposed();
        if (!textReady || !lineReady && !overviewReady)
        {
            if (attempt < MAX_ATTACH_ATTEMPTS && display != null && !display.isDisposed())
            {
                display.asyncExec(() -> tryAttach(editor, attempt + 1));
            }
            return;
        }
        Session session = new Session(editor, viewer, column, overview);
        if (lineReady)
        {
            lineControl.setData(LINE_NUMBER_KEY, session);
            suppressBackgroundErase(lineControl);
            session.ensureWrapped();
            session.installQuietRedraw();
        }
        if (overviewReady)
            session.ensureOverviewFiltered();
        if ((!lineReady || !overviewReady)
            && attempt < MAX_ATTACH_ATTEMPTS && display != null && !display.isDisposed())
        {
            display.asyncExec(() -> tryAttach(editor, attempt + 1));
        }
    }

    /**
     * Гасит закраску фона канвы номеров строк.
     *
     * <p>Причина мигания — не сама линейка. Декоратор EDT рассылает {@code labelProviderChanged}
     * ~5 раз в секунду, {@code DtGranularEditor} на это зовёт {@code firePropertyChange}, EDT
     * переписывает заголовок формы тем же текстом, а {@code TitleRegion.setText} безусловно делает
     * {@code layout()} и {@code redraw()} — перерисовывается вся область части, линейка в том числе
     * как сосед по composite. Источник — в EDT, отсюда не гасится.
     *
     * <p>Здесь снимается видимость: {@code LineNumberRulerColumn} создаёт канву как
     * {@code new Canvas(parent, SWT.NO_FOCUS)}, без {@code SWT.NO_BACKGROUND}, поэтому Windows на
     * каждую перерисовку сначала закрашивает полосу фоном, а готовый кадр выкладывается из
     * {@code fBuffer} только после. Промежуток между ними и виден как вспышка. Закраска не нужна:
     * {@code doubleBufferPaint} накрывает клиентскую область целиком. Стиль после создания штатно
     * не меняется, но SWT/Win32 читает {@code NO_BACKGROUND} не при создании окна, а в
     * {@code Composite.WM_ERASEBKGND}, поэтому бит достаточно выставить в поле {@code style}.
     */
    private static void suppressBackgroundErase(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        Object current = Global.getField(control, "style"); //$NON-NLS-1$
        if (!(current instanceof Integer style))
            return;
        if ((style.intValue() & SWT.NO_BACKGROUND) != 0)
            return;
        Global.setField(control, "style", //$NON-NLS-1$
            Integer.valueOf(style.intValue() | SWT.NO_BACKGROUND));
    }

    private static void beforeLineNumberPaint(Event e)
    {
        if (!(e.widget instanceof Control control) || control.isDisposed())
            return;
        if (control.getData(LINE_NUMBER_KEY) instanceof Session session)
        {
            session.ensureWrapped();
            session.installQuietRedraw();
        }
    }

    private static LineNumberChangeRulerColumn findLineNumberColumn(ISourceViewer viewer)
    {
        return asChangeColumn(findRulerColumnRaw(viewer));
    }

    /**
     * Колонка в {@link CompositeRuler}: в EDT это часто оболочка
     * {@code LineNumberColumn} с {@code fDelegate} =
     * {@link LineNumberChangeRulerColumn}.
     */
    private static Object findRulerColumnRaw(ISourceViewer viewer)
    {
        IVerticalRuler ruler = verticalRulerOf(viewer);
        if (!(ruler instanceof CompositeRuler composite))
            return null;
        Iterator<?> columns = composite.getDecoratorIterator();
        while (columns.hasNext())
        {
            Object next = columns.next();
            if (asChangeColumn(next) != null)
                return next;
        }
        return null;
    }

    /**
     * В EDT колонка номеров — {@code LineNumberColumn}: оболочка, внутри
     * {@code fDelegate} = {@link LineNumberChangeRulerColumn}.
     */
    private static LineNumberChangeRulerColumn asChangeColumn(Object column)
    {
        if (column instanceof LineNumberChangeRulerColumn changeColumn)
            return changeColumn;
        Object delegate = Global.getField(column, "fDelegate"); //$NON-NLS-1$
        return delegate instanceof LineNumberChangeRulerColumn changeColumn ? changeColumn : null;
    }

    private static IVerticalRuler verticalRulerOf(ISourceViewer viewer)
    {
        Object ruler = Global.invoke(viewer, "getVerticalRuler"); //$NON-NLS-1$
        if (ruler instanceof IVerticalRuler vertical)
            return vertical;
        ruler = Global.getField(viewer, "fVerticalRuler"); //$NON-NLS-1$
        return ruler instanceof IVerticalRuler vertical ? vertical : null;
    }

    private static IOverviewRuler overviewRulerOf(ISourceViewer viewer)
    {
        Object ruler = Global.invoke(viewer, "getOverviewRuler"); //$NON-NLS-1$
        if (ruler instanceof IOverviewRuler overview)
            return overview;
        ruler = Global.getField(viewer, "fOverviewRuler"); //$NON-NLS-1$
        return ruler instanceof IOverviewRuler overview ? overview : null;
    }

    static ILineDiffer lineDifferOf(ISourceViewer viewer)
    {
        if (viewer == null)
            return null;
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model instanceof IAnnotationModelExtension ext)
        {
            IAnnotationModel diff = ext.getAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID);
            if (diff instanceof ILineDiffer differ)
                return unwrap(differ);
        }
        return model instanceof ILineDiffer differ ? unwrap(differ) : null;
    }

    /**
     * Тот же фильтр пробелов, что у {@link DiffPainter} на полосе номеров. Штатный
     * {@link LineChangeHover} берёт сырой differ из annotation model — без обёртки
     * тултип раздувается по пробельным CHANGED, хотя маркеры уже скрыты.
     */
    static ILineDiffer filteredLineDifferOf(ISourceViewer viewer)
    {
        ILineDiffer raw = lineDifferOf(viewer);
        if (raw == null)
            return null;
        return new DifferWrapper(raw, viewer);
    }

    static ILineDiffer unwrap(ILineDiffer differ)
    {
        while (differ instanceof DifferWrapper wrapper)
            differ = wrapper.delegate;
        return differ;
    }

    static boolean isNonPrintable(int codePoint)
    {
        return Character.isWhitespace(codePoint) || Character.isISOControl(codePoint);
    }

    static String printable(String text)
    {
        if (text == null || text.isEmpty())
            return ""; //$NON-NLS-1$
        StringBuilder builder = new StringBuilder(text.length());
        text.codePoints().filter(cp -> !isNonPrintable(cp)).forEach(builder::appendCodePoint);
        return builder.toString();
    }

    static String lineText(IDocument document, int modelLine)
    {
        if (document == null || modelLine < 0)
            return ""; //$NON-NLS-1$
        try
        {
            return document.get(document.getLineOffset(modelLine), document.getLineLength(modelLine));
        }
        catch (BadLocationException ex)
        {
            return ""; //$NON-NLS-1$
        }
    }

    static boolean isWhitespaceOnlyChange(IDocument document, int modelLine, ILineDiffInfo info)
    {
        if (info == null)
            return false;
        int type = info.getChangeType();
        if (type != ILineDiffInfo.ADDED && type != ILineDiffInfo.CHANGED)
            return false;
        String current = lineText(document, modelLine);
        if (type == ILineDiffInfo.ADDED)
            return printable(current).isEmpty();
        String[] original = info.getOriginalText();
        if (original == null || original.length == 0)
            return false;
        return printable(original[0]).equals(printable(current));
    }

    static boolean isDeletedLinesWhitespace(ILineDiffInfo info)
    {
        if (info == null || info.getRemovedLinesBelow() <= 0)
            return false;
        String[] original = info.getOriginalText();
        if (original == null || original.length == 0)
            return false;
        int skip = 0;
        int type = info.getChangeType();
        if (type == ILineDiffInfo.ADDED || type == ILineDiffInfo.CHANGED)
            skip = 1;
        boolean sawDeleted = false;
        for (int i = skip; i < original.length; i++)
        {
            sawDeleted = true;
            if (!printable(original[i]).isEmpty())
                return false;
        }
        return sawDeleted;
    }

    static boolean isWhitespaceOnlyDeletionAbove(ILineDiffer differ, int modelLine, ILineDiffInfo info)
    {
        if (info == null || info.getRemovedLinesAbove() <= 0 || differ == null || modelLine <= 0)
            return false;
        return isDeletedLinesWhitespace(differ.getLineInfo(modelLine - 1));
    }

    static boolean hideLine(IDocument document, ILineDiffer differ, int line, ILineDiffInfo info)
    {
        boolean changeWs = isWhitespaceOnlyChange(document, line, info);
        boolean delBelow = isDeletedLinesWhitespace(info);
        boolean delAbove = isWhitespaceOnlyDeletionAbove(differ, line, info);
        return changeWs || delBelow || delAbove;
    }

    static boolean isWhitespaceQuickDiffAnnotation(Annotation annotation, ISourceViewer viewer, IAnnotationModel model)
    {
        String type = annotation.getType();
        if (type == null)
            return false;
        if (!QD_DELETION.equals(type) && !QD_CHANGE.equals(type) && !QD_ADDITION.equals(type))
            return false;
        if (!(annotation instanceof ILineDiffInfo info))
            return false;
        if (QD_DELETION.equals(type))
        {
            String[] original = info.getOriginalText();
            if (original == null || original.length == 0)
                return false;
            for (String line : original)
            {
                if (!printable(line).isEmpty())
                    return false;
            }
            return true;
        }
        IDocument document = viewer == null ? null : viewer.getDocument();
        ILineDiffer differ = lineDifferOf(viewer);
        if (document == null || differ == null)
            return false;
        Position position = model.getPosition(annotation);
        if (position == null)
            return false;
        try
        {
            int start = document.getLineOfOffset(position.getOffset());
            int lastOffset = position.getOffset() + Math.max(0, position.getLength() - 1);
            int end = document.getLineOfOffset(Math.min(lastOffset, document.getLength()));
            boolean anyHide = false;
            for (int line = start; line <= end; line++)
            {
                ILineDiffInfo lineInfo = differ.getLineInfo(line);
                if (lineInfo == null || !lineInfo.hasChanges())
                    continue;
                int changeType = lineInfo.getChangeType();
                boolean printableChange = (changeType == ILineDiffInfo.ADDED || changeType == ILineDiffInfo.CHANGED)
                    && !isWhitespaceOnlyChange(document, line, lineInfo);
                if (printableChange)
                    return false;
                if (hideLine(document, differ, line, lineInfo))
                    anyHide = true;
            }
            return anyHide;
        }
        catch (BadLocationException ex)
        {
            return false;
        }
    }

    private static final class Session
    {
        final ITextEditor editor;
        final ISourceViewer viewer;
        final LineNumberChangeRulerColumn column;
        final IOverviewRuler overview;
        private boolean differRedrawQuieted;

        Session(ITextEditor editor, ISourceViewer viewer, LineNumberChangeRulerColumn column,
            IOverviewRuler overview)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.column = column;
            this.overview = overview;
        }

        void ensureWrapped()
        {
            wrapPainter();
            ensureHoverWrapped();
            ensureOverviewFiltered();
        }

        /**
         * Подменяет колонку номеров в {@code CompositeRuler.fDecorators},
         * чтобы {@code update()} после аннотаций не вызывал второй {@code redraw()} линейки.
         */
        void installQuietRedraw()
        {
            IVerticalRuler ruler = verticalRulerOf(viewer);
            if (!(ruler instanceof CompositeRuler composite))
                return;
            Object listObj = Global.getField(composite, "fDecorators"); //$NON-NLS-1$
            if (!(listObj instanceof List<?> list))
                return;
            for (int i = 0; i < list.size(); i++)
            {
                Object next = list.get(i);
                if (next instanceof QuietLineNumberColumn)
                    return;
                if (asChangeColumn(next) != column || !(next instanceof IVerticalRulerColumn inner))
                    continue;
                @SuppressWarnings("unchecked")
                List<Object> raw = (List<Object>) list;
                raw.set(i, new QuietLineNumberColumn(inner));
                return;
            }
        }

        /**
         * Только {@code DiffPainter.fHover}. {@code column.getHover()} зависит
         * от последней строки мыши и на ревизии отдаёт {@code RevisionHover} —
         * его нельзя класть в delegate. Ревизионная подсказка не подменяется.
         */
        void ensureHoverWrapped()
        {
            if (column == null)
                return;
            Object painter = Global.getField(column, "fDiffPainter"); //$NON-NLS-1$
            if (painter == null)
                return;
            Object current = Global.getField(painter, "fHover"); //$NON-NLS-1$
            IAnnotationHover stock = null;
            if (current instanceof ChangeHoverWrapper wrapper)
            {
                if (wrapper.delegate == null || wrapper.delegate instanceof LineChangeHover)
                    return;
                stock = null;
            }
            else if (current instanceof LineChangeHover lineChange)
                stock = lineChange;
            Global.setField(painter, "fHover", new ChangeHoverWrapper(editor, viewer, stock)); //$NON-NLS-1$
        }

        void wrapPainter()
        {
            if (column == null)
                return;
            Object diffPainter = Global.getField(column, "fDiffPainter"); //$NON-NLS-1$
            wrapDifferOnPainter(diffPainter, "diff"); //$NON-NLS-1$
            quietDifferCanvasRedraw(diffPainter);
            Object revisionPainter = Global.getField(column, "fRevisionPainter"); //$NON-NLS-1$
            if (wrapDifferOnPainter(revisionPainter, "rev") && column.isShowingRevisionInformation()) //$NON-NLS-1$
                Global.invoke(revisionPainter, "clearRangeCache"); //$NON-NLS-1$
            quietDifferCanvasRedraw(revisionPainter);
        }

        /**
         * {@code DiffPainter} после пересчёта Quick Diff зовёт {@code fColumn.redraw()}
         * — это вторая полная отрисовка линейки ~500 мс после правки, в обход
         * {@link QuietLineNumberColumn}. Клетки Quick Diff рисуются в штатном
         * {@code doPaint} вместе с номерами.
         */
        private void quietDifferCanvasRedraw(Object painter)
        {
            if (painter == null)
                return;
            Object listener = Global.getField(painter, "fAnnotationListener"); //$NON-NLS-1$
            Object differ = Global.getField(painter, "fLineDiffer"); //$NON-NLS-1$
            if (!(listener instanceof IAnnotationModelListener modelListener))
                return;
            IAnnotationModel model = differ instanceof IAnnotationModel annotationModel
                ? annotationModel : null;
            if (model == null)
                return;
            model.removeAnnotationModelListener(modelListener);
            if (differRedrawQuieted)
                return;
            differRedrawQuieted = true;
            NaparnikManualModeHook.logFlickerCause("quickDiff.quietDifferRedraw"); //$NON-NLS-1$
        }

        /**
         * @return {@code true}, если поле {@code fLineDiffer} заменили обёрткой
         */
        private boolean wrapDifferOnPainter(Object painter, String tag)
        {
            if (painter == null)
                return false;
            Object current = Global.getField(painter, "fLineDiffer"); //$NON-NLS-1$
            if (current instanceof DifferWrapper)
                return false;
            if (!(current instanceof ILineDiffer differ))
                return false;
            Global.setField(painter, "fLineDiffer", new DifferWrapper(differ, viewer)); //$NON-NLS-1$
            return true;
        }

        void ensureOverviewFiltered()
        {
            if (overview == null)
                return;
            Object current = Global.getField(overview, "fModel"); //$NON-NLS-1$
            if (current instanceof OverviewFilterModel)
                return;
            if (!(current instanceof IAnnotationModel real))
                return;
            Global.setField(overview, "fModel", new OverviewFilterModel(real, viewer)); //$NON-NLS-1$
        }
    }

    /**
     * Глушит {@code CompositeRuler.immediateUpdate()}: после смены аннотаций
     * (валидация, орфография, вхождения) линейка зовёт {@code redraw()} у всех
     * колонок. Номера строк уже рисует {@code LineNumberRulerColumn.postRedraw}
     * вместе с текстом. Второй проход — видимая вспышка; без плагина её нет
     * даже при добавлении/удалении строки. Пересчёт Quick Diff больше не зовёт
     * отдельный {@code redraw()} колонки — клетки рисуются в том же {@code doPaint}.
     */
    private static final class QuietLineNumberColumn implements IChangeRulerColumn
    {
        private final IVerticalRulerColumn inner;

        QuietLineNumberColumn(IVerticalRulerColumn inner)
        {
            this.inner = inner;
        }

        @Override
        public void redraw()
        {
            // См. javadoc класса: не вызывать inner.redraw() из CompositeRuler.update().
        }

        @Override
        public Control createControl(CompositeRuler parentRuler, Composite parentControl)
        {
            return inner.createControl(parentRuler, parentControl);
        }

        @Override
        public Control getControl()
        {
            return inner.getControl();
        }

        @Override
        public int getWidth()
        {
            return inner.getWidth();
        }

        @Override
        public void setModel(IAnnotationModel model)
        {
            inner.setModel(model);
        }

        @Override
        public void setFont(Font font)
        {
            inner.setFont(font);
        }

        @Override
        public IAnnotationModel getModel()
        {
            return inner instanceof IVerticalRulerInfoExtension ext ? ext.getModel() : null;
        }

        @Override
        public void addVerticalRulerListener(IVerticalRulerListener listener)
        {
            if (inner instanceof IVerticalRulerInfoExtension ext)
                ext.addVerticalRulerListener(listener);
        }

        @Override
        public void removeVerticalRulerListener(IVerticalRulerListener listener)
        {
            if (inner instanceof IVerticalRulerInfoExtension ext)
                ext.removeVerticalRulerListener(listener);
        }

        @Override
        public void setHover(IAnnotationHover hover)
        {
            if (inner instanceof IChangeRulerColumn change)
                change.setHover(hover);
        }

        @Override
        public IAnnotationHover getHover()
        {
            return inner instanceof IVerticalRulerInfoExtension ext ? ext.getHover() : null;
        }

        @Override
        public void setBackground(Color background)
        {
            if (inner instanceof IChangeRulerColumn change)
                change.setBackground(background);
        }

        @Override
        public void setAddedColor(Color color)
        {
            if (inner instanceof IChangeRulerColumn change)
                change.setAddedColor(color);
        }

        @Override
        public void setChangedColor(Color color)
        {
            if (inner instanceof IChangeRulerColumn change)
                change.setChangedColor(color);
        }

        @Override
        public void setDeletedColor(Color color)
        {
            if (inner instanceof IChangeRulerColumn change)
                change.setDeletedColor(color);
        }
    }

    /**
     * На Windows {@code OverviewRuler.redraw} рисует через {@code new GC}, без
     * {@code PaintListener}. Фильтр на {@code fModel} убирает пробельные Quick Diff
     * до штатной отрисовки.
     */
    private static final class OverviewFilterModel implements IAnnotationModel
    {
        final IAnnotationModel delegate;
        final ISourceViewer viewer;

        OverviewFilterModel(IAnnotationModel delegate, ISourceViewer viewer)
        {
            this.delegate = delegate;
            this.viewer = viewer;
        }

        @Override
        public Iterator<Annotation> getAnnotationIterator()
        {
            List<Annotation> kept = new ArrayList<>();
            Iterator<Annotation> iterator = delegate.getAnnotationIterator();
            while (iterator.hasNext())
            {
                Annotation annotation = iterator.next();
                if (isWhitespaceQuickDiffAnnotation(annotation, viewer, delegate))
                    continue;
                kept.add(annotation);
            }
            return kept.iterator();
        }

        @Override
        public Position getPosition(Annotation annotation)
        {
            return delegate.getPosition(annotation);
        }

        @Override
        public void addAnnotationModelListener(IAnnotationModelListener listener)
        {
            delegate.addAnnotationModelListener(listener);
        }

        @Override
        public void removeAnnotationModelListener(IAnnotationModelListener listener)
        {
            delegate.removeAnnotationModelListener(listener);
        }

        @Override
        public void addAnnotation(Annotation annotation, Position position)
        {
            delegate.addAnnotation(annotation, position);
        }

        @Override
        public void removeAnnotation(Annotation annotation)
        {
            delegate.removeAnnotation(annotation);
        }

        @Override
        public void connect(IDocument document)
        {
            delegate.connect(document);
        }

        @Override
        public void disconnect(IDocument document)
        {
            delegate.disconnect(document);
        }
    }

    private static final class DifferWrapper implements ILineDiffer, ILineDifferExtension, ILineDifferExtension2,
        IAnnotationModel
    {
        final ILineDiffer delegate;
        final ISourceViewer viewer;

        DifferWrapper(ILineDiffer delegate, ISourceViewer viewer)
        {
            this.delegate = delegate;
            this.viewer = viewer;
        }

        private IAnnotationModel model()
        {
            return delegate instanceof IAnnotationModel annotationModel ? annotationModel : null;
        }

        private static boolean isColumnRedrawListener(IAnnotationModelListener listener)
        {
            if (listener == null)
                return false;
            String name = listener.getClass().getName();
            return name.contains("DiffPainter") || name.contains("RevisionPainter"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public ILineDiffInfo getLineInfo(int line)
        {
            ILineDiffInfo inner = delegate.getLineInfo(line);
            if (inner == null)
                return null;
            IDocument document = viewer.getDocument();
            boolean changeWs = isWhitespaceOnlyChange(document, line, inner);
            boolean delBelow = isDeletedLinesWhitespace(inner);
            boolean delAbove = isWhitespaceOnlyDeletionAbove(delegate, line, inner);
            if (!changeWs && !delBelow && !delAbove)
                return inner;
            return new FilteredInfo(inner, changeWs, delBelow, delAbove);
        }

        @Override
        public void revertLine(int line) throws BadLocationException
        {
            delegate.revertLine(line);
        }

        @Override
        public void revertBlock(int line) throws BadLocationException
        {
            delegate.revertBlock(line);
        }

        @Override
        public void revertSelection(int line, int length) throws BadLocationException
        {
            delegate.revertSelection(line, length);
        }

        @Override
        public int restoreAfterLine(int line) throws BadLocationException
        {
            return delegate.restoreAfterLine(line);
        }

        @Override
        public void suspend()
        {
            if (delegate instanceof ILineDifferExtension ext)
                ext.suspend();
        }

        @Override
        public void resume()
        {
            if (delegate instanceof ILineDifferExtension ext)
                ext.resume();
        }

        @Override
        public boolean isSuspended()
        {
            return delegate instanceof ILineDifferExtension2 ext2 && ext2.isSuspended();
        }

        @Override
        public void addAnnotationModelListener(IAnnotationModelListener listener)
        {
            if (isColumnRedrawListener(listener))
                return;
            IAnnotationModel model = model();
            if (model != null)
                model.addAnnotationModelListener(listener);
        }

        @Override
        public void removeAnnotationModelListener(IAnnotationModelListener listener)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.removeAnnotationModelListener(listener);
        }

        @Override
        public void addAnnotation(Annotation annotation, Position position)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.addAnnotation(annotation, position);
        }

        @Override
        public void removeAnnotation(Annotation annotation)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.removeAnnotation(annotation);
        }

        @Override
        public void connect(IDocument document)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.connect(document);
        }

        @Override
        public void disconnect(IDocument document)
        {
            IAnnotationModel model = model();
            if (model != null)
                model.disconnect(document);
        }

        @Override
        public Iterator<Annotation> getAnnotationIterator()
        {
            IAnnotationModel model = model();
            if (model == null)
                return Collections.emptyIterator();
            @SuppressWarnings("unchecked")
            Iterator<Annotation> iterator = model.getAnnotationIterator();
            return iterator;
        }

        @Override
        public Position getPosition(Annotation annotation)
        {
            IAnnotationModel model = model();
            return model == null ? null : model.getPosition(annotation);
        }
    }

    private static final class FilteredInfo implements ILineDiffInfo
    {
        private final ILineDiffInfo inner;
        private final boolean hideChange;
        private final boolean hideDelBelow;
        private final boolean hideDelAbove;

        FilteredInfo(ILineDiffInfo inner, boolean hideChange, boolean hideDelBelow, boolean hideDelAbove)
        {
            this.inner = inner;
            this.hideChange = hideChange;
            this.hideDelBelow = hideDelBelow;
            this.hideDelAbove = hideDelAbove;
        }

        @Override
        public int getChangeType()
        {
            return hideChange ? UNCHANGED : inner.getChangeType();
        }

        @Override
        public int getRemovedLinesBelow()
        {
            return hideDelBelow ? 0 : inner.getRemovedLinesBelow();
        }

        @Override
        public int getRemovedLinesAbove()
        {
            return hideDelAbove ? 0 : inner.getRemovedLinesAbove();
        }

        @Override
        public boolean hasChanges()
        {
            return getChangeType() != UNCHANGED
                || getRemovedLinesAbove() > 0
                || getRemovedLinesBelow() > 0;
        }

        @Override
        public String[] getOriginalText()
        {
            return inner.getOriginalText();
        }
    }

    /**
     * Интерактивный тултип Quick Diff: штатный текст хунка и кнопки в нижней
     * панели ({@link DefaultInformationControl} с {@link ToolBarManager}).
     */
    private static final class ChangeHoverWrapper implements IAnnotationHover, IAnnotationHoverExtension,
        IAnnotationHoverExtension2, IInformationProviderExtension2
    {
        private static final String COMPARE_ID = "tormozit.comfort.qdCompare"; //$NON-NLS-1$
        private static final String REVERT_ID = "tormozit.comfort.qdRevert"; //$NON-NLS-1$

        private static final double RULER_SHADE_SCALE = 0.6;
        private static final double DELETION_FILL_SCALE = 0.75;
        private static final int CELL_GAP = 4;

        final IAnnotationHover delegate;
        private final ITextEditor editor;
        private final ISourceViewer viewer;
        private final IAnnotationHoverExtension delegateExt;
        private final LineChangeHover textHover;
        private final List<Integer> addedOffsets = new ArrayList<>();
        private final List<Integer> changedOffsets = new ArrayList<>();
        private final List<Integer> deletedOffsets = new ArrayList<>();
        private int hoverLine;
        private IInformationControl currentControl;

        ChangeHoverWrapper(ITextEditor editor, ISourceViewer viewer, IAnnotationHover delegate)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.delegate = delegate;
            this.delegateExt = delegate instanceof IAnnotationHoverExtension ext ? ext : null;
            this.textHover = new ComfortLineChangeHover(addedOffsets, changedOffsets, deletedOffsets);
        }

        @Override
        public String getHoverInfo(ISourceViewer sourceViewer, int lineNumber)
        {
            rememberLine(lineNumber);
            addedOffsets.clear();
            changedOffsets.clear();
            deletedOffsets.clear();
            if (delegate != null && !(delegate instanceof LineChangeHover))
            {
                String info = delegate.getHoverInfo(sourceViewer, lineNumber);
                if (info != null)
                    return info;
            }
            return textHover.getHoverInfo(sourceViewer, lineNumber);
        }

        @Override
        public Object getHoverInfo(ISourceViewer sourceViewer, ILineRange lineRange, int visibleNumberOfLines)
        {
            int line = lineRange != null ? lineRange.getStartLine() : 0;
            rememberLine(line);
            addedOffsets.clear();
            changedOffsets.clear();
            deletedOffsets.clear();
            if (delegateExt != null && !(delegate instanceof LineChangeHover))
            {
                Object info = delegateExt.getHoverInfo(sourceViewer, lineRange, visibleNumberOfLines);
                if (info != null)
                    return info;
            }
            return textHover.getHoverInfo(sourceViewer, lineRange, visibleNumberOfLines);
        }

        @Override
        public ILineRange getHoverLineRange(ISourceViewer sourceViewer, int lineNumber)
        {
            rememberLine(lineNumber);
            // Не delegate: штатный LineChangeHover смотрит сырой differ и раздувает
            // диапазон по пробельным CHANGED, которые DifferWrapper на полосе скрыл.
            return textHover.getHoverLineRange(sourceViewer, lineNumber);
        }

        @Override
        public boolean canHandleMouseCursor()
        {
            return true;
        }

        @Override
        public boolean canHandleMouseWheel()
        {
            return true;
        }

        @Override
        public IInformationControlCreator getHoverControlCreator()
        {
            return parent -> createControl(parent);
        }

        @Override
        public IInformationControlCreator getInformationPresenterControlCreator()
        {
            return parent -> createControl(parent);
        }

        private IInformationControl createControl(Shell parent)
        {
            ToolBarManager manager = new ToolBarManager(SWT.FLAT);
            try
            {
                if (canCompare())
                    manager.add(new CompareAction());
                if (canRevert())
                    manager.add(new RevertAction());
            }
            catch (Exception e)
            {
                Global.log("QuickDiffHover.createControl: " + e); //$NON-NLS-1$
            }
            HoverControl control = new HoverControl(parent, manager);
            currentControl = control;
            return control;
        }

        /**
         * Штатный {@link DefaultInformationControl} при переходе мыши на попап
         * подменяет себя presenter'ом <b>без</b> {@link ToolBarManager}
         * ({@code getInformationPresenterControlCreator} передаёт {@code null}).
         * Повторяем создание с кнопками.
         */
        private final class HoverControl extends DefaultInformationControl
        {
            private Color deletionBg;
            private Color changeBg;
            private Color additionBg;
            private boolean cellPaintInstalled;

            HoverControl(Shell parent, ToolBarManager manager)
            {
                // Без HTML-presenter: штатный LineChangeHover отдаёт обычный
                // текст с переводами строк. Конструктор (parent, manager)
                // подключает HTMLTextPresenter и склеивает строки в одну.
                super(parent, manager, null);
                getShell().addDisposeListener(e ->
                {
                    disposeColor(deletionBg);
                    deletionBg = null;
                    disposeColor(changeBg);
                    changeBg = null;
                    disposeColor(additionBg);
                    additionBg = null;
                });
            }

            @Override
            public IInformationControlCreator getInformationPresenterControlCreator()
            {
                return parent -> createControl(parent);
            }

            @Override
            public void setInformation(String content)
            {
                super.setInformation(content);
                paintHoverHighlights();
            }

            /**
             * При {@code canHandleMouseCursor} менеджер ставит попап поверх
             * полосы номеров ({@code subjectArea.x - 4}), чтобы мышь могла
             * заехать. Сдвигаем к началу текста — как штатный тултип Quick Diff.
             */
            @Override
            public void setLocation(Point location)
            {
                StyledText text = viewer.getTextWidget();
                if (location != null && text != null && !text.isDisposed())
                {
                    int textLeft = text.toDisplay(0, 0).x;
                    if (location.x < textLeft)
                        location.x = textLeft;
                }
                super.setLocation(location);
            }

            private void paintHoverHighlights()
            {
                Object widget = Global.getField(this, "fText"); //$NON-NLS-1$
                if (!(widget instanceof StyledText text) || text.isDisposed())
                    return;
                int cellW = Math.max(12, text.getLineHeight());
                text.setLeftMargin(cellW + CELL_GAP);
                if (!cellPaintInstalled)
                {
                    cellPaintInstalled = true;
                    text.addPaintListener(e -> paintLineCells(text, e.gc, e.y, e.height));
                }
                text.redraw();
            }

            private void paintLineCells(StyledText text, GC gc, int clipY, int clipH)
            {
                int cellW = Math.max(0, text.getLeftMargin() - CELL_GAP);
                if (cellW <= 0 || gc == null)
                    return;
                paintCells(text, gc, clipY, clipH, cellW, addedOffsets, additionColor(text));
                paintCells(text, gc, clipY, clipH, cellW, changedOffsets, changeColor(text));
                paintCells(text, gc, clipY, clipH, cellW, deletedOffsets, deletionColor(text));
            }

            private void paintCells(StyledText text, GC gc, int clipY, int clipH, int cellW,
                List<Integer> offsets, Color bg)
            {
                if (offsets.isEmpty() || bg == null)
                    return;
                int charCount = text.getCharCount();
                int lineCount = text.getLineCount();
                if (charCount <= 0 || lineCount <= 0)
                    return;
                gc.setBackground(bg);
                for (Integer offset : offsets)
                {
                    if (offset == null || offset < 0)
                        continue;
                    int at = offset >= charCount ? charCount - 1 : offset;
                    int line = text.getLineAtOffset(at);
                    if (line < 0 || line >= lineCount)
                        continue;
                    int y = text.getLinePixel(line);
                    int h = text.getLineHeight(line);
                    if (y + h < clipY || y > clipY + clipH)
                        continue;
                    gc.fillRectangle(0, y, cellW, h);
                }
            }

            private Color deletionColor(StyledText text)
            {
                if (deletionBg != null && !deletionBg.isDisposed())
                    return deletionBg;
                deletionBg = new Color(text.getDisplay(), weakenMarker(text.getDisplay(),
                    QD_DELETION, new RGB(0, 0, 0), DELETION_FILL_SCALE));
                return deletionBg;
            }

            private Color changeColor(StyledText text)
            {
                if (changeBg != null && !changeBg.isDisposed())
                    return changeBg;
                changeBg = new Color(text.getDisplay(),
                    shadeLikeRuler(text.getDisplay(), QD_CHANGE, new RGB(204, 163, 205)));
                return changeBg;
            }

            private Color additionColor(StyledText text)
            {
                if (additionBg != null && !additionBg.isDisposed())
                    return additionBg;
                additionBg = new Color(text.getDisplay(),
                    shadeLikeRuler(text.getDisplay(), QD_ADDITION, new RGB(188, 188, 222)));
                return additionBg;
            }

            private static void disposeColor(Color color)
            {
                if (color != null && !color.isDisposed())
                    color.dispose();
            }
        }

        /**
         * Хунк без префиксов «> »/«- »: добавленные строки — текущий текст,
         * изменённые и удалённые — исходный. Слева ячейка цветом маркера.
         */
        private static final class ComfortLineChangeHover extends LineChangeHover
        {
            private final List<Integer> addedOffsets;
            private final List<Integer> changedOffsets;
            private final List<Integer> deletedOffsets;

            ComfortLineChangeHover(List<Integer> addedOffsets, List<Integer> changedOffsets,
                List<Integer> deletedOffsets)
            {
                this.addedOffsets = addedOffsets;
                this.changedOffsets = changedOffsets;
                this.deletedOffsets = deletedOffsets;
            }

            @Override
            public ILineRange getHoverLineRange(ISourceViewer viewer, int lineNumber)
            {
                IDocument document = viewer.getDocument();
                if (document == null)
                    return null;
                ILineDiffer differ = filteredLineDifferOf(viewer);
                if (differ == null || !lineHasVisibleDiff(differ, lineNumber))
                    return null;
                Point range = computeLineRange(viewer, lineNumber, 0,
                    Math.max(0, document.getNumberOfLines() - 1));
                if (range.x < 0 || range.y < 0)
                    return null;
                return new LineRange(range.x, range.y - range.x + 1);
            }

            @Override
            protected Point computeLineRange(ISourceViewer viewer, int line, int min, int max)
            {
                ILineDiffer differ = filteredLineDifferOf(viewer);
                if (differ == null)
                    return new Point(-1, -1);
                int l = line;
                ILineDiffInfo info = differ.getLineInfo(l);
                while (l >= min && info != null
                    && (info.getChangeType() == ILineDiffInfo.CHANGED
                        || info.getChangeType() == ILineDiffInfo.ADDED))
                    info = differ.getLineInfo(--l);
                int first = Math.min(l + 1, line);
                l = line;
                info = differ.getLineInfo(l);
                while (l <= max && info != null
                    && (info.getChangeType() == ILineDiffInfo.CHANGED
                        || info.getChangeType() == ILineDiffInfo.ADDED))
                    info = differ.getLineInfo(++l);
                int last = Math.max(l - 1, line);
                return new Point(first, last);
            }

            private static boolean lineHasVisibleDiff(ILineDiffer differ, int lineNumber)
            {
                ILineDiffInfo info = differ.getLineInfo(lineNumber);
                if (info != null && info.hasChanges())
                    return true;
                if (lineNumber > 0)
                {
                    ILineDiffInfo above = differ.getLineInfo(lineNumber - 1);
                    if (above != null && above.getRemovedLinesBelow() > 0)
                        return true;
                }
                return false;
            }

            @Override
            public Object getHoverInfo(ISourceViewer sourceViewer, ILineRange lineRange, int visibleLines)
            {
                addedOffsets.clear();
                changedOffsets.clear();
                deletedOffsets.clear();
                if (lineRange == null)
                    return null;
                int start = lineRange.getStartLine();
                int first = adaptFirstLine(sourceViewer, start);
                int last = adaptLastLine(sourceViewer, start + lineRange.getNumberOfLines() - 1);
                String content = buildHover(sourceViewer, first, last, visibleLines);
                return formatSource(content);
            }

            private int adaptFirstLine(ISourceViewer viewer, int startLine)
            {
                ILineDiffer differ = filteredLineDifferOf(viewer);
                if (differ != null && startLine > 0)
                {
                    ILineDiffInfo info = differ.getLineInfo(startLine - 1);
                    if (info != null && info.getChangeType() == ILineDiffInfo.UNCHANGED
                        && info.getRemovedLinesBelow() > 0)
                        return startLine - 1;
                }
                return startLine;
            }

            private int adaptLastLine(ISourceViewer viewer, int lastLine)
            {
                ILineDiffer differ = filteredLineDifferOf(viewer);
                if (differ != null && lastLine > 0)
                {
                    ILineDiffInfo info = differ.getLineInfo(lastLine);
                    if (info != null && info.getChangeType() == ILineDiffInfo.UNCHANGED)
                        return lastLine - 1;
                }
                return lastLine;
            }

            private String buildHover(ISourceViewer sourceViewer, int first, int last, int maxLines)
            {
                ILineDiffer differ = filteredLineDifferOf(sourceViewer);
                if (differ == null)
                    return null;
                IDocument document = sourceViewer.getDocument();
                StringBuilder text = new StringBuilder();
                for (int line = first; line <= last; line++)
                {
                    ILineDiffInfo info = differ.getLineInfo(line);
                    if (info == null)
                        continue;
                    String[] original = info.getOriginalText();
                    if (original == null)
                        original = new String[0];
                    int type = info.getChangeType();
                    int i = 0;
                    if (type == ILineDiffInfo.ADDED)
                    {
                        addedOffsets.add(appendLine(text, lineText(document, line)));
                        maxLines--;
                    }
                    else if (type == ILineDiffInfo.CHANGED)
                    {
                        changedOffsets.add(appendLine(text,
                            original.length > 0 ? original[i++] : "")); //$NON-NLS-1$
                        maxLines--;
                    }
                    else if (type == ILineDiffInfo.UNCHANGED)
                    {
                        maxLines++;
                    }
                    if (maxLines == 0)
                        return finish(text);
                    for (; i < original.length; i++)
                    {
                        deletedOffsets.add(appendLine(text,
                            original[i] != null ? original[i] : "")); //$NON-NLS-1$
                        if (--maxLines == 0)
                            return finish(text);
                    }
                }
                return finish(text);
            }

            private static int appendLine(StringBuilder text, String line)
            {
                if (line == null)
                    line = ""; //$NON-NLS-1$
                if (text.length() > 0 && !endsWithBreak(text) && !line.isEmpty())
                    text.append('\n');
                int start = text.length();
                text.append(line);
                return start;
            }

            private static boolean endsWithBreak(StringBuilder text)
            {
                if (text.length() == 0)
                    return false;
                char last = text.charAt(text.length() - 1);
                return last == '\n' || last == '\r';
            }

            private String finish(StringBuilder text)
            {
                String raw = text.toString();
                int end = raw.length();
                while (end > 0 && Character.isWhitespace(raw.charAt(end - 1)))
                    end--;
                String trimmed = raw.substring(0, end);
                addedOffsets.removeIf(offset -> offset >= trimmed.length());
                changedOffsets.removeIf(offset -> offset >= trimmed.length());
                deletedOffsets.removeIf(offset -> offset >= trimmed.length());
                return trimmed;
            }
        }

        /** Как {@code DiffPainter.getShadedColor}: чуть слабее сырого маркера, как клетка на полосе номеров. */
        private static RGB shadeLikeRuler(Display display, String annotationType, RGB fallback)
        {
            RGB marker = quickDiffMarkerColor(annotationType, fallback);
            RGB widgetBg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
            RGB toward = widgetBg;
            boolean lightMarker = greyLevel(marker) > 128;
            boolean lightBg = greyLevel(widgetBg) > 128;
            if (lightMarker && lightBg)
                toward = new RGB(255, 255, 255);
            else if (!lightMarker && !lightBg)
                toward = new RGB(0, 0, 0);
            return interpolate(marker, toward, RULER_SHADE_SCALE);
        }

        private static double greyLevel(RGB rgb)
        {
            if (rgb.red == rgb.green && rgb.green == rgb.blue)
                return rgb.red;
            return 0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue + 0.5;
        }

        /** Ослабленный цвет маркера Quick Diff: смешение с {@code COLOR_LIST_BACKGROUND}, не с жёлтым тултипом. */
        private static RGB weakenMarker(Display display, String annotationType, RGB fallback, double scale)
        {
            RGB marker = quickDiffMarkerColor(annotationType, fallback);
            RGB listBg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
            return interpolate(marker, listBg, scale);
        }

        private static RGB quickDiffMarkerColor(String annotationType, RGB fallback)
        {
            AnnotationPreference pref = EditorsUI.getAnnotationPreferenceLookup()
                .getAnnotationPreference(annotationType);
            if (pref == null)
                return fallback;
            IPreferenceStore store = EditorsUI.getPreferenceStore();
            String key = pref.getColorPreferenceKey();
            RGB rgb = null;
            if (store != null && key != null && store.contains(key))
            {
                if (store.isDefault(key))
                    rgb = pref.getColorPreferenceValue();
                else
                    rgb = PreferenceConverter.getColor(store, key);
            }
            if (rgb == null)
                rgb = pref.getColorPreferenceValue();
            return rgb != null ? rgb : fallback;
        }

        private static RGB interpolate(RGB fg, RGB bg, double scale)
        {
            return new RGB(
                (int) ((1.0 - scale) * fg.red + scale * bg.red),
                (int) ((1.0 - scale) * fg.green + scale * bg.green),
                (int) ((1.0 - scale) * fg.blue + scale * bg.blue));
        }

        private void rememberLine(int lineNumber)
        {
            hoverLine = Math.max(0, lineNumber);
        }

        private void closeHover()
        {
            IInformationControl control = currentControl;
            currentControl = null;
            if (control != null)
                control.setVisible(false);
        }

        private boolean canRevert()
        {
            return editor != null && editor.isEditable() && lineDifferOf(viewer) != null;
        }

        private boolean canCompare()
        {
            IFile file = fileOf(editor);
            if (file == null)
                return referenceText() != null && currentText() != null;
            return hasGitMapping(file) || hasLocalHistory(file) || file.exists()
                || referenceText() != null;
        }

        private void runCompare()
        {
            int line = hoverLine;
            closeHover();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                try
                {
                    IFile file = fileOf(editor);
                    IWorkbenchPage page = editor != null && editor.getSite() != null
                        ? editor.getSite().getPage()
                        : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    if (openGitCompare(file, page, line))
                        return;
                    if (openLocalHistoryCompare(file, page, line))
                        return;
                    if (openReferenceCompare(file, line))
                        return;
                    ToastNotification.show("Сравнить",
                        "Нет исходной версии для сравнения", 4000); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    Global.log("QuickDiffHover.compare: " + e); //$NON-NLS-1$
                    ToastNotification.show("Сравнить",
                        "Не удалось открыть сравнение: " + e.getMessage(), 5000); //$NON-NLS-1$
                }
            });
        }

        private void runRevert()
        {
            int line = hoverLine;
            closeHover();
            ILineDiffer differ = lineDifferOf(viewer);
            if (differ == null)
                return;
            try
            {
                differ.revertBlock(line);
            }
            catch (BadLocationException e)
            {
                Global.log("QuickDiffHover.revert: " + e); //$NON-NLS-1$
                ToastNotification.show("Откатить",
                    "Не удалось откатить изменение: " + e.getMessage(), 4000); //$NON-NLS-1$
            }
        }

        private String currentText()
        {
            IDocument document = viewer != null ? viewer.getDocument() : null;
            return document != null ? document.get() : null;
        }

        private String referenceText()
        {
            try
            {
                ILineDiffer differ = lineDifferOf(viewer);
                if (differ == null)
                    return null;
                Object provider = Global.invoke(differ, "getReferenceProvider"); //$NON-NLS-1$
                if (provider == null)
                    return null;
                Object ref = Global.invoke(provider, "getReference", new NullProgressMonitor()); //$NON-NLS-1$
                return ref instanceof IDocument document ? document.get() : null;
            }
            catch (Exception e)
            {
                return null;
            }
        }

        private boolean openReferenceCompare(IFile file, int line)
        {
            String current = currentText();
            String reference = referenceText();
            if (current == null || reference == null)
                return false;
            String name = file != null ? file.getName() : "module.bsl"; //$NON-NLS-1$
            CompareEditorCurrentLinesHook.setPendingLineReveal(line, true);
            CompareUI.openCompareEditor(
                new ReferenceCompareInput(current, reference, name));
            return true;
        }

        private final class CompareAction extends Action
        {
            CompareAction()
            {
                setId(COMPARE_ID);
                ImageDescriptor icon = compareIcon();
                if (icon != null)
                    setImageDescriptor(icon);
                else
                    setText("Сравнить"); //$NON-NLS-1$
                setToolTipText("Открыть сравнение с исходной версией" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            }

            @Override
            public void run()
            {
                runCompare();
            }
        }

        private final class RevertAction extends Action
        {
            RevertAction()
            {
                setId(REVERT_ID);
                ImageDescriptor icon = revertIcon();
                if (icon != null)
                    setImageDescriptor(icon);
                else
                    setText("Откатить"); //$NON-NLS-1$
                setToolTipText("Откатить изменение к исходной версии" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            }

            @Override
            public void run()
            {
                runRevert();
            }
        }
    }

    private static ImageDescriptor compareIcon()
    {
        try
        {
            return AbstractUIPlugin.imageDescriptorFromPlugin("org.eclipse.egit.ui", //$NON-NLS-1$
                "icons/elcl16/compare_view.png"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static ImageDescriptor revertIcon()
    {
        try
        {
            return PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_TOOL_UNDO);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static IFile fileOf(ITextEditor editor)
    {
        if (editor == null)
            return null;
        IEditorInput input = editor.getEditorInput();
        if (input == null)
            return null;
        IFile file = input.getAdapter(IFile.class);
        return file;
    }

    private static boolean hasGitMapping(IFile file)
    {
        if (file == null)
            return false;
        RepositoryMapping mapping = RepositoryMapping.getMapping(file);
        return mapping != null && mapping.getRepository() != null;
    }

    private static boolean hasLocalHistory(IFile file)
    {
        if (file == null || !file.exists())
            return false;
        try
        {
            IFileState[] states = file.getHistory(null);
            return states != null && states.length > 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Как двойной клик по неиндексированному файлу в {@code StagingView}:
     * {@code CompareWithIndexActionHandler} → {@code CompareUtils.compareWorkspaceWithRef}
     * (слева {@code SaveableCompareEditorInput.createFileElement}, справа индекс).
     */
    private static boolean openGitCompare(IFile file, IWorkbenchPage page, int line)
    {
        if (file == null)
            return false;
        try
        {
            RepositoryMapping mapping = RepositoryMapping.getMapping(file);
            if (mapping == null)
                return false;
            Repository repository = mapping.getRepository();
            if (repository == null)
                return false;
            Class<?> utils = Class.forName("org.eclipse.egit.ui.internal.CompareUtils"); //$NON-NLS-1$
            CompareEditorCurrentLinesHook.setPendingLineReveal(line, true);
            Global.invoke(utils, "compareWorkspaceWithRef", //$NON-NLS-1$
                repository, file, "Index", page); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Global.log("QuickDiffHover.openGitCompare: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean openLocalHistoryCompare(IFile file, IWorkbenchPage page, int line)
    {
        if (file == null || !file.exists())
            return false;
        try
        {
            IFileState[] states = file.getHistory(null);
            if (states == null || states.length == 0)
                return false;
            Object left = construct(
                "org.eclipse.team.internal.ui.synchronize.LocalResourceTypedElement", //$NON-NLS-1$
                new Class<?>[] { IResource.class },
                file);
            Object revision = construct(
                "org.eclipse.team.internal.core.history.LocalFileRevision", //$NON-NLS-1$
                new Class<?>[] { IFileState.class },
                states[0]);
            Class<?> fileRevisionClass = Class.forName("org.eclipse.team.core.history.IFileRevision"); //$NON-NLS-1$
            Object right = construct(
                "org.eclipse.team.internal.ui.history.FileRevisionTypedElement", //$NON-NLS-1$
                new Class<?>[] { fileRevisionClass },
                revision);
            Object input = construct(
                "org.eclipse.team.internal.ui.history.CompareFileRevisionEditorInput", //$NON-NLS-1$
                new Class<?>[] { ITypedElement.class, ITypedElement.class, IWorkbenchPage.class },
                left, right, page);
            if (!(input instanceof CompareEditorInput editorInput))
                return false;
            CompareEditorCurrentLinesHook.setPendingLineReveal(line, true);
            CompareUI.openCompareEditor(editorInput);
            return true;
        }
        catch (Exception e)
        {
            Global.log("QuickDiffHover.openLocalHistoryCompare: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static Object construct(String className, Class<?>[] types, Object... args) throws Exception
    {
        Constructor<?> ctor = Class.forName(className).getConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    /**
     * Запасное сравнение текущего документа с референсом Quick Diff
     * (last saved / содержимое {@code IQuickDiffReferenceProvider}).
     */
    private static final class ReferenceCompareInput extends CompareEditorInput
    {
        private final StringCompareElement leftElement;
        private final StringCompareElement rightElement;

        ReferenceCompareInput(String currentText, String referenceText, String fileName)
        {
            super(createConfiguration());
            String type = viewerType(fileName);
            leftElement = new StringCompareElement(fileName, currentText, type);
            rightElement = new StringCompareElement(fileName, referenceText, type);
            setTitle(fileName);
        }

        private static CompareConfiguration createConfiguration()
        {
            CompareConfiguration config = new CompareConfiguration();
            config.setLeftEditable(false);
            config.setRightEditable(false);
            config.setLeftLabel("Текущий"); //$NON-NLS-1$
            config.setRightLabel("Исходный"); //$NON-NLS-1$
            return config;
        }

        private static String viewerType(String fileName)
        {
            if (fileName == null)
                return "txt"; //$NON-NLS-1$
            int dot = fileName.lastIndexOf('.');
            if (dot < 0 || dot == fileName.length() - 1)
                return "txt"; //$NON-NLS-1$
            return fileName.substring(dot + 1);
        }

        @Override
        protected Object prepareInput(IProgressMonitor monitor)
        {
            return new DiffNode(null, Differencer.CHANGE, null, leftElement, rightElement);
        }

        private static final class StringCompareElement
            implements ITypedElement, IStreamContentAccessor, IEncodedStreamContentAccessor
        {
            private final String name;
            private final String content;
            private final String type;

            StringCompareElement(String name, String content, String type)
            {
                this.name = name != null ? name : ""; //$NON-NLS-1$
                this.content = content != null ? content : ""; //$NON-NLS-1$
                this.type = type != null ? type : "txt"; //$NON-NLS-1$
            }

            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public Image getImage()
            {
                return null;
            }

            @Override
            public String getType()
            {
                return type;
            }

            @Override
            public InputStream getContents()
            {
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String getCharset()
            {
                return StandardCharsets.UTF_8.name();
            }
        }
    }
}
