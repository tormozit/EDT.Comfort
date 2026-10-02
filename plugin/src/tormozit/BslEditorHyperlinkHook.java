package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.ui.editor.hyperlinking.XtextHyperlink;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * История переходов по ссылкам BSL-редактора (Ctrl+клик), issue 664.
 * Команды перехода к определению сохраняют штатные обработчики.
 */
public final class BslEditorHyperlinkHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            display.addFilter(SWT.MouseMove, BslEditorHyperlinkHook::attach);
            display.addFilter(SWT.MouseDown, BslEditorHyperlinkHook::attach);
        });
    }

    private static void attach(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed()
            || (event.stateMask & SWT.MOD1) == 0 || !PlatformUI.isWorkbenchRunning())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        BslXtextEditor editor = page == null ? null : GetRef.getActiveBslEditor(page.getActivePart());
        if (editor == null)
            return;
        ITextViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null || viewer.getTextWidget() != text)
            return;
        Object value = Global.getField(viewer, "fHyperlinkDetectors");
        if (!(value instanceof IHyperlinkDetector[] detectors))
            return;
        // TextViewer и HyperlinkManager используют один массив (проверено в
        // .tmp/bundles/jface-text/). setHyperlinkDetectors вызвал бы dispose
        // действующих детекторов. Меняем только элементы общего массива.
        synchronized (detectors)
        {
            for (int i = 0; i < detectors.length; i++)
            {
                IHyperlinkDetector detector = detectors[i];
                if (detector == null || detector instanceof HistoryDetector
                    || !"com._1c.g5.v8.dt.bsl.ui.editor.CustomHyperlinkDetector"
                        .equals(detector.getClass().getName()))
                    continue;
                detectors[i] = detector instanceof IHyperlinkDetectorExtension2
                    ? new ModifiedHistoryDetector(detector, editor, page)
                    : new HistoryDetector(detector, editor, page);
            }
        }
    }

    private static class HistoryDetector implements IHyperlinkDetector, IHyperlinkDetectorExtension
    {
        final IHyperlinkDetector delegate;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        HistoryDetector(IHyperlinkDetector delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            this.delegate = delegate;
            this.source = source;
            this.page = page;
        }

        @Override
        public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean multiple)
        {
            IHyperlink[] links = delegate.detectHyperlinks(viewer, region, multiple);
            if (links == null)
                return null;
            IHyperlink[] result = links.clone();
            for (int i = 0; i < result.length; i++)
            {
                // Ссылки на строки BSL оставляем штатными. Для реквизита
                // BslHyperlinkHelper создаёт XtextHyperlink с URI объекта модели.
                if (result[i] instanceof XtextHyperlink link && link.getURI() != null
                    && !"bsl".equalsIgnoreCase(link.getURI().fileExtension()))
                    result[i] = new HistoryHyperlink(link, source, page);
            }
            return result;
        }

        @Override
        public void dispose()
        {
            if (delegate instanceof IHyperlinkDetectorExtension extension)
                extension.dispose();
        }
    }

    private static final class ModifiedHistoryDetector extends HistoryDetector
        implements IHyperlinkDetectorExtension2
    {
        ModifiedHistoryDetector(IHyperlinkDetector delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            super(delegate, source, page);
        }

        @Override
        public int getStateMask()
        {
            return ((IHyperlinkDetectorExtension2) delegate).getStateMask();
        }
    }

    private static final class HistoryHyperlink implements IHyperlink
    {
        private final XtextHyperlink delegate;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        HistoryHyperlink(XtextHyperlink delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            this.delegate = delegate;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return delegate.getHyperlinkRegion(); }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return delegate.getHyperlinkText(); }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                delegate.open();
                scope.opened = true;
            }
        }
    }

    private static final class HistoryScope implements AutoCloseable
    {
        private final IWorkbenchPage page;
        private final INavigationHistory history;
        private Field ignoreEntries;
        private boolean opened;

        HistoryScope(IWorkbenchPage page, IEditorPart source)
        {
            this.page = page;
            history = page.getNavigationHistory();
            if (history == null)
                return;
            try
            {
                // NavigationHistory.addEntry/markEditor пропускают записи при
                // ignoreEntries > 0; gotoEntry использует этот же счётчик.
                // Проверено в .tmp/bundles/ui-workbench/NavigationHistory-c.javap.txt.
                if (!"org.eclipse.ui.internal.NavigationHistory".equals(history.getClass().getName()))
                    return;
                Field field = history.getClass().getDeclaredField("ignoreEntries");
                field.setAccessible(true);
                history.markLocation(source);
                int previous = field.getInt(history);
                field.setInt(history, previous + 1);
                ignoreEntries = field;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                // При несовместимости платформы оставляем штатную историю переходов.
            }
        }

        @Override
        public void close()
        {
            if (ignoreEntries != null)
            {
                try
                {
                    ignoreEntries.setInt(history, ignoreEntries.getInt(history) - 1);
                    IEditorPart destination = page.getActiveEditor();
                    if (opened && destination != null)
                        history.markLocation(destination);
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    // Не прерываем штатное открытие ссылки из-за ошибки истории.
                }
            }
        }
    }
}
