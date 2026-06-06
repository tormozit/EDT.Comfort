package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Добавляет пункт «Редактировать вложенный текст» в контекстное меню
 * BSL-редактора EDT.
 *
 * <p>Поддерживает оба варианта открытия BSL-редактора:
 * <ul>
 *   <li><b>Автономный</b> — {@link BslXtextEditor} открыт напрямую
 *       (файл *.bsl / ресурс, открытый через «Открыть как текст»).</li>
 *   <li><b>Встроенный</b> — {@link BslXtextEditor} является страницей
 *       многостраничного {@link DtGranularEditor} (стандартный редактор
 *       модуля EDT: «Текст модуля», «Форма», «Права» и т. д.).</li>
 * </ul>
 *
 * <h3>Механизм внедрения меню</h3>
 * <p>В отличие от подхода через {@code org.eclipse.ui.popupMenus} /
 * {@code org.eclipse.ui.menus}, данный хук использует SWT {@link MenuAdapter},
 * что позволяет динамически добавлять/удалять пункты и не требует регистрации
 * дополнительных extension point'ов.</p>
 *
 * <h3>Жизненный цикл</h3>
 * <ol>
 *   <li>{@code earlyStartup()} → подписывается на все текущие и будущие окна.</li>
 *   <li>Для каждого окна — слушает открытие редакторов ({@link IPartListener2}).</li>
 *   <li>При открытии редактора подходящего типа — прикрепляет меню через
 *       {@link #attachMenuToBslEditor}.</li>
 * </ol>
 */
public class BSLEditorMenuHook implements IStartup
{
    private static final String ITEM_TEXT_EditEmbedded = "Вложенный текст ИР";

    /**
     * Ключ SWT-данных для маркировки «меню уже прикреплено».
     * Храним на виджете {@link StyledText}, чтобы не дублировать при повторном вызове.
     */
    private static final String HOOK_MARKER = "tormozit.bslMenuHooked"; //$NON-NLS-1$

    /**
     * Набор DtGranularEditor-ов, к которым уже добавлен IPageChangedListener.
     * WeakHashMap без значений используется как WeakHashSet: не удерживает редакторы в памяти.
     */
    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    // =========================================================================
    // IStartup
    // =========================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?

            // Подписка на будущие окна — сразу, без задержки.
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            // Для уже открытых редакторов (восстановлённая сессия) — даём EDT время
            // завершить инициализацию маркеров (DtGranularEditorMarkerSupport),
            // иначе race condition при старте приводит к NPE в BuildMarkersJob.
//            Display.getDefault().timerExec(3000, () ->
//            {
                for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                    hookWindow(w);
//            });
        });
    }

    // =========================================================================
    // Подключение к окну / редактору
    // =========================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        // Уже открытые редакторы (восстановленная сессия)
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null) hookEditorIfNeeded(ed);
            }
        }

        // Будущие редакторы
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference)) return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null) hookEditorIfNeeded(ed);
            }

            @Override public void partActivated(IWorkbenchPartReference r)    {}
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
            hookBslEditor((BslXtextEditor) editor);
        else if (editor instanceof DtGranularEditor<?>)
            hookGranularEditor((DtGranularEditor<?>) editor);
    }

    // =========================================================================
    // Автономный BslXtextEditor
    // =========================================================================

    private void hookBslEditor(BslXtextEditor editor)
    {
        // Попытка немедленно, если виджет ещё не готов — повтор через asyncExec
        Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor));
    }

    /**
     * Прикрепляет {@link MenuAdapter} к SWT-меню StyledText виджета редактора.
     * Если виджет ещё не создан или меню не установлено — планирует повтор.
     * Дублирование предотвращается флагом {@link #HOOK_MARKER} на виджете.
     */
    private void attachMenuToBslEditor(BslXtextEditor editor)
    {
        if (editor.getSite() == null) return; // редактор ещё не инициализирован

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer))
        {
            // Viewer ещё не создан — попробуем чуть позже
            Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor));
            return;
        }

        StyledText textWidget = ((SourceViewer) viewer).getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        // Защита от повторного прикрепления
        if (Boolean.TRUE.equals(textWidget.getData(HOOK_MARKER)))
            return;

        Menu menu = textWidget.getMenu();
        if (menu == null || menu.isDisposed())
        {
            // Меню ещё не создано (XtextEditor регистрирует его позже)
            Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor));
            return;
        }

        textWidget.setData(HOOK_MARKER, Boolean.TRUE);

        MenuAdapter listener = buildMenuListener(editor);
        menu.addMenuListener(listener);

        // Очистка при уничтожении виджета
        textWidget.addDisposeListener(e ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    // =========================================================================
    // Многостраничный DtGranularEditor (редактор модуля EDT)
    // =========================================================================

    private void hookGranularEditor(DtGranularEditor<?> editor)
    {
        // Текущая активная страница (если уже открыта нужная)
        hookGranularEditorActivePage(editor);

        // Подписываемся на смену страниц (одна подписка на редактор)
        if (hookedGranularEditors.add(editor))
        {
            IPageChangedListener pageListener = new IPageChangedListener()
            {
                @Override
                public void pageChanged(PageChangedEvent event)
                {
                    Object selectedPage = event.getSelectedPage();
                    if (selectedPage instanceof DtGranularEditorXtextEditorPage<?>)
                    {
                        IEditorPart embedded =
                            ((DtGranularEditorXtextEditorPage<?>) selectedPage)
                                .getEmbeddedEditor();
                        if (embedded instanceof BslXtextEditor)
                            hookBslEditor((BslXtextEditor) embedded);
                    }
                }
            };
            editor.addPageChangedListener(pageListener);
        }
    }

    private void hookGranularEditorActivePage(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?>)) return;
        IEditorPart embedded =
            ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor) embedded);
    }

    // =========================================================================
    // Построение MenuAdapter
    // =========================================================================

    /**
     * Создаёт MenuAdapter, который при показе меню добавляет пункт
     * «Редактировать вложенный текст», а при скрытии — удаляет его.
     *
     * <p>Динамическое добавление/удаление (в отличие от статической регистрации)
     * позволяет проверить перед показом, применима ли команда в текущем контексте.
     */
    private static MenuAdapter buildMenuListener(BslXtextEditor editor)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                if (!EditEmbeddedTextHandler.isApplicable(editor))
                    return;

                Menu menu = (Menu) e.widget;

//                addedItems.add(new MenuItem(menu, SWT.SEPARATOR));

                MenuItem item = new MenuItem(menu, SWT.PUSH);
                item.setText(ITEM_TEXT_EditEmbedded);
                item.setToolTipText(
                    "Открыть вложенный текст в редакторе текста приложения ИР (Comfort)");
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        EditEmbeddedTextHandler.editEmbeddedText(editor);
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() ->
                {
                    for (MenuItem mi : toDispose)
                        if (!mi.isDisposed()) mi.dispose();
                });
            }
        };
    }
}
