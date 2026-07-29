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

import java.util.HashSet;
import java.util.Set;

/**
 * Штатный {@code selectAndReveal}/{@code revealRange} (переход по «Иерархии вызовов», «Перейти к
 * определению», брейкпоинтам и т.п. на уже открытый BSL-редактор) подкручивает окно по горизонтали
 * так, что строка технически видна, но неудобно — например, у самого правого края, без контекста
 * слева. Раньше это лечилось точечно, только для перехода из панелей поиска (см.
 * {@link SearchMatchScrollSupport}, вызывался из {@code ConfigSearchResultsHook} /
 * {@code FileSearchResultsHook} сразу после открытия). Здесь то же самое включено на уровне самого
 * редактора — единый слушатель ловит любой прыжок каретки, а не конкретный путь навигации.
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
 * страницы «Модуль» в granular-редакторе) наш {@code attachToBslEditor} выполняется через
 * {@code asyncExec} на тик позже, чем уже успевший отработать штатный reveal — сравнивать
 * {@code getHorizontalPixel()} не с чем, «прыжок» этим способом не поймать. Поэтому на самом
 * подключении текущее выделение сразу приводится к тому же виду, не дожидаясь очередного
 * {@code selectionChanged}.
 */
public class BslEditorRevealScrollFixHook implements IStartup
{
    private static final String INSTALLED_MARKER = "tormozit.bslRevealScrollFixInstalled"; //$NON-NLS-1$
    private static final String LAST_SCROLL_MARKER = "tormozit.bslRevealScrollFixLastPixel"; //$NON-NLS-1$
    private static final int MAX_ATTACH_ATTEMPTS = 100;

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

        if (Boolean.TRUE.equals(textWidget.getData(INSTALLED_MARKER)))
            return;
        textWidget.setData(INSTALLED_MARKER, Boolean.TRUE);

        // К моменту подключения (мы приходим сюда через asyncExec) штатный selectAndReveal у
        // свежесозданного виджета уже мог отработать — сравнивать не с чем, «прошлого» состояния
        // без прыжка мы не видели. Поэтому сразу приводим текущее выделение к тому же виду, не
        // дожидаясь следующего selectionChanged.
        Point initialSelection = textWidget.getSelectionRange();
        if (initialSelection != null)
        {
            SearchMatchScrollSupport.applyLeftmost(textWidget, initialSelection.x,
                initialSelection.x + Math.max(0, initialSelection.y));
        }
        textWidget.setData(LAST_SCROLL_MARKER, textWidget.getHorizontalPixel());

        if (viewer.getSelectionProvider() == null)
            return;

        ISelectionChangedListener selListener = event -> onSelectionChanged(textWidget);
        viewer.getSelectionProvider().addSelectionChangedListener(selListener);
        textWidget.addDisposeListener(e -> viewer.getSelectionProvider().removeSelectionChangedListener(selListener));

        // Прокрутка колёсиком/драгом скроллбара без движения каретки не порождает
        // selectionChanged — держим кэш «последней известной» позиции в курсе и от неё,
        // иначе следующий обычный клик по уже видимому месту ошибочно примем за прыжок.
        ScrollBar hBar = textWidget.getHorizontalBar();
        if (hBar != null)
        {
            hBar.addListener(SWT.Selection, e -> textWidget.setData(LAST_SCROLL_MARKER, textWidget.getHorizontalPixel()));
        }
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    // =========================================================================
    // Коррекция прокрутки
    // =========================================================================

    private static void onSelectionChanged(StyledText textWidget)
    {
        Display.getDefault().asyncExec(() -> fixHorizontalScrollIfJumped(textWidget));
    }

    private static void fixHorizontalScrollIfJumped(StyledText textWidget)
    {
        if (textWidget.isDisposed())
            return;
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
