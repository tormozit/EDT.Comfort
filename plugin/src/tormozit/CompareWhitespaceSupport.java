package tormozit;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.WhitespaceCharacterPainter;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractTextEditor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Визуализация непечатных символов во всех текстовых панелях сравнения.
 *
 * <p>У {@code org.eclipse.xtext.ui.compare.DefaultMergeViewer} метод
 * {@code isEditorBacked} возвращает {@code true}, и штатный
 * {@code org.eclipse.compare.internal.ShowWhitespaceAction} не ставит
 * {@link WhitespaceCharacterPainter} на такие панели. После
 * {@code configureSourceViewer} декорации встроенного редактора объединения
 * часто теряются — на редактируемой стороне (индекс Git, результат объединения
 * и т.п.) маркеры пробелов и табуляций не рисуются, хотя режим включён в
 * «Текстовые редакторы».
 *
 * <p>На стороне с уже установленным штатным painter (как у рабочей копии справа)
 * повторно не рисуем. Установку на «пустые» панели откладываем до завершения
 * {@code configureSourceViewer}, иначе {@link WhitespaceCharacterPainter} может
 * не увидеть advanced graphics и рисовать символы слишком контрастно.
 */
public final class CompareWhitespaceSupport
{
    private static final Set<SourceViewer> viewers =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final java.util.Map<SourceViewer, WhitespaceCharacterPainter> painters =
        new WeakHashMap<>();

    private static volatile IPropertyChangeListener preferenceListener;

    private CompareWhitespaceSupport()
    {
    }

    /** Двухсторонний {@code TextMergeViewer} ({@code fLeft}/{@code fRight}). */
    public static void installTwoWay(Object mergeViewer)
    {
        register(MergeViewerReflection.extractSourceViewer(mergeViewer, "fLeft")); //$NON-NLS-1$
        register(MergeViewerReflection.extractSourceViewer(mergeViewer, "fRight")); //$NON-NLS-1$
    }

    /** Трёхсторонний {@code ThreeSideTextMergeViewer}. */
    public static void installThreeWay(Object mergeViewer)
    {
        register(MergeViewerReflection.extractSourceViewer(mergeViewer, "leftViewer")); //$NON-NLS-1$
        register(MergeViewerReflection.extractSourceViewer(mergeViewer, "rightViewer")); //$NON-NLS-1$
        register(MergeViewerReflection.extractSourceViewer(mergeViewer, "resultViewer")); //$NON-NLS-1$
    }

    private static void register(SourceViewer viewer)
    {
        if (viewer == null)
            return;

        synchronized (CompareWhitespaceSupport.class)
        {
            if (viewers.add(viewer))
                hookDispose(viewer);
            ensurePreferenceListener();
        }

        scheduleDeferredSync(viewer);
    }

    /**
     * {@code configureSourceViewer} (Xtext/BSL) может выполниться позже attach-хука —
     * двухпроходная отложенная синхронизация после текущей очереди UI.
     */
    private static void scheduleDeferredSync(SourceViewer viewer)
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;

