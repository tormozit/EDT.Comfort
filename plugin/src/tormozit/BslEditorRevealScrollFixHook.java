package tormozit;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
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
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

import java.util.HashSet;
import java.util.Set;

/**
 * Штатный {@code selectAndReveal}/{@code revealRange} (переход по «Иерархии вызовов», «Перейти к
 * определению», по вхождениям F3/Ctrl+F3, брейкпоинтам и т.п. на уже открытый редактор) подкручивает
 * окно по горизонтали
 * так, что строка технически видна, но неудобно — например, у самого правого края, без контекста
 * слева. Раньше это лечилось точечно, только для перехода из панелей поиска (см.
 * {@link SearchMatchScrollSupport}, вызывался из {@code ConfigSearchResultsHook} /
 * {@code FileSearchResultsHook} сразу после открытия). Здесь то же самое включено на уровне самого
 * редактора — единый слушатель ловит любой прыжок каретки, а не конкретный путь навигации.
 *
 * <p>Кроме BSL-редакторов подключается ко всем прочим текстовым редакторам (XML, простой
 * текстовый, {@code .log}) — через общий {@link TextEditor#resolveTextEditor}: подключение
 * одно и то же, отличается только способ добраться до {@link ISourceViewer}.
 *
 * <p>Отличить «настоящий» прыжок (revealRange уже успел подкрутить экран до срабатывания нашего
 * listener'а — реальный порядок в {@code AbstractTextEditor.selectAndReveal}: сначала
 * {@code revealRange}, потом {@code setSelectedRange}) от обычного клика/печати — по факту изменения
 * {@code getHorizontalPixel()} между двумя срабатываниями {@code selectionChanged}: обычный клик
 * возможен только по уже видимому месту (прокрутка не меняется), обычная печать штатно скроллит по
 * минимуму. Кэш последней позиции обновляется и от горизонтального скроллбара (колесо/драг — они не
 * порождают selectionChanged), иначе следующий не связанный с ними клик ошибочно принял бы её за
 * прыжок. {@code HorizontalScrollBarPositionFixHook} корректирует позицию только в ответ на
 * настоящие нативные {@code SWT.Selection} события (детаl {@code SWT.DRAG}) — отдельного пути
 * рассылки нет, сюда он долетает штатно вместе с остальными подписчиками того же события.
 *
 * <p>Для свежесозданного виджета (переход в модуль, который ещё не был открыт / переключение
 * страницы «Модуль» в granular-редакторе) наш {@code attachToBslEditor} выполняется
 * синхронно, но штатный reveal уже мог отработать — сравнивать
 * {@code getHorizontalPixel()} не с чем, «прыжок» этим способом не поймать. Поэтому при
 * первом рисовании текущее выделение приводится к тому же виду, не дожидаясь очередного
 * {@code selectionChanged}.
 *
 * <p>Восстановление редакторов при старте EDT — тот же штатный {@code selectAndReveal}, но
 * раньше хук его часто пропускал: активный редактор не находился через
 * {@code IEditorReference.getEditor(false)} (см. {@link BslModulePositionMemoryHook}),
 * вложенный BSL-редактор granular-страницы «Модуль» ещё не был создан, а
 * {@code applyLeftmost} при нулевой ширине клиентской области сам ставил прокрутку по каретке.
 * Подключение совпадает с соседними хуками; первое применение идёт при ненулевой ширине
 * в фильтре {@code SWT.Paint}, который завершает коррекцию до внутреннего
 * {@code StyledText.handlePaint}: первый кадр уже использует правильную прокрутку.
 */
public class BslEditorRevealScrollFixHook implements IStartup
{
    private static final String INSTALLED_MARKER = "tormozit.bslRevealScrollFixInstalled"; //$NON-NLS-1$
    private static final String LAST_SCROLL_MARKER = "tormozit.bslRevealScrollFixLastPixel"; //$NON-NLS-1$
    private static final String USER_HSCROLL_MARKER = "tormozit.bslRevealScrollFixUserHScroll"; //$NON-NLS-1$
    private static final String INITIAL_PAINT_MARKER = "tormozit.bslRevealScrollFixInitialPaint"; //$NON-NLS-1$
    private static final String SELECTION_PENDING_MARKER = "tormozit.bslRevealScrollFixSelectionPending"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;

