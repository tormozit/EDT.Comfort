package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import org.eclipse.xtext.ui.editor.contentassist.XtextContentAssistProcessor;

public final class ContentAssistPatcher
{
    private static Field contentAssistantField;
    private static Field fProcessorsField;

    private ContentAssistPatcher() {}

    public static boolean applyPatch(SourceViewer sourceViewer, int timeout, String charset)
    {
        return applyPatch(sourceViewer, timeout, charset, (TextEditorFacade) null);
    }

    public static boolean applyPatch(SourceViewer sourceViewer, int timeout, String charset,
                                    BslXtextEditor editor)
    {
        return applyPatch(sourceViewer, timeout, charset,
            editor != null ? new BslTextEditorFacade(editor) : null);
    }

    public static boolean applyPatch(SourceViewer sourceViewer, int timeout, String charset,
                                    TextEditorFacade facade)
    {
        boolean isQueryEditor = facade != null && facade.isQueryMode();
        ContentAssistant contentAssist = getContentAssistant(sourceViewer);
        if (contentAssist == null)
        {
            ContentAssistDebug.log("applyPatch FAIL: ContentAssistant null"); //$NON-NLS-1$
            return false;
        }

        IContentAssistProcessor current =
            contentAssist.getContentAssistProcessor(IDocument.DEFAULT_CONTENT_TYPE);
        if (current == null)
        {
            ContentAssistDebug.log("applyPatch FAIL: processor null"); //$NON-NLS-1$
            return false;
        }

        IContentAssistProcessor xtext = unwrap(current);
        if (!(xtext instanceof XtextContentAssistProcessor))
        {
            ContentAssistDebug.log("applyPatch FAIL: not Xtext, got " + current.getClass().getName()); //$NON-NLS-1$
            return false;
        }

        ((XtextContentAssistProcessor) xtext)
            .setCompletionProposalAutoActivationCharacters(resolveAutoActivationCharset(charset));

        applyCommonAssistSettings(contentAssist, timeout);

        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            restoreNativeAssist(contentAssist, current, sourceViewer);
            ContentAssistDebug.log("applyPatch NATIVE delegate=" + xtext.getClass().getSimpleName()); //$NON-NLS-1$
            return true;
        }

        SmartContentAssistProcessor wrapper;
        if (current instanceof SmartContentAssistProcessor)
            wrapper = (SmartContentAssistProcessor) current;
        else
        {
            wrapper = new SmartContentAssistProcessor(xtext, resolveAutoActivationCharset(charset));
            contentAssist.setContentAssistProcessor(wrapper, IDocument.DEFAULT_CONTENT_TYPE);
            forceReplaceProcessor(contentAssist, IDocument.DEFAULT_CONTENT_TYPE, wrapper);
        }

        contentAssist.setSorter(new SmartCodeProposalSorter());
        ContentAssistSessionReloader.install(sourceViewer, contentAssist, wrapper, facade);

        ContentAssistDebug.log("applyPatch OK delegate=" + xtext.getClass().getSimpleName()); //$NON-NLS-1$
        // #region agent log
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        boolean autoOpenEnabled = settings != null && settings.isEnabled();
        ContentAssistDebug.debugModeLog("H79", "ContentAssistPatcher.applyPatch", "autoOpenReady", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"comfortFiltersOn\":" + ComfortSettings.isReplaceListFiltersEnabled() //$NON-NLS-1$
                + ",\"autoOpenEnabled\":" + autoOpenEnabled //$NON-NLS-1$
                + ",\"nativeCharsetEmpty\":" + (autoOpenEnabled && ComfortSettings.isReplaceListFiltersEnabled()) //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        return true;
    }

    private static String resolveAutoActivationCharset(String charset)
    {
        if (ComfortSettings.isReplaceListFiltersEnabled()
            && ContentAssistSettings.getInstance() != null
            && ContentAssistSettings.getInstance().isEnabled())
            return ""; //$NON-NLS-1$
        return charset;
    }

    private static void applyCommonAssistSettings(ContentAssistant contentAssist, int timeout)
    {
        contentAssist.setAutoActivationDelay(timeout);
        contentAssist.enableAutoActivation(true);
        contentAssist.enableAutoInsert(false);
        contentAssist.setRepeatedInvocationMode(true);
        contentAssist.setStatusLineVisible(false);
    }

    /** Глобально выключены фильтры — штатный processor и сортировка EDT. */
    private static void restoreNativeAssist(ContentAssistant contentAssist,
                                            IContentAssistProcessor current,
                                            SourceViewer sourceViewer)
    {
        IContentAssistProcessor nativeProc = unwrap(current);
        ContentAssistPopupSync.uninstallFilterTrackerPrepend(contentAssist);
        ContentAssistPopupSync.clearSyncState();
        ContentAssistPopupUi.removeFilterToggle(contentAssist);
        if (current instanceof SmartContentAssistProcessor)
        {
            contentAssist.setContentAssistProcessor(nativeProc, IDocument.DEFAULT_CONTENT_TYPE);
            forceReplaceProcessor(contentAssist, IDocument.DEFAULT_CONTENT_TYPE, nativeProc);
        }
        contentAssist.setSorter(null);
        ContentAssistSessionReloader.uninstall(sourceViewer, contentAssist);
    }

    public static ContentAssistant getContentAssistant(SourceViewer sourceViewer)
    {
        if (contentAssistantField == null && !initContentAssistantField())
            return null;
        try {
            return (ContentAssistant) contentAssistantField.get(sourceViewer);
        } catch (Exception ignored) { return null; }
    }

    private static IContentAssistProcessor unwrap(IContentAssistProcessor p)
    {
        while (p instanceof SmartContentAssistProcessor)
            p = ((SmartContentAssistProcessor) p).getDelegate();
        return p;
    }

    private static void forceReplaceProcessor(ContentAssistant ca,
                                              String contentType,
                                              IContentAssistProcessor wrapper)
    {
        try {
            if (fProcessorsField == null) {
                fProcessorsField = ContentAssistant.class.getDeclaredField("fProcessors"); //$NON-NLS-1$
                fProcessorsField.setAccessible(true);
            }
            Object map = fProcessorsField.get(ca);
            if (!(map instanceof java.util.Map)) return;

            Object value = ((java.util.Map<?, ?>) map).get(contentType);
            if (value instanceof java.util.Set) {
                java.util.Set<?> set = (java.util.Set<?>) value;
                set.clear();
                ((java.util.Set) set).add(wrapper);
            } else {
                ((java.util.Map) map).put(contentType, wrapper);
            }
        } catch (Exception e) {
            Global.log("ContentAssistPatcher: forceReplaceProcessor failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static boolean initContentAssistantField()
    {
        try {
            Field f = SourceViewer.class.getDeclaredField("fContentAssistant"); //$NON-NLS-1$
            f.setAccessible(true);
            contentAssistantField = f;
            return true;
        } catch (Exception ignored) { return false; }
    }
}