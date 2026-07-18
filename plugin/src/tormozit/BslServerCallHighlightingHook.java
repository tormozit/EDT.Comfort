package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.syntaxcoloring.HighlightingReconciler;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

public final class BslServerCallHighlightingHook implements IStartup
{
    private static final String TAG = "ServerCallHL"; //$NON-NLS-1$
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final List<Object> patchedReconcilers = new ArrayList<>();
    private static final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners = new HashMap<>();
    private static final Map<Object, BslXtextEditor> reconcilerEditors = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        if (!installed.compareAndSet(false, true))
        {
            Global.log(TAG, "earlyStartup already installed, skipping"); //$NON-NLS-1$
            return;
        }

        Global.log(TAG, "earlyStartup called"); //$NON-NLS-1$
        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            Global.log(TAG, "workbench windows: " + windows.length); //$NON-NLS-1$
            for (IWorkbenchWindow window : windows)
                registerWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new WindowAdapter());
            Global.log(TAG, "window listener registered"); //$NON-NLS-1$
        });

        Activator.getDefault().getPreferenceStore()
            .addPropertyChangeListener(event -> {
                String prop = event.getProperty();
                if (ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED.equals(prop)
                    || ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR.equals(prop)
                    || ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR.equals(prop))
                {
                    Global.log(TAG, "property changed: " + prop); //$NON-NLS-1$
                    PlatformUI.getWorkbench().getDisplay().asyncExec(BslServerCallHighlightingHook::refreshAllEditors);
                }
            });
        Global.log(TAG, "property listener registered"); //$NON-NLS-1$
    }

    static void refreshAllEditors()
    {
        if (!installed.get())
        {
            Global.log(TAG, "refreshAllEditors: not installed"); //$NON-NLS-1$
            return;
        }
        if (PlatformUI.getWorkbench().getDisplay() == null
            || PlatformUI.getWorkbench().getDisplay().isDisposed())
        {
            Global.log(TAG, "refreshAllEditors: display null/disposed"); //$NON-NLS-1$
            return;
        }
        Global.log(TAG, "refreshAllEditors: refreshing " + patchedReconcilers.size() + " reconcilers"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Object reconciler : patchedReconcilers)
        {
            // Обновляем TextAttribute (цвет/стиль) перед пересчётом — иначе refresh
            // просто заново нарисует старым, закэшированным значением. Раньше это
            // делал отдельный updateServerCallStyleInProvider(), вызываемый только
            // из property-change слушателя на Activator.getDefault().getPreferenceStore(),
            // но ComfortPreferencePage.performOk() зовёт только refreshAllEditors()
            // напрямую (без слушателя) — цвет из «Параметров» из-за этого не долетал.
            registerServerCallStyle(reconciler);
            forceRefresh(reconciler);
        }
    }

    /**
     * {@link HighlightingReconciler#refresh()} (публичный, тот же путь, что
     * {@code install()} при первом открытии редактора) НЕ годится: внутри он берёт
     * {@code document.tryReadOnly(...)} — не блокирующий вариант, который тихо
     * ничего не делает (без единой ошибки), если read-lock сразу не достался —
     * подтверждено экспериментом: после перехода на {@code refresh()} раскраска
     * серверных вызовов переставала появляться. Прямой вызов
     * {@code HighlightingReconciler.modelChanged(resource)} — та же проблема.
     * Единственный надёжный способ — настоящее изменение документа (идёт через
     * блокирующий путь), поэтому делаем тривиальный самовосстанавливающийся edit:
     * заменяем первый символ документа на него же самого, это порождает
     * {@code DocumentEvent} и запускает штатный конвейер пересвязывания.
     * <p>
     * Побочный эффект: этот {@code DocumentEvent} — даже самовосстанавливающийся —
     * взводит модифицированность редактора; {@code DirtyStateEditorSupport.markEditorClean()}
     * тут не помогает (это внутреннее состояние Xtext, а не {@code ITextEditor.isDirty()}).
     * Второй самозаменяющий edit с восстановленным modification stamp тоже не годится:
     * он порождает ещё один {@code DocumentEvent}, и {@code HighlightingReconciler
     * .updatePresentation()} (сверяет {@code resourceSet.getModificationStamp()} перед
     * применением) отбрасывает результат ПЕРВОГО пересчёта как устаревший — раскраска
     * пропадает (подтверждено экспериментом). Поэтому модифицированность снимаем без
     * единой лишней записи в документ — напрямую через {@code fCanBeSaved} в
     * {@code ElementInfo} документ-провайдера (см. {@link #resetDirtyFlagIfClean}),
     * тем же приёмом, что использует сам {@code XtextDocumentProvider
     * .UnchangedElementListener}.
     */
    private static void forceRefresh(Object reconciler)
    {
        try
        {
            if (!(reconciler instanceof HighlightingReconciler hr))
            {
                Global.log(TAG, "forceRefresh: not a HighlightingReconciler, class=" //$NON-NLS-1$
                    + reconciler.getClass().getName());
                return;
            }

            Field sourceViewerField = findField(HighlightingReconciler.class, "sourceViewer"); //$NON-NLS-1$
            if (sourceViewerField == null)
            {
                Global.log(TAG, "forceRefresh: 'sourceViewer' field not found"); //$NON-NLS-1$
                return;
            }
            sourceViewerField.setAccessible(true);
            Object sourceViewerObj = sourceViewerField.get(hr);
            if (!(sourceViewerObj instanceof XtextSourceViewer sourceViewer))
            {
                Global.log(TAG, "forceRefresh: sourceViewer=" //$NON-NLS-1$
                    + (sourceViewerObj == null ? "null" : sourceViewerObj.getClass().getName())); //$NON-NLS-1$
                return;
            }

            IXtextDocument document = sourceViewer.getXtextDocument();
            if (document == null)
            {
                Global.log(TAG, "forceRefresh: xtextDocument null"); //$NON-NLS-1$
                return;
            }

            if (document.getLength() == 0)
            {
                Global.log(TAG, "forceRefresh: document empty, nothing to trigger on"); //$NON-NLS-1$
                return;
            }

            BslXtextEditor editorForCleanup = reconcilerEditors.get(reconciler);
            boolean wasDirty = editorForCleanup != null && editorForCleanup.isDirty();

            char firstChar = document.getChar(0);
            document.replace(0, 1, String.valueOf(firstChar));
            Global.log(TAG, "forceRefresh: self-replace edit triggered at offset 0"); //$NON-NLS-1$

            if (editorForCleanup != null && !wasDirty)
                resetDirtyFlagIfClean(editorForCleanup);
        }
        catch (Exception e)
        {
            Global.log(TAG, "forceRefresh failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void registerWindow(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(new PartAdapter());
        for (IWorkbenchPage page : window.getPages())
        {
            IEditorReference[] refs = page.getEditorReferences();
            Global.log(TAG, "registerWindow: " + refs.length + " editors in page"); //$NON-NLS-1$ //$NON-NLS-2$
            for (IEditorReference ref : refs)
                inspectEditor(ref);
        }
    }

    private static void inspectEditor(IEditorReference ref)
    {
        try
        {
            IWorkbenchPart part = ref.getPart(false);
            Global.log(TAG, "  editor: id=" + ref.getId() + " part=" //$NON-NLS-1$ //$NON-NLS-2$
                + (part == null ? "null" : part.getClass().getName()));

            if (part instanceof BslXtextEditor)
            {
                patchEditor((BslXtextEditor)part);
            }
            else if (part instanceof DtGranularEditor)
            {
                patchGranularEditor((DtGranularEditor<?>)part);
            }
        }
        catch (Exception e)
        {
            Global.log(TAG, "  inspectEditor failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void patchGranularEditor(DtGranularEditor<?> editor)
    {
        try
        {
            patchFormPageIfBsl(editor.getActivePageInstance());

            if (!pageListeners.containsKey(editor))
            {
                IPageChangedListener listener = event -> patchFormPageIfBsl(event.getSelectedPage());
                editor.addPageChangedListener(listener);
                pageListeners.put(editor, listener);
            }
        }
        catch (Exception e)
        {
            Global.log(TAG, "patchGranular failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void unregisterGranularEditor(DtGranularEditor<?> editor)
    {
        IPageChangedListener listener = pageListeners.remove(editor);
        if (listener != null)
            editor.removePageChangedListener(listener);
    }

    private static void patchFormPageIfBsl(Object page)
    {
        if (page instanceof DtGranularEditorXtextEditorPage)
        {
            DtGranularEditorXtextEditorPage<?> xtextPage = (DtGranularEditorXtextEditorPage<?>)page;
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor)
                patchEditor((BslXtextEditor)embedded);
        }
    }

    private static void patchEditor(BslXtextEditor editor)
    {
        try
        {
            Object viewer = editor.getInternalSourceViewer();
            if (viewer == null)
                return;

            Object reconciler = findHighlightingReconciler(viewer);
            if (reconciler == null)
            {
                Global.log(TAG, "patchEditor: reconciler not found"); //$NON-NLS-1$
                return;
            }

            if (wrapCalculator(reconciler))
            {
                if (!patchedReconcilers.contains(reconciler))
                    patchedReconcilers.add(reconciler);
                reconcilerEditors.put(reconciler, editor);
                registerServerCallStyle(reconciler);
                forceRefresh(reconciler);
                Global.log(TAG, "patchEditor: OK, patched=" + patchedReconcilers.size()); //$NON-NLS-1$

                // Сразу после патча связывание BSL (isIsServerCall()) может быть ещё
                // не завершено фоновой задачей EDT — первый forceRefresh посчитает 0
                // серверных вызовов. Повторяем ещё раз с задержкой, чтобы поймать уже
                // связанное состояние без необходимости правки текста пользователем.
                Display display = PlatformUI.getWorkbench().getDisplay();
                if (display != null && !display.isDisposed())
                    display.timerExec(1500, () -> forceRefresh(reconciler));
            }
        }
        catch (Exception e)
        {
            Global.log(TAG, "patchEditor failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Object getAttributeProvider(Object reconciler)
    {
        try
        {
            Field field = findField(reconciler.getClass(), "attributeProvider"); //$NON-NLS-1$
            if (field == null)
            {
                Global.log(TAG, "getAttrProvider: 'attributeProvider' not found"); //$NON-NLS-1$
                return null;
            }
            field.setAccessible(true);
            return field.get(reconciler);
        }
        catch (Exception e)
        {
            Global.log(TAG, "getAttrProvider failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /** Регистрирует/обновляет стили {@code SERVER_CALL_ID}/{@code SERVER_CALL_CONTEXT_ID}
     *  в собственном {@code attributeProvider} конкретного реконсилера — этот
     *  провайдер не является общим синглтоном для всех редакторов, поэтому
     *  регистрировать стиль нужно у каждого патченного реконсилера отдельно. */
    @SuppressWarnings("unchecked")
    private static void registerServerCallStyle(Object reconciler)
    {
        Object attributeProvider = getAttributeProvider(reconciler);
        if (attributeProvider == null)
            return;
        try
        {
            Field attrsField = findField(attributeProvider.getClass(), "attributes"); //$NON-NLS-1$
            if (attrsField == null)
            {
                Global.log(TAG, "registerStyle: 'attributes' not found on " + attributeProvider.getClass().getName()); //$NON-NLS-1$
                return;
            }
            attrsField.setAccessible(true);
            Object mapObj = attrsField.get(attributeProvider);
            if (!(mapObj instanceof HashMap))
            {
                Global.log(TAG, "registerStyle: attributes is not HashMap"); //$NON-NLS-1$
                return;
            }
            HashMap<String, TextAttribute> attributes = (HashMap<String, TextAttribute>)mapObj;

            putServerCallStyle(attributes, BslServerCallHighlightingConfiguration.SERVER_CALL_ID,
                ComfortSettings.getServerCallHighlightingColor(),
                ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
            putServerCallStyle(attributes, BslServerCallHighlightingConfiguration.SERVER_CALL_CONTEXT_ID,
                ComfortSettings.getServerCallContextHighlightingColor(),
                ComfortSettings.DEFAULT_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR);

            Global.log(TAG, "registerStyle: OK"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log(TAG, "registerStyle failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void putServerCallStyle(HashMap<String, TextAttribute> attributes, String id, String colorStr,
            String fallback)
    {
        TextAttribute attr = createServerCallTextAttribute(colorStr, fallback);
        if (attr == null)
        {
            Global.log(TAG, "putServerCallStyle: failed to create TextAttribute for " + id); //$NON-NLS-1$
            return;
        }
        attributes.put(id, attr);

        // TextAttributeProvider.getMergedAttributes(ids) кэширует результат
        // объединения нескольких id (напр. когда серверный вызов совпадает по
        // диапазону с нативным "Methods") под отдельным ключом
        // "$$$Merged:.../id/...$$$" и пересчитывает его ТОЛЬКО если такого
        // ключа ещё нет в attributes — иначе рендер вечно берёт объединённый
        // стиль с тем цветом, что был при первом вычислении. Точный merged-ключ
        // не собрать (порядок id заранее не известен), поэтому просто
        // выбрасываем все "$$$Merged:...$$$"-записи, где встречается этот id —
        // они лениво пересчитаются заново.
        Iterator<String> keys = attributes.keySet().iterator();
        while (keys.hasNext())
        {
            String key = keys.next();
            if (key.startsWith("$$$Merged:") && key.contains(id)) //$NON-NLS-1$
                keys.remove();
        }
    }

    /** Стиль всегда {@code SWT.NONE} — только цвет, без модификации шрифта
     *  (жирный/курсив), чтобы текст оставался штатным. */
    private static TextAttribute createServerCallTextAttribute(String colorStr, String fallback)
    {
        try
        {
            RGB light = ComfortSettings.parseRgb(colorStr, fallback);
            RGB effective = SmartMatchHighlight.toEffectiveRgb(light);
            Color foreground = new Color(Display.getCurrent(), effective);
            return new TextAttribute(foreground, null, org.eclipse.swt.SWT.NONE, null);
        }
        catch (Exception e)
        {
            Global.log(TAG, "createTextAttr failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static Object findHighlightingReconciler(Object viewer)
    {
        try
        {
            Field fTextInputListeners = findField(viewer.getClass(), "fTextInputListeners"); //$NON-NLS-1$
            if (fTextInputListeners == null)
                return null;
            fTextInputListeners.setAccessible(true);
            Object listenersObj = fTextInputListeners.get(viewer);
            if (listenersObj == null)
                return null;

            Class<?> reconcilerClass = loadClass(
                "org.eclipse.xtext.ui.editor.syntaxcoloring.HighlightingReconciler"); //$NON-NLS-1$
            if (reconcilerClass == null)
                return null;

            if (listenersObj instanceof List)
            {
                for (Object listener : (List<?>)listenersObj)
                {
                    if (reconcilerClass.isInstance(listener))
                        return listener;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    private static boolean wrapCalculator(Object reconciler)
    {
        try
        {
            Field newCalcField = findField(reconciler.getClass(), "newCalculator"); //$NON-NLS-1$
            if (newCalcField == null)
                return false;
            newCalcField.setAccessible(true);

            Object original = newCalcField.get(reconciler);
            if (original instanceof DelegatingCalculator)
                return false;

            newCalcField.set(reconciler, new DelegatingCalculator(original));
            Global.log(TAG, "wrapCalculator: OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Global.log(TAG, "wrapCalculator failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static Field findField(Class<?> clazz, String name)
    {
        Class<?> c = clazz;
        while (c != null)
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
    {
        Class<?> c = clazz;
        while (c != null)
        {
            try
            {
                return c.getDeclaredMethod(name, paramTypes);
            }
            catch (NoSuchMethodException e)
            {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Снимает модифицированность редактора без единой записи в документ — напрямую
     * через {@code fCanBeSaved} в {@code AbstractDocumentProvider.ElementInfo} (тот
     * же публичный флаг, что читает/пишет {@code XtextDocumentProvider
     * .UnchangedElementListener.documentChanged()}), и штатно оповещает об этом
     * оповещателем {@code fireElementDirtyStateChanged}, чтобы у {@code ITextEditor}
     * (и заголовка вкладки) сразу обновился признак "*". Оба метода/поля —
     * {@code protected}/пакетные в {@code AbstractDocumentProvider}, отсюда рефлексия.
     */
    private static void resetDirtyFlagIfClean(BslXtextEditor editor)
    {
        try
        {
            Object provider = editor.getDocumentProvider();
            Object input = editor.getEditorInput();
            if (provider == null || input == null)
                return;

            Method getElementInfo = findMethod(provider.getClass(), "getElementInfo", Object.class); //$NON-NLS-1$
            if (getElementInfo == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: 'getElementInfo' not found"); //$NON-NLS-1$
                return;
            }
            getElementInfo.setAccessible(true);
            Object elementInfo = getElementInfo.invoke(provider, input);
            if (elementInfo == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: elementInfo null"); //$NON-NLS-1$
                return;
            }

            Field canBeSavedField = findField(elementInfo.getClass(), "fCanBeSaved"); //$NON-NLS-1$
            if (canBeSavedField == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: 'fCanBeSaved' not found"); //$NON-NLS-1$
                return;
            }
            canBeSavedField.setAccessible(true);
            canBeSavedField.set(elementInfo, false);

            Method fireDirty = findMethod(provider.getClass(), "fireElementDirtyStateChanged", //$NON-NLS-1$
                Object.class, boolean.class);
            if (fireDirty == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: 'fireElementDirtyStateChanged' not found"); //$NON-NLS-1$
                return;
            }
            fireDirty.setAccessible(true);
            fireDirty.invoke(provider, input, false);

            Global.log(TAG, "resetDirtyFlagIfClean: OK"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log(TAG, "resetDirtyFlagIfClean failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Class<?> loadClass(String name)
    {
        try
        {
            return Class.forName(name);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    static final class DelegatingCalculator implements ISemanticHighlightingCalculator
    {
        private final Object delegate;

        DelegatingCalculator(Object delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void provideHighlightingFor(XtextResource resource,
            IHighlightedPositionAcceptor acceptor, CancelIndicator cancelIndicator)
        {
            if (delegate instanceof ISemanticHighlightingCalculator)
            {
                ((ISemanticHighlightingCalculator)delegate)
                    .provideHighlightingFor(resource, acceptor, cancelIndicator);
            }

            new BslServerCallHighlightingCalculator()
                .provideHighlightingFor(resource, acceptor, cancelIndicator);
        }
    }

    private static final class PartAdapter implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference ref)
        {
            if (ref instanceof IEditorReference)
                inspectEditor((IEditorReference)ref);
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof DtGranularEditor)
                unregisterGranularEditor((DtGranularEditor<?>)part);
        }

        @Override public void partActivated(IWorkbenchPartReference ref) {}
        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        @Override public void partVisible(IWorkbenchPartReference ref) {}
    }

    private static final class WindowAdapter implements org.eclipse.ui.IWindowListener
    {
        @Override public void windowActivated(IWorkbenchWindow window) {}
        @Override public void windowDeactivated(IWorkbenchWindow window) {}

        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerWindow(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window) {}
    }
}
