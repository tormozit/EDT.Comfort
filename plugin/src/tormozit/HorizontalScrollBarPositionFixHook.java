package tormozit;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Перетаскивание thumb горизонтальной полосы прокрутки BSL-редактора за пределы узкой зоны
 * трека у нативного Win32-скроллбара «отменяет» drag и откатывает позицию назад — так ведёт
 * себя внутренний трек-цикл ОС (решение об откате принимается до отправки WM_HSCROLL,
 * перехватить это сообщением нельзя — к моменту, когда оно приходит, позиция уже испорчена
 * самой ОС).
 *
 * <p>Первая версия этого фикса (см. историю: {@code HorizontalScrollDragFixHook} /
 * {@code ScrollDragFixHook}) полностью подменяла отрисовку скроллбара своим Canvas-оверлеем —
 * подход оказался хрупким (гонки при замере цвета с экрана, невидимый скроллбар на части
 * редакторов) и был отклонён.
 *
 * <p>Этот вариант нативную полосу не трогает вообще — она продолжает рисоваться и вести себя
 * штатно. Вместо этого во время drag'а (у {@code ScrollBar.Selection} детаl {@link SWT#DRAG})
 * положение thumb пересчитывается заново из настоящей текущей позиции курсора мыши
 * ({@link Display#getCursorLocation()}, координата не ограничена границами полосы) и
 * принудительно возвращается через {@code StyledText.setHorizontalPixel} — тем самым «поверх»
 * значения, которое ОС уже успела откатить. Смещение курсора относительно начала захваченного
 * thumb фиксируется в момент первого drag-события, чтобы thumb не «прыгал» под курсор, а
 * продолжал тянуться за той же точкой, за которую его схватили — как и в обычном
 * перетаскивании.
 */
public class HorizontalScrollBarPositionFixHook implements IStartup
{
    private static final String INSTALLED_MARKER = "tormozit.hScrollPositionFixInstalled"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;
    /** Оценка ширины квадратной кнопки-стрелки скроллбара (SM_CXHSCROLL на большинстве систем). */
    private static final int FALLBACK_BUTTON_WIDTH = 17;

    private final Set<DtGranularEditor<?>> hookedGranularEditors = new HashSet<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
        });
    }

    // =========================================================================
    // Подключение к окну / редактору (см. BslModulePositionMemoryHook)
    // =========================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            // Активный редактор при старте EDT восстановлен синхронно (уже виден на экране),
            // но остальные вкладки — лениво: ref.getEditor(false) для них вернёт null, и это
            // нормально, partActivated поймает их при реальном переключении позже. А для уже
            // активного (значит, точно материализованного) редактора getEditor(false) мог
            // вернуть null из-за гонки между own asyncExec здесь и восстановлением состояния
            // workbench — тогда partActivated для него уже не прилетит. Поэтому активный
            // редактор достаётся отдельно, напрямую через getActiveEditor(), в обход ref.
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
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference)ref).getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference)ref).getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor)editor);
        else if (editor instanceof DtGranularEditor<?>)
            hookGranularEditor((DtGranularEditor<?>)editor);
    }

    private void hookGranularEditor(DtGranularEditor<?> editor)
    {
        hookGranularEditorActivePage(editor);

        if (hookedGranularEditors.add(editor))
        {
            editor.addPageChangedListener(new IPageChangedListener()
            {
                @Override
                public void pageChanged(PageChangedEvent event)
                {
                    Object selectedPage = event.getSelectedPage();
                    if (selectedPage instanceof DtGranularEditorXtextEditorPage<?>)
                    {
                        IEditorPart embedded =
                            ((DtGranularEditorXtextEditorPage<?>)selectedPage).getEmbeddedEditor();
                        if (embedded instanceof BslXtextEditor)
                            hookBslEditor((BslXtextEditor)embedded);
                    }
                }
            });
        }
    }

    private void hookGranularEditorActivePage(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?>))
            return;
        IEditorPart embedded =
            ((DtGranularEditorXtextEditorPage<?>)activePage).getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor)embedded);
    }

    private void hookBslEditor(BslXtextEditor editor)
    {
        Display.getDefault().asyncExec(() -> attachToBslEditor(editor, 0));
    }

    private void attachToBslEditor(BslXtextEditor editor, int attempt)
    {
        if (editor.getSite() == null || isWorkbenchClosing())
            return;

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer))
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachToBslEditor(editor, attempt + 1));
            return;
        }

        StyledText textWidget = ((SourceViewer)viewer).getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        if (!Boolean.TRUE.equals(textWidget.getData(INSTALLED_MARKER)))
        {
            textWidget.setData(INSTALLED_MARKER, Boolean.TRUE);
            new DragPositionFix(textWidget).install();
        }
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    // =========================================================================
    // Коррекция положения во время drag (см. Javadoc класса)
    // =========================================================================

    private static final class DragPositionFix
    {
        private final StyledText textWidget;
        private boolean dragging;
        /** Смещение курсора относительно начала thumb на момент захвата — сохраняет точку хвата. */
        private int dragOffsetPx;

        DragPositionFix(StyledText textWidget)
        {
            this.textWidget = textWidget;
        }

        void install()
        {
            ScrollBar hBar = textWidget.getHorizontalBar();
            if (hBar == null)
                return;

            hBar.addListener(SWT.Selection, this::onSelection);
        }

        private void onSelection(Event event)
        {
            if (textWidget.isDisposed())
                return;
            ScrollBar hBar = textWidget.getHorizontalBar();
            if (hBar == null || hBar.isDisposed())
                return;

            boolean isDragEvent = event.detail == SWT.DRAG;
            if (!isDragEvent && !dragging)
            {
                // Обычный клик по стрелке/треку, колесо, программная установка вне drag —
                // не наша забота, доверяем нативному значению как есть.
                return;
            }
            // Если !isDragEvent, но dragging==true — это финальное событие при отпускании
            // мыши (SB_THUMBPOSITION/SB_ENDSCROLL, detail=SWT.NONE). ОС в этот момент шлёт
            // СВОЁ (тоже испорченное, если отпустили далеко от полосы) значение — раньше оно
            // просто пропускалось необработанным и затирало нашу коррекцию, сделанную на
            // последнем SWT.DRAG-событии. Поэтому корректируем и это событие тоже, тем же
            // способом (по живой позиции курсора), и только потом закрываем сессию drag.

            int min = hBar.getMinimum();
            int max = hBar.getMaximum();
            int thumb = hBar.getThumb();
            int range = max - min;
            if (range <= 0)
                return;

            Rectangle textBounds = textWidget.getBounds();
            ScrollBar vBar = textWidget.getVerticalBar();
            int vWidth = (vBar != null && vBar.isVisible()) ? Math.max(vBar.getSize().x, FALLBACK_BUTTON_WIDTH) : 0;
            int fullWidth = Math.max(0, textBounds.width - vWidth);

            int buttonWidth = Math.min(FALLBACK_BUTTON_WIDTH, fullWidth / 2);
            int trackWidth = Math.max(0, fullWidth - 2 * buttonWidth);
            if (trackWidth <= 0)
                return;

            int thumbWidthPx = Math.max(1, Math.min(trackWidth, Math.round(trackWidth * (float)thumb / range)));
            int maxThumbX = Math.max(0, trackWidth - thumbWidthPx);
            int selRange = Math.max(1, range - thumb);

            Point cursor = textWidget.getDisplay().getCursorLocation();
            Point local = textWidget.toControl(cursor);
            // Не ограничиваем local.x границами полосы — курсор может уйти сколь угодно
            // далеко в любую сторону, именно в этом и была изначальная проблема.
            int cursorTrackX = local.x - buttonWidth;

            if (!dragging)
            {
                dragging = true;
                int currentThumbX = maxThumbX > 0
                    ? Math.round((hBar.getSelection() - min) * (float)maxThumbX / selRange)
                    : 0;
                dragOffsetPx = cursorTrackX - currentThumbX;
            }

            int desiredThumbX = clamp(cursorTrackX - dragOffsetPx, 0, maxThumbX);
            int desiredSelection = maxThumbX > 0
                ? min + Math.round(desiredThumbX * (float)selRange / maxThumbX)
                : min;
            desiredSelection = clamp(desiredSelection, min, max - thumb);

            if (desiredSelection != textWidget.getHorizontalPixel())
                textWidget.setHorizontalPixel(desiredSelection);

            if (!isDragEvent)
                dragging = false;
        }

        private static int clamp(int value, int min, int max)
        {
            if (min > max)
                return min;
            return Math.max(min, Math.min(max, value));
        }
    }
}