        display.asyncExec(() ->
        {
            sync(viewer);
            display.asyncExec(() -> sync(viewer));
        });
    }

    private static void hookDispose(SourceViewer viewer)
    {
        StyledText text = viewer.getTextWidget();
        if (text == null || text.isDisposed())
            return;

        text.addDisposeListener(e ->
        {
            synchronized (CompareWhitespaceSupport.class)
            {
                removeManagedPainter(viewer);
                viewers.remove(viewer);
            }
        });
    }

    private static void ensurePreferenceListener()
    {
        if (preferenceListener != null)
            return;

        IPreferenceStore store = EditorsUI.getPreferenceStore();
        if (store == null)
            return;

        preferenceListener = CompareWhitespaceSupport::onPreferenceChange;
        store.addPropertyChangeListener(preferenceListener);
    }

    private static void onPreferenceChange(PropertyChangeEvent event)
    {
        if (!isWhitespacePreference(event.getProperty()))
            return;

        synchronized (CompareWhitespaceSupport.class)
        {
            for (SourceViewer viewer : viewers.toArray(new SourceViewer[0]))
                sync(viewer);
        }
    }

    private static boolean isWhitespacePreference(String property)
    {
        if (property == null)
            return false;

        return AbstractTextEditor.PREFERENCE_SHOW_WHITESPACE_CHARACTERS.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_LEADING_SPACES.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_ENCLOSED_SPACES.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_TRAILING_SPACES.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_LEADING_IDEOGRAPHIC_SPACES.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_ENCLOSED_IDEOGRAPHIC_SPACES.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_TRAILING_IDEOGRAPHIC_SPACES.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_LEADING_TABS.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_ENCLOSED_TABS.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_TRAILING_TABS.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_CARRIAGE_RETURN.equals(property)
            || AbstractTextEditor.PREFERENCE_SHOW_LINE_FEED.equals(property)
            || AbstractTextEditor.PREFERENCE_WHITESPACE_CHARACTER_ALPHA_VALUE.equals(property);
    }

    private static void sync(SourceViewer viewer)
    {
        if (viewer == null)
            return;

        StyledText text = viewer.getTextWidget();
        if (text == null || text.isDisposed())
            return;

        removeManagedPainter(viewer);

        IPreferenceStore store = EditorsUI.getPreferenceStore();
        if (store == null || !store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_WHITESPACE_CHARACTERS))
            return;

        List<WhitespaceCharacterPainter> existing = findWhitespacePainters(viewer);
        if (existing.size() > 1)
        {
            removeAllWhitespacePainters(viewer);
            existing = List.of();
        }

        if (existing.size() == 1 && !painters.containsKey(viewer))
            return;

        if (existing.size() == 1)
            removeAllWhitespacePainters(viewer);

        WhitespaceCharacterPainter painter = createPainter(viewer, store);
        viewer.addPainter(painter);
        painters.put(viewer, painter);
    }

    private static WhitespaceCharacterPainter createPainter(SourceViewer viewer, IPreferenceStore store)
    {
        return new WhitespaceCharacterPainter(viewer,
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_LEADING_SPACES),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_ENCLOSED_SPACES),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_TRAILING_SPACES),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_LEADING_IDEOGRAPHIC_SPACES),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_ENCLOSED_IDEOGRAPHIC_SPACES),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_TRAILING_IDEOGRAPHIC_SPACES),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_LEADING_TABS),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_ENCLOSED_TABS),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_TRAILING_TABS),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_CARRIAGE_RETURN),
            store.getBoolean(AbstractTextEditor.PREFERENCE_SHOW_LINE_FEED),
            store.getInt(AbstractTextEditor.PREFERENCE_WHITESPACE_CHARACTER_ALPHA_VALUE));
    }

    private static List<WhitespaceCharacterPainter> findWhitespacePainters(SourceViewer viewer)
    {
        List<WhitespaceCharacterPainter> found = new ArrayList<>();
        Object paintManager = Global.getField(viewer, "fPaintManager"); //$NON-NLS-1$
        if (paintManager == null)
            return found;

        Object listObj = Global.getField(paintManager, "fPainters"); //$NON-NLS-1$
        if (!(listObj instanceof List<?> list))
            return found;

        for (Object painter : list)
        {
            if (painter instanceof WhitespaceCharacterPainter whitespacePainter)
                found.add(whitespacePainter);
        }

        return found;
    }

    private static void removeAllWhitespacePainters(SourceViewer viewer)
    {
        for (WhitespaceCharacterPainter painter : findWhitespacePainters(viewer))
        {
            viewer.removePainter(painter);
            painter.deactivate(true);
        }
    }

    private static void removeManagedPainter(SourceViewer viewer)
    {
        WhitespaceCharacterPainter painter = painters.remove(viewer);
        if (painter == null)
            return;

        StyledText text = viewer.getTextWidget();
        if (text != null && !text.isDisposed())
            viewer.removePainter(painter);

        painter.deactivate(true);
    }
}
