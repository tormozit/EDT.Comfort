package tormozit;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
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
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.occurrences.MarkOccurrenceActionContributor;
import org.eclipse.xtext.ui.editor.occurrences.OccurrenceMarker;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com.google.inject.Injector;

/**
 * Доработки штатной подсветки текущего идентификатора в редакторе модуля BSL
 * ({@code BslOccurrenceMarker} бандла {@code com._1c.g5.v8.dt.bsl.ui}).
 *
 * <p><b>Граница слова на {@code _}</b> ({@code #452}). В стратегии «по совпадению строкового
 * представления» (значение {@code MarkOccurrencesAnalyzerType_PlainText} — она же по умолчанию)
 * шаблон подсветки EDT берёт в {@code BslOccurrenceMarker$InnerMarkOccurrencesJob.getPattern}:
 * при пустом выделении слово расширяется от каретки циклом по
 * {@link Character#isLetterOrDigit(char)}, для которого {@code _} — разделитель. У каретки внутри
 * {@code ОповеститьПользователя_Получатели} шаблоном становится {@code Получатели}, и подсвечены
 * не вхождения этого имени, а хвосты всех имён, кончающихся на {@code Получатели}. Тем же
 * {@code isLetterOrDigit} меряет границы слова и {@code AbstractMatcherEngine.isDelimiter}
 * (бандл {@code com._1c.g5.v8.dt.markermatcher}), поэтому «целое слово» его не спасает.
 *
 * <p><b>Обход.</b> Шаблон EDT считает сам, влезть в его расчёт нельзя: job — приватный вложенный
 * класс, создаётся Guice-провайдером маркера. Зато у {@code getPattern} есть вторая ветка: при
 * <i>непустом</i> выделении шаблон — просто текст выделения. Поэтому подменяется не расчёт, а
 * вход: у штатного маркера снимается его слушатель выделения
 * ({@code OccurrenceMarker.getSelectionChangedListener}, подписан на
 * {@link IPostSelectionProvider} редактора) и ставится обёртка. Каретка без выделения внутри
 * идентификатора с {@code _} — слушателю передаётся синтетическое выделение на весь
 * идентификатор; во всех остальных случаях событие уходит как есть.
 *
 * <p>Расширяем только в стратегии «строковое представление»: в семантической EDT ищет элемент по
 * офсету выделения, и сдвиг офсета к началу идентификатора менял бы результат поиска, а не только
 * ширину слова. Стратегия читается у самого маркера (его поле {@code preferenceStore} —
 * {@code MarkOccurencesPreferences}), чтобы совпадали и значения по умолчанию.
 *
 * <p>Остальное остаётся штатным: цвета и линейка обзора, парная подсветка логических скобок
 * ({@code Если} — {@code КонецЕсли}), переключатель «Переключить маркеры вхождений».
 */
public final class BslOccurrenceMarkerHook implements IStartup
{
    /** Тема временного лога модуля. */
    private static final String TEMP_TOPIC = "bsl-occurrence-marker"; //$NON-NLS-1$

    /** Сколько раз ждём появления маркера редактора (его создаёт callback при открытии). */
    private static final int MAX_ATTACH_ATTEMPTS = 100;

