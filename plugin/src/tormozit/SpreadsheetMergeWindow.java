package tormozit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.views.properties.IPropertySheetPage;

import com._1c.g5.v8.dt.ui.commands.ShowPropertiesHandler;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.compare.ui.CompareUiSharedImages;
import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.core.operations.model.IEditingContextListener;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.md.ui.presentation.IPresentationService;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.Drawing;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.MoxelResourceMxlx;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetRect;
import com._1c.g5.v8.dt.moxel.api.BaseOperation;
import com._1c.g5.v8.dt.moxel.api.PasteCellsOperation;
import com._1c.g5.v8.dt.moxel.geometry.MoxelDescriptor;
import com._1c.g5.v8.dt.moxel.sheet.CellsSelection;
import com._1c.g5.v8.dt.moxel.sheet.ColumnsSelection;
import com._1c.g5.v8.dt.moxel.sheet.RowsSelection;
import com._1c.g5.v8.dt.moxel.sheet.Selection;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelControl;
import com._1c.g5.v8.dt.moxel.ui.editor.PositionHolder;
import com._1c.g5.v8.dt.moxel.ui.editor.ViewPort;
import com._1c.g5.v8.dt.moxel.util.ClipboardCopyUtil;
import com._1c.g5.v8.dt.moxel.util.PasteSpecialOptions;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Окно табличного объединения макета {@code .mxlx} (#1015): стороны сравнения и результат
 * в родном контроле табличного документа EDT ({@link MoxelControl}).
 *
 * <p>Документы загружаются из XML сторон и прикрепляются к частным контекстам редактирования
 * модели проекта (схема edt-agent-bridge, см. {@link #createPane}); правится только результат
 * объединения, а при двустороннем сравнении — основная сторона. Строки и колонки сторон сопоставляются штатным
 * {@link RangeDifferencer} (наибольшая общая последовательность) по ключам: текст крайней
 * закреплённой колонки (для строк) и крайней закреплённой строки (для колонок), если закрепление
 * есть у всех сторон; иначе — по всему содержимому строки/колонки. Отличающиеся ячейки
 * закрашиваются поверх отрисовки контрола, текущая ячейка синхронна на всех сторонах.
 *
 * <p>Команды повторяют штатные окна: при трёх сторонах — тулбары сторон и результата
 * {@code ThreeSideTextMergeViewer} EDT (копирование текущего/всех изменений слева и справа,
 * переходы по изменениям сторон и конфликтам), при двух — копирование справа налево Eclipse
 * Compare. Группа строк переносится штатными операциями «Вставить со сдвигом» и «Удалить»
 * табличного документа. Для каждой группы показывается, чья версия сейчас в результате.
 * «ОК» записывает XML правленого документа в штатный просмотрщик результата (левой стороны).
 *
 * <p>Вызывается из окон, которые открывает шестерёнка дерева сравнения: «Сравнение текста»
 * ({@link CompareDialogCurrentLinesHook}) и диалога объединения ({@link TextMergeEditorHook}).
 */
public final class SpreadsheetMergeWindow
{
    /** Тема временного лога. */
    private static final String TEMP_LOG = "spreadsheet-merge-1015"; //$NON-NLS-1$

    public static final String MENU_LABEL = "Табличное объединение"; //$NON-NLS-1$

    public static final String TOOLTIP =
        "Сравнить стороны макета табличного документа в табличном виде"; //$NON-NLS-1$

    /** Пакет штатных операций табличного документа (бандл {@code com._1c.g5.v8.dt.moxel.ui} его не экспортирует). */
    private static final String MOXEL_OPERATIONS = "com._1c.g5.v8.dt.moxel.ui.operations."; //$NON-NLS-1$

    private static final byte MARK_NONE = 0;
    private static final byte MARK_CHANGED = 1;
    private static final byte MARK_ADDED = 2;
    private static final byte MARK_CONFLICT = 3;

    /** Чья версия группы в результате. */
    private static final byte WIN_MANUAL = 0;
    private static final byte WIN_LEFT = 1;
    private static final byte WIN_RIGHT = 2;
    private static final byte WIN_BOTH = 3;
    private static final byte WIN_ANCESTOR = 4;

    /** Прозрачность заливки отличий: лист рисуется светлым в обеих темах. */
    private static final int MARK_ALPHA = 70;

    /** Сторона окна: подпись и элемент сравнения (может быть {@code null} — стороны нет). */
    public record Side(String label, ITypedElement element)
    {
    }

    private SpreadsheetMergeWindow()
    {
    }

    /** Открытые окна по ключу (узел дерева сравнения): повторное открытие активирует окно. */
    private static final java.util.Map<Object, Shell> OPEN_WINDOWS = new java.util.HashMap<>();

    /**
     * Полное имя макета для заголовка: подпись основной стороны без приписки «(источник)» в конце
     * (подпись имеет вид «ОбщийМакет.ТаблДок1 (Рабочий каталог)»).
     */
    private static String templateName(Side main)
    {
        String label = main != null ? main.label() : null;
        if (label == null || label.isBlank())
            return ""; //$NON-NLS-1$
        int bracket = label.lastIndexOf(" ("); //$NON-NLS-1$
        String name = bracket > 0 && label.endsWith(")") ? label.substring(0, bracket) : label; //$NON-NLS-1$
        return " — " + name; //$NON-NLS-1$
    }

    /**
     * Запись XML в документ штатного просмотрщика сравнения (результата или левой стороны):
     * дальше изменения сохраняет штатная кнопка «ОК» окна сравнения.
     *
     * @return {@code null}, если просмотрщика нет или он не правится
     */
    public static Consumer<String> viewerWriter(SourceViewer viewer, String tag)
    {
        boolean editable = viewer != null && viewer.getDocument() != null && viewer.isEditable();
        tempLog("viewerWriter[" + tag + "]: viewer=" + (viewer != null) + ", editable=" + editable); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!editable)
            return null;
        return xml -> {
            IDocument document = viewer.getDocument();
            String before = document.get();
            document.set(xml);
            // #region agent log — временный, #1015: запись в штатный просмотрщик
            tempLog("viewerWriter[" + tag + "]: записано, было=" + before.length() + ", стало=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + document.getLength() + ", совпадает=" + before.equals(xml)); //$NON-NLS-1$
            // #endregion
        };
    }

    /**
     * @param ancestor общий предок; {@code null} — двустороннее сравнение
     * @param result результат объединения; {@code null} — двустороннее окно «Сравнение текста»
     * @param writer запись XML правленого документа (результата, а без него — основной стороны)
     *     по «ОК»: в узел плана объединения и/или штатный просмотрщик; {@code null} — записать некуда
     */
    public static void open(Shell parent, IDtProject dtProject, Side main, Side other, Side ancestor, Side result,
        Consumer<String> writer, Object key)
    {
        // Повторное открытие той же строки дерева сравнения активирует ранее открытое окно
        Shell existing = key != null ? OPEN_WINDOWS.get(key) : null;
        if (existing != null && !existing.isDisposed())
        {
            if (existing.getMinimized())
                existing.setMinimized(false);
            var hwnd = WinWindowActivator.hwndFromShell(existing);
            if (hwnd != null)
                WinWindowActivator.activateWindowOnUiThread(hwnd);
            else
                existing.forceActive();
            return;
        }
        tempLog("open: project=" + (dtProject != null ? dtProject.getName() : null) //$NON-NLS-1$
            + ", main=" + describe(main) + ", other=" + describe(other) //$NON-NLS-1$ //$NON-NLS-2$
            + ", ancestor=" + describe(ancestor) + ", result=" + describe(result) //$NON-NLS-1$ //$NON-NLS-2$
            + ", writer=" + (writer != null)); //$NON-NLS-1$

        IPresentationService presentationService = null;
        try
        {
            presentationService = ServiceAccess.get(IPresentationService.class);
        }
        catch (RuntimeException | LinkageError e)
        {
            tempLog("presentationService: исключение " + stackTrace(e)); //$NON-NLS-1$
        }

        Shell shell = new Shell(parent, SWT.SHELL_TRIM);
        // Не модальное: свойства области правятся в панели «Свойства» главного окна
        shell.setText(Global.withPluginWindowTitle(MENU_LABEL + templateName(main)));
        shell.setLayout(new GridLayout(1, false));
        if (key != null)
        {
            OPEN_WINDOWS.put(key, shell);
            shell.addDisposeListener(e -> OPEN_WINDOWS.remove(key, shell));
        }

        boolean threeWay = result != null;
        // Правится только результат объединения, а без него (двустороннее сравнение) — основная сторона
        boolean mainEditable = !threeWay;
        Session session = new Session(shell, dtProject, threeWay, writer);
        session.createNavigationBar(mainEditable);

        SashForm vertical = new SashForm(shell, SWT.VERTICAL);
        vertical.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite compareArea = new Composite(vertical, SWT.NONE);
        GridLayout compareLayout = new GridLayout(2, false);
        compareLayout.marginWidth = 0;
        compareLayout.marginHeight = 0;
        compareLayout.horizontalSpacing = 2;
        compareArea.setLayout(compareLayout);
        SashForm sides = new SashForm(compareArea, SWT.HORIZONTAL);
        sides.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        session.createRuler(compareArea);
        Pane mainPane = createPane(sides, main, dtProject, presentationService, mainEditable);
        Pane otherPane = createPane(sides, other, dtProject, presentationService, false);
        Pane ancestorPane = ancestor != null ? createPane(sides, ancestor, dtProject, presentationService, false) : null;
        Pane resultPane = null;
        if (threeWay)
        {
            resultPane = createPane(vertical, result, dtProject, presentationService, true);
            vertical.setWeights(1, 1);
        }
        List<Pane> created = new ArrayList<>();
        for (Pane pane : new Pane[] { mainPane, otherPane, ancestorPane, resultPane })
            if (pane != null)
                created.add(pane);
        shell.addDisposeListener(e -> created.forEach(Pane::disposeContext));

        Composite buttons = new Composite(shell, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        buttons.setLayout(new GridLayout(2, true));
        // Как в штатном окне «Сравнение текста»: «ОК» (доступна после изменений) и «Отмена»
        // шириной кнопок JFace (Dialog.initializeBounds/setButtonLayoutData)
        Button ok = createDialogButton(buttons, IDialogConstants.OK_LABEL);
        ok.addListener(SWT.Selection, e -> session.accept());
        ok.setEnabled(false);
        session.okButton = ok;
        Button cancel = createDialogButton(buttons, IDialogConstants.CANCEL_LABEL);
        cancel.addListener(SWT.Selection, e -> shell.close());
        // Без кнопки по умолчанию: Enter в таблице не должен закрывать окно

        session.init(mainPane, otherPane, ancestorPane, resultPane);

        Rectangle parentBounds = parent != null && !parent.isDisposed() ? parent.getBounds() : null;
        if (parentBounds != null)
        {
            // Окно не должно закрывать панель «Свойства»: она остаётся рядом и доступна для правки
            Rectangle sheet = Session.openSheetBounds();
            if (sheet != null && sheet.intersects(parentBounds))
            {
                int parentRight = parentBounds.x + parentBounds.width;
                int parentBottom = parentBounds.y + parentBounds.height;
                if (sheet.x >= parentBounds.x + parentBounds.width / 2)
                    parentBounds.width = Math.max(600, sheet.x - parentBounds.x);
                else if (sheet.x + sheet.width <= parentBounds.x + parentBounds.width / 2)
                {
                    parentBounds.x = sheet.x + sheet.width;
                    parentBounds.width = Math.max(600, parentRight - parentBounds.x);
                }
                else if (sheet.y >= parentBounds.y + parentBounds.height / 2)
                    parentBounds.height = Math.max(400, sheet.y - parentBounds.y);
                else
                    parentBounds.height = Math.max(400, parentBottom - (sheet.y + sheet.height));
            }
            shell.setBounds(parentBounds);
        }
        else
            shell.setSize(1200, 800);
        shell.open();
        tempLog("open: окно показано"); //$NON-NLS-1$
        shell.getDisplay().asyncExec(() -> {
            if (!shell.isDisposed())
                session.goToGroup(0);
        });
        // #region agent log — временный, #1015: состояние контролов после первой отрисовки
        shell.getDisplay().timerExec(700, () -> dumpControls(shell, "700ms")); //$NON-NLS-1$
        shell.getDisplay().timerExec(3000, () -> dumpControls(shell, "3000ms")); //$NON-NLS-1$
        // #endregion
    }

    /** Кнопка шириной не меньше штатной кнопки диалога JFace ({@link IDialogConstants#BUTTON_WIDTH}). */
    private static Button createDialogButton(Composite parent, String text)
    {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        GC gc = new GC(button);
        int minimum;
        try
        {
            gc.setFont(button.getFont());
            minimum = Dialog.convertHorizontalDLUsToPixels(gc.getFontMetrics(), IDialogConstants.BUTTON_WIDTH);
        }
        finally
        {
            gc.dispose();
        }
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = Math.max(minimum, button.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x);
        button.setLayoutData(data);
        return button;
    }

    // ------------------------------------------------------------------------------------------
    // Сторона

    /** Сторона окна: документ, контрол и текст ячеек для сравнения. */
    private static final class Pane
    {
        final String label;
        final SpreadsheetDocument document;
        final MarkedMoxelControl control;
        /** Частный контекст редактирования стороны (как в edt-agent-bridge): в модель проекта не сохраняется. */
        final IEditingContext context;
        final String fqn;
        final boolean editable;
        /** Строка заголовка стороны: подпись и (по требованию) тулбар команд стороны. */
        final Composite header;
        ToolBar toolBar;
        /** {@code text[row]} — {@code null}, если строки в документе нет; ячейка без содержимого — {@code null}. */
        String[][] text = new String[0][];
        /** {@code full[row][column]} — всё сравниваемое в ячейке, см. {@link #cell}; строки есть всегда. */
        String[][] full = new String[0][];
        int rowCount;
        int columnCount;
        int fixedRow;
        int fixedColumn;
        /** Отметки отличий: {@code marks[row]} — {@code null}, если в строке отличий нет. */
        byte[][] marks = new byte[0][];
        /** Строки, над которыми у других сторон есть строки, отсутствующие здесь. */
        final Set<Integer> gapRows = new HashSet<>();
        /** Колонки, левее которых у других сторон есть колонки, отсутствующие здесь. */
        final Set<Integer> gapColumns = new HashSet<>();
        /** {@code inColumnGroup[column]} — колонка входит в группу отличий по колонкам. */
        boolean[] inColumnGroup = new boolean[0];
        /** Активная ячейка, выставленная окном (переход, синхронизация сторон), а не пользователем. */
        Point programmaticCell;

        Pane(String label, SpreadsheetDocument document, MarkedMoxelControl control, IEditingContext context,
            String fqn, boolean editable, Composite header)
        {
            this.label = label;
            this.document = document;
            this.control = control;
            this.context = context;
            this.fqn = fqn;
            this.editable = editable;
            this.header = header;
        }

        /** Тулбар в правой части заголовка стороны — создаётся при первой команде. */
        ToolBar toolBar()
        {
            if (toolBar == null)
            {
                toolBar = new ToolBar(header, SWT.FLAT | SWT.RIGHT);
                toolBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
                ((GridLayout) header.getLayout()).numColumns = 2;
            }
            return toolBar;
        }

        void disposeContext()
        {
            try
            {
                if (!control.isDisposed() && control.isInplaceEdit())
                    control.finishInplaceEdit(true);
            }
            catch (RuntimeException e)
            {
                tempLog("[" + label + "] finishInplaceEdit при закрытии: исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!context.isDisposed())
            {
                OperationHistoryFactory.getOperationHistory().dispose(context.getUndoContext(), true, true, true);
                context.dispose();
            }
        }

        /** Текст ячеек для сравнения — в задаче чтения контекста стороны (документ — объект BM). */
        void readContent()
        {
            context.executeReadonlyTask(new AbstractBmTask<Void>("Комфорт: чтение табличного документа") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    readContent((SpreadsheetDocument) transaction.getTopObjectByFqn(fqn));
                    return null;
                }
            }, new NullProgressMonitor());
        }

        void resetMarks()
        {
            marks = new byte[rowCount][];
            gapRows.clear();
            gapColumns.clear();
            inColumnGroup = new boolean[columnCount];
        }

        /** Всё, что сравнивается в ячейке: содержимое, свойства, итоговое оформление, рисунки, объединение. */
        String cell(int row, int column)
        {
            if (row < 0 || row >= full.length || full[row] == null || column < 0 || column >= full[row].length)
                return null;
            return full[row][column];
        }

        /** Только содержимое ячейки — ключ сопоставления строк и колонок по закреплённой области. */
        String keyCell(int row, int column)
        {
            if (row < 0 || row >= text.length || text[row] == null || column < 0 || column >= text[row].length)
                return null;
            return text[row][column];
        }

        byte mark(int row, int column)
        {
            if (row < 0 || row >= marks.length || marks[row] == null || column < 0 || column >= marks[row].length)
                return MARK_NONE;
            return marks[row][column];
        }

        private void readContent(SpreadsheetDocument document)
        {
            SheetAccessor sheet = control.getSheet();
            int maxRow = Math.max(document.getHeight(), 0) - 1;
            int maxColumn = document.getColumns() != null ? document.getColumns().getSize() - 1 : -1;
            for (Map.Entry<Integer, Row> rowEntry : document.getRows())
            {
                maxRow = Math.max(maxRow, rowEntry.getKey());
                Row row = rowEntry.getValue();
                if (row != null)
                    for (Map.Entry<Integer, Cell> cellEntry : row.getCells())
                        maxColumn = Math.max(maxColumn, cellEntry.getKey());
            }
            // Рисунок сравнивается в ячейке своего левого верхнего угла
            Map<Point, List<String>> drawings = new HashMap<>();
            for (Drawing drawing : document.getDrawings())
            {
                SpreadsheetRect position = drawing.getPosition();
                com._1c.g5.v8.dt.mcore.Point begin = position != null && position.getBegin() != null
                    ? position.getBegin().getCell() : null;
                if (begin == null || begin.getX() < 0 || begin.getY() < 0)
                    continue;
                drawings.computeIfAbsent(new Point(begin.getX(), begin.getY()), key -> new ArrayList<>())
                    .add(drawingSignature(document, drawing));
                maxRow = Math.max(maxRow, begin.getY());
                maxColumn = Math.max(maxColumn, begin.getX());
            }
            // Объединение ячеек — в ячейке его левого верхнего угла
            for (Merge merge : document.getMerges())
            {
                Rect rect = merge.getPosition();
                if (rect == null || rect.normalX() < 0 || rect.normalY() < 0)
                    continue;
                drawings.computeIfAbsent(new Point(rect.normalX(), rect.normalY()), key -> new ArrayList<>())
                    .add("Merge " + rect.normalWidth() + 'x' + rect.normalHeight() + ' ' + merge.getAntimerge()); //$NON-NLS-1$
                maxRow = Math.max(maxRow, rect.normalY());
                maxColumn = Math.max(maxColumn, rect.normalX());
            }
            long started = System.nanoTime();
            rowCount = maxRow + 1;
            columnCount = maxColumn + 1;
            text = new String[rowCount][];
            full = new String[rowCount][];
            Map<String, String> formats = new HashMap<>();
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++)
            {
                // Ключ — Integer: EMap.get(int) берёт элемент по позиции
                Row row = document.getRows().get(Integer.valueOf(rowIndex));
                String[] cells = row != null ? new String[columnCount] : null;
                String[] fullCells = new String[columnCount];
                for (int column = 0; column < columnCount; column++)
                {
                    Cell cell = row != null ? row.getCells().get(Integer.valueOf(column)) : null;
                    String content = cellText(sheet, cell);
                    if (cells != null)
                        cells[column] = content;
                    String format = formatSignature(sheet, rowIndex, column, row, cell, formats);
                    fullCells[column] = (content != null ? content : "") + '\u0004' + format //$NON-NLS-1$
                        + '\u0004' + cellAttributes(cell);
                }
                text[rowIndex] = cells;
                full[rowIndex] = fullCells;
            }
            for (Map.Entry<Point, List<String>> entry : drawings.entrySet())
            {
                int row = entry.getKey().y;
                int column = entry.getKey().x;
                List<String> signatures = entry.getValue();
                signatures.sort(null);
                full[row][column] += '\u0003' + String.join("\u0003", signatures); //$NON-NLS-1$
            }
            tempLog("attributes: [" + label + "] rows=" + rowCount + " cols=" + columnCount + " formats=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + formats.size() + " drawings+merges=" + drawings.size() + " ms=" //$NON-NLS-1$ //$NON-NLS-2$
                + (System.nanoTime() - started) / 1_000_000);
            // #region agent log — временный, #1015: наборы колонок строк (ширина колонки берётся из набора строки)
            try
            {
                Map<Object, StringBuilder> sets = new java.util.LinkedHashMap<>();
                for (int rowIndex = 0; rowIndex < rowCount; rowIndex++)
                {
                    Row row = document.getRows().get(Integer.valueOf(rowIndex));
                    Columns rowColumns = sheet.getRowColumns(rowIndex);
                    String own = row == null ? "нет строки" : row.getColumns() == null ? "строка без набора" : "свой"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    sets.computeIfAbsent(rowColumns, key -> new StringBuilder()).append(rowIndex).append('(').append(own)
                        .append(") "); //$NON-NLS-1$
                }
                StringBuilder log = new StringBuilder("column-sets: [").append(label).append("] default=") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(document.getColumns() != null ? document.getColumns().getColumnsId() : null);
                for (Map.Entry<Object, StringBuilder> entry : sets.entrySet())
                {
                    Columns set = (Columns) entry.getKey();
                    log.append("\n  набор ").append(set != null ? set.getColumnsId() : null).append(" size=") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(set != null ? set.getSize() : -1).append(" ширины="); //$NON-NLS-1$
                    for (int column = 0; set != null && column < Math.min(columnCount, 24); column++)
                        log.append(sheet.getColumnWidthCharUnits(set, column)).append(',');
                    log.append(" строки: ").append(entry.getValue()); //$NON-NLS-1$
                }
                tempLog(log.toString());
            }
            catch (RuntimeException e)
            {
                tempLog("column-sets: [" + label + "] исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // #endregion
            fixedRow = control.getFixedRow();
            fixedColumn = control.getFixedColumn();
            resetMarks();
        }

        /** Свойства ячейки кроме формата: тексты на всех языках, значение, расшифровка, примечание, элемент управления. */
        private static String cellAttributes(Cell cell)
        {
            if (cell == null)
                return ""; //$NON-NLS-1$
            StringBuilder builder = new StringBuilder();
            appendFeatures(builder, "", cell, 3); //$NON-NLS-1$
            // Ячейка без единого свойства (её создаёт вставка) — то же, что отсутствующая ячейка
            return "{}".equals(builder.toString()) ? "" : builder.toString(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Итоговое оформление ячейки, как его видит EDT ({@code SheetAccessor.getCellFullFormat}: формат по
         * умолчанию, колонки, строки и ячейки). Все свойства — с учётом значений по умолчанию; индексы шрифта,
         * линий, цветов и картинки заменены самими значениями: в разных документах индексы разные.
         */
        private static String formatSignature(SheetAccessor sheet, int row, int column, Row rowObject, Cell cell,
            Map<String, String> cache)
        {
            if (sheet == null)
                return ""; //$NON-NLS-1$
            String key = (rowObject != null ? rowObject.getFormatIndex() : -2) + "|" //$NON-NLS-1$
                + (cell != null ? cell.getFormatIndex() : -2) + "|" + sheet.lookupColFormatIndex(row, column); //$NON-NLS-1$
            return cache.computeIfAbsent(key, k -> {
                Format format = sheet.getCellFullFormat(row, column, false);
                StringBuilder builder = new StringBuilder();
                for (EStructuralFeature feature : format.eClass().getEAllStructuralFeatures())
                {
                    if (feature.isTransient() || feature.isDerived())
                        continue;
                    String name = feature.getName();
                    Object value = format.eGet(feature);
                    builder.append(name).append('=');
                    if (feature instanceof EReference)
                    {
                        if (value instanceof EObject child && format.eIsSet(feature))
                            appendFeatures(builder, "", child, 2); //$NON-NLS-1$
                    }
                    else if (value instanceof Integer index && FORMAT_REFERENCES.contains(name))
                        appendResolved(builder, sheet, name, index);
                    else
                        builder.append(value);
                    builder.append(';');
                }
                return builder.toString();
            });
        }

        private static final Set<String> FORMAT_REFERENCES = Set.of("font", "leftBorder", "topBorder", "rightBorder", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "bottomBorder", "drawingBorder", "borderColor", "textColor", "backColor", "patternColor", "pictureIndex"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

        private static void appendResolved(StringBuilder builder, SheetAccessor sheet, String name, int index)
        {
            if (index < 0)
            {
                builder.append("none"); //$NON-NLS-1$
                return;
            }
            try
            {
                Object resolved = switch (name)
                {
                    case "font" -> sheet.getFontByKey(index); //$NON-NLS-1$
                    case "pictureIndex" -> sheet.getPictureByIndex(index); //$NON-NLS-1$
                    case "borderColor", "textColor", "backColor", "patternColor" -> sheet.getColorByIndex(index); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    default -> sheet.getLineByKey(index);
                };
                if (resolved instanceof EObject object)
                    appendFeatures(builder, "", object, 2); //$NON-NLS-1$
                else
                    builder.append(resolved);
            }
            catch (RuntimeException e)
            {
                builder.append('?').append(index);
            }
        }

        /** Текст для сравнения: отображаемый текст, параметр и параметр расшифровки. */
        private static String cellText(SheetAccessor sheet, Cell cell)
        {
            if (cell == null)
                return null;
            String shown = null;
            try
            {
                shown = sheet != null ? sheet.getCellText(cell) : null;
            }
            catch (RuntimeException e)
            {
                // Текст недоступен — сравниваются параметры.
            }
            String parameter = cell.getParameter();
            String detail = cell.getDetailParameter();
            if (isEmpty(shown) && isEmpty(parameter) && isEmpty(detail))
                return null;
            return (shown != null ? shown : "") + '\u0001' + (parameter != null ? parameter : "") //$NON-NLS-1$ //$NON-NLS-2$
                + '\u0001' + (detail != null ? detail : ""); //$NON-NLS-1$
        }

        /**
         * Вид рисунка, размер в ячейках, смещения углов, его свойства и оформление; без номеров
         * (идентификатор, порядок по Z, индексы формата и картинки разные в разных документах).
         */
        private static String drawingSignature(SpreadsheetDocument document, Drawing drawing)
        {
            StringBuilder builder = new StringBuilder(drawing.eClass().getName());
            SpreadsheetRect position = drawing.getPosition();
            if (position != null && position.getBegin() != null && position.getEnd() != null)
            {
                com._1c.g5.v8.dt.mcore.Point begin = position.getBegin().getCell();
                com._1c.g5.v8.dt.mcore.Point end = position.getEnd().getCell();
                if (begin != null && end != null)
                    builder.append(" size=").append(end.getX() - begin.getX()).append('x').append(end.getY() - begin.getY()); //$NON-NLS-1$
                appendFeatures(builder, " from", position.getBegin().getOffset(), 1); //$NON-NLS-1$
                appendFeatures(builder, " to", position.getEnd().getOffset(), 1); //$NON-NLS-1$
            }
            appendFeatures(builder, "", drawing, 2); //$NON-NLS-1$
            int format = drawing.getFormatIndex();
            if (format >= 0 && format < document.getFormats().size())
                appendFeatures(builder, " format", document.getFormats().get(format), 1); //$NON-NLS-1$
            return builder.toString();
        }

        private static final Set<String> DRAWING_SKIPPED_FEATURES =
            Set.of("drawingId", "formatIndex", "zOrder", "pictureIndex", "position", "object"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        private static void appendFeatures(StringBuilder builder, String prefix, EObject object, int depth)
        {
            if (object == null)
                return;
            builder.append(prefix).append('{');
            for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
            {
                if (feature.isTransient() || feature.isDerived() || !object.eIsSet(feature)
                    || DRAWING_SKIPPED_FEATURES.contains(feature.getName()))
                    continue;
                Object value = object.eGet(feature);
                if (value instanceof byte[] bytes)
                    builder.append(feature.getName()).append("=#").append(bytes.length).append('/') //$NON-NLS-1$
                        .append(java.util.Arrays.hashCode(bytes)).append(';');
                else if (feature instanceof EAttribute)
                    builder.append(feature.getName()).append('=').append(value).append(';');
                else if (feature instanceof EReference reference && reference.isContainment() && depth > 0)
                {
                    if (value instanceof EObject child)
                        appendFeatures(builder, feature.getName(), child, depth - 1);
                    else if (value instanceof List<?> list)
                        for (Object item : list)
                            if (item instanceof EObject child)
                                appendFeatures(builder, feature.getName(), child, depth - 1);
                }
            }
            builder.append('}');
        }

        /** Только содержимое строки — для сопоставления строк, когда ключа из закреплённых колонок нет. */
        String contentRowSignature(int row)
        {
            String[] cells = row >= 0 && row < text.length ? text[row] : null;
            if (cells == null)
                return "S"; //$NON-NLS-1$
            StringBuilder builder = new StringBuilder("S"); //$NON-NLS-1$
            int last = cells.length - 1;
            while (last >= 0 && cells[last] == null)
                last--;
            for (int i = 0; i <= last; i++)
                builder.append(cells[i] != null ? cells[i] : "").append('\u0002'); //$NON-NLS-1$
            return builder.toString();
        }

        String columnSignature(int column)
        {
            StringBuilder builder = new StringBuilder("S"); //$NON-NLS-1$
            for (int row = 0; row < rowCount; row++)
            {
                String value = keyCell(row, column);
                if (value != null)
                    builder.append(row).append('=').append(value).append('\u0002');
            }
            return builder.toString();
        }
    }

    private static boolean isEmpty(String value)
    {
        return value == null || value.isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Сопоставление

    /** Сопоставление одной оси (строк или колонок) сторон в общую («объединённую») нумерацию. */
    private static final class Axis
    {
        /** {@code index[pane][u]} — номер у стороны или {@code -1}, если у стороны его нет. */
        final int[][] index;
        /** {@code unified[pane][i]} — объединённый номер. */
        final int[][] unified;
        final int size;

        Axis(int[][] index, int[][] unified, int size)
        {
            this.index = index;
            this.unified = unified;
            this.size = size;
        }

        /** Номер у стороны {@code to}, соответствующий номеру {@code value} у стороны {@code from}. */
        int map(int from, int to, int value)
        {
            if (value < 0 || value >= unified[from].length)
                return value;
            int u = unified[from][value];
            for (int k = u; k < size; k++)
                if (index[to][k] >= 0)
                    return index[to][k];
            for (int k = u - 1; k >= 0; k--)
                if (index[to][k] >= 0)
                    return index[to][k];
            return 0;
        }

        /**
         * @param keys ключи сторон: две стороны — {@code [левая, правая]}; три —
         *     {@code [левая, правая, предок]}
         */
        static Axis align(String[][] keys)
        {
            int paneCount = keys.length;
            KeyComparator left = new KeyComparator(keys[0]);
            KeyComparator right = new KeyComparator(keys[1]);
            RangeDifference[] ranges = paneCount == 3
                ? RangeDifferencer.findRanges(new KeyComparator(keys[2]), left, right)
                : RangeDifferencer.findRanges(left, right);
            List<int[]> rows = new ArrayList<>();
            for (RangeDifference range : ranges)
            {
                int[] starts = { range.leftStart(), range.rightStart(), paneCount == 3 ? range.ancestorStart() : 0 };
                int[] lengths = { range.leftLength(), range.rightLength(), paneCount == 3 ? range.ancestorLength() : 0 };
                int length = 0;
                for (int p = 0; p < paneCount; p++)
                    length = Math.max(length, lengths[p]);
                for (int k = 0; k < length; k++)
                {
                    int[] entry = new int[paneCount];
                    for (int p = 0; p < paneCount; p++)
                        entry[p] = k < lengths[p] ? starts[p] + k : -1;
                    rows.add(entry);
                }
            }
            int size = rows.size();
            int[][] index = new int[paneCount][size];
            int[][] unified = new int[paneCount][];
            for (int p = 0; p < paneCount; p++)
                unified[p] = new int[keys[p].length];
            for (int u = 0; u < size; u++)
                for (int p = 0; p < paneCount; p++)
                {
                    int value = rows.get(u)[p];
                    index[p][u] = value;
                    if (value >= 0)
                        unified[p][value] = u;
                }
            return new Axis(index, unified, size);
        }
    }

    private static final class KeyComparator implements IRangeComparator
    {
        private final String[] keys;

        KeyComparator(String[] keys)
        {
            this.keys = keys;
        }

        @Override
        public int getRangeCount()
        {
            return keys.length;
        }

        @Override
        public boolean rangesEqual(int thisIndex, IRangeComparator other, int otherIndex)
        {
            return other instanceof KeyComparator comparator
                && Objects.equals(keys[thisIndex], comparator.keys[otherIndex]);
        }

        @Override
        public boolean skipRangeComparison(int length, int maxLength, IRangeComparator other)
        {
            return false;
        }
    }

    /**
     * Группа подряд идущих отличающихся объединённых строк, а при {@code columns} — колонок, которых нет
     * у части сторон (добавленные/удалённые колонки переносятся колонками целиком, с шириной и форматом).
     */
    private record Group(int first, int last, boolean conflict, boolean columns)
    {
    }

    /** Как вставляется кусок в правимую сторону. */
    private enum PieceKind
    {
        /** Строки или колонки целиком — во вставленные строки (колонки). */
        WHOLE,
        /** Только формат колонок (ширина и пр.) — во вставленные колонки, без ячеек. */
        COLUMN_FORMAT,
        /**
         * Ячейки: у группы строк — с колонки {@code targetColumn} во вставленные строки; у группы колонок —
         * со строки {@code targetRow} во вставленные колонки.
         */
        CELLS
    }

    /** Скопированный штатным копированием кусок стороны-источника. */
    private record Piece(PieceKind kind, int targetColumn, int targetRow, int width, int height,
        SpreadsheetDocument copy, MoxelDescriptor descriptor)
    {
    }

    /** Перенос группы: строки (колонки) источника заменяют {@code targetCount} строк (колонок) правимой стороны. */
    private record Fragment(int group, boolean columns, int targetStart, int targetCount, int count,
        List<Piece> pieces)
    {
    }

    // ------------------------------------------------------------------------------------------
    // Сеанс окна

    private static final class Session
    {
        private final Shell shell;
        private final IDtProject project;
        private final boolean threeWay;
        private final Consumer<String> writer;
        /** Сравниваемые стороны: основная, другая, [предок]. */
        private final List<Pane> panes = new ArrayList<>();
        private Pane resultPane;
        /** Правимая сторона, в которую копируются изменения: результат, а без него — основная. */
        private Pane target;
        private Axis rows;
        private Axis columns;
        /** Сопоставление результата с основной стороной: стороны {@code [основная, результат]}. */
        private Axis resultRows;
        private Axis resultColumns;
        private final List<Group> groups = new ArrayList<>();
        /** Строки правимой стороны, занятые группой: {@code [targetStart..targetEnd]}, пусто при {@code end < start}. */
        private int[] targetStart = new int[0];
        private int[] targetEnd = new int[0];
        /** Трёхстороннее: чья версия группы в результате ({@code WIN_*}). */
        private byte[] winners = new byte[0];
        private boolean[] mainChanged = new boolean[0];
        private boolean[] otherChanged = new boolean[0];
        private boolean[] resultEqualsMain = new boolean[0];
        private boolean[] resultEqualsOther = new boolean[0];
        private int currentGroup = -1;
        private boolean syncing;
        /** Правимая сторона изменена после открытия окна (слушатель контекста). */
        private volatile boolean changed;
        private boolean refreshPending;
        private boolean closeConfirmed;
        private Label status;
        /** «ОК» доступна, когда есть что перенести (как в штатном окне сравнения). */
        Button okButton;
        private ToolItem previous;
        private ToolItem next;
        private final List<ToolItem> copyCurrentItems = new ArrayList<>();
        private final List<ToolItem> copyAllItems = new ArrayList<>();
        private Canvas ruler;
        private Color changedColor;
        private Color addedColor;
        private Color conflictColor;
        private Color frameColor;

        Session(Shell shell, IDtProject project, boolean threeWay, Consumer<String> writer)
        {
            this.shell = shell;
            this.project = project;
            this.threeWay = threeWay;
            this.writer = writer;
        }

        /**
         * @param copyRightToLeft двустороннее окно с правимой основной стороной: кнопки копирования
         *     справа налево, как в тулбаре сравнения текста Eclipse Compare
         */
        void createNavigationBar(boolean copyRightToLeft)
        {
            Composite bar = new Composite(shell, SWT.NONE);
            bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            GridLayout layout = new GridLayout(2, false);
            layout.marginWidth = 2;
            layout.marginHeight = 0;
            bar.setLayout(layout);
            // Значки — штатные Eclipse Compare, как в окне сравнения текста
            // SWT.RIGHT — надпись справа от значка (у кнопки «Синхронизировать»)
            ToolBar toolBar = new ToolBar(bar, SWT.FLAT | SWT.RIGHT);
            if (copyRightToLeft)
            {
                copyAllItems.add(addTool(toolBar, ownedImage(toolBar, compareIcon("copy_l_co")), "⇐", //$NON-NLS-1$ //$NON-NLS-2$
                    "Копировать все изменения, в которых нет конфликтов, справа налево", //$NON-NLS-1$
                    () -> copyAll(1)));
                ToolItem sync = addTool(toolBar, ownedImage(toolBar, compareIcon("copycont_l_co")), "←", //$NON-NLS-1$ //$NON-NLS-2$
                    "Копировать текущее изменение справа налево", //$NON-NLS-1$
                    () -> copyCurrent(1));
                sync.setText("Синхронизировать"); //$NON-NLS-1$
                copyCurrentItems.add(sync);
            }
            next = addTool(toolBar, ownedImage(toolBar, compareIcon("next_diff_nav")), "▼", //$NON-NLS-1$ //$NON-NLS-2$
                "Следующее отличие (Ctrl+.)", () -> step(1, g -> true)); //$NON-NLS-1$
            previous = addTool(toolBar, ownedImage(toolBar, compareIcon("prev_diff_nav")), "▲", //$NON-NLS-1$ //$NON-NLS-2$
                "Предыдущее отличие (Ctrl+,)", () -> step(-1, g -> true)); //$NON-NLS-1$
            status = new Label(bar, SWT.NONE);
            status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Display display = shell.getDisplay();
            changedColor = new Color(display, new RGB(0, 120, 215));
            addedColor = new Color(display, new RGB(0, 170, 0));
            conflictColor = new Color(display, new RGB(225, 0, 0));
            frameColor = new Color(display, new RGB(255, 140, 0));

            Listener keys = event -> {
                if (!(event.widget instanceof Control control) || control.isDisposed() || control.getShell() != shell)
                    return;
                if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && event.stateMask == 0)
                {
                    // Enter — выход из ввода в ячейку с фиксацией (штатный finishInplaceEdit(false))
                    for (Pane pane : allPanes())
                        if (!pane.control.isDisposed() && pane.control.isInplaceEdit())
                        {
                            event.doit = false;
                            pane.control.finishInplaceEdit(false);
                            // #region agent log — временный, #1015: фиксация ввода по Enter
                            tempLog("enter: [" + pane.label + "] после finishInplaceEdit(false) isInplaceEdit=" //$NON-NLS-1$ //$NON-NLS-2$
                                + pane.control.isInplaceEdit() + ", contextDirty=" + pane.context.isDirty()); //$NON-NLS-1$
                            // #endregion
                            return;
                        }
                    return;
                }
                if (isEditing())
                    return;
                if (handleMergeKeys(event))
                {
                    event.doit = false;
                    return;
                }
                if ((event.stateMask & SWT.MOD1) == 0)
                    return;
                if ((event.stateMask & SWT.MODIFIER_MASK) == SWT.MOD1 && (event.keyCode == 'c' || event.keyCode == SWT.INSERT)
                    && !isEditing())
                {
                    // Запасной путь Ctrl+C, если привязка клавиш EDT в этом окне не сработала
                    for (Pane pane : allPanes())
                        if (!pane.control.isDisposed() && isInside(event.widget, pane.control))
                        {
                            tempLog("ctrl+c: KeyDown дошёл до фильтра окна"); //$NON-NLS-1$
                            copySelection(pane);
                            event.doit = false;
                            return;
                        }
                    return;
                }
                if (event.character == '.' || event.keyCode == '.')
                {
                    step(1, g -> true);
                    event.doit = false;
                }
                else if (event.character == ',' || event.keyCode == ',')
                {
                    step(-1, g -> true);
                    event.doit = false;
                }
            };
            // Escape: сначала отмена ввода в ячейку, затем закрытие окна с подтверждением
            Listener escape = event -> {
                if (event.detail == SWT.TRAVERSE_RETURN && event.widget instanceof Control returnControl
                    && !returnControl.isDisposed() && returnControl.getShell() == shell)
                {
                    // Enter в поле ввода ячейки приходит обходом раньше нажатия клавиши
                    for (Pane pane : allPanes())
                        if (!pane.control.isDisposed() && pane.control.isInplaceEdit())
                        {
                            event.doit = false;
                            event.detail = SWT.TRAVERSE_NONE;
                            pane.control.finishInplaceEdit(false);
                            // #region agent log — временный, #1015: фиксация ввода по Enter
                            tempLog("enter(traverse): [" + pane.label + "] после finishInplaceEdit(false) isInplaceEdit=" //$NON-NLS-1$ //$NON-NLS-2$
                                + pane.control.isInplaceEdit() + ", contextDirty=" + pane.context.isDirty()); //$NON-NLS-1$
                            // #endregion
                            return;
                        }
                    return;
                }
                if (event.detail != SWT.TRAVERSE_ESCAPE || !(event.widget instanceof Control control)
                    || control.isDisposed() || control.getShell() != shell)
                    return;
                event.doit = false;
                event.detail = SWT.TRAVERSE_NONE;
                for (Pane pane : allPanes())
                    if (!pane.control.isDisposed() && pane.control.isInplaceEdit())
                    {
                        pane.control.finishInplaceEdit(true);
                        return;
                    }
                display.asyncExec(() -> {
                    if (shell.isDisposed())
                        return;
                    if (MessageDialog.openQuestion(shell, Global.withPluginWindowTitle(MENU_LABEL),
                        changed ? "Закрыть окно табличного объединения? Изменения не будут перенесены." //$NON-NLS-1$
                            : "Закрыть окно табличного объединения?")) //$NON-NLS-1$
                    {
                        closeConfirmed = true;
                        shell.close();
                    }
                });
            };
            display.addFilter(SWT.KeyDown, keys);
            display.addFilter(SWT.Traverse, escape);
            // Закрытие крестиком при перенесённых изменениях — с подтверждением
            shell.addListener(SWT.Close, event -> {
                if (closeConfirmed || !changed)
                    return;
                event.doit = MessageDialog.openQuestion(shell, Global.withPluginWindowTitle(MENU_LABEL),
                    "Закрыть окно табличного объединения? Изменения не будут перенесены."); //$NON-NLS-1$
            });
            shell.addDisposeListener(e -> {
                display.removeFilter(SWT.KeyDown, keys);
                display.removeFilter(SWT.Traverse, escape);
                changedColor.dispose();
                addedColor.dispose();
                conflictColor.dispose();
                frameColor.dispose();
            });
        }

        private static boolean isInside(Widget widget, Control parent)
        {
            for (Control control = widget instanceof Control c ? c : null; control != null; control = control.getParent())
                if (control == parent)
                    return true;
            return false;
        }

        private boolean isEditing()
        {
            for (Pane pane : allPanes())
                if (!pane.control.isDisposed() && pane.control.isInplaceEdit())
                    return true;
            return false;
        }

        /** Сочетания штатного {@code ThreeSideTextMergeViewer} EDT (трёхстороннее окно). */
        private boolean handleMergeKeys(Event event)
        {
            if (!threeWay)
                return false;
            int modifiers = event.stateMask & SWT.MODIFIER_MASK;
            if ((event.keyCode == SWT.F9 || event.keyCode == SWT.F10) && modifiers == 0)
            {
                step(event.keyCode == SWT.F9 ? 1 : -1, this::isConflict);
                return true;
            }
            boolean plus = event.character == '+' || event.keyCode == SWT.KEYPAD_ADD
                || event.keyCode == '=' && (modifiers & SWT.SHIFT) != 0;
            boolean ctrl = (modifiers & SWT.MOD1) != 0;
            boolean alt = (modifiers & SWT.ALT) != 0;
            if (plus && ctrl && !alt)
            {
                copyCurrent(0);
                return true;
            }
            if (plus && alt && !ctrl)
            {
                copyCurrent(1);
                return true;
            }
            if (event.keyCode != SWT.ARROW_DOWN && event.keyCode != SWT.ARROW_UP)
                return false;
            int direction = event.keyCode == SWT.ARROW_DOWN ? 1 : -1;
            if (modifiers == SWT.MOD1)
                step(direction, g -> !resultEqualsMain[g]);
            else if (modifiers == (SWT.MOD1 | SWT.SHIFT))
                step(direction, g -> mainChanged[g]);
            else if (modifiers == SWT.ALT)
                step(direction, g -> !resultEqualsOther[g]);
            else if (modifiers == (SWT.ALT | SWT.SHIFT))
                step(direction, g -> otherChanged[g]);
            else
                return false;
            return true;
        }

        private boolean isConflict(int group)
        {
            return groups.get(group).conflict();
        }

        /**
         * Тулбары сторон и результата как у штатного трёхстороннего окна EDT: значки
         * {@link CompareUiSharedImages}, подписи — штатные.
         */
        private void createMergeToolBars()
        {
            Pane main = panes.get(0);
            Pane other = panes.get(1);
            if (main.header != null)
            {
                ToolBar bar = main.toolBar();
                copyAllItems.add(addTool(bar, edtIcon(CompareUiSharedImages.IMG_COPY_ALL_FROM_LEFT), "⇒", //$NON-NLS-1$
                    "Копировать все изменения слева", () -> copyAll(0))); //$NON-NLS-1$
                copyCurrentItems.add(addTool(bar, edtIcon(CompareUiSharedImages.IMG_COPY_DIFF_FROM_LEFT), "→", //$NON-NLS-1$
                    "Копировать текущее изменение слева (Ctrl++)", () -> copyCurrent(0))); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_NEXT_DIFF), "▼", //$NON-NLS-1$
                    "Следующее изменение слева (Ctrl+↓)", () -> step(1, g -> !resultEqualsMain[g])); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_PREV_DIFF), "▲", //$NON-NLS-1$
                    "Предыдущее изменение слева (Ctrl+↑)", () -> step(-1, g -> !resultEqualsMain[g])); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_NEXT_CHANGE), "▽", //$NON-NLS-1$
                    "Следующее отличие слева (Ctrl+Shift+↓)", () -> step(1, g -> mainChanged[g])); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_PREV_CHANGE), "△", //$NON-NLS-1$
                    "Предыдущее отличие слева (Ctrl+Shift+↑)", () -> step(-1, g -> mainChanged[g])); //$NON-NLS-1$
                main.header.layout(true);
            }
            if (other.header != null)
            {
                ToolBar bar = other.toolBar();
                copyCurrentItems.add(addTool(bar, edtIcon(CompareUiSharedImages.IMG_COPY_DIFF_FROM_RIGHT), "←", //$NON-NLS-1$
                    "Копировать текущее изменение справа (Alt++)", () -> copyCurrent(1))); //$NON-NLS-1$
                copyAllItems.add(addTool(bar, edtIcon(CompareUiSharedImages.IMG_COPY_ALL_FROM_RIGHT), "⇐", //$NON-NLS-1$
                    "Копировать все изменения справа", () -> copyAll(1))); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_NEXT_DIFF), "▼", //$NON-NLS-1$
                    "Следующее изменение справа (Alt+↓)", () -> step(1, g -> !resultEqualsOther[g])); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_PREV_DIFF), "▲", //$NON-NLS-1$
                    "Предыдущее изменение справа (Alt+↑)", () -> step(-1, g -> !resultEqualsOther[g])); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_NEXT_CHANGE), "▽", //$NON-NLS-1$
                    "Следующее отличие справа (Alt+Shift+↓)", () -> step(1, g -> otherChanged[g])); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_PREV_CHANGE), "△", //$NON-NLS-1$
                    "Предыдущее отличие справа (Alt+Shift+↑)", () -> step(-1, g -> otherChanged[g])); //$NON-NLS-1$
                other.header.layout(true);
            }
            if (resultPane != null && resultPane.header != null)
            {
                ToolBar bar = resultPane.toolBar();
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_NEXT_CONFLICT), "▼", //$NON-NLS-1$
                    "Следующее конфликтное изменение (F9)", () -> step(1, this::isConflict)); //$NON-NLS-1$
                addTool(bar, edtIcon(CompareUiSharedImages.IMG_PREV_CONFLICT), "▲", //$NON-NLS-1$
                    "Предыдущее конфликтное изменение (F10)", () -> step(-1, this::isConflict)); //$NON-NLS-1$
                resultPane.header.layout(true);
            }
        }

        private static ToolItem addTool(ToolBar toolBar, Image image, String fallback, String tooltip, Runnable action)
        {
            ToolItem item = new ToolItem(toolBar, SWT.PUSH);
            if (image != null)
                item.setImage(image);
            else
                item.setText(fallback);
            item.setToolTipText(TooltipText.wrap(toolBar, tooltip));
            item.addListener(SWT.Selection, e -> action.run());
            return item;
        }

        /** Значок EDT из общего реестра — освобождать не нужно. */
        private static Image edtIcon(String key)
        {
            try
            {
                return CompareUiSharedImages.getImage(key);
            }
            catch (RuntimeException | LinkageError e)
            {
                tempLog("edtIcon: " + key + " исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }

        private static Image compareIcon(String name)
        {
            ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin("org.eclipse.compare", //$NON-NLS-1$
                "icons/full/elcl16/" + name + ".png"); //$NON-NLS-1$ //$NON-NLS-2$
            return descriptor != null ? descriptor.createImage(false) : null;
        }

        /** Значок, созданный окном, освобождается вместе с тулбаром. */
        private static Image ownedImage(ToolBar toolBar, Image image)
        {
            if (image != null)
                toolBar.addDisposeListener(e -> image.dispose());
            return image;
        }

        private List<Pane> allPanes()
        {
            List<Pane> result = new ArrayList<>(panes);
            if (resultPane != null)
                result.add(resultPane);
            return result;
        }

        /**
         * Обзорная линейка справа от сторон, как у сравнения текста: группы отличий во всю высоту
         * документа (по объединённым строкам), щелчок — переход к группе.
         */
        void createRuler(Composite parent)
        {
            ruler = new Canvas(parent, SWT.DOUBLE_BUFFERED);
            GridData data = new GridData(SWT.FILL, SWT.FILL, false, true);
            data.widthHint = 14;
            ruler.setLayoutData(data);
            ruler.addListener(SWT.Paint, e -> paintRuler(e.gc));
            ruler.addListener(SWT.MouseDown, e -> {
                if (e.button != 1)
                    return;
                int group = groupAtRulerY(e.y);
                if (group >= 0)
                    goToGroup(group);
            });
            ruler.addListener(SWT.MouseMove, e -> {
                int group = groupAtRulerY(e.y);
                ruler.setCursor(group >= 0 ? ruler.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
                String tip = group >= 0 ? "Отличие " + (group + 1) + " из " + groups.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + (groups.get(group).conflict() ? ", конфликт" : "") + winnerText(group) : null; //$NON-NLS-1$ //$NON-NLS-2$
                if (!Objects.equals(tip, ruler.getToolTipText()))
                    ruler.setToolTipText(tip);
            });
        }

        /**
         * Прямоугольник группы на линейке: не ниже 3 px, чтобы одиночная строка была видна. Группы колонок
         * (затрагивают все строки) — полосами сверху, по порядку.
         */
        private Rectangle rulerRect(Group group, Rectangle area)
        {
            if (group.columns())
            {
                int index = groups.indexOf(group);
                return new Rectangle(area.x + 2, area.y + 1 + 5 * Math.max(index, 0), Math.max(area.width - 4, 1), 3);
            }
            int total = Math.max(rows != null ? rows.size : 0, 1);
            int y1 = area.y + (int) ((long) area.height * group.first() / total);
            int y2 = area.y + (int) ((long) area.height * (group.last() + 1) / total);
            return new Rectangle(area.x + 2, y1, Math.max(area.width - 4, 1), Math.max(y2 - y1, 3));
        }

        private int groupAtRulerY(int y)
        {
            if (ruler == null || ruler.isDisposed() || groups.isEmpty())
                return -1;
            Rectangle area = ruler.getClientArea();
            int nearest = -1;
            int nearestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < groups.size(); i++)
            {
                Rectangle rect = rulerRect(groups.get(i), area);
                int distance = y < rect.y ? rect.y - y : y >= rect.y + rect.height ? y - (rect.y + rect.height) + 1 : 0;
                if (distance < nearestDistance)
                {
                    nearestDistance = distance;
                    nearest = i;
                }
            }
            return nearestDistance <= 2 ? nearest : -1;
        }

        private void paintRuler(GC gc)
        {
            Rectangle area = ruler.getClientArea();
            gc.setBackground(ruler.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(area);
            for (int i = 0; i < groups.size(); i++)
            {
                Group group = groups.get(i);
                Rectangle rect = rulerRect(group, area);
                gc.setBackground(group.conflict() ? conflictColor : changedColor);
                gc.fillRectangle(rect);
                // Чья версия в результате: полоса слева — левая сторона, справа — правая
                byte winner = winner(i);
                gc.setBackground(addedColor);
                if (winner == WIN_LEFT || winner == WIN_BOTH)
                    gc.fillRectangle(rect.x, rect.y, Math.min(3, rect.width), rect.height);
                if (winner == WIN_RIGHT || winner == WIN_BOTH)
                    gc.fillRectangle(rect.x + Math.max(rect.width - 3, 0), rect.y, Math.min(3, rect.width), rect.height);
                if (i == currentGroup)
                {
                    gc.setForeground(frameColor);
                    gc.setLineWidth(2);
                    gc.drawRectangle(rect.x - 1, rect.y - 1, rect.width + 1, rect.height + 1);
                    gc.setLineWidth(1);
                }
            }
        }

        /** {@code -1} — победитель не определяется (двустороннее окно). */
        private byte winner(int group)
        {
            return threeWay && group >= 0 && group < winners.length ? winners[group] : -1;
        }

        private String winnerText(int group)
        {
            return switch (winner(group))
            {
                case WIN_LEFT -> "; в результате — слева"; //$NON-NLS-1$
                case WIN_RIGHT -> "; в результате — справа"; //$NON-NLS-1$
                case WIN_BOTH -> "; в результате — слева и справа одинаково"; //$NON-NLS-1$
                case WIN_ANCESTOR -> "; в результате — как в предке"; //$NON-NLS-1$
                case WIN_MANUAL -> "; в результате — изменено вручную"; //$NON-NLS-1$
                default -> ""; //$NON-NLS-1$
            };
        }

        void init(Pane main, Pane other, Pane ancestor, Pane result)
        {
            if (main == null || other == null)
            {
                updateStatus("Сравнение недоступно: не удалось показать стороны"); //$NON-NLS-1$
                return;
            }
            panes.add(main);
            panes.add(other);
            if (ancestor != null)
                panes.add(ancestor);
            resultPane = result;
            target = resultPane != null ? resultPane : main.editable ? main : null;
            sheetPane = target != null ? target : main;
            try
            {
                for (Pane pane : allPanes())
                    pane.readContent();
                recompute();
            }
            catch (RuntimeException | LinkageError e)
            {
                tempLog("compare: исключение " + stackTrace(e)); //$NON-NLS-1$
                updateStatus("Ошибка сопоставления: " + e); //$NON-NLS-1$
                return;
            }
            for (Pane pane : allPanes())
                install(pane);
            if (threeWay)
                createMergeToolBars();
            if (target != null)
                listenTarget();
            updateStatus(null);
        }

        private void install(Pane pane)
        {
            MoxelControl control = pane.control;
            pane.control.painter = (viewPort, gc, area) -> paintMarks(pane, viewPort, gc, area);
            Listener check = e -> afterUserMove(pane);
            control.addListener(SWT.MouseUp, check);
            control.addListener(SWT.KeyUp, check);
            // Окно само переставляет выделение в остальных сторонах (синхронизация) — в панель «Свойства»
            // отдаётся только сторона, в которой работает пользователь, иначе панель показывала бы
            // свойства чужой стороны (и не давала бы править редактируемую)
            control.addSelectionChangedListener(e -> {
                afterUserMove(pane);
                if (pane == sheetPane)
                    showInProperties(pane);
            });
            control.addListener(SWT.FocusIn, e -> {
                sheetPane = pane;
                showInProperties(pane);
            });
            // Окно — не редактор табличного документа: штатный обработчик Copy здесь не активен
            CopyCommandSupport.wireCopyOverride(control, () -> copySelection(pane));
        }

        /** Сторона, свойства которой сейчас в панели «Свойства»: та, что последней получила фокус. */
        private Pane sheetPane;
        private IPropertySheetPage sheetPage;
        private IWorkbenchPart sheetPart;
        private ISelection sheetBefore;
        private boolean sheetResolved;

        /**
         * Свойства текущей области стороны — в штатной панели «Свойства»: окно не часть воркбенча,
         * поэтому выделение {@code MoxelControl} отдаётся странице панели напрямую
         * ({@code IPropertySheetPage.selectionChanged}), как в {@code FormEditorHook}. Что панель
         * показывала до окна, возвращается при закрытии.
         */
        private void showInProperties(Pane pane)
        {
            Display display = shell.getDisplay();
            // Выделение контрола обновляется после уведомления слушателей — читаем позже
            display.asyncExec(() -> {
                if (shell.isDisposed() || pane.control.isDisposed())
                    return;
                try
                {
                    if (!sheetResolved)
                        resolveSheet();
                    ISelection selection = pane.control.getSelection();
                    if (sheetPage != null && selection != null && !selection.isEmpty())
                        sheetPage.selectionChanged(sheetPart, selection);
                }
                catch (RuntimeException | LinkageError e)
                {
                    tempLog("properties: исключение " + stackTrace(e)); //$NON-NLS-1$
                }
            });
        }

        private void resolveSheet()
        {
            sheetResolved = true;
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage workbenchPage = window != null ? window.getActivePage() : null;
            if (workbenchPage == null)
                return;
            IViewPart view = findSheetView(workbenchPage);
            if (view == null && workbenchPage.getActiveEditor() != null)
            {
                // Панель не открыта — открываем штатной командой «Свойства»
                ShowPropertiesHandler.run(workbenchPage.getActiveEditor().getSite());
                view = findSheetView(workbenchPage);
            }
            if (view != null)
            {
                Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
                if (page instanceof IPropertySheetPage sheet)
                {
                    sheetPage = sheet;
                    sheetPart = workbenchPage.getActiveEditor() != null ? workbenchPage.getActiveEditor() : view;
                    Object before = Global.invoke(page, "getCurrentSelection"); //$NON-NLS-1$
                    sheetBefore = before instanceof ISelection s ? s : null;
                    shell.addDisposeListener(e -> restoreSheet());
                }
            }
        }

        /** Границы панели «Свойства» на экране; панель открывается и выводится наверх, если её не видно. */
        static Rectangle openSheetBounds()
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage workbenchPage = window != null ? window.getActivePage() : null;
                if (workbenchPage == null)
                    return null;
                IViewPart view = findSheetView(workbenchPage);
                if (view == null && workbenchPage.getActiveEditor() != null)
                {
                    ShowPropertiesHandler.run(workbenchPage.getActiveEditor().getSite());
                    view = findSheetView(workbenchPage);
                }
                if (view == null)
                    return null;
                // getView(true) лишь создаёт представление; показывает его showView
                workbenchPage.showView(view.getSite().getId(), null, IWorkbenchPage.VIEW_VISIBLE);
                Object book = Global.invoke(view, "getPageBook"); //$NON-NLS-1$
                if (!(book instanceof Control control) || control.isDisposed() || control.getParent() == null)
                    return null;
                return control.getDisplay().map(control.getParent(), null, control.getBounds());
            }
            catch (PartInitException | RuntimeException | LinkageError e)
            {
                tempLog("properties: границы: исключение " + stackTrace(e)); //$NON-NLS-1$
                return null;
            }
        }

        private static IViewPart findSheetView(IWorkbenchPage workbenchPage)
        {
            for (IViewReference reference : workbenchPage.getViewReferences())
            {
                IViewPart view = reference.getView(true);
                if (view != null && PropertyNameIdentifierHook.isPropertySheetView(view))
                    return view;
            }
            return null;
        }

        private void restoreSheet()
        {
            try
            {
                if (sheetPage != null && sheetBefore != null && !sheetBefore.isEmpty())
                    sheetPage.selectionChanged(sheetPart, sheetBefore);
            }
            catch (RuntimeException | LinkageError e)
            {
                tempLog("properties: возврат: исключение " + stackTrace(e)); //$NON-NLS-1$
            }
        }

        /**
         * Ctrl+C / «Копировать» стороны — как штатный {@code CopyCommandHandler} редактора табличного документа:
         * {@code CopyDrawingsOperation} для выделенных рисунков, иначе {@code CopyCellsOperation} первого
         * выделения, через {@code MoxelClipboardService} и {@code OperationsHelper.executeOperation}.
         */
        private static void copySelection(Pane pane)
        {
            MoxelControl control = pane.control;
            String outcome;
            try
            {
                if (control.isDisposed())
                    return;
                if (control.isInplaceEdit())
                    control.finishInplaceEdit(true);
                Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.moxel.ui"); //$NON-NLS-1$
                BaseOperation operation;
                if (control.isDrawingsSelection())
                    operation = (BaseOperation) bundle.loadClass(MOXEL_OPERATIONS + "CopyDrawingsOperation") //$NON-NLS-1$
                        .getConstructor(String.class, SheetAccessor.class, List.class, PositionHolder.class)
                        .newInstance("Копировать", control.getSheet(), control.getSelectedDrawgins(), //$NON-NLS-1$
                            control.getDisplayPositionHolder());
                else
                {
                    List<?> selections = control.getCellsSelection();
                    if (selections == null || selections.isEmpty())
                    {
                        tempLog("ctrl+c: [" + pane.label + "] нет выделения"); //$NON-NLS-1$ //$NON-NLS-2$
                        return;
                    }
                    operation = (BaseOperation) bundle.loadClass(MOXEL_OPERATIONS + "CopyCellsOperation") //$NON-NLS-1$
                        .getConstructor(String.class, SheetAccessor.class, Selection.class, boolean.class)
                        .newInstance("Копировать", control.getSheet(), selections.get(0), false); //$NON-NLS-1$
                }
                Class<?> clipboardService = bundle.loadClass(MOXEL_OPERATIONS + "MoxelClipboardService"); //$NON-NLS-1$
                Object service = clipboardService.getMethod("getInstance").invoke(null); //$NON-NLS-1$
                clipboardService.getMethod("setDisplay", Display.class).invoke(service, control.getDisplay()); //$NON-NLS-1$
                bundle.loadClass(MOXEL_OPERATIONS + "OperationsHelper") //$NON-NLS-1$
                    .getMethod("executeOperation", BaseOperation.class, IEditingContext.class) //$NON-NLS-1$
                    .invoke(null, operation, pane.context);
                outcome = "выполнено " + operation.getClass().getSimpleName(); //$NON-NLS-1$
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                outcome = "исключение " + stackTrace(e); //$NON-NLS-1$
            }
            // #region agent log — временный, #1015: Ctrl+C стороны
            tempLog("ctrl+c: [" + pane.label + "] " + outcome); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
        }

        /** Правка правимой стороны (ввод, копирование изменений) — пересчёт отличий и победителей. */
        private void listenTarget()
        {
            IEditingContext context = target.context;
            Display display = shell.getDisplay();
            IEditingContextListener listener = new IEditingContextListener()
            {
                @Override
                public void contextChanged()
                {
                    changed = true;
                    // #region agent log — временный, #1015: изменение правимой стороны
                    tempLog("contextChanged: [" + target.label + "] thread=" + Thread.currentThread().getName()); //$NON-NLS-1$ //$NON-NLS-2$
                    // #endregion
                    if (!display.isDisposed())
                        display.asyncExec(() -> scheduleRefresh());
                }
            };
            context.addListener(listener);
            shell.addDisposeListener(e -> {
                try
                {
                    if (!context.isDisposed())
                        context.removeListener(listener);
                }
                catch (RuntimeException ex)
                {
                    tempLog("removeListener: исключение " + stackTrace(ex)); //$NON-NLS-1$
                }
            });
        }

        private void scheduleRefresh()
        {
            if (refreshPending || shell.isDisposed())
                return;
            refreshPending = true;
            shell.getDisplay().timerExec(300, () -> {
                refreshPending = false;
                refresh();
            });
        }

        private void refresh()
        {
            if (shell.isDisposed() || target == null || target.control.isDisposed())
                return;
            try
            {
                target.readContent();
                for (Pane pane : allPanes())
                    pane.resetMarks();
                recompute();
            }
            catch (RuntimeException | LinkageError e)
            {
                tempLog("refresh: исключение " + stackTrace(e)); //$NON-NLS-1$
                updateStatus("Ошибка сопоставления: " + e); //$NON-NLS-1$
                return;
            }
            Pane main = panes.get(0);
            Point active = main.control.isDisposed() ? null : main.control.getActiveCell();
            currentGroup = active != null ? groupAtCell(main, active.y, active.x) : -1;
            redrawAll();
            updateStatus(null);
        }

        private void recompute()
        {
            compare();
            if (resultPane != null)
            {
                Pane main = panes.get(0);
                resultRows = Axis.align(new String[][] { rowKeys(main, List.of(main, resultPane)),
                    rowKeys(resultPane, List.of(main, resultPane)) });
                resultColumns = Axis.align(new String[][] { columnKeys(main, List.of(main, resultPane)),
                    columnKeys(resultPane, List.of(main, resultPane)) });
            }
            computeTargets();
        }

        // ---- сравнение

        private void compare()
        {
            groups.clear();
            String[][] rowKeys = new String[panes.size()][];
            String[][] columnKeys = new String[panes.size()][];
            for (int p = 0; p < panes.size(); p++)
            {
                rowKeys[p] = rowKeys(panes.get(p), panes);
                columnKeys[p] = columnKeys(panes.get(p), panes);
            }
            rows = Axis.align(rowKeys);
            columns = Axis.align(columnKeys);
            boolean three = panes.size() == 3;
            // Колонки, которых нет у части сторон, — отдельные группы; строки сравниваются по общим колонкам,
            // иначе вставленная колонка делает отличающимися все строки
            boolean[] gapColumn = new boolean[columns.size];
            for (int v = 0; v < columns.size; v++)
                for (int p = 0; p < panes.size(); p++)
                    if (columns.index[p][v] < 0)
                        gapColumn[v] = true;
            boolean[] differs = new boolean[rows.size];
            boolean[] conflicts = new boolean[rows.size];
            cellSamples = 0;
            String[] values = new String[panes.size()];
            boolean[] present = new boolean[panes.size()];
            for (int u = 0; u < rows.size; u++)
            {
                for (int v = 0; v < columns.size; v++)
                {
                    if (gapColumn[v])
                        continue;
                    for (int p = 0; p < panes.size(); p++)
                    {
                        int row = rows.index[p][u];
                        int column = columns.index[p][v];
                        present[p] = row >= 0 && column >= 0;
                        // Отсутствующая строка/колонка отличается от пустой ячейки
                        values[p] = present[p] ? Objects.toString(panes.get(p).cell(row, column), "") : null; //$NON-NLS-1$
                    }
                    byte[] kinds = three ? compareThree(values) : compareTwo(values);
                    // #region agent log — временный, #1015: что отличается в ячейке (первые отличия, все стороны есть)
                    if (cellSamples < 12 && allPresent(present) && !Objects.equals(values[0], values[1]))
                    {
                        cellSamples++;
                        StringBuilder sample = new StringBuilder("cell-diff: u=").append(u).append(" v=").append(v); //$NON-NLS-1$ //$NON-NLS-2$
                        for (int p = 0; p < panes.size(); p++)
                            sample.append(" | [").append(p).append("] ").append(rows.index[p][u]).append(':') //$NON-NLS-1$ //$NON-NLS-2$
                                .append(columns.index[p][v]).append(' ').append(visible(values[p]));
                        tempLog(sample.toString());
                    }
                    // #endregion
                    for (int p = 0; p < panes.size(); p++)
                    {
                        if (kinds[p] == MARK_NONE || !present[p])
                            continue;
                        differs[u] = true;
                        if (kinds[p] == MARK_CONFLICT)
                            conflicts[u] = true;
                        Pane pane = panes.get(p);
                        int row = rows.index[p][u];
                        if (pane.marks[row] == null)
                            pane.marks[row] = new byte[Math.max(pane.columnCount, 1)];
                        int column = columns.index[p][v];
                        if (column < pane.marks[row].length)
                            pane.marks[row][column] = kinds[p];
                    }
                }
                if (!differs[u])
                    for (int p = 0; p < panes.size(); p++)
                        if (rows.index[p][u] < 0)
                            differs[u] = true;
            }
            for (int p = 0; p < panes.size(); p++)
            {
                Pane pane = panes.get(p);
                for (int u = 0; u < rows.size; u++)
                    if (rows.index[p][u] < 0 && differs[u])
                        pane.gapRows.add(nextPresent(p, u, pane.rowCount));
            }
            // Колонки части сторон: отличие — наличие колонки; отмечается вся колонка
            boolean[] columnDiffers = new boolean[columns.size];
            boolean[] columnConflicts = new boolean[columns.size];
            for (int v = 0; v < columns.size; v++)
            {
                if (!gapColumn[v])
                    continue;
                for (int p = 0; p < panes.size(); p++)
                    values[p] = columns.index[p][v] >= 0 ? "" : null; //$NON-NLS-1$
                byte[] kinds = three ? compareThree(values) : compareTwo(values);
                for (int p = 0; p < panes.size(); p++)
                {
                    int column = columns.index[p][v];
                    if (kinds[p] == MARK_NONE || column < 0)
                        continue;
                    columnDiffers[v] = true;
                    if (kinds[p] == MARK_CONFLICT)
                        columnConflicts[v] = true;
                    Pane pane = panes.get(p);
                    if (column < pane.inColumnGroup.length)
                        pane.inColumnGroup[column] = true;
                    for (int row = 0; row < pane.rowCount; row++)
                    {
                        if (pane.marks[row] == null)
                            pane.marks[row] = new byte[Math.max(pane.columnCount, 1)];
                        if (column < pane.marks[row].length)
                            pane.marks[row][column] = kinds[p];
                    }
                }
                for (int p = 0; p < panes.size(); p++)
                    if (columns.index[p][v] < 0 && columnDiffers[v])
                        panes.get(p).gapColumns.add(nextPresentColumn(p, v, panes.get(p).columnCount));
            }
            // Сначала группы колонок (они затрагивают все строки), затем группы строк
            addGroups(columnDiffers, columnConflicts, true);
            addGroups(differs, conflicts, false);
            tempLog("compare: panes=" + panes.size() + ", rows=" + rows.size + ", columns=" + columns.size //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", groups=" + groups.size() + ", колонок в группах=" + countTrue(columnDiffers) //$NON-NLS-1$ //$NON-NLS-2$
                + describeFixation());
        }

        // #region agent log — временный, #1015: образцы отличающихся ячеек
        private int cellSamples;

        private static boolean allPresent(boolean[] present)
        {
            for (boolean value : present)
                if (!value)
                    return false;
            return true;
        }

        /** Служебные разделители сравниваемого значения — видимыми знаками. */
        private static String visible(String value)
        {
            return value == null ? "null" : value.replace('\u0002', '¦').replace('\u0003', '§').replace('\u0004', '‖') //$NON-NLS-1$
                .replace('\n', '↵');
        }
        // #endregion

        private void addGroups(boolean[] differs, boolean[] conflicts, boolean byColumns)
        {
            int start = -1;
            boolean conflict = false;
            for (int u = 0; u <= differs.length; u++)
            {
                boolean diff = u < differs.length && differs[u];
                if (diff && start < 0)
                {
                    start = u;
                    conflict = false;
                }
                if (diff)
                    conflict |= conflicts[u];
                else if (start >= 0)
                {
                    groups.add(new Group(start, u - 1, conflict, byColumns));
                    start = -1;
                }
            }
        }

        private static int countTrue(boolean[] values)
        {
            int count = 0;
            for (boolean value : values)
                if (value)
                    count++;
            return count;
        }

        /**
         * Колонки стороны {@code p}, общие для всех сторон (вне групп колонок), в порядке объединённой
         * нумерации — по ним сравниваются строки.
         */
        private int[] commonColumns(int p)
        {
            List<Integer> result = new ArrayList<>();
            for (int v = 0; v < columns.size; v++)
            {
                boolean common = true;
                for (int q = 0; q < panes.size() && common; q++)
                    common = columns.index[q][v] >= 0;
                if (common)
                    result.add(columns.index[p][v]);
            }
            return result.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Колонки результата, соответствующие общим колонкам основной стороны; {@code -1} — нет такой. */
        private int[] resultCommonColumns()
        {
            int[] main = commonColumns(0);
            int[] result = new int[main.length];
            for (int i = 0; i < main.length; i++)
                result[i] = exactResultColumn(main[i]);
            return result;
        }

        /** Колонка результата, сопоставленная колонке основной стороны, или {@code -1}. */
        private int exactResultColumn(int mainColumn)
        {
            if (resultColumns == null || mainColumn < 0 || mainColumn >= resultColumns.unified[0].length)
                return -1;
            return resultColumns.index[1][resultColumns.unified[0][mainColumn]];
        }

        /** Строка стороны по заданным колонкам ({@code -1} — колонки нет). */
        private static String rowOn(Pane pane, int row, int[] cols)
        {
            StringBuilder builder = new StringBuilder();
            for (int column : cols)
                builder.append(column < 0 ? "\u0005" : Objects.toString(pane.cell(row, column), "")).append('\u0002'); //$NON-NLS-1$ //$NON-NLS-2$
            return builder.toString();
        }

        private int nextPresentColumn(int pane, int v, int fallback)
        {
            for (int k = v; k < columns.size; k++)
                if (columns.index[pane][k] >= 0)
                    return columns.index[pane][k];
            return fallback;
        }

        /**
         * Строки (колонки) правимой стороны каждой группы и (при трёх сторонах) чья версия в результате.
         * Диапазон результата — между «якорями»: ближайшими строками (колонками) вне групп, совпавшими у
         * основной стороны и результата.
         */
        private void computeTargets()
        {
            int count = groups.size();
            targetStart = new int[count];
            targetEnd = new int[count];
            winners = new byte[count];
            mainChanged = new boolean[count];
            otherChanged = new boolean[count];
            resultEqualsMain = new boolean[count];
            resultEqualsOther = new boolean[count];
            if (rows == null)
                return;
            boolean[] inGroup = new boolean[rows.size];
            boolean[] inColumnGroup = new boolean[columns.size];
            for (Group group : groups)
                for (int u = group.first(); u <= group.last(); u++)
                    (group.columns() ? inColumnGroup : inGroup)[u] = true;
            int[][] common = new int[panes.size()][];
            for (int p = 0; p < panes.size(); p++)
                common[p] = commonColumns(p);
            int[] resultCommon = resultPane != null ? resultCommonColumns() : null;
            Pane main = panes.get(0);
            int previousEnd = -1;
            int previousColumnEnd = -1;
            StringBuilder log = new StringBuilder("targets:"); //$NON-NLS-1$
            for (int g = 0; g < count; g++)
            {
                Group group = groups.get(g);
                boolean byColumns = group.columns();
                int[][] index = byColumns ? columns.index : rows.index;
                int start;
                int end;
                if (resultPane == null)
                {
                    // Двустороннее: правится основная сторона — её строки (колонки) группы
                    start = -1;
                    end = -1;
                    for (int u = group.first(); u <= group.last(); u++)
                    {
                        int value = index[0][u];
                        if (value < 0)
                            continue;
                        if (start < 0)
                            start = value;
                        end = value;
                    }
                    if (start < 0)
                    {
                        start = byColumns ? nextPresentColumn(0, group.first(), main.columnCount)
                            : nextPresent(0, group.first(), main.rowCount);
                        end = start - 1;
                    }
                }
                else
                {
                    boolean[] grouped = byColumns ? inColumnGroup : inGroup;
                    int size = byColumns ? columns.size : rows.size;
                    start = 0;
                    for (int u = group.first() - 1; u >= 0; u--)
                    {
                        int value = grouped[u] ? -1
                            : byColumns ? resultColumnOfUnified(u) : resultRowOfUnified(u, common[0], resultCommon);
                        if (value >= 0)
                        {
                            start = value + 1;
                            break;
                        }
                    }
                    end = (byColumns ? resultPane.columnCount : resultPane.rowCount) - 1;
                    for (int u = group.last() + 1; u < size; u++)
                    {
                        int value = grouped[u] ? -1
                            : byColumns ? resultColumnOfUnified(u) : resultRowOfUnified(u, common[0], resultCommon);
                        if (value >= 0)
                        {
                            end = value - 1;
                            break;
                        }
                    }
                    int previous = byColumns ? previousColumnEnd : previousEnd;
                    start = Math.max(start, previous + 1);
                    end = Math.max(end, start - 1);
                    if (byColumns)
                        previousColumnEnd = Math.max(previousColumnEnd, end);
                    else
                        previousEnd = Math.max(previousEnd, end);
                }
                targetStart[g] = start;
                targetEnd[g] = end;
                if (threeWay && panes.size() == 3)
                {
                    List<String> mainItems = sideItems(0, group, common[0]);
                    List<String> otherItems = sideItems(1, group, common[1]);
                    List<String> ancestorItems = sideItems(2, group, common[2]);
                    List<String> resultItems = new ArrayList<>();
                    for (int value = start; value <= end; value++)
                        resultItems.add(byColumns ? resultPane.columnSignature(value)
                            : rowOn(resultPane, value, resultCommon));
                    mainChanged[g] = !mainItems.equals(ancestorItems);
                    otherChanged[g] = !otherItems.equals(ancestorItems);
                    resultEqualsMain[g] = resultItems.equals(mainItems);
                    resultEqualsOther[g] = resultItems.equals(otherItems);
                    if (resultEqualsMain[g] && resultEqualsOther[g])
                        winners[g] = WIN_BOTH;
                    else if (resultEqualsMain[g] && mainChanged[g])
                        winners[g] = WIN_LEFT;
                    else if (resultEqualsOther[g] && otherChanged[g])
                        winners[g] = WIN_RIGHT;
                    else if (resultItems.equals(ancestorItems))
                        winners[g] = WIN_ANCESTOR;
                    else
                        winners[g] = WIN_MANUAL;
                }
                else
                {
                    mainChanged[g] = true;
                    otherChanged[g] = true;
                }
                // #region agent log — временный, #1015: диапазоны групп в правимой стороне
                log.append(" #").append(g).append(byColumns ? "C[" : "[").append(group.first()).append("..") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(group.last()).append(group.conflict() ? " K" : "").append(" -> ").append(start) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append("..").append(end).append(" w=").append(winners[g]).append(" mc=").append(mainChanged[g]) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(" oc=").append(otherChanged[g]).append(']'); //$NON-NLS-1$
                // #endregion
            }
            tempLog(log.toString());
        }

        /**
         * Строка результата, совпадающая с основной в объединённой строке {@code u} (по общим колонкам),
         * или {@code -1}.
         */
        private int resultRowOfUnified(int u, int[] mainCommon, int[] resultCommon)
        {
            int mainRow = rows.index[0][u];
            if (mainRow < 0 || resultRows == null || mainRow >= resultRows.unified[0].length)
                return -1;
            int row = resultRows.index[1][resultRows.unified[0][mainRow]];
            // Сопоставление пар внутри отличающегося участка — не якорь
            if (row < 0 || !Objects.equals(rowOn(panes.get(0), mainRow, mainCommon), rowOn(resultPane, row, resultCommon)))
                return -1;
            return row;
        }

        /** Колонка результата, совпадающая с основной в объединённой колонке {@code v}, или {@code -1}. */
        private int resultColumnOfUnified(int v)
        {
            int mainColumn = columns.index[0][v];
            int column = exactResultColumn(mainColumn);
            if (column < 0 || !Objects.equals(panes.get(0).columnSignature(mainColumn), resultPane.columnSignature(column)))
                return -1;
            return column;
        }

        /** Подписи строк (по общим колонкам) или колонок группы у стороны {@code p}. */
        private List<String> sideItems(int p, Group group, int[] common)
        {
            List<String> result = new ArrayList<>();
            for (int u = group.first(); u <= group.last(); u++)
            {
                int value = (group.columns() ? columns.index : rows.index)[p][u];
                if (value >= 0)
                    result.add(group.columns() ? panes.get(p).columnSignature(value) : rowOn(panes.get(p), value, common));
            }
            return result;
        }

        private String describeFixation()
        {
            StringBuilder builder = new StringBuilder();
            for (Pane pane : panes)
                builder.append(" [").append(pane.label).append(": rows=").append(pane.rowCount) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" cols=").append(pane.columnCount).append(" fixedRow=").append(pane.fixedRow) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" fixedColumn=").append(pane.fixedColumn).append(']'); //$NON-NLS-1$
            return builder.toString();
        }

        private int nextPresent(int pane, int u, int fallback)
        {
            for (int k = u; k < rows.size; k++)
                if (rows.index[pane][k] >= 0)
                    return rows.index[pane][k];
            return fallback;
        }

        private static byte[] compareTwo(String[] values)
        {
            byte[] kinds = new byte[2];
            if (!Objects.equals(values[0], values[1]))
            {
                kinds[0] = values[1] == null ? MARK_ADDED : MARK_CHANGED;
                kinds[1] = values[0] == null ? MARK_ADDED : MARK_CHANGED;
            }
            return kinds;
        }

        /** {@code values} — {@code [основная, другая, предок]}. */
        private static byte[] compareThree(String[] values)
        {
            byte[] kinds = new byte[3];
            String base = values[2];
            boolean mainChange = !Objects.equals(values[0], base);
            boolean otherChange = !Objects.equals(values[1], base);
            boolean conflict = mainChange && otherChange && !Objects.equals(values[0], values[1]);
            if (mainChange)
                kinds[0] = conflict ? MARK_CONFLICT : base == null ? MARK_ADDED : MARK_CHANGED;
            if (otherChange)
                kinds[1] = conflict ? MARK_CONFLICT : base == null ? MARK_ADDED : MARK_CHANGED;
            if (mainChange || otherChange)
                kinds[2] = conflict ? MARK_CONFLICT : MARK_CHANGED;
            return kinds;
        }

        /**
         * Ключ строки — содержимое её крайней (первой) ячейки, если колонки закреплены у всех сторон;
         * иначе (или там пусто) — содержимое всей строки. Оформление в ключ не входит: строка с другим
         * оформлением — та же строка, отличие отмечается в ячейках.
         */
        private static String[] rowKeys(Pane pane, List<Pane> all)
        {
            boolean keyed = all.stream().allMatch(p -> p.fixedColumn > 0);
            String[] keys = new String[pane.rowCount];
            for (int row = 0; row < pane.rowCount; row++)
            {
                // Крайняя ячейка закреплённых колонок — в первой колонке
                String key = keyed ? pane.keyCell(row, 0) : null;
                keys[row] = !isEmpty(key) ? "K" + key : pane.contentRowSignature(row); //$NON-NLS-1$
            }
            return keys;
        }

        /**
         * Ключ колонки — содержимое её крайней (верхней) ячейки, если строки закреплены у всех сторон;
         * иначе (или там пусто) — содержимое всей колонки.
         */
        private static String[] columnKeys(Pane pane, List<Pane> all)
        {
            boolean keyed = all.stream().allMatch(p -> p.fixedRow > 0);
            String[] keys = new String[pane.columnCount];
            for (int column = 0; column < pane.columnCount; column++)
            {
                // Крайняя ячейка закреплённых строк — в первой строке
                String key = keyed ? pane.keyCell(0, column) : null;
                keys[column] = !isEmpty(key) ? "K" + key : pane.columnSignature(column); //$NON-NLS-1$
            }
            return keys;
        }

        // ---- перенос изменений

        private void copyCurrent(int source)
        {
            if (currentGroup < 0 || currentGroup >= groups.size())
                return;
            int group = currentGroup;
            copyGroups(source, g -> g == group, false, source == 0 ? "Копировать текущее изменение слева" //$NON-NLS-1$
                : "Копировать текущее изменение справа"); //$NON-NLS-1$
        }

        /** Все изменения стороны: при трёх сторонах — группы, где сторона отличается от предка. */
        private void copyAll(int source)
        {
            copyGroups(source, g -> !threeWay || (source == 0 ? mainChanged[g] : otherChanged[g]), true,
                source == 0 ? "Копировать все изменения слева" //$NON-NLS-1$
                    : "Копировать все изменения справа"); //$NON-NLS-1$
        }

        /**
         * Группы стороны {@code sourceIndex}, выбранные {@code filter}, заменяют группы правимой стороны —
         * штатными операциями табличного документа, как «Вставить со сдвигом» после «Копировать»
         * ({@code PasteInsertHandler}) и «Удалить»: сначала колонки (целиком — с шириной и форматом), затем
         * строки. После переноса колонок номера групп меняются, поэтому при {@code reselect} строки выбираются
         * заново по пересчитанному сравнению.
         */
        private void copyGroups(int sourceIndex, IntPredicate filter, boolean reselect, String operationName)
        {
            if (target == null || !target.editable || target.control.isDisposed())
                return;
            Pane source = panes.get(sourceIndex);
            if (source == target)
                return;
            if (target.control.isInplaceEdit())
                target.control.finishInplaceEdit(false);
            try
            {
                List<Integer> columnGroups = select(filter, true);
                List<Integer> rowGroups = select(filter, false);
                if (!columnGroups.isEmpty())
                {
                    replaceGroups(source, sourceIndex, columnGroups, operationName);
                    if (rowGroups.isEmpty())
                    {
                        scheduleRefresh();
                        return;
                    }
                    if (!reselect)
                        rowGroups = List.of();
                    else
                    {
                        // Пересчёт по изменённой правимой стороне, затем — строки
                        target.readContent();
                        for (Pane pane : allPanes())
                            pane.resetMarks();
                        recompute();
                        rowGroups = select(filter, false);
                    }
                }
                if (!rowGroups.isEmpty())
                    replaceGroups(source, sourceIndex, rowGroups, operationName);
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                tempLog("copy: исключение " + stackTrace(e)); //$NON-NLS-1$
                updateStatus("Не удалось перенести изменение: " + rootMessage(e)); //$NON-NLS-1$
                return;
            }
            scheduleRefresh();
        }

        private List<Integer> select(IntPredicate filter, boolean byColumns)
        {
            List<Integer> result = new ArrayList<>();
            for (int g = 0; g < groups.size(); g++)
                if (groups.get(g).columns() == byColumns && filter.test(g))
                    result.add(g);
            return result;
        }

        /** Группы одного вида: одна составная операция, снизу вверх (справа налево), чтобы номера не сдвигались. */
        private void replaceGroups(Pane source, int sourceIndex, List<Integer> selected, String operationName)
            throws ReflectiveOperationException
        {
            List<Integer> order = new ArrayList<>(selected);
            order.sort((a, b) -> Integer.compare(groups.get(b).first(), groups.get(a).first()));
            int[] columnMap = targetColumnMap(sourceIndex, source);
            boolean wholeRows = isIdentity(columnMap, source.columnCount, target.columnCount);
            int[] rowMap = targetRowMap(sourceIndex, source);
            boolean wholeColumns = isIdentity(rowMap, source.rowCount, target.rowCount);
            List<Fragment> fragments = new ArrayList<>();
            for (int g : order)
            {
                Group group = groups.get(g);
                boolean byColumns = group.columns();
                int first = -1;
                int last = -1;
                for (int u = group.first(); u <= group.last(); u++)
                {
                    int value = (byColumns ? columns.index : rows.index)[sourceIndex][u];
                    if (value < 0)
                        continue;
                    if (first < 0)
                        first = value;
                    last = value;
                }
                int count = first < 0 ? 0 : last - first + 1;
                int targetCount = Math.max(targetEnd[g] - targetStart[g] + 1, 0);
                List<Piece> pieces = count > 0
                    ? copyPieces(source, byColumns, first, last, byColumns ? wholeColumns : wholeRows,
                        byColumns ? rowMap : columnMap)
                    : List.of();
                // #region agent log — временный, #1015: перенос группы
                StringBuilder log = new StringBuilder("copy: ").append(operationName).append(" group=").append(g) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(byColumns ? " колонки" : " строки").append(" source=[").append(source.label).append("] ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .append(first).append("..").append(last).append(" target=[").append(target.label).append("] ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(targetStart[g]).append("..").append(targetEnd[g]).append(" целиком=") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(byColumns ? wholeColumns : wholeRows).append(" куски="); //$NON-NLS-1$
                for (Piece piece : pieces)
                    log.append('[').append(piece.kind()).append(" col=").append(piece.targetColumn()).append(" row=") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(piece.targetRow()).append(' ').append(piece.width()).append('x').append(piece.height())
                        .append(" h=").append(piece.copy() != null ? piece.copy().getHeight() : -1).append(']'); //$NON-NLS-1$
                tempLog(log.toString());
                // #endregion
                if (count > 0 || targetCount > 0)
                    fragments.add(new Fragment(g, byColumns, targetStart[g], targetCount, count, pieces));
            }
            if (fragments.isEmpty())
                return;
            executeReplace(operationName, fragments);
            changed = true;
            tempLog("copy: выполнено, фрагментов=" + fragments.size() + ", contextDirty=" + target.context.isDirty()); //$NON-NLS-1$ //$NON-NLS-2$
            // #region agent log — временный, #1015: вставка только формата колонок не должна переносить ячейки
            if (!wholeColumns && fragments.stream().anyMatch(Fragment::columns))
            {
                target.readContent();
                Set<Integer> mapped = new HashSet<>();
                for (int value : rowMap)
                    if (value >= 0)
                        mapped.add(value);
                StringBuilder check = new StringBuilder("copy: проверка колонок —"); //$NON-NLS-1$
                for (Fragment fragment : fragments)
                {
                    if (!fragment.columns())
                        continue;
                    int filled = 0;
                    int unmappedFilled = 0;
                    for (int row = 0; row < target.rowCount; row++)
                        for (int column = fragment.targetStart(); column < fragment.targetStart() + fragment.count(); column++)
                            if (!isEmpty(target.keyCell(row, column)))
                            {
                                filled++;
                                if (!mapped.contains(row))
                                    unmappedFilled++;
                            }
                    check.append(" колонки ").append(fragment.targetStart()).append('+').append(fragment.count())
                        .append(": непустых=").append(filled).append(", в строках без источника=").append(unmappedFilled); //$NON-NLS-1$ //$NON-NLS-2$
                }
                tempLog(check.toString());
            }
            // #endregion
        }

        /**
         * {@code map[sourceColumn]} — та же колонка у правимой стороны или {@code -1}: через объединённую
         * нумерацию к основной стороне, а от неё (при результате) — к результату.
         */
        private int[] targetColumnMap(int sourceIndex, Pane source)
        {
            int[] map = new int[source.columnCount];
            for (int column = 0; column < source.columnCount; column++)
            {
                int u = column < columns.unified[sourceIndex].length ? columns.unified[sourceIndex][column] : -1;
                int mainColumn = u >= 0 ? columns.index[0][u] : -1;
                map[column] = target == panes.get(0) ? mainColumn : exactResultColumn(mainColumn);
            }
            return map;
        }

        /** {@code map[sourceRow]} — та же строка у правимой стороны или {@code -1} (как {@link #targetColumnMap}). */
        private int[] targetRowMap(int sourceIndex, Pane source)
        {
            int[] map = new int[source.rowCount];
            for (int row = 0; row < source.rowCount; row++)
            {
                int u = row < rows.unified[sourceIndex].length ? rows.unified[sourceIndex][row] : -1;
                int mainRow = u >= 0 ? rows.index[0][u] : -1;
                if (target == panes.get(0) || mainRow < 0)
                    map[row] = mainRow;
                else
                    map[row] = resultRows == null || mainRow >= resultRows.unified[0].length ? -1
                        : resultRows.index[1][resultRows.unified[0][mainRow]];
            }
            return map;
        }

        private static boolean isIdentity(int[] map, int sourceCount, int targetCount)
        {
            if (sourceCount != targetCount)
                return false;
            for (int column = 0; column < map.length; column++)
                if (map[column] != column)
                    return false;
            return true;
        }

        /**
         * Копии штатным копированием ({@code CopyCellsOperation}): колонки или строки целиком, а при разных
         * колонках сторон — ячейки строк по участкам общих колонок, идущих подряд у обеих сторон (колонки,
         * которых нет у правимой стороны, переносятся своей группой).
         */
        private static List<Piece> copyPieces(Pane source, boolean byColumns, int first, int last, boolean whole,
            int[] crossMap)
        {
            return source.context.executeReadonlyTask(
                new AbstractBmTask<List<Piece>>("Комфорт: копирование табличного документа") //$NON-NLS-1$
                {
                    @Override
                    public List<Piece> execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        SheetAccessor sheet = source.control.getSheet();
                        List<Piece> pieces = new ArrayList<>();
                        int span = last - first + 1;
                        if (byColumns)
                        {
                            Selection columnsSelection = new ColumnsSelection(sheet, sheet.getRowColumns(0), first, last);
                            if (whole)
                            {
                                pieces.add(piece(sheet, columnsSelection, PieceKind.WHOLE, -1, -1, span, 0));
                                return pieces;
                            }
                            // Строки сторон не совпадают: формат колонок отдельно, ячейки — по сопоставленным строкам
                            pieces.add(piece(sheet, columnsSelection, PieceKind.COLUMN_FORMAT, -1, -1, span, 0));
                        }
                        else if (whole)
                        {
                            pieces.add(piece(sheet, new RowsSelection(sheet, first, last), PieceKind.WHOLE, -1, -1, 0, span));
                            return pieces;
                        }
                        // Участки, идущие подряд у обеих сторон: колонки (для строк) или строки (для колонок)
                        for (int index = 0; index < crossMap.length;)
                        {
                            if (crossMap[index] < 0)
                            {
                                index++;
                                continue;
                            }
                            int end = index;
                            while (end + 1 < crossMap.length && crossMap[end + 1] == crossMap[end] + 1)
                                end++;
                            // CellsSelection: x — колонка, y — строка, ширина/высота — разность границ
                            if (byColumns)
                                pieces.add(piece(sheet, new CellsSelection(sheet, first, index, last - first, end - index),
                                    PieceKind.CELLS, -1, crossMap[index], span, end - index + 1));
                            else
                                pieces.add(piece(sheet, new CellsSelection(sheet, index, first, end - index, last - first),
                                    PieceKind.CELLS, crossMap[index], -1, end - index + 1, span));
                            index = end + 1;
                        }
                        return pieces;
                    }
                }, new NullProgressMonitor());
        }

        /** Как штатный {@code CopyCellsOperation}: копия выделения и её описание. */
        private static Piece piece(SheetAccessor sheet, Selection selection, PieceKind kind, int targetColumn,
            int targetRow, int width, int height)
        {
            SpreadsheetDocument document = ClipboardCopyUtil.copySheet(sheet, selection, false);
            if (kind == PieceKind.COLUMN_FORMAT)
            {
                // Формат колонок штатная вставка переносит только по маске свойств формата, а она же переносит
                // формат ячеек — по несопоставленным строкам. Поэтому из копии (отдельный документ) убираются
                // строки, рисунки и объединения: остаются только колонки
                document.getRows().clear();
                document.getDrawings().clear();
                document.getMerges().clear();
            }
            return new Piece(kind, targetColumn, targetRow, width, height, document,
                ClipboardCopyUtil.makeDocumentDescriptor(selection, document));
        }

        private void executeReplace(String operationName, List<Fragment> fragments) throws ReflectiveOperationException
        {
            Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.moxel.ui"); //$NON-NLS-1$
            Class<?> compositeType = bundle.loadClass(MOXEL_OPERATIONS + "CompositeOperation"); //$NON-NLS-1$
            Class<?> deleteInsertType = bundle.loadClass(MOXEL_OPERATIONS + "DeleteInsertCellsOperation"); //$NON-NLS-1$
            Class<?> preserveType = bundle.loadClass(MOXEL_OPERATIONS + "DeleteInsertCellsOperation$PreserveFormatType"); //$NON-NLS-1$
            Constructor<?> deleteInsert = deleteInsertType.getConstructor(String.class, SheetAccessor.class,
                Selection.class, boolean.class, boolean.class, preserveType, PositionHolder.class, boolean.class);
            Method addOperation = compositeType.getMethod("addOperation", BaseOperation.class); //$NON-NLS-1$
            Object preserveCurrent = enumConstant(preserveType, "PRESERVE_CURRENT"); //$NON-NLS-1$
            Object notPreserve = enumConstant(preserveType, "NOT_PRESERVE"); //$NON-NLS-1$

            MoxelControl control = target.control;
            SheetAccessor sheet = control.getSheet();
            PositionHolder holder = control.getDisplayPositionHolder();
            BaseOperation composite = (BaseOperation) compositeType
                .getConstructor(String.class, SheetAccessor.class, MoxelControl.class)
                .newInstance(operationName, sheet, control);
            for (Fragment fragment : fragments)
            {
                int start = fragment.targetStart();
                boolean byColumns = fragment.columns();
                if (fragment.count() > 0)
                {
                    // «Вставить со сдвигом»: вставка строк вниз (колонок вправо) и вставка скопированного в них
                    Selection insert = byColumns
                        ? new ColumnsSelection(sheet, sheet.getRowColumns(0), start, start + fragment.count() - 1)
                        : new RowsSelection(sheet, start, start + fragment.count() - 1);
                    addOperation.invoke(composite, deleteInsert.newInstance(operationName, sheet, insert, true, true,
                        preserveCurrent, holder, true));
                    for (Piece piece : fragment.pieces())
                    {
                        switch (piece.kind())
                        {
                            case WHOLE -> addOperation.invoke(composite, new PasteCellsOperation(operationName, sheet,
                                insert, false, piece.copy(), piece.descriptor(), holder, true));
                            case COLUMN_FORMAT ->
                            {
                                // «Специальная вставка» форматов колонок (все свойства) из копии без строк:
                                // без ячеек, рисунков, имён, объединений
                                PasteSpecialOptions options = new PasteSpecialOptions();
                                options.setPasteColumnsFormats(true);
                                options.setPasteNames(false);
                                options.setPasteDrawings(false);
                                options.setPasteMerges(false);
                                options.setPasteGroups(false);
                                options.setPasteHeaders(false);
                                addOperation.invoke(composite, new PasteCellsOperation(operationName, sheet, insert,
                                    piece.copy(), piece.descriptor(), holder, options, true));
                            }
                            case CELLS ->
                            {
                                Selection paste = byColumns
                                    ? new CellsSelection(sheet, start, piece.targetRow(), piece.width() - 1,
                                        piece.height() - 1)
                                    : new CellsSelection(sheet, piece.targetColumn(), start, piece.width() - 1,
                                        piece.height() - 1);
                                addOperation.invoke(composite, new PasteCellsOperation(operationName, sheet, paste, false,
                                    piece.copy(), piece.descriptor(), holder, true));
                            }
                        }
                    }
                }
                if (fragment.targetCount() > 0)
                {
                    // «Удалить» прежние строки (колонки) группы со сдвигом
                    int from = start + fragment.count();
                    int to = from + fragment.targetCount() - 1;
                    Selection remove = byColumns ? new ColumnsSelection(sheet, sheet.getRowColumns(0), from, to)
                        : new RowsSelection(sheet, from, to);
                    addOperation.invoke(composite, deleteInsert.newInstance(operationName, sheet, remove, false, true,
                        notPreserve, holder, true));
                }
            }
            // Как OperationsHelper.executeOperation: задача контекста, затем onExecute
            target.context.execute(composite, new NullProgressMonitor());
            composite.onExecute();
            control.updateSelection();
            control.redraw();
        }

        private static Object enumConstant(Class<?> type, String name)
        {
            for (Object constant : type.getEnumConstants())
                if (((Enum<?>) constant).name().equals(name))
                    return constant;
            throw new IllegalStateException("Нет значения " + name + " в " + type.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String rootMessage(Throwable e)
        {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root)
                root = root.getCause();
            return root.getMessage() != null ? root.getMessage() : root.toString();
        }

        // ---- запись результата

        /** «ОК»: перенесённые изменения — XML правимого документа в штатный просмотрщик, затем закрытие. */
        void accept()
        {
            if (target != null && !target.control.isDisposed() && target.control.isInplaceEdit())
                target.control.finishInplaceEdit(false);
            if (changed && writer == null)
            {
                tempLog("write: изменения есть, а записать некуда"); //$NON-NLS-1$
                if (!MessageDialog.openQuestion(shell, Global.withPluginWindowTitle(MENU_LABEL),
                    threeWay ? "Штатный результат объединения не правится — изменения перенести некуда. Закрыть окно?" //$NON-NLS-1$
                        : "Не найден узел плана объединения для этого макета — изменения перенести некуда. Закрыть окно?")) //$NON-NLS-1$
                    return;
            }
            else if (changed && target != null)
            {
                try
                {
                    String xml = serialize(target);
                    writer.accept(xml);
                    tempLog("write: [" + target.label + "] length=" + xml.length()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (RuntimeException | LinkageError e)
                {
                    tempLog("write: исключение " + stackTrace(e)); //$NON-NLS-1$
                    MessageDialog.openError(shell, Global.withPluginWindowTitle(MENU_LABEL),
                        "Не удалось записать табличный документ: " + rootMessage(e)); //$NON-NLS-1$
                    return;
                }
            }
            else
                tempLog("write: пропуск, changed=" + changed + ", target=" + (target != null) + ", writer=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + (writer != null));
            closeConfirmed = true;
            shell.close();
        }

        /** XML документа штатным ресурсом {@code mxlx} (как {@code SpreadsheetMergeCodec.saveDocument} образца). */
        private String serialize(Pane pane)
        {
            return pane.context.executeReadonlyTask(new AbstractBmTask<String>("Комфорт: запись табличного документа") //$NON-NLS-1$
            {
                @Override
                public String execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    SpreadsheetDocument document = (SpreadsheetDocument) transaction.getTopObjectByFqn(pane.fqn);
                    // Ресурс не владеет документом: объект BM остаётся в контексте
                    MoxelResourceMxlx resource = new MoxelResourceMxlx(
                        URI.createURI("tormozit-spreadsheet-merge:/result.mxlx")) //$NON-NLS-1$
                    {
                        private final EList<EObject> roots = new BasicEList<>(List.of(document));

                        @Override
                        public EList<EObject> getContents()
                        {
                            return roots;
                        }
                    };
                    resource.setDtProject(project);
                    try
                    {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        resource.save(out, Map.of("ENCODING", "UTF-8")); //$NON-NLS-1$ //$NON-NLS-2$
                        return out.toString(StandardCharsets.UTF_8);
                    }
                    catch (java.io.IOException e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
            }, new NullProgressMonitor());
        }

        // ---- навигация и синхронизация

        private void afterUserMove(Pane source)
        {
            if (syncing || source.control.isDisposed())
                return;
            Point active = source.control.getActiveCell();
            if (active == null)
                return;
            boolean programmatic = active.equals(source.programmaticCell);
            // Событие о выделении, сделанном самим окном (переход по отличиям, синхронизация сторон), —
            // не выбор пользователя: текущее изменение остаётся
            if (programmatic)
                return;
            for (Pane pane : allPanes())
                pane.programmaticCell = null;
            syncFrom(source, active.y, active.x);
            int group = groupAtCell(source, active.y, active.x);
            if (source == resultPane && group < 0)
                group = resultGroupAt(active.y, active.x);
            if (group != currentGroup)
            {
                currentGroup = group;
                redrawAll();
                updateStatus(null);
            }
        }

        /** Группа, в диапазон результата которой попадает колонка, а затем строка результата. */
        private int resultGroupAt(int row, int column)
        {
            for (int pass = 0; pass < 2; pass++)
                for (int g = 0; g < targetStart.length && g < groups.size(); g++)
                {
                    boolean byColumns = groups.get(g).columns();
                    int value = byColumns ? column : row;
                    if (byColumns == (pass == 0) && targetStart[g] <= value && value <= targetEnd[g])
                        return g;
                }
            return -1;
        }

        /** Ставит ячейку {@code (row, column)} стороны {@code source} текущей на остальных сторонах. */
        private void syncFrom(Pane source, int row, int column)
        {
            if (rows == null)
                return;
            syncing = true;
            try
            {
                int mainRow = row;
                int mainColumn = column;
                if (source == resultPane)
                {
                    mainRow = resultRows.map(1, 0, row);
                    mainColumn = resultColumns.map(1, 0, column);
                }
                else if (source != panes.get(0))
                {
                    int p = panes.indexOf(source);
                    mainRow = rows.map(p, 0, row);
                    mainColumn = columns.map(p, 0, column);
                }
                for (int p = 0; p < panes.size(); p++)
                {
                    Pane pane = panes.get(p);
                    if (pane == source)
                        continue;
                    int paneRow = p == 0 ? mainRow : rows.map(0, p, mainRow);
                    int paneColumn = p == 0 ? mainColumn : columns.map(0, p, mainColumn);
                    select(pane, paneRow, paneColumn);
                    alignViewport(source, row, column, pane, paneRow, paneColumn);
                }
                if (resultPane != null && resultPane != source)
                {
                    int paneRow = resultRows.map(0, 1, mainRow);
                    int paneColumn = resultColumns.map(0, 1, mainColumn);
                    select(resultPane, paneRow, paneColumn);
                    alignViewport(source, row, column, resultPane, paneRow, paneColumn);
                }
            }
            finally
            {
                syncing = false;
            }
        }

        private static void select(Pane pane, int row, int column)
        {
            MoxelControl control = pane.control;
            if (control.isDisposed())
                return;
            int safeRow = Math.max(0, row);
            int safeColumn = Math.max(0, column);
            try
            {
                control.setSelectionToCellSelection(safeRow, safeColumn);
                // Выделение окна, а не пользователя: событие о нём не меняет текущее изменение
                pane.programmaticCell = control.getActiveCell();
                control.ensureCellVisible(safeRow, safeColumn);
            }
            catch (RuntimeException e)
            {
                tempLog("select: [" + pane.label + "] row=" + safeRow + " column=" + safeColumn //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " исключение " + stackTrace(e)); //$NON-NLS-1$
            }
            control.redraw();
        }

        /**
         * Прокручивает сторону {@code pane} так, чтобы левый верхний угол её ячейки
         * {@code (row, column)} стоял в области просмотра там же, где у стороны {@code source}
         * угол активной ячейки. По вертикали контрол прокручивается целыми строками — выбирается
         * ближайшая верхняя строка; по горизонтали — точно. Закреплённые строки/колонки не
         * прокручиваются — по такой оси остаётся только видимость ячейки.
         *
         * <p>Прокрутка — закрытыми {@code MoxelControl.scrollToRow(int)} и
         * {@code scrollToColumnUnit(int)}: их зовут полосы прокрутки и {@code ensureRowVisible},
         * они двигают все области просмотра, заголовки и полосы прокрутки вместе.
         */
        private static void alignViewport(Pane source, int sourceRow, int sourceColumn, Pane pane, int row, int column)
        {
            MoxelControl from = source.control;
            MoxelControl to = pane.control;
            if (from.isDisposed() || to.isDisposed() || row < 0 || column < 0)
                return;
            StringBuilder log = new StringBuilder("align: [").append(source.label).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(sourceRow).append(':').append(sourceColumn).append(" -> [").append(pane.label).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(row).append(':').append(column);
            try
            {
                ViewPort fromView = from.getMainScrollableViewPort();
                ViewPort toView = to.getMainScrollableViewPort();
                if (fromView == null || toView == null || fromView.getDevicePosition() == null)
                    return;
                Rectangle fromDevice = fromView.getDevicePosition();
                Point fromTop = from.getTopScrollbalePoint();
                Point toTop = to.getTopScrollbalePoint();
                PositionHolder holder = toView.getPositionHolder();
                if (sourceRow >= fromTop.y && row >= toTop.y)
                {
                    int offset = fromView.getRowPositionPixel(sourceRow) - fromDevice.y;
                    int top = row;
                    int above = 0;
                    while (top > toTop.y && above < offset)
                    {
                        int height = holder.getRowHeightPixel(top - 1);
                        // Ближайшая к нужному смещению верхняя строка
                        if (above + height > offset && offset - above < above + height - offset)
                            break;
                        above += height;
                        top--;
                    }
                    scrollMethod("scrollToRow").invoke(to, top); //$NON-NLS-1$
                    log.append(" offsetY=").append(offset).append(" topRow=").append(top).append(" above=").append(above); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                else
                    log.append(" вертикаль: закреплено (").append(fromTop.y).append('/').append(toTop.y).append(')'); //$NON-NLS-1$
                if (sourceColumn >= fromTop.x && column >= toTop.x)
                {
                    Rectangle cell = fromView.getCellPositionPixel(sourceRow, sourceColumn);
                    int offset = cell.x - fromDevice.x;
                    Columns rowColumns = to.getSheet().getRowColumns(row);
                    int unit = holder.getColLeftPositionUnit(rowColumns, column)
                        - holder.getUnitsConverter().XPixelToUnit(offset);
                    scrollMethod("scrollToColumnUnit").invoke(to, Math.max(unit, 0)); //$NON-NLS-1$
                    log.append(" offsetX=").append(offset).append(" leftUnit=").append(unit); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                    log.append(" горизонталь: закреплено (").append(fromTop.x).append('/').append(toTop.x).append(')'); //$NON-NLS-1$
                to.redraw();
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                log.append(" исключение ").append(stackTrace(e)); //$NON-NLS-1$
            }
            // #region agent log — временный, #1015: выравнивание прокрутки сторон
            tempLog(log.toString());
            // #endregion
        }

        private static Method scrollMethod(String name) throws NoSuchMethodException
        {
            Method method = MoxelControl.class.getDeclaredMethod(name, int.class);
            method.setAccessible(true);
            return method;
        }

        private int unifiedRow(Pane pane, int row)
        {
            int p = panes.indexOf(pane);
            if (p < 0)
            {
                p = 0;
                row = resultRows != null ? resultRows.map(1, 0, row) : row;
            }
            int[] unified = rows.unified[p];
            return row >= 0 && row < unified.length ? unified[row] : -1;
        }

        private int unifiedColumn(Pane pane, int column)
        {
            int p = panes.indexOf(pane);
            if (p < 0)
            {
                p = 0;
                column = resultColumns != null ? resultColumns.map(1, 0, column) : column;
            }
            int[] unified = columns.unified[p];
            return column >= 0 && column < unified.length ? unified[column] : -1;
        }

        /** Группа строк, содержащая объединённую строку {@code u}. */
        private int groupAt(int u)
        {
            for (int i = 0; i < groups.size(); i++)
                if (!groups.get(i).columns() && groups.get(i).first() <= u && u <= groups.get(i).last())
                    return i;
            return -1;
        }

        /** Группа колонок, содержащая колонку ячейки, иначе — группа строк, содержащая её строку. */
        private int groupAtCell(Pane pane, int row, int column)
        {
            if (rows == null)
                return -1;
            int p = panes.indexOf(pane);
            // У результата колонки групп сопоставляются по диапазонам результата
            if (p >= 0)
            {
                int v = unifiedColumn(pane, column);
                for (int i = 0; i < groups.size(); i++)
                    if (groups.get(i).columns() && groups.get(i).first() <= v && v <= groups.get(i).last())
                        return i;
            }
            return groupAt(unifiedRow(pane, row));
        }

        /**
         * Переход к следующей/предыдущей группе, подходящей под {@code filter}.
         *
         * @return {@code true}, если переход выполнен
         */
        boolean step(int direction, IntPredicate filter)
        {
            if (groups.isEmpty())
                return false;
            Pane main = panes.get(0);
            Point active = main.control.isDisposed() ? null : main.control.getActiveCell();
            int u = active != null ? unifiedRow(main, active.y) : -1;
            int found = -1;
            if (currentGroup >= 0 && currentGroup < groups.size() && groups.get(currentGroup).columns())
            {
                // Из группы колонок — по порядку списка: группы колонок идут перед группами строк
                for (int i = currentGroup + direction; i >= 0 && i < groups.size() && found < 0; i += direction)
                    if (filter.test(i))
                        found = i;
            }
            else if (direction > 0)
            {
                for (int i = 0; i < groups.size(); i++)
                    if (!groups.get(i).columns() && groups.get(i).first() > u && filter.test(i))
                    {
                        found = i;
                        break;
                    }
            }
            else
            {
                int inside = groupAt(u);
                int limit = inside >= 0 ? groups.get(inside).first() : u;
                for (int i = groups.size() - 1; i >= 0; i--)
                    if ((groups.get(i).columns() || groups.get(i).first() < limit) && filter.test(i))
                    {
                        found = i;
                        break;
                    }
            }
            if (found < 0)
                return false;
            goToGroup(found);
            return true;
        }

        void goToGroup(int index)
        {
            if (index < 0 || index >= groups.size() || panes.isEmpty())
            {
                updateStatus(null);
                return;
            }
            Group group = groups.get(index);
            Pane main = panes.get(0);
            if (group.columns())
            {
                goToColumnGroup(index, group);
                return;
            }
            int row = -1;
            for (int u = group.first(); u <= group.last() && row < 0; u++)
                row = rows.index[0][u];
            if (row < 0)
                row = nextPresent(0, group.first(), Math.max(main.rowCount - 1, 0));
            int column = firstMarkedColumn(main, row);
            if (column < 0)
            {
                // Строки у основной стороны нет — первая отмеченная колонка другой стороны
                for (int p = 1; p < panes.size() && column < 0; p++)
                {
                    int otherRow = rows.index[p][group.first()];
                    int otherColumn = otherRow >= 0 ? firstMarkedColumn(panes.get(p), otherRow) : -1;
                    if (otherColumn >= 0)
                        column = columns.map(p, 0, otherColumn);
                }
            }
            currentGroup = index;
            select(main, row, Math.max(column, 0));
            syncFrom(main, Math.max(row, 0), Math.max(column, 0));
            if (resultPane != null && index < targetStart.length)
            {
                int resultColumn = resultColumns.map(0, 1, Math.max(column, 0));
                select(resultPane, targetStart[index], resultColumn);
                alignViewport(main, Math.max(row, 0), Math.max(column, 0), resultPane, targetStart[index], resultColumn);
            }
            redrawAll();
            updateStatus(null);
        }

        /**
         * Группа колонок: текущей становится первая колонка группы у стороны, где она есть; строка — текущая
         * строка основной стороны.
         */
        private void goToColumnGroup(int index, Group group)
        {
            Pane main = panes.get(0);
            Point active = main.control.isDisposed() ? null : main.control.getActiveCell();
            int mainRow = active != null ? Math.max(active.y, 0) : 0;
            Pane source = main;
            int sourceColumn = -1;
            for (int p = 0; p < panes.size() && sourceColumn < 0; p++)
                for (int v = group.first(); v <= group.last() && sourceColumn < 0; v++)
                    if (columns.index[p][v] >= 0)
                    {
                        source = panes.get(p);
                        sourceColumn = columns.index[p][v];
                    }
            if (sourceColumn < 0)
                sourceColumn = nextPresentColumn(0, group.first(), Math.max(main.columnCount - 1, 0));
            int p = panes.indexOf(source);
            int sourceRow = p == 0 ? mainRow : rows.map(0, p, mainRow);
            currentGroup = index;
            select(source, sourceRow, sourceColumn);
            syncFrom(source, Math.max(sourceRow, 0), sourceColumn);
            if (resultPane != null && index < targetStart.length)
            {
                int resultRow = resultRows.map(0, 1, mainRow);
                select(resultPane, resultRow, targetStart[index]);
                alignViewport(source, Math.max(sourceRow, 0), sourceColumn, resultPane, resultRow, targetStart[index]);
            }
            redrawAll();
            updateStatus(null);
        }

        /** Первая отмеченная колонка строки вне групп колонок (их отметки — во всех строках). */
        private static int firstMarkedColumn(Pane pane, int row)
        {
            if (row < 0 || row >= pane.marks.length || pane.marks[row] == null)
                return -1;
            for (int column = 0; column < pane.marks[row].length; column++)
                if (pane.marks[row][column] != MARK_NONE
                    && !(column < pane.inColumnGroup.length && pane.inColumnGroup[column]))
                    return column;
            return -1;
        }

        private void redrawAll()
        {
            for (Pane pane : allPanes())
                if (!pane.control.isDisposed())
                    pane.control.redraw();
            if (ruler != null && !ruler.isDisposed())
                ruler.redraw();
        }

        private void updateStatus(String override)
        {
            if (status == null || status.isDisposed())
                return;
            String text;
            if (override != null)
                text = override;
            else if (groups.isEmpty())
                text = "Отличий нет"; //$NON-NLS-1$
            else
            {
                long conflictCount = groups.stream().filter(Group::conflict).count();
                text = (currentGroup >= 0 ? "Отличие " + (currentGroup + 1) + " из " + groups.size() //$NON-NLS-1$ //$NON-NLS-2$
                    : "Отличий: " + groups.size()) //$NON-NLS-1$
                    + (conflictCount > 0 ? ", конфликтов: " + conflictCount : "") //$NON-NLS-1$ //$NON-NLS-2$
                    + (currentGroup >= 0 && groups.get(currentGroup).conflict() ? " — текущее конфликтное" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    + winnerText(currentGroup);
            }
            status.setText(text);
            status.getParent().layout(true);
            if (previous != null && !previous.isDisposed())
            {
                previous.setEnabled(!groups.isEmpty());
                next.setEnabled(!groups.isEmpty());
            }
            if (okButton != null && !okButton.isDisposed())
                okButton.setEnabled(changed);
            boolean canEdit = target != null && target.editable;
            for (ToolItem item : copyCurrentItems)
                if (!item.isDisposed())
                    item.setEnabled(canEdit && currentGroup >= 0);
            for (ToolItem item : copyAllItems)
                if (!item.isDisposed())
                    item.setEnabled(canEdit && !groups.isEmpty());
            if (ruler != null && !ruler.isDisposed())
                ruler.redraw();
        }

        // ---- отрисовка отметок (путь отрисовки: без вывода в журнал)

        /**
         * Вызывается из {@link MarkedMoxelControl#paintViewPort} — внутри штатной задачи чтения
         * отрисовки, в буфер контрола (как в edt-agent-bridge).
         */
        private void paintMarks(Pane pane, ViewPort viewPort, GC gc, Rectangle area)
        {
            MoxelControl control = pane.control;
            int p = panes.indexOf(pane);
            if ((p < 0 && pane != resultPane) || control.isDisposed())
                return;
            Group group = currentGroup >= 0 && currentGroup < groups.size() ? groups.get(currentGroup) : null;
            int oldAlpha = gc.getAlpha();
            int oldWidth = gc.getLineWidth();
            Rectangle oldClip = gc.getClipping();
            try
            {
                Rectangle device = viewPort.getDevicePosition();
                SpreadsheetRect sheetPosition = viewPort.getSheetPosition();
                if (device == null || device.isEmpty() || sheetPosition == null)
                    return;
                int firstRow = Math.max(0, sheetPosition.getBegin().getCell().getY());
                int lastRow = Math.min(pane.rowCount - 1, sheetPosition.getEnd().getCell().getY());
                gc.setClipping(area != null ? device.intersection(area) : device);
                if (pane == resultPane)
                {
                    paintResult(viewPort, gc, device, firstRow, lastRow);
                    return;
                }
                Set<Rectangle> painted = new HashSet<>();
                gc.setAlpha(MARK_ALPHA);
                for (int row = firstRow; row <= lastRow; row++)
                {
                    byte[] rowMarks = row < pane.marks.length ? pane.marks[row] : null;
                    if (rowMarks == null)
                        continue;
                    for (int column = 0; column < rowMarks.length; column++)
                    {
                        if (rowMarks[column] == MARK_NONE)
                            continue;
                        Rectangle rect = viewPort.getCellPositionPixel(row, column);
                        if (rect == null || rect.isEmpty() || !rect.intersects(device) || !painted.add(rect))
                            continue;
                        gc.setBackground(colorOf(rowMarks[column]));
                        gc.fillRectangle(rect);
                    }
                }
                gc.setAlpha(255);
                gc.setLineWidth(2);
                for (int row : pane.gapRows)
                {
                    if (row < firstRow || row > lastRow + 1)
                        continue;
                    int y = viewPort.getRowPositionPixel(row);
                    gc.setForeground(addedColor);
                    gc.drawLine(device.x, y, device.x + device.width, y);
                }
                for (int column : pane.gapColumns)
                {
                    int x = columnLeft(viewPort, pane, firstRow, column);
                    gc.setForeground(addedColor);
                    gc.drawLine(x, device.y, x, device.y + device.height);
                }
                if (group != null)
                    paintGroupFrame(pane, p, group, viewPort, device, firstRow, lastRow, gc);
            }
            catch (RuntimeException e)
            {
                lastPaintError = e;
            }
            finally
            {
                gc.setClipping(oldClip);
                gc.setAlpha(oldAlpha);
                gc.setLineWidth(oldWidth);
            }
        }

        /**
         * Строки групп в результате: зелёным — версия стороны принята, красным — конфликт без
         * решения, синим — изменение не принято или изменено вручную; рамка — текущая группа.
         */
        private void paintResult(ViewPort viewPort, GC gc, Rectangle device, int firstRow, int lastRow)
        {
            for (int g = 0; g < targetStart.length && g < groups.size(); g++)
            {
                int start = targetStart[g];
                int end = targetEnd[g];
                byte winner = winner(g);
                Color color = winner == WIN_LEFT || winner == WIN_RIGHT || winner == WIN_BOTH ? addedColor
                    : winner == WIN_ANCESTOR && groups.get(g).conflict() ? conflictColor : changedColor;
                if (groups.get(g).columns())
                {
                    paintColumnRange(viewPort, gc, device, resultPane, firstRow, start, end, color, g == currentGroup);
                    continue;
                }
                if (end < start)
                {
                    // Группа без строк в результате — линия на месте вставки
                    if (start < firstRow || start > lastRow + 1)
                        continue;
                    int y = viewPort.getRowPositionPixel(start);
                    gc.setAlpha(255);
                    gc.setLineWidth(g == currentGroup ? 4 : 2);
                    gc.setForeground(g == currentGroup ? frameColor : color);
                    gc.drawLine(device.x, y, device.x + device.width, y);
                    continue;
                }
                if (end < firstRow || start > lastRow)
                    continue;
                int y1 = viewPort.getRowPositionPixel(Math.max(start, firstRow));
                int y2 = viewPort.getRowPositionPixel(Math.min(end, lastRow) + 1);
                gc.setAlpha(MARK_ALPHA);
                gc.setBackground(color);
                gc.fillRectangle(device.x, y1, device.width, Math.max(y2 - y1, 1));
                if (g == currentGroup)
                {
                    gc.setAlpha(255);
                    gc.setLineWidth(2);
                    gc.setForeground(frameColor);
                    gc.drawRectangle(device.x + 1, y1, device.width - 3, Math.max(y2 - y1 - 1, 1));
                }
            }
        }

        /** Левая граница колонки в пикселях области просмотра. */
        private static int columnLeft(ViewPort viewPort, Pane pane, int row, int column)
        {
            if (column >= pane.columnCount && pane.columnCount > 0)
            {
                Rectangle last = viewPort.getCellPositionPixel(row, pane.columnCount - 1);
                return last.x + last.width;
            }
            return viewPort.getCellPositionPixel(row, Math.max(column, 0)).x;
        }

        /**
         * Колонки {@code start..end} во всю высоту области: заливка {@code color} (если задан) и рамка
         * текущей группы; пустой диапазон — вертикальная линия на месте вставки.
         */
        private void paintColumnRange(ViewPort viewPort, GC gc, Rectangle device, Pane pane, int row, int start,
            int end, Color color, boolean current)
        {
            int x1 = columnLeft(viewPort, pane, row, start);
            if (end < start)
            {
                gc.setAlpha(255);
                gc.setLineWidth(current ? 4 : 2);
                gc.setForeground(current || color == null ? frameColor : color);
                gc.drawLine(x1, device.y, x1, device.y + device.height);
                return;
            }
            int x2 = columnLeft(viewPort, pane, row, end + 1);
            if (color != null)
            {
                gc.setAlpha(MARK_ALPHA);
                gc.setBackground(color);
                gc.fillRectangle(x1, device.y, Math.max(x2 - x1, 1), device.height);
            }
            if (current)
            {
                gc.setAlpha(255);
                gc.setLineWidth(2);
                gc.setForeground(frameColor);
                gc.drawRectangle(x1, device.y + 1, Math.max(x2 - x1 - 1, 1), Math.max(device.height - 3, 1));
            }
        }

        private void paintGroupFrame(Pane pane, int p, Group group, ViewPort viewPort, Rectangle device,
            int firstRow, int lastRow, GC gc)
        {
            if (group.columns())
            {
                int left = -1;
                int right = -1;
                for (int v = group.first(); v <= group.last(); v++)
                {
                    int column = columns.index[p][v];
                    if (column < 0)
                        continue;
                    if (left < 0)
                        left = column;
                    right = column;
                }
                if (left < 0)
                {
                    left = nextPresentColumn(p, group.first(), pane.columnCount);
                    right = left - 1;
                }
                paintColumnRange(viewPort, gc, device, pane, firstRow, left, right, null, true);
                return;
            }
            int top = -1;
            int bottom = -1;
            for (int u = group.first(); u <= group.last(); u++)
            {
                int row = rows.index[p][u];
                if (row < 0)
                    continue;
                if (top < 0)
                    top = row;
                bottom = row;
            }
            gc.setForeground(frameColor);
            gc.setLineWidth(2);
            if (top < 0)
            {
                int anchor = nextPresent(p, group.first(), pane.rowCount);
                if (anchor < firstRow || anchor > lastRow + 1)
                    return;
                int y = viewPort.getRowPositionPixel(anchor);
                gc.setLineWidth(4);
                gc.drawLine(device.x, y, device.x + device.width, y);
                return;
            }
            if (bottom < firstRow || top > lastRow)
                return;
            int y1 = viewPort.getRowPositionPixel(Math.max(top, firstRow));
            int y2 = viewPort.getRowPositionPixel(Math.min(bottom, lastRow) + 1);
            gc.drawRectangle(device.x + 1, y1, device.width - 3, Math.max(y2 - y1 - 1, 1));
        }

        private Color colorOf(byte mark)
        {
            return switch (mark)
            {
                case MARK_ADDED -> addedColor;
                case MARK_CONFLICT -> conflictColor;
                default -> changedColor;
            };
        }
    }

    /** Последняя ошибка отрисовки отметок — выводится в журнал вне пути отрисовки. */
    private static volatile Throwable lastPaintError;

    // #region agent log — временный, #1015
    private static void dumpControls(Composite root, String stage)
    {
        if (root.isDisposed())
            return;
        Throwable paintError = lastPaintError;
        if (paintError != null)
            tempLog("dump[" + stage + "] ошибка отрисовки отметок " + stackTrace(paintError)); //$NON-NLS-1$ //$NON-NLS-2$
        for (Control child : root.getChildren())
        {
            if (child instanceof MoxelControl control)
            {
                try
                {
                    StringBuilder text = new StringBuilder("dump[" + stage + "] control=" //$NON-NLS-1$ //$NON-NLS-2$
                        + System.identityHashCode(control) + " clientArea=" + control.getClientArea() //$NON-NLS-1$
                        + " bufferImage=" + (Global.getField(control, "bufferImage") != null) //$NON-NLS-1$ //$NON-NLS-2$
                        + " activeCell=" + control.getActiveCell()); //$NON-NLS-1$
                    int index = 0;
                    for (ViewPort viewPort : control.getListOfViewPorts())
                    {
                        SpreadsheetRect sheet = viewPort.getSheetPosition();
                        text.append("\n  viewPort#").append(index++).append(" device=") //$NON-NLS-1$ //$NON-NLS-2$
                            .append(viewPort.getDevicePosition());
                        if (sheet != null)
                        {
                            int row = sheet.getBegin().getCell().getY();
                            int column = sheet.getBegin().getCell().getX();
                            text.append(" sheet=").append(column).append(':').append(row).append(" .. ") //$NON-NLS-1$ //$NON-NLS-2$
                                .append(sheet.getEnd().getCell().getX()).append(':').append(sheet.getEnd().getCell().getY())
                                .append(" firstCellPixel=").append(viewPort.getCellPositionPixel(row, column)) //$NON-NLS-1$
                                .append(" firstRowPixel=").append(viewPort.getRowPositionPixel(row)); //$NON-NLS-1$
                        }
                    }
                    tempLog(text.toString());
                }
                catch (RuntimeException | LinkageError e)
                {
                    tempLog("dump[" + stage + "] исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else if (child instanceof Composite composite)
                dumpControls(composite, stage);
        }
    }
    // #endregion

    /** Отрисовщик отметок поверх области просмотра. */
    private interface ViewPortPainter
    {
        void paint(ViewPort viewPort, GC gc, Rectangle area);
    }

    /**
     * Контрол с отметками отличий, рисуемыми в буфер после штатной отрисовки каждой области
     * просмотра (как {@code SnapshotMoxelEditor.OverlayViewer} в edt-agent-bridge).
     */
    private static final class MarkedMoxelControl extends MoxelControl
    {
        ViewPortPainter painter;

        MarkedMoxelControl(Composite parent, int style, IPresentationService presentationService)
        {
            super(parent, style, presentationService);
        }

        @Override
        public void paintViewPort(ViewPort viewPort, GC gc, Rectangle area, boolean buffered)
        {
            super.paintViewPort(viewPort, gc, area, buffered);
            ViewPortPainter current = painter;
            if (current != null)
                current.paint(viewPort, gc, area);
        }
    }

    /**
     * Сторона по схеме edt-agent-bridge ({@code SpreadsheetMergeEditor.addSheet}): документ из XML
     * прикрепляется под вымышленным FQN к частному {@code BmEditingContext} модели проекта. Контекст
     * не сохраняется, в модель проекта ничего не попадает, а контрол работает штатно: отрисовка,
     * «только чтение», ввод в ячейку с фиксацией.
     */
    private static Pane createPane(Composite parent, Side side, IDtProject dtProject,
        IPresentationService presentationService, boolean editable)
    {
        Composite panel = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        panel.setLayout(layout);

        Composite header = new Composite(panel, SWT.NONE);
        GridLayout headerLayout = new GridLayout(1, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label label = new Label(header, SWT.NONE);
        String labelText = side != null && side.label() != null ? side.label() : ""; //$NON-NLS-1$
        label.setText(labelText);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        SpreadsheetDocument document = side != null ? load(side.element(), dtProject, labelText) : null;
        if (document == null || presentationService == null)
        {
            Label absent = new Label(panel, SWT.WRAP);
            absent.setText(presentationService == null
                ? "Не удалось получить сервис представления EDT" //$NON-NLS-1$
                : "Не удалось загрузить табличный документ"); //$NON-NLS-1$
            absent.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            return null;
        }
        IEditingContext context = null;
        try
        {
            context = createPrivateContext(dtProject);
            String fqn = "ComfortSpreadsheetMerge." + UUID.randomUUID().toString().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            SpreadsheetDocument attached = context.execute(
                new AbstractBmTask<SpreadsheetDocument>("Комфорт: табличный документ стороны") //$NON-NLS-1$
                {
                    @Override
                    public SpreadsheetDocument execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        transaction.attachTopObject((IBmObject) document, fqn);
                        return (SpreadsheetDocument) transaction.getTopObjectByFqn(fqn);
                    }
                }, new NullProgressMonitor());
            // Прикрепление не должно попадать в историю отмены
            OperationHistoryFactory.getOperationHistory().dispose(context.getUndoContext(), true, true, false);
            // Стиль как у MoxelEditor.createPartControl (262914): без полос прокрутки конструктор падает
            MarkedMoxelControl control = new MarkedMoxelControl(panel,
                SWT.NO_BACKGROUND | SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI, presentationService);
            control.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            control.setEditingContext(context);
            control.setDocument(attached);
            context.execute(attached, (SpreadsheetDocument doc, IProgressMonitor monitor) -> {
                control.setReadOnlyMode(!editable);
                return null;
            }, new NullProgressMonitor());
            tempLog("[" + labelText + "] context=" + context + ", fqn=" + fqn + ", editable=" + editable //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + ", isReadOnlyMode=" + control.isReadOnlyMode()); //$NON-NLS-1$
            return new Pane(labelText, attached, control, context, fqn, editable, header);
        }
        catch (RuntimeException | LinkageError | ReflectiveOperationException e)
        {
            tempLog("[" + labelText + "] MoxelControl: исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
            if (context != null && !context.isDisposed())
                context.dispose();
            Label error = new Label(panel, SWT.WRAP);
            error.setText("Ошибка показа табличного документа: " + e); //$NON-NLS-1$
            error.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            return null;
        }
    }

    /**
     * Частный контекст редактирования модели проекта. {@code BmEditingContext} — внутренний класс
     * {@code com._1c.g5.v8.dt.core} с публичным конструктором {@code (IBmModel)}, одинаковым в
     * dt.core 26–28; так же создаёт контекст edt-agent-bridge.
     */
    private static IEditingContext createPrivateContext(IDtProject dtProject) throws ReflectiveOperationException
    {
        IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
        IBmModel model = models != null && dtProject != null ? models.getModel(dtProject.getWorkspaceProject()) : null;
        if (model == null)
            throw new IllegalStateException("Нет BM-модели проекта " + (dtProject != null ? dtProject.getName() : null)); //$NON-NLS-1$
        Class<?> type = Platform.getBundle("com._1c.g5.v8.dt.core") //$NON-NLS-1$
            .loadClass("com._1c.g5.v8.dt.internal.core.operations.model.BmEditingContext"); //$NON-NLS-1$
        IEditingContext context = (IEditingContext) type.getConstructor(IBmModel.class).newInstance(model);
        context.initialize("Комфорт: табличное объединение " + UUID.randomUUID()); //$NON-NLS-1$
        return context;
    }

    /** Документ стороны из XML штатным ресурсом {@code mxlx} (как {@code SpreadsheetMergeCodec.load} образца). */
    private static SpreadsheetDocument load(ITypedElement element, IDtProject dtProject, String tag)
    {
        if (!(element instanceof IStreamContentAccessor accessor))
        {
            tempLog("[" + tag + "] load: нет содержимого, element=" + element); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        byte[] content;
        try (InputStream stream = accessor.getContents())
        {
            content = stream != null ? stream.readAllBytes() : null;
        }
        catch (Exception e)
        {
            tempLog("[" + tag + "] load: чтение содержимого, исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        if (content == null || content.length == 0)
        {
            tempLog("[" + tag + "] load: содержимое пусто"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        try
        {
            URI uri = URI.createURI("tormozit-spreadsheet-merge:/" + System.identityHashCode(element) + ".mxlx"); //$NON-NLS-1$ //$NON-NLS-2$
            MoxelResourceMxlx resource = new MoxelResourceMxlx(uri);
            resource.setDtProject(dtProject);
            resource.load(new ByteArrayInputStream(content), Map.of());
            List<EObject> contents = resource.getContents();
            EObject root = contents.isEmpty() ? null : contents.get(0);
            if (root instanceof SpreadsheetDocument document)
                return document;
            tempLog("[" + tag + "] load: root=" + (root != null ? root.eClass().getName() : null) //$NON-NLS-1$ //$NON-NLS-2$
                + ", errors=" + resource.getErrors()); //$NON-NLS-1$
            return null;
        }
        catch (Exception | LinkageError e)
        {
            tempLog("[" + tag + "] load: исключение " + stackTrace(e)); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static String describe(Side side)
    {
        if (side == null)
            return "null"; //$NON-NLS-1$
        ITypedElement element = side.element();
        return "'" + side.label() + "' " + (element != null //$NON-NLS-1$ //$NON-NLS-2$
            ? element.getClass().getName() + " name=" + element.getName() + " type=" + element.getType() //$NON-NLS-1$ //$NON-NLS-2$
            : "без элемента"); //$NON-NLS-1$
    }

    private static String stackTrace(Throwable e)
    {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void tempLog(String text)
    {
        Global.tempLog(TEMP_LOG, text);
    }
}
