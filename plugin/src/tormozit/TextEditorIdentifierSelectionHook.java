package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;
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
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.preferences.IPreferenceStoreAccess;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * В текстовых редакторах EDT, а также в любых полях ввода ({@link org.eclipse.swt.widgets.Text},
 * {@link org.eclipse.swt.widgets.Combo}) Ctrl+←/→ и Ctrl+Shift+←/→ используют границу идентификатора
 * (буквы, цифры, {@code _}), а не sub-word в CamelCase. Там же, в полях ввода, Ctrl+клик и двойной
 * клик выделяют слово по границам идентификатора. В редакторах Ctrl+клик и двойной клик не трогаем:
 * там свои механизмы (гиперссылки, {@link TextEditorCtrlClickSelectWordHook}).
 */
public final class TextEditorIdentifierSelectionHook implements IStartup
{
    private static final String XTEXT_UI_PLUGIN = "org.eclipse.xtext.ui"; //$NON-NLS-1$
    private static final String SUB_WORD_NAVIGATION = "subWordNavigation"; //$NON-NLS-1$
    private static final String KEY_INTERCEPTOR_KEY = "tormozit.identifierWordKeyInterceptor"; //$NON-NLS-1$
    private static final String EDITOR_ACTIONS_KEY = "tormozit.identifierWordEditorActions"; //$NON-NLS-1$