    /** Обёртки по редакторам: и отметка «уже подключено», и ссылка для отписки. */
    private static final Map<XtextEditor, ISelectionChangedListener> wrappers =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Многостраничные редакторы, на смену страницы которых уже подписаны. */
    private static final Map<IEditorPart, IEditorPart> pageListeners =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        // earlyStartup идёт не в потоке UI — вся работа с рабочим столом только через asyncExec.
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
                hookEditor(ref.getEditor(false));
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromPartRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromPartRef(ref); }
            @Override public void partClosed(IWorkbenchPartReference ref) { unhookFromPartRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partVisible(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookFromPartRef(IWorkbenchPartReference ref)
    {
        if (ref instanceof IEditorReference editorRef)
            hookEditor(editorRef.getEditor(false));
    }

    private void unhookFromPartRef(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference editorRef))
            return;
        BslXtextEditor editor = GetRef.getActiveBslEditor(editorRef.getEditor(false));
        if (editor == null)
            return;
        ISelectionChangedListener wrapper = wrappers.remove(editor);
        if (wrapper != null && editor.getSelectionProvider() instanceof IPostSelectionProvider post)
            post.removePostSelectionChangedListener(wrapper);
    }

    /**
     * Модуль редко бывает редактором верхнего уровня: в редакторе объекта метаданных или формы
     * ({@code CommonModuleEditor}, {@code FormEditor}, {@code CatalogEditor} и т.п.)
     * {@link BslXtextEditor} — вложенный редактор страницы «Модуль», и открытая вкладка
     * {@code instanceof BslXtextEditor} не является. Поэтому редактор модуля вынимается через
     * {@link GetRef#getActiveBslEditor(org.eclipse.ui.IWorkbenchPart)}, а на смену страницы внутри
     * многостраничного редактора ставится {@link IPageChangedListener}: страницы создаются лениво,
     * и до первого открытия «Модуля» вкладка ещё не знает своего вложенного редактора.
     */
    private void hookEditor(IEditorPart part)
    {
        if (part == null)
            return;
        if (part instanceof IPageChangeProvider pages && pageListeners.putIfAbsent(part, part) == null)
            pages.addPageChangedListener(event -> hookEditor(part));

        BslXtextEditor editor = GetRef.getActiveBslEditor(part);
        if (editor == null || wrappers.containsKey(editor))
            return;
        Display.getDefault().asyncExec(() -> attach(editor, 0));
    }

    /**
     * Подменяет слушателя выделения у штатного маркера редактора. Маркер создаётся
     * {@code MarkOccurrenceActionContributor.contributeActions} при открытии редактора — если его
     * ещё нет, пробуем позже.
     */
    private void attach(BslXtextEditor editor, int attempt)
    {
        if (editor.getSite() == null || wrappers.containsKey(editor))
            return;

        OccurrenceMarker marker = findMarker(editor);
        ISelectionProvider provider = editor.getSelectionProvider();
        if (marker == null || !(provider instanceof IPostSelectionProvider post))
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
            {
                Global.tempLog(TEMP_TOPIC, "сдались после " + attempt + " попыток: маркер=" //$NON-NLS-1$ //$NON-NLS-2$
                    + marker + ", провайдер=" + provider); //$NON-NLS-1$
                return;
            }
            Display.getDefault().asyncExec(() -> attach(editor, attempt + 1));
            return;
        }

        Object listener = Global.invoke(marker, "getSelectionChangedListener"); //$NON-NLS-1$
        if (!(listener instanceof ISelectionChangedListener stock))
        {
            Global.tempLog(TEMP_TOPIC, "слушатель маркера недоступен: " + listener); //$NON-NLS-1$
            return;
        }

        ISelectionChangedListener wrapper =
            event -> stock.selectionChanged(widen(editor, marker, event));
        post.removePostSelectionChangedListener(stock);
        post.addPostSelectionChangedListener(wrapper);
        wrappers.put(editor, wrapper);
    }

    /** Штатный маркер вхождений редактора — {@code null}, если он ещё не создан. */
    private static OccurrenceMarker findMarker(XtextEditor editor)
    {
        try
        {
            Injector injector = bslUiInjector();
            if (injector == null)
                return null;
            return injector.getInstance(MarkOccurrenceActionContributor.class)
                .findOccurrenceMarker(editor);
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "маркер редактора", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Guice-инжектор языка BSL — тот же, из которого редактор берёт свои части
     * ({@code MarkOccurrenceActionContributor} в нём — синглтон, у всех редакторов один).
     * У {@code BslActivator.getInjector} в этой версии EDT есть параметр языка.
     */
    private static Injector bslUiInjector() throws Exception
    {
        Class<?> activatorClass =
            BslSyntaxAssist.bslUiClass("com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"); //$NON-NLS-1$
        if (activatorClass == null)
            return null;
        Object activator = activatorClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
        if (activator == null)
            return null;
        Object language = activatorClass.getField("COM__1C_G5_V8_DT_BSL_BSL").get(null); //$NON-NLS-1$
        Object injector = activatorClass.getMethod("getInjector", String.class) //$NON-NLS-1$
            .invoke(activator, language);
        return injector instanceof Injector guice ? guice : null;
    }

    /**
     * Событие для штатного слушателя: если каретка стоит без выделения внутри идентификатора с
     * {@code _}, вместо неё подставляется выделение на весь идентификатор. Иначе — исходное
     * событие: там штатный расчёт слова уже верен, и трогать его незачем.
     */
    private static SelectionChangedEvent widen(BslXtextEditor editor, OccurrenceMarker marker,
        SelectionChangedEvent event)
    {
        try
        {
            ISelection selection = event.getSelection();
            if (!(selection instanceof ITextSelection text) || text.getLength() != 0)
                return event;
            if (!isPlainTextStrategy(marker))
                return event;

            ISourceViewer viewer = editor.getInternalSourceViewer();
            IDocument document = viewer != null ? viewer.getDocument() : null;
            if (document == null)
                return event;

            int[] word = identifierAt(document, text.getOffset());
            if (word == null)
                return event;

            return new SelectionChangedEvent(event.getSelectionProvider(),
                new TextSelection(document, word[0], word[1] - word[0]));
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "расширение выделения", e); //$NON-NLS-1$
            return event;
        }
    }

    /**
     * Границы идентификатора вокруг {@code offset}, если он содержит {@code _} — иначе
     * {@code null} (штатный расчёт даст то же слово). Символы идентификатора — как у навигации
     * по словам плагина ({@link IdentifierSelectionSupport#isIdentifierChar(char)}).
     *
     * @return {@code [начало, конец)} или {@code null}
     */
    private static int[] identifierAt(IDocument document, int offset) throws BadLocationException
    {
        int length = document.getLength();
        if (offset < 0 || offset > length)
            return null;

        int start = offset;
        while (start > 0 && IdentifierSelectionSupport.isIdentifierChar(document.getChar(start - 1)))
            start--;
        int end = offset;
        while (end < length && IdentifierSelectionSupport.isIdentifierChar(document.getChar(end)))
            end++;

        boolean underscore = false;
        for (int i = start; i < end && !underscore; i++)
            underscore = document.getChar(i) == '_';
        return underscore ? new int[] { start, end } : null;
    }

    /**
     * Стратегия «по совпадению строкового представления». Читается у самого маркера
     * ({@code MarkOccurencesPreferences} в его поле {@code preferenceStore}) — так учитывается и
     * значение по умолчанию, которое EDT задаёт своим инициализатором преференсов.
     */
    private static boolean isPlainTextStrategy(OccurrenceMarker marker)
    {
        Object preferences = marker != null ? Global.getField(marker, "preferenceStore") : null; //$NON-NLS-1$
        Object plainText = preferences != null
            ? Global.invoke(preferences, "isPlainTextAnalyzerType") //$NON-NLS-1$
            : null;
        return Boolean.TRUE.equals(plainText);
    }
}