    private final Set<DtGranularEditor<?>> hookedGranularEditors = new HashSet<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display.getDefault().addFilter(SWT.Paint, BslEditorRevealScrollFixHook::beforeTextPaint);
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
            @Override public void partHidden(IWorkbenchPartReference r)      {}
            @Override public void partVisible(IWorkbenchPartReference r)     {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor)editor);
        else if (editor instanceof DtGranularEditor<?>)
            hookGranularEditor((DtGranularEditor<?>)editor);
        else
            hookPlainTextEditor(editor);
    }

    /**
     * Прочие текстовые редакторы (XML, простой текстовый, {@code .log} и т.п.) — через общий
     * {@link TextEditor#resolveTextEditor(org.eclipse.ui.IWorkbenchPart)}: переход по
     * вхождениям (F3/Ctrl+F3), к определению и т.п. должен так же оставлять горизонтальную
     * прокрутку в самом левом положении, при котором вхождение видно целиком.
     */
    private void hookPlainTextEditor(IEditorPart editor)
    {
        ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
        if (textEditor == null)
            return;
        attachToTextEditor(textEditor, 0);
    }

    private void attachToTextEditor(ITextEditor editor, int attempt)
    {
        if (editor.getSite() == null || isWorkbenchClosing())
            return;
        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        if (viewer == null || viewer.getTextWidget() == null)
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachToTextEditor(editor, attempt + 1));
            return;
        }
        attachToViewer(viewer);
    }

    private void hookGranularEditor(DtGranularEditor<?> editor)
    {
        hookGranularEditorActivePage(editor, 0);

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
                        else
                            hookGranularEditorActivePage(editor, 0);
                    }
                }
            });
        }
    }

    private void hookGranularEditorActivePage(DtGranularEditor<?> editor, int attempt)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?>))
            return;
        IEditorPart embedded =
            ((DtGranularEditorXtextEditorPage<?>)activePage).getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor)
        {
            hookBslEditor((BslXtextEditor)embedded);
            return;
        }
        if (attempt >= MAX_ATTACH_ATTEMPTS || isWorkbenchClosing())
            return;
        Display.getDefault().asyncExec(() -> hookGranularEditorActivePage(editor, attempt + 1));
    }

    private void hookBslEditor(BslXtextEditor editor)
    {
        // Готовый viewer подключаем до возврата из partOpened/pageChanged, без очереди UI.
        attachToBslEditor(editor, 0);
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

        attachToViewer(viewer);
    }

    /** Общая часть подключения к полю редактора — одинакова для BSL и прочих редакторов. */
    private static void attachToViewer(ISourceViewer viewer)
    {
        StyledText textWidget = viewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        if (Boolean.TRUE.equals(textWidget.getData(INSTALLED_MARKER)))
            return;
        textWidget.setData(INSTALLED_MARKER, Boolean.TRUE);
        textWidget.setData(INITIAL_PAINT_MARKER, Boolean.TRUE);

        if (viewer.getSelectionProvider() != null)
        {
            ISelectionChangedListener selListener = event -> onSelectionChanged(textWidget);
            viewer.getSelectionProvider().addSelectionChangedListener(selListener);
            textWidget.addDisposeListener(e -> viewer.getSelectionProvider()
                .removeSelectionChangedListener(selListener));
        }

        // Прокрутка колёсиком/драгом скроллбара без движения каретки не порождает
        // selectionChanged — держим кэш «последней известной» позиции в курсе и от неё,
        // иначе следующий обычный клик по уже видимому месту ошибочно примем за прыжок.
        // Нативный SWT.Selection полосы — жест пользователя: дальше не перебиваем прокрутку
        // отложенным apply при restore.
        ScrollBar hBar = textWidget.getHorizontalBar();
        if (hBar != null)
        {
            hBar.addListener(SWT.Selection, e ->
            {
                textWidget.setData(LAST_SCROLL_MARKER, textWidget.getHorizontalPixel());
                textWidget.setData(USER_HSCROLL_MARKER, Boolean.TRUE);
            });
        }

        // Не рассчитываем геометрию на attach/Resize: только один раз перед первым Paint,
        // когда размеры уже известны и штатное восстановление позиции отработало.
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    // =========================================================================
    // Коррекция прокрутки
    // =========================================================================

    private static void applyLeftmostIfReady(StyledText textWidget)
    {
        if (textWidget.isDisposed())
            return;
        if (Boolean.TRUE.equals(textWidget.getData(USER_HSCROLL_MARKER)))
            return;
        if (textWidget.getClientArea().width <= 0)
            return;
        // Уже самое левое положение: штатный reveal не сдвинул экран, исправлять нечего.
        if (textWidget.getHorizontalPixel() == 0)
        {
            textWidget.setData(LAST_SCROLL_MARKER, 0);
            return;
        }
        Point selection = textWidget.getSelectionRange();
        if (selection == null)
            return;
        SearchMatchScrollSupport.applyLeftmost(textWidget, selection.x,
            selection.x + Math.max(0, selection.y));
        textWidget.setData(LAST_SCROLL_MARKER, textWidget.getHorizontalPixel());
    }

    private static void beforeTextPaint(Event event)
    {
        if (!(event.widget instanceof StyledText textWidget) || textWidget.isDisposed()
            || !Boolean.TRUE.equals(textWidget.getData(INSTALLED_MARKER)))
            return;
        if (Boolean.TRUE.equals(textWidget.getData(INITIAL_PAINT_MARKER)))
        {
            if (textWidget.getClientArea().width <= 0 || event.width <= 0 || event.height <= 0)
                return;
            // Снимаем маркер до setHorizontalPixel: прокрутка может вызвать вложенную отрисовку.
            textWidget.setData(INITIAL_PAINT_MARKER, null);
            applyLeftmostIfReady(textWidget);
        }
        if (Boolean.TRUE.equals(textWidget.getData(SELECTION_PENDING_MARKER)))
            fixHorizontalScrollIfJumped(textWidget);
    }

    private static void onSelectionChanged(StyledText textWidget)
    {
        textWidget.setData(SELECTION_PENDING_MARKER, Boolean.TRUE);
        // Paint-фильтр успевает раньше внутреннего painter даже при занятой очереди asyncExec.
        // Очередь остаётся резервом для переходов, которые не вызвали отрисовку.
        Display.getDefault().asyncExec(() -> fixHorizontalScrollIfJumped(textWidget));
    }

    private static void fixHorizontalScrollIfJumped(StyledText textWidget)
    {
        if (textWidget.isDisposed())
            return;
        if (!Boolean.TRUE.equals(textWidget.getData(SELECTION_PENDING_MARKER)))
            return;
        if (textWidget.getClientArea().width <= 0)
            return;
        textWidget.setData(SELECTION_PENDING_MARKER, null);
        Point selection = textWidget.getSelectionRange(); // x=начало, y=длина
        if (selection == null)
            return;

        int scrollNow = textWidget.getHorizontalPixel();
        Object prevData = textWidget.getData(LAST_SCROLL_MARKER);
        int prevScroll = prevData instanceof Integer ? (Integer)prevData : scrollNow;

        if (scrollNow != prevScroll)
        {
            // Штатный revealRange уже подкрутил экран (в AbstractTextEditor.selectAndReveal он
            // вызывается раньше setSelectedRange, т.е. раньше этого listener'а) — заменяем его
            // результат на подкрутку до самой левой позиции, при которой вхождение видно целиком.
            SearchMatchScrollSupport.applyLeftmost(textWidget, selection.x, selection.x + Math.max(0, selection.y));
        }

        textWidget.setData(LAST_SCROLL_MARKER, textWidget.getHorizontalPixel());
    }
}