    /** Ключ SWT-данных: слово для выделения после отпускания кнопки (Ctrl+клик). */
    private static final String PENDING_WORD_KEY = "tormozit.identifierWordPending"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            disableXtextSubWordNavigation();
            install(Display.getDefault());
            hookWorkbench();
        });
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.FocusIn, TextEditorIdentifierSelectionHook::handleFocusIn);
        display.addFilter(SWT.KeyDown, TextEditorIdentifierSelectionHook::handleKeyDown);
        display.addFilter(SWT.MouseDown, TextEditorIdentifierSelectionHook::handleMouseDown);
        display.addFilter(SWT.MouseUp, TextEditorIdentifierSelectionHook::handleMouseUp);
        display.addFilter(SWT.MouseDoubleClick, TextEditorIdentifierSelectionHook::handleMouseDoubleClick);
    }

    /** При получении фокуса любым {@link StyledText} устанавливаем идентификаторную навигацию. */
    private static void handleFocusIn(Event event)
    {
        if (event.widget instanceof StyledText text && !text.isDisposed())
            installOnStyledText(text);
    }

    /** Подключает границы идентификатора к {@link StyledText} без {@link ITextEditor}. */
    static void installOnStyledText(StyledText text)
    {
        if (text == null || text.isDisposed())
            return;
        IdentifierSelectionSupport.installWordMovement(text);
        installKeyInterceptor(text);
    }

    /** Число повторов ожидания viewer через asyncExec, прежде чем сдаться (защита от вечного цикла). */
    private static final int MAX_ATTACH_ATTEMPTS = 100;

    /** Полное подключение по редактору (разрешает viewer сам). */
    public static void attachToTextEditor(ITextEditor textEditor)
    {
        attachToTextEditor(textEditor, 0);
    }

    private static void attachToTextEditor(ITextEditor textEditor, int attempt)
    {
        if (textEditor == null || textEditor.getSite() == null || isWorkbenchClosing())
            return;

        ISourceViewer viewer = TextEditor.getSourceViewer(textEditor);
        if (viewer == null)
        {
            // Если viewer никогда не появится (редактор не инициализируется/закрывается),
            // ограничиваем число попыток — иначе asyncExec крутится вечно и не даёт
            // Display.release() опустошить очередь при закрытии EDT (issue #130).
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachToTextEditor(textEditor, attempt + 1));
            return;
        }

        Control widget = viewer.getTextWidget();
        if (!(widget instanceof StyledText textWidget) || textWidget.isDisposed())
            return;

        attachToTextEditor(textEditor, textWidget);
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    /** Полное подключение, когда {@link StyledText} уже известен (BSL-хук). */
    public static void attachToTextEditor(ITextEditor textEditor, StyledText textWidget)
    {
        if (textWidget == null || textWidget.isDisposed())
            return;

        installOnStyledText(textWidget);
        if (textEditor != null)
            disableSubWordOnEditor(textEditor);
        if (textEditor != null)
            replaceWordNavigationActions(textEditor, textWidget);
    }

    private static void replaceWordNavigationActions(ITextEditor editor, StyledText text)
    {
        if (Boolean.TRUE.equals(text.getData(EDITOR_ACTIONS_KEY)))
            return;

        setIdentifierWordAction(editor, text, ITextEditorActionDefinitionIds.WORD_PREVIOUS, true, false);
        setIdentifierWordAction(editor, text, ITextEditorActionDefinitionIds.WORD_NEXT, false, false);
        setIdentifierWordAction(editor, text, ITextEditorActionDefinitionIds.SELECT_WORD_PREVIOUS, true, true);
        setIdentifierWordAction(editor, text, ITextEditorActionDefinitionIds.SELECT_WORD_NEXT, false, true);

        text.setData(EDITOR_ACTIONS_KEY, Boolean.TRUE);
    }

    private static void setIdentifierWordAction(ITextEditor editor, StyledText text,
        String actionId, boolean toLeft, boolean extend)
    {
        Action action = new Action()
        {
            @Override
            public void run()
            {
                if (text.isDisposed())
                    return;
                if (extend)
                    IdentifierSelectionSupport.extendSelection(text, toLeft);
                else
                    IdentifierSelectionSupport.moveCaret(text, toLeft);
            }
        };
        action.setActionDefinitionId(actionId);
        Global.invoke(editor, "setAction", actionId, action); //$NON-NLS-1$
    }

    private static void installKeyInterceptor(StyledText text)
    {
        if (Boolean.TRUE.equals(text.getData(KEY_INTERCEPTOR_KEY)))
            return;

        Listener listener = TextEditorIdentifierSelectionHook::handleWidgetKeyDown;
        text.addListener(SWT.KeyDown, listener);
        text.setData(KEY_INTERCEPTOR_KEY, Boolean.TRUE);
        text.addDisposeListener(e -> text.removeListener(SWT.KeyDown, listener));
    }

    private static void handleWidgetKeyDown(Event e)
    {
        if (e.keyCode != SWT.ARROW_LEFT && e.keyCode != SWT.ARROW_RIGHT)
            return;
        if ((e.stateMask & (SWT.CTRL | SWT.MOD1)) == 0)
            return;
        if ((e.stateMask & SWT.ALT) != 0)
            return;
        if (!(e.widget instanceof StyledText text))
            return;
        if (text.isDisposed() || text.getBlockSelection())
            return;

        boolean toLeft = e.keyCode == SWT.ARROW_LEFT;
        boolean handled = (e.stateMask & SWT.SHIFT) != 0
            ? IdentifierSelectionSupport.extendSelection(text, toLeft)
            : IdentifierSelectionSupport.moveCaret(text, toLeft);
        if (!handled)
            return;

        e.doit = false;
    }

    private static void disableXtextSubWordNavigation()
    {
        try
        {
            IPreferenceStore store =
                new ScopedPreferenceStore(InstanceScope.INSTANCE, XTEXT_UI_PLUGIN);
            store.setDefault(SUB_WORD_NAVIGATION, false);
            store.setValue(SUB_WORD_NAVIGATION, false);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void disableSubWordOnEditor(ITextEditor textEditor)
    {
        if (!(textEditor instanceof XtextEditor xtextEditor))
            return;
        try
        {
            IPreferenceStore writable = resolveWritableEditorPreferenceStore(xtextEditor);
            if (writable != null)
            {
                writable.setDefault(SUB_WORD_NAVIGATION, false);
                writable.setValue(SUB_WORD_NAVIGATION, false);
            }
            else
            {
                IPreferenceStore fallback =
                    new ScopedPreferenceStore(InstanceScope.INSTANCE, XTEXT_UI_PLUGIN);
                fallback.setDefault(SUB_WORD_NAVIGATION, false);
                fallback.setValue(SUB_WORD_NAVIGATION, false);
            }
        }
        catch (RuntimeException ignored)
        {
        }
    }

    private static IPreferenceStore resolveWritableEditorPreferenceStore(XtextEditor editor)
    {
        Object accessObj = Global.getField(editor, "preferenceStoreAccess"); //$NON-NLS-1$
        if (accessObj != null)
        {
            try
            {
                Object writable = Global.invoke(accessObj, "getWritablePreferenceStore", editor); //$NON-NLS-1$
                if (writable instanceof IPreferenceStore store)
                    return store;
                writable = Global.invoke(accessObj, "getWritablePreferenceStore"); //$NON-NLS-1$
                if (writable instanceof IPreferenceStore store)
                    return store;
            }
            catch (RuntimeException ignored)
            {
            }
        }

        try
        {
            IPreferenceStoreAccess access = Global.getOsgiService(IPreferenceStoreAccess.class);
            if (access != null)
            {
                IPreferenceStore store = access.getWritablePreferenceStore(editor);
                if (store != null)
                    return store;
                return access.getWritablePreferenceStore();
            }
        }
        catch (RuntimeException ignored)
        {
        }
        return null;
    }

    private void hookWorkbench()
    {
        if (PlatformUI.getWorkbench() == null)
            return;

        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
        });

        for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(w);
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

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
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
        if (editor instanceof ITextEditor textEditor)
            hookTextEditor(textEditor);
        else if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private void hookTextEditor(ITextEditor textEditor)
    {
        Display.getDefault().asyncExec(() -> attachToTextEditor(textEditor));
    }

    private void hookGranularEditor(DtGranularEditor<?> granular)
    {
        hookActiveTextPage(granular);
        if (!hookedGranularEditors.contains(granular))
        {
            IPageChangedListener listener = event -> hookActiveTextPage(granular);
            granular.addPageChangedListener(listener);
            hookedGranularEditors.add(granular);
        }
    }

    private void hookActiveTextPage(DtGranularEditor<?> granular)
    {
        IFormPage activePage = granular.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof ITextEditor textEditor)
            hookTextEditor(textEditor);
    }

    /** Запасной путь, если клавиша дойдёт до Display-фильтра (а также все поля ввода). */
    private static void handleKeyDown(Event e)
    {
        if (e.keyCode != SWT.ARROW_LEFT && e.keyCode != SWT.ARROW_RIGHT)
            return;
        int mod = e.stateMask & (SWT.CTRL | SWT.MOD1);
        if (mod == 0)
            return;
        if ((e.stateMask & SWT.ALT) != 0)
            return;

        boolean toLeft = e.keyCode == SWT.ARROW_LEFT;
        boolean extend = (e.stateMask & SWT.SHIFT) != 0;
        boolean handled;
        if (e.widget instanceof StyledText text)
        {
            if (text.isDisposed() || text.getBlockSelection())
                return;
            handled = extend
                ? IdentifierSelectionSupport.extendSelection(text, toLeft)
                : IdentifierSelectionSupport.moveCaret(text, toLeft);
        }
        else if (e.widget instanceof Text field)
        {
            handled = IdentifierSelectionSupport.navigateField(field, toLeft, extend);
        }
        else if (e.widget instanceof Combo combo)
        {
            handled = IdentifierSelectionSupport.navigateField(combo, toLeft, extend);
        }
        else
        {
            return;
        }
        if (!handled)
            return;

        e.doit = false;
    }

    /**
     * Ctrl+клик в поле ввода ({@link Text}, {@link Combo}): выделяет слово-идентификатор
     * под курсором. Слово запоминается на нажатии, а выделение ставится после отпускания
     * кнопки: штатная обработка нажатой кнопки (каретка, протяжка выделения) успевает
     * закончиться и больше не перекрывает наше выделение (иначе микродвижение указателя
     * при зажатой кнопке подтягивало конец выделения к указателю — «левая часть слова»).
     * В редакторах ({@link StyledText}) Ctrl+клик занят гиперссылками — там этим
     * занимается {@link TextEditorCtrlClickSelectWordHook}.
     */
    private static void handleMouseDown(Event e)
    {
        Control field = asInputField(e.widget);
        if (field == null || field.isDisposed())
            return;

        field.setData(PENDING_WORD_KEY, null);

        if (e.button != 1 || !isCtrlOnly(e.stateMask))
            return;
        if (!ComfortSettings.isCtrlClickSelectWordEnabled())
            return;

        Point word = IdentifierSelectionSupport.wordAtCursor(field);
        if (word != null)
            field.setData(PENDING_WORD_KEY, word);
    }

    /** Применяет слово, запомненное при Ctrl+нажатии, — после штатной обработки отпускания. */
    private static void handleMouseUp(Event e)
    {
        if (e.button != 1)
            return;
        Control field = asInputField(e.widget);
        if (field == null || field.isDisposed())
            return;
        if (!(field.getData(PENDING_WORD_KEY) instanceof Point word))
            return;

        field.setData(PENDING_WORD_KEY, null);
        if (field instanceof Text text)
            IdentifierSelectionSupport.applyWordSelection(text, word);
        else if (field instanceof Combo combo)
            IdentifierSelectionSupport.applyWordSelection(combo, word);
    }

    /** Поле ввода ({@link Text} или {@link Combo}), получившее событие, либо {@code null}. */
    private static Control asInputField(Widget widget)
    {
        if (widget instanceof Text text && !text.isDisposed())
            return text;
        if (widget instanceof Combo combo && !combo.isDisposed())
            return combo;
        return null;
    }

    /**
     * Двойной клик в поле ввода: выделяет слово-идентификатор в точке клика, заменяя
     * штатный результат двойного клика (нативное слово, «выделить всё» EDT). Третий
     * и последующие клики ({@code count > 2}, выделение строки) не трогаем.
     */
    private static void handleMouseDoubleClick(Event e)
    {
        if (e.button != 1 || e.count != 2)
            return;

        if (e.widget instanceof Text field)
            IdentifierSelectionSupport.selectWordAtDoubleClick(field);
        else if (e.widget instanceof Combo combo)
            IdentifierSelectionSupport.selectWordAtDoubleClick(combo);
    }

    /** Ctrl без Shift и Alt — как у {@link TextEditorCtrlClickSelectWordHook}. */
    private static boolean isCtrlOnly(int stateMask)
    {
        return (stateMask & SWT.MOD1) != 0 && (stateMask & (SWT.SHIFT | SWT.ALT)) == 0;
    }
}
