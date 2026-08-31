package tormozit;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Реестр панелей текста в окнах и диалогах сравнения/объединения модулей: для каждой
 * панели ({@link StyledText}) — файл соответствующей стороны сравнения (обе стороны — один
 * и тот же модуль, разные ревизии).
 *
 * <p>Нужен глобальной команде «Копировать ссылку» ({@link GetRef}): панель сравнения — не
 * BSL-редактор, поэтому штатный путь резолва до строки модуля до неё не доходит, и команда
 * копировала ссылку на модуль целиком. Когда фокус ввода в такой панели, команда должна
 * копировать ссылку на строку модуля под кареткой панели.
 *
 * <p>Заполняют {@link CompareEditorCurrentLinesHook}, {@link CompareDialogCurrentLinesHook}
 * и {@link TextMergeEditorHook} сразу после извлечения {@code fLeft}/{@code fRight}
 * (и {@code resultViewer}) вьюера. Ключи слабые, запись снимается при уничтожении виджета.
 */
public final class CompareModuleLineRef
{
    private static final Map<StyledText, IFile> SIDE_FILES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private CompareModuleLineRef()
    {
    }

    /** Регистрирует панель сравнения и файл её стороны. */
    public static void registerSide(StyledText widget, IFile sideFile)
    {
        if (widget == null || widget.isDisposed() || sideFile == null)
            return;
        SIDE_FILES.put(widget, sideFile);
        widget.addDisposeListener(e -> SIDE_FILES.remove(widget));
    }

    /**
     * Если фокус ввода сейчас в зарегистрированной панели сравнения модуля — строит и
     * копирует ссылку на строку модуля под кареткой этой панели.
     *
     * @return {@code true}, если команду «Копировать ссылку» обработали здесь
     */
    public static boolean tryCopyLineRefForFocusedSide(Shell shell)
    {
        Display display = Display.getCurrent() != null ? Display.getCurrent() : Display.getDefault();
        if (display == null || display.isDisposed())
            return false;
        Control focus = display.getFocusControl();
        if (!(focus instanceof StyledText widget) || widget.isDisposed())
            return false;
        IFile sideFile = SIDE_FILES.get(widget);
        if (sideFile == null)
            return false;

        int caretOffset = widget.getCaretOffset();
        int line0 = widget.getLineAtOffset(caretOffset);
        int column = caretOffset - widget.getOffsetAtLine(line0);
        Shell targetShell = shell != null && !shell.isDisposed() ? shell : widget.getShell();
        if (targetShell == null || targetShell.isDisposed())
            return false;
        return GetRef.copyModuleLineRefFromCompare(sideFile, widget.getText(), line0, column, targetShell);
    }
}
