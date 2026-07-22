package tormozit;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;

/**
 * Запоминает позицию каретки в редакторе модуля BSL при его закрытии
 * и восстанавливает при повторном открытии. Хранение — между сеансами EDT
 * (см. {@link ModulePositionStore}).
 *
 * <p>Приоритет явной навигации (GoToDefinition, «Показать в модуле», переход
 * по брейкпоинту, результаты поиска) обеспечивается порядком очереди
 * {@code Display.asyncExec}: эти механизмы синхронно открывают редактор
 * ({@code IDE.openEditor} / {@code OpenHelper}), из-за чего наш
 * {@code partOpened} успевает поставить восстановление в очередь раньше их
 * собственного отложенного {@code selectAndReveal} — их вызов выполняется
 * позже и перекрывает восстановленную позицию. Специальный код подавления
 * не требуется.
 */
public class BslModulePositionMemoryHook implements IStartup
{
    private static final String RESTORE_MARKER = "tormozit.bslModulePositionRestored"; //$NON-NLS-1$

    /** Число повторов ожидания viewer через asyncExec (issue #130 — не бесконечно). */
    private static final int MAX_ATTACH_ATTEMPTS = 100;

    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        new HashSet<>();

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
    // Подключение к окну / редактору
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
        if (editor instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor) editor);
        else if (editor instanceof DtGranularEditor<?>)
            hookGranularEditor((DtGranularEditor<?>) editor);
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
                            ((DtGranularEditorXtextEditorPage<?>) selectedPage).getEmbeddedEditor();
                        if (embedded instanceof BslXtextEditor)
                            hookBslEditor((BslXtextEditor) embedded);
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
            ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor) embedded);
    }

    // =========================================================================
    // Восстановление / сохранение позиции
    // =========================================================================

    private void hookBslEditor(BslXtextEditor editor)
    {
        Display.getDefault().asyncExec(() -> attachToBslEditor(editor, 0));
    }

    private void attachToBslEditor(BslXtextEditor editor, int attempt)
    {
        if (editor.getSite() == null || isWorkbenchClosing())
        {
            return;
        }

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer))
        {
            if (attempt >= MAX_ATTACH_ATTEMPTS)
            {
                return;
            }
            Display.getDefault().asyncExec(() -> attachToBslEditor(editor, attempt + 1));
            return;
        }

        StyledText textWidget = ((SourceViewer) viewer).getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
        {
            return;
        }

        if (!Boolean.TRUE.equals(textWidget.getData(RESTORE_MARKER)))
        {
            textWidget.setData(RESTORE_MARKER, Boolean.TRUE);
            restorePosition(editor, viewer);

            // К моменту dispose/FocusOut документ у viewer часто уже отсоединён (особенно
            // в переиспользуемых редакторах вроде CommonModuleEditor, где содержимое части
            // подменяется без dispose виджета) — читать позицию в этот момент поздно.
            // Поэтому текущая позиция непрерывно кэшируется в памяти при каждом изменении
            // выделения (дёшево — без записи на диск), а на уходе (FocusOut/dispose) просто
            // сбрасывается уже известное значение на диск, не обращаясь к документу заново.
            if (viewer.getSelectionProvider() != null)
            {
                ISelectionChangedListener selListener = event -> updateLiveCache(editor, viewer);
                viewer.getSelectionProvider().addSelectionChangedListener(selListener);
                textWidget.addDisposeListener(e -> viewer.getSelectionProvider().removeSelectionChangedListener(selListener));
            }
            // Прокрутка колёсиком/скроллбаром без движения каретки не порождает
            // selectionChanged — отслеживаем её отдельно через вертикальный скроллбар.
            org.eclipse.swt.widgets.ScrollBar vBar = textWidget.getVerticalBar();
            if (vBar != null)
            {
                vBar.addListener(SWT.Selection, e -> updateLiveCache(editor, viewer));
            }
            updateLiveCache(editor, viewer);

            textWidget.addListener(SWT.FocusOut, e ->
            {
                // Не полагаемся только на прежде пойманные события — довзводим актуальную
                // позицию непосредственно перед уходом (документ здесь ещё доступен).
                updateLiveCache(editor, viewer);
                ModulePositionStore.flush();
            });
            textWidget.addDisposeListener(e ->
            {
                // На dispose документ уже может быть отсоединён — best-effort, ошибки внутри
                // updateLiveCache проглатываются самим методом.
                updateLiveCache(editor, viewer);
                ModulePositionStore.flush();
            });
        }
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    private static void restorePosition(BslXtextEditor editor, ISourceViewer viewer)
    {
        String key = moduleKey(editor);
        if (key == null)
        {
            return;
        }
        int[] pos = ModulePositionStore.load(key);
        if (pos == null)
            return;

        Display.getDefault().asyncExec(() ->
        {
            try
            {
                // Восстановление выполняется асинхронно и может сработать уже после того, как
                // пользователь сам успел прокрутить/кликнуть в свежеоткрытом редакторе — тогда
                // видимая область уже не в начале файла и/или каретка уже не на нулевой позиции.
                // В этом случае не вмешиваемся: пользовательское действие имеет приоритет над
                // восстановлением сохранённой позиции.
                if (viewer instanceof SourceViewer)
                {
                    StyledText textWidget = ((SourceViewer) viewer).getTextWidget();
                    if (textWidget != null && !textWidget.isDisposed() && textWidget.getTopIndex() != 0)
                    {
                        return;
                    }
                }
                if (viewer.getSelectionProvider() != null
                    && viewer.getSelectionProvider().getSelection() instanceof ITextSelection currentSel
                    && currentSel.getOffset() != 0)
                {
                    return;
                }
                IDocument doc = viewer.getDocument();
                if (doc == null)
                {
                    return;
                }
                int offset = clampToDocument(doc, pos[0], pos[1]);
                editor.selectAndReveal(offset, 0);
                // selectAndReveal сам скроллит к каретке — если отдельно был сохранён
                // topIndex (пользователь листал, не двигая каретку), возвращаем именно его.
                if (pos.length > 2 && viewer instanceof SourceViewer)
                {
                    StyledText textWidget = ((SourceViewer) viewer).getTextWidget();
                    if (textWidget != null && !textWidget.isDisposed())
                    {
                        int lastLine = Math.max(0, textWidget.getLineCount() - 1);
                        textWidget.setTopIndex(Math.max(0, Math.min(pos[2], lastLine)));
                    }
                }
            }
            catch (Exception e)
            {
            }
        });
    }

    /**
     * Обновляет позицию каретки для модуля в памяти (без записи на диск — дёшево, можно
     * вызывать при каждом изменении выделения). На диск значение попадает отдельно, через
     * {@link ModulePositionStore#flush()}, вызываемый при уходе с редактора (см. вызовы выше).
     */
    private static void updateLiveCache(BslXtextEditor editor, ISourceViewer viewer)
    {
        try
        {
            String key = moduleKey(editor);
            if (key == null)
            {
                return;
            }
            IDocument doc = viewer.getDocument();
            if (doc == null)
            {
                return;
            }
            if (viewer.getSelectionProvider() == null)
            {
                return;
            }
            Object selObj = viewer.getSelectionProvider().getSelection();
            if (!(selObj instanceof ITextSelection))
            {
                return;
            }
            ITextSelection textSel = (ITextSelection) selObj;
            int line0 = doc.getLineOfOffset(textSel.getOffset());
            IRegion li = doc.getLineInformation(line0);
            int column = textSel.getOffset() - li.getOffset();
            int topIndex = 0;
            if (viewer instanceof SourceViewer)
            {
                StyledText textWidget = ((SourceViewer) viewer).getTextWidget();
                if (textWidget != null && !textWidget.isDisposed())
                    topIndex = textWidget.getTopIndex();
            }
            ModulePositionStore.updateMemory(key, line0, column, topIndex);
        }
        catch (Exception e)
        {
        }
    }

    private static int clampToDocument(IDocument doc, int line0, int column) throws BadLocationException
    {
        int lastLine = Math.max(0, doc.getNumberOfLines() - 1);
        int line = Math.max(0, Math.min(line0, lastLine));
        IRegion li = doc.getLineInformation(line);
        int col = Math.max(0, Math.min(column, li.getLength()));
        return li.getOffset() + col;
    }

    private static String moduleKey(BslXtextEditor editor)
    {
        IEditorInput input = editor.getEditorInput();
        if (input == null)
            return null;
        IFile file = input.getAdapter(IFile.class);
        if (file == null)
            return null;
        GetRef.ModuleRef moduleRef = GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        return moduleRef != null ? moduleRef.toRefPrefix() : null;
    }

    // =========================================================================
    // Персистентное хранилище позиций (единственный потребитель — этот хук)
    // =========================================================================

    private static final class ModulePositionStore
    {
        private static final String PREF_KEY = "bslModulePosition.entries"; //$NON-NLS-1$
        private static final char   SEP      = '\t';
        private static final int    MAX_SIZE = 500;

        private static ScopedPreferenceStore prefs;
        private static Map<String, int[]> cache;

        private ModulePositionStore() {}

        static synchronized int[] load(String key)
        {
            ensureLoaded();
            return cache.get(key);
        }

        /** Обновляет позицию в памяти без записи на диск (дёшево, для вызова на каждое изменение выделения). */
        static synchronized void updateMemory(String key, int line, int column, int topIndex)
        {
            ensureLoaded();
            cache.remove(key);
            cache.put(key, new int[] { line, column, topIndex });
            while (cache.size() > MAX_SIZE)
            {
                String oldest = cache.keySet().iterator().next();
                cache.remove(oldest);
            }
        }

        /** Сбрасывает текущее состояние памяти на диск (вызывается при уходе с редактора). */
        static synchronized void flush()
        {
            ensureLoaded();
            persist();
        }

        private static void ensureLoaded()
        {
            if (cache != null)
                return;
            cache = new LinkedHashMap<>();
            ScopedPreferenceStore store = prefs();
            if (store == null)
                return;
            String raw = store.getString(PREF_KEY);
            if (raw == null || raw.isBlank())
                return;
            for (String line : raw.split("\n")) //$NON-NLS-1$
            {
                String[] parts = line.split(String.valueOf(SEP), -1);
                if (parts.length != 4)
                    continue;
                try
                {
                    cache.put(parts[0], new int[] {
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) });
                }
                catch (NumberFormatException ignored) {}
            }
        }

        private static void persist()
        {
            ScopedPreferenceStore store = prefs();
            if (store == null)
                return;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, int[]> e : cache.entrySet())
            {
                if (sb.length() > 0)
                    sb.append('\n');
                int[] v = e.getValue();
                sb.append(e.getKey()).append(SEP).append(v[0]).append(SEP).append(v[1]).append(SEP)
                    .append(v.length > 2 ? v[2] : 0);
            }
            store.setValue(PREF_KEY, sb.toString());
            try
            {
                store.save();
            }
            catch (Exception ignored)
            {
                // prefs опциональны
            }
        }

        private static ScopedPreferenceStore prefs()
        {
            if (prefs != null)
                return prefs;
            try
            {
                String pluginId = FrameworkUtil.getBundle(ModulePositionStore.class).getSymbolicName();
                prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
            }
            catch (Exception ignored)
            {
                return null;
            }
            return prefs;
        }
    }
}
