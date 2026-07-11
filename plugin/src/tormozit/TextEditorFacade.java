package tormozit;

import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.ui.IWorkbenchPartSite;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Общий контракт для {@link com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor}
 * и модальных редакторов запросов (QueryTextEditDialog и т.п.).
 *
 * <p>Используется {@link ContentAssistPatcher} и
 * {@link ContentAssistSessionReloader} для единообразной работы
 * с любым XText-редактором (BSL или язык запросов).</p>
 */
interface TextEditorFacade
{
    /** SourceViewer для патча ContentAssistant (конкретный {@code SourceViewer}). */
    SourceViewer getSourceViewer();

    /** Проект EDT (для IR-интеграции). */
    IDtProject getDtProject();

    /** Workbench site для {@code page.activate()} ({@code null} для модальных диалогов). */
    IWorkbenchPartSite getSite();

    /** Доступность редактирования. */
    boolean isEditable();

    /** Имя для логов. */
    String getDisplayName();

    /** {@code true} для языка запросов, {@code false} для BSL. */
    boolean isQueryMode();

    /** Сырой объект ({@code BslXtextEditor} или {@code queryDialog}). */
    Object getRaw();
}
