package tormozit;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Вкладка «Модуль» {@code FormEditor} не активирует {@link #XTEXT_CONTEXT_ID}.
 * При фокусе в BSL {@link StyledText} поднимает Xtext-контекст на уровне окна,
 * чтобы срабатывали Xtext-привязки клавиш и {@code activeWhen} handlers.
 */
public final class EmbeddedBslXtextContextHook
{
    private static final String TAG = "EmbeddedBslContext"; //$NON-NLS-1$

    public static final String XTEXT_CONTEXT_ID =
            "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$

    static final String FORM_EDITOR_CONTEXT_ID =
            "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$

    private static final String ORDINARY_FORM_EDITOR_CONTEXT_ID =
            "com._1c.g5.v8.dt.form.ui.ordinaryFormEditor"; //$NON-NLS-1$

    private static final Set<String> FORM_EDITOR_PART_IDS = new HashSet<>(Arrays.asList(
        "com._1c.g5.v8.dt.form.ui.formEditor", //$NON-NLS-1$
        "com._1c.g5.v8.dt.form.ui.ordinaryFormEditor" //$NON-NLS-1$
    ));

    private static final Set<String> FORM_EDITOR_CONTEXT_IDS = new HashSet<>(Arrays.asList(
        FORM_EDITOR_CONTEXT_ID,
        ORDINARY_FORM_EDITOR_CONTEXT_ID
    ));

    static final String EMBEDDED_HOOK_MARKER = "tormozit.embeddedBslXtextContext"; //$NON-NLS-1$

    static final String ACTIVATION_DATA_KEY = "tormozit.embeddedBslXtextActivation"; //$NON-NLS-1$

    private EmbeddedBslXtextContextHook() {}

    /** Подключает FocusIn/FocusOut на {@code textWidget} редактора. */
    public static void attach(BslXtextEditor editor, StyledText textWidget)
    {
        if (editor == null || textWidget == null || textWidget.isDisposed())
            return;
        if (Boolean.TRUE.equals(textWidget.getData(EMBEDDED_HOOK_MARKER)))
            return;

        IContextService contextService = resolveWindowContextService(editor);
        if (contextService == null)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "[!] attach: IContextService null editor=" + editorBrief(editor)); //$NON-NLS-1$
            return;
        }

        Listener focusIn = event -> ensureActivated(editor, textWidget, contextService, "focusIn"); //$NON-NLS-1$
        Listener focusOut = event -> onFocusOut(textWidget, contextService);

        textWidget.addListener(SWT.FocusIn, focusIn);
        textWidget.addListener(SWT.FocusOut, focusOut);

        textWidget.setData(EMBEDDED_HOOK_MARKER, Boolean.TRUE);

        textWidget.addDisposeListener(e ->
        {
            deactivateIfNeeded(textWidget, contextService);
            if (!textWidget.isDisposed())
            {
                textWidget.removeListener(SWT.FocusIn, focusIn);
                textWidget.removeListener(SWT.FocusOut, focusOut);
            }
        });

        if (textWidget.isFocusControl())
            ensureActivated(editor, textWidget, contextService, "attach"); //$NON-NLS-1$
    }

    private static IContextService resolveWindowContextService(BslXtextEditor editor)
    {
        if (editor.getSite() == null)
            return null;
        IWorkbenchWindow window = editor.getSite().getWorkbenchWindow();
        if (window == null)
            return null;
        return window.getService(IContextService.class);
    }

    private static void ensureActivated(
            BslXtextEditor editor,
            StyledText textWidget,
            IContextService contextService,
            String source)
    {
        String skip = formatEnsureSkipReason(editor, textWidget, contextService);
        if (skip != null)
        {
            return;
        }

        IContextActivation activation = contextService.activateContext(XTEXT_CONTEXT_ID);
        textWidget.setData(ACTIVATION_DATA_KEY, activation);

        if (Global.isLogEnabled())
            Global.log(TAG, "activate source=" + source + " editor=" + editorBrief(editor)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String formatEnsureSkipReason(
            BslXtextEditor editor,
            StyledText textWidget,
            IContextService contextService)
    {
        if (contextService == null)
            return "contextService=null"; //$NON-NLS-1$
        if (isXtextContextActive(contextService))
            return "xtextAlready"; //$NON-NLS-1$
        if (!needsContextBoost(editor, contextService))
            return "!formEditor && !embeddedForm && !embeddedGranular"; //$NON-NLS-1$
        if (textWidget != null && textWidget.getData(ACTIVATION_DATA_KEY) != null)
            return "alreadyActivated"; //$NON-NLS-1$
        return null;
    }

    private static void onFocusOut(StyledText textWidget, IContextService contextService)
    {
        deactivateIfNeeded(textWidget, contextService);
    }

    private static void deactivateIfNeeded(StyledText textWidget, IContextService contextService)
    {
        Object data = textWidget.getData(ACTIVATION_DATA_KEY);
        if (!(data instanceof IContextActivation activation))
            return;

        contextService.deactivateContext(activation);
        textWidget.setData(ACTIVATION_DATA_KEY, null);

        if (Global.isLogEnabled())
            Global.log(TAG, "deactivate"); //$NON-NLS-1$
    }

    private static boolean needsContextBoost(BslXtextEditor editor, IContextService contextService)
    {
        if (isXtextContextActive(contextService))
            return false;
        if (isFormEditorContextActive(contextService))
            return true;
        if (isEmbeddedInFormEditor(editor))
            return true;
        return isEmbeddedInDtGranularEditor(editor);
    }

    private static boolean isXtextContextActive(IContextService contextService)
    {
        return containsContext(contextService, XTEXT_CONTEXT_ID);
    }

    private static boolean isFormEditorContextActive(IContextService contextService)
    {
        if (contextService == null)
            return false;
        Collection<?> activeIds = contextService.getActiveContextIds();
        if (activeIds == null)
            return false;
        for (Object id : activeIds)
        {
            if (id instanceof String contextId && FORM_EDITOR_CONTEXT_IDS.contains(contextId))
                return true;
        }
        return false;
    }

    private static boolean containsContext(IContextService contextService, String contextId)
    {
        if (contextService == null)
            return false;
        Collection<?> activeIds = contextService.getActiveContextIds();
        if (activeIds == null)
            return false;
        return activeIds.contains(contextId);
    }

    private static boolean isEmbeddedInFormEditor(BslXtextEditor editor)
    {
        if (editor.getSite() == null)
            return false;
        IWorkbenchPage page = editor.getSite().getPage();
        if (page == null)
            return false;
        IEditorPart rootEditor = page.getActiveEditor();
        if (rootEditor == null || rootEditor instanceof BslXtextEditor)
            return false;
        if (rootEditor.getSite() == null)
            return false;
        return FORM_EDITOR_PART_IDS.contains(rootEditor.getSite().getId());
    }

    /** BSL внутри {@link DtGranularEditor} (справочник, документ и т.д.), не автономный *.bsl. */
    private static boolean isEmbeddedInDtGranularEditor(BslXtextEditor editor)
    {
        if (editor == null || editor.getSite() == null)
            return false;
        IWorkbenchPage page = editor.getSite().getPage();
        if (page == null)
            return false;
        IEditorPart rootEditor = page.getActiveEditor();
        if (rootEditor == null || rootEditor instanceof BslXtextEditor)
            return false;
        return rootEditor instanceof DtGranularEditor<?>;
    }

    private static String editorBrief(BslXtextEditor editor)
    {
        try
        {
            if (editor.getEditorInput() != null)
                return editor.getEditorInput().getName();
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        return editor.getClass().getSimpleName();
    }
}
