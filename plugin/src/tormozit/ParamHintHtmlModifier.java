// ParamHintHtmlModifier.java
package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Подсказка параметров метода (CTRL+SHIFT+Space / LinkedMode):
 * заголовок {@code Владелец : Метод : Типы<br>(…)}; строка типа
 * {@code [Вх|Вых] Тип: … [=default] - описание}; выбор сигнатуры
 * по числу фактических аргументов и пересечению типов.
 */
public final class ParamHintHtmlModifier
{
    private static final String HEADING_CLASS = "contentassist-heading-content"; //$NON-NLS-1$
    private static final String TYPE_CLASS = "contentassist-type"; //$NON-NLS-1$
    private static final String DESC_CLASS = "contentassist-description"; //$NON-NLS-1$
    private static final String COMFORT_META_MARKER = "comfort-param-meta"; //$NON-NLS-1$
    private static final String INVOCATION_PARAMETERS_HOVER_COMMAND =
        "com._1c.g5.v8.dt.bsl.ui.hover.InvocationParametersHover"; //$NON-NLS-1$
    private static final String PARAMETERS_HOVER_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.contentassist.ParametersHoverInfoControl"; //$NON-NLS-1$
    private static final String BSL_SELECTION_LISTENER_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.contentassist.BslSelectionChangedListener"; //$NON-NLS-1$

    private static volatile boolean installed;
    private static IExecutionListener paramHoverCommandListener;

    private ParamHintHtmlModifier() {}

    public static boolean isInstalled()
    {
        return installed;
    }

    /** Установить глобальный перехватчик HTML подсказки параметров. */
    public static void install(Display display)
    {
        if (installed)
            return;
        installed = true;

        ContentAssistDebug.log("ParamHintHtmlModifier: install SWT.Show filter"); //$NON-NLS-1$

        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed())
                return;

            Browser browser = IrBslHoverHtml.findControlBrowser(shell);
            if (browser == null || browser.isDisposed())
                return;

            ContentAssistDebug.debugModeLog("ParamHintHtml", "browserFound", //$NON-NLS-1$ //$NON-NLS-2$
                shell.getClass().getSimpleName(),
                "{\"browser\":\"" + browser.getClass().getName() + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$

            browser.addProgressListener(new ProgressListener()
            {
                @Override
                public void completed(ProgressEvent event)
                {
                    tryModifyBrowserHtml(browser);
                }

                @Override
                public void changed(ProgressEvent event)
                {
                    // не используется
                }
            });

            tryModifyBrowserHtml(browser);
        });

        installParamHoverCommandProbe(display);

        ContentAssistDebug.log("ParamHintHtmlModifier: SWT.Show filter installed"); //$NON-NLS-1$
    }

    /**
     * Только диагностика ручного Ctrl+Shift+Space. Каретку не двигаем.
     */
    private static void installParamHoverCommandProbe(Display display)
    {
        if (paramHoverCommandListener != null)
            return;
        try
        {
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands == null)
                return;
            paramHoverCommandListener = new IExecutionListener()
            {
                @Override
                public void preExecute(String commandId, ExecutionEvent event)
                {
                    if (!INVOCATION_PARAMETERS_HOVER_COMMAND.equals(commandId))
                        return;
                    probeInvocationParametersHoverCaret(null);
                }

                @Override
                public void notHandled(String commandId, NotHandledException exception)
                {
                }

                @Override
                public void postExecuteFailure(String commandId, ExecutionException exception)
                {
                    if (!INVOCATION_PARAMETERS_HOVER_COMMAND.equals(commandId))
                        return;
                    ContentAssistDebug.debugModeLog("H-manual", "paramHoverCmd", "failure", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                            String.valueOf(exception)) + "\"}"); //$NON-NLS-1$
                }

                @Override
                public void postExecuteSuccess(String commandId, Object returnValue)
                {
                    if (!INVOCATION_PARAMETERS_HOVER_COMMAND.equals(commandId))
                        return;
                    boolean infoPresent = isParamHoverInfoControlPresent();
                    ContentAssistDebug.debugModeLog("H-manual", "paramHoverCmd", "success", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "{\"infoPresent\":" + infoPresent + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            };
            commands.addExecutionListener(paramHoverCommandListener);
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Диагностика без изменения selection: как EDT {@code inInvocationParameters}.
     *
     * @return всегда {@code false} — каретку не трогаем
     */
    public static boolean prepareCaretForInvocationParametersHover(ITextViewer viewer)
    {
        probeInvocationParametersHoverCaret(viewer);
        return false;
    }

    private static void probeInvocationParametersHoverCaret(ITextViewer viewer)
    {
        if (viewer == null)
        {
            ActiveEditor active = resolveActiveBslEditor();
            if (active == null)
                return;
            BslXtextEditor editor = null;
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null && window.getActivePage() != null)
                    editor = GetRef.getActiveBslEditor(window.getActivePage().getActiveEditor());
            }
            catch (Exception ignored)
            {
            }
            if (editor != null)
                viewer = editor.getInternalSourceViewer();
            if (viewer == null)
                return;
        }
        IDocument document = viewer.getDocument();
        if (!(document instanceof IXtextDocument xdoc))
            return;
        StyledText st = viewer.getTextWidget();
        if (st == null || st.isDisposed())
            return;
        int caret = st.getCaretOffset();
        try
        {
            xdoc.readOnly((IUnitOfWork<Void, XtextResource>) resource -> {
                if (resource == null)
                    return null;
                EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
                EObject obj = helper.resolveContainedElementAt(resource, caret);
                String objClass = obj != null && obj.eClass() != null
                    ? obj.eClass().getName() : "null"; //$NON-NLS-1$
                CallSiteInfo site = findCallSiteAt(resource, caret);
                boolean edtInParams = false;
                if (site != null)
                {
                    // Как EDT inInvocationParameters: methodEnd <= caret < callEnd
                    edtInParams = caret >= site.methodAccessEnd && caret < site.callEnd;
                }
                ContentAssistDebug.debugModeLog("H-manual", "probeCaret", "preExecute", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"caret\":" + caret //$NON-NLS-1$
                        + ",\"obj\":\"" + ContentAssistDebug.jsonEscapeForLog(objClass) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"methodEnd\":" + (site != null ? site.methodAccessEnd : -1) //$NON-NLS-1$
                        + ",\"callEnd\":" + (site != null ? site.callEnd : -1) //$NON-NLS-1$
                        + ",\"edtInParams\":" + edtInParams //$NON-NLS-1$
                        + ",\"infoBefore\":" + isParamHoverInfoControlPresent() + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            });
        }
        catch (Exception ex)
        {
            ContentAssistDebug.debugModeLog("H-manual", "probeCaret", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(ex)) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean isParamHoverInfoControlPresent()
    {
        try
        {
            org.eclipse.core.commands.IHandler handler = resolveInvocationParametersHoverHandler();
            if (handler == null)
                return false;
            Object infoControl = Global.getField(handler, "infoControl"); //$NON-NLS-1$
            return infoControl != null;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void logCallSiteFound(int caret, String via, CallSiteInfo site, EObject obj)
    {
        String objClass = "null"; //$NON-NLS-1$
        if (obj != null && obj.eClass() != null)
            objClass = obj.eClass().getName();
        ContentAssistDebug.debugModeLog("H-manual", "findCallSiteAt", "hit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret //$NON-NLS-1$
                + ",\"via\":\"" + ContentAssistDebug.jsonEscapeForLog(via) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"obj\":\"" + ContentAssistDebug.jsonEscapeForLog(objClass) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"methodEnd\":" + site.methodAccessEnd //$NON-NLS-1$
                + ",\"callEnd\":" + site.callEnd + "}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static CallSiteInfo findCallSiteAt(XtextResource resource, int caret)
    {
        EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
        EObject obj = helper.resolveContainedElementAt(resource, caret);
        if (obj == null && caret > 0)
            obj = helper.resolveContainedElementAt(resource, caret - 1);
        for (EObject cur = obj; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof Invocation invocation)
            {
                CallSiteInfo site = callSiteForInvocation(invocation);
                if (site != null)
                    logCallSiteFound(caret, "astAncestor", site, obj); //$NON-NLS-1$
                return site;
            }
            if (cur instanceof OperatorStyleCreator ctor)
            {
                CallSiteInfo site = callSiteForOperatorCtor(ctor);
                if (site != null)
                    logCallSiteFound(caret, "astAncestorCtor", site, obj); //$NON-NLS-1$
                return site;
            }
        }
        if (obj instanceof Invocation invocation)
        {
            CallSiteInfo site = callSiteForInvocation(invocation);
            if (site != null)
                logCallSiteFound(caret, "astInvocation", site, obj); //$NON-NLS-1$
            return site;
        }
        if (obj instanceof FeatureAccess)
        {
            EObject parent = obj.eContainer();
            if (parent instanceof Invocation invocation)
            {
                CallSiteInfo site = callSiteForInvocation(invocation);
                if (site != null)
                    logCallSiteFound(caret, "astFeatureParent", site, obj); //$NON-NLS-1$
                return site;
            }
        }
        CallSiteInfo fromNode = findCallSiteFromNodeModel(resource, caret);
        if (fromNode != null)
        {
            logCallSiteFound(caret, "nodeModel", fromNode, obj); //$NON-NLS-1$
            return fromNode;
        }
        ContentAssistDebug.debugModeLog("H-manual", "findCallSiteAt", "miss", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"caret\":" + caret //$NON-NLS-1$
                + ",\"obj\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                    obj != null && obj.eClass() != null ? obj.eClass().getName() : "null") //$NON-NLS-1$
                + "\"}"); //$NON-NLS-1$
        return null;
    }

    private static CallSiteInfo findCallSiteFromNodeModel(XtextResource resource, int caret)
    {
        if (resource.getParseResult() == null)
            return null;
        ICompositeNode root = resource.getParseResult().getRootNode();
        if (root == null)
            return null;
        INode leaf = NodeModelUtils.findLeafNodeAtOffset(root, caret);
        if (leaf == null && caret > 0)
            leaf = NodeModelUtils.findLeafNodeAtOffset(root, caret - 1);
        for (INode node = leaf; node != null; node = node.getParent())
        {
            EObject sem = node.getSemanticElement();
            if (sem instanceof Invocation invocation)
                return callSiteForInvocation(invocation);
            if (sem instanceof OperatorStyleCreator ctor)
                return callSiteForOperatorCtor(ctor);
        }
        return null;
    }

    private static CallSiteInfo callSiteForInvocation(Invocation invocation)
    {
        if (invocation == null)
            return null;
        List<INode> methodNodes = NodeModelUtils.findNodesForFeature(invocation,
            BslPackage.Literals.INVOCATION__METHOD_ACCESS);
        if (methodNodes == null || methodNodes.isEmpty())
            return null;
        ICompositeNode invNode = NodeModelUtils.findActualNodeFor(invocation);
        if (invNode == null)
            return null;
        return new CallSiteInfo(methodNodes.get(0).getTotalEndOffset(),
            invNode.getTotalEndOffset());
    }

    private static CallSiteInfo callSiteForOperatorCtor(OperatorStyleCreator ctor)
    {
        if (ctor == null)
            return null;
        List<INode> typeNodes = NodeModelUtils.findNodesForFeature(ctor,
            BslPackage.Literals.OPERATOR_STYLE_CREATOR__TYPE);
        if (typeNodes == null || typeNodes.isEmpty())
            return null;
        ICompositeNode ctorNode = NodeModelUtils.findActualNodeFor(ctor);
        if (ctorNode == null)
            return null;
        return new CallSiteInfo(typeNodes.get(0).getTotalEndOffset(),
            ctorNode.getTotalEndOffset());
    }

    private static final class CallSiteInfo
    {
        final int methodAccessEnd;
        final int callEnd;

        CallSiteInfo(int methodAccessEnd, int callEnd)
        {
            this.methodAccessEnd = methodAccessEnd;
            this.callEnd = callEnd;
        }

        boolean contains(int offset)
        {
            return offset >= methodAccessEnd && offset < callEnd;
        }
    }

    /** Модифицировать HTML в браузере (сигнатура + формат строки типа). */
    private static void tryModifyBrowserHtml(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return;

        String html = browser.getText();
        if (html == null || html.isBlank())
            return;
        if (html.indexOf(HEADING_CLASS) < 0)
            return;

        HoverContext ctx = resolveHoverContext(browser);
        if (ctx != null && ctx.pages != null && ctx.pages.size() > 1)
        {
            int best = pickBestSignatureIndex(ctx);
            if (best >= 0 && best != ctx.pageIndex)
            {
                ContentAssistDebug.debugModeLog("ParamHintHtml", "signaturePick", //$NON-NLS-1$ //$NON-NLS-2$
                    "switch", //$NON-NLS-1$
                    "{\"from\":" + ctx.pageIndex + ",\"to\":" + best //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"paramIndex\":" + ctx.paramIndex //$NON-NLS-1$
                        + ",\"actualArgs\":" + ctx.actualArgCount + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                if (Global.invokeVoid(ctx.parametersHover, "showPage", //$NON-NLS-1$
                    ctx.pages, Integer.valueOf(best), Integer.valueOf(ctx.paramIndex)))
                {
                    return;
                }
            }
        }

        String modified = modifyHtml(html, ctx);
        if (modified == null || modified.equals(html))
        {
            ContentAssistDebug.debugModeLog("ParamHintHtml", "skip", //$NON-NLS-1$ //$NON-NLS-2$
                ctx == null ? "noChangeCtxNull" : "noChange", //$NON-NLS-1$ //$NON-NLS-2$
                "{" + contentAssistIndexJson(html) //$NON-NLS-1$
                    + ",\"ctxNull\":" + (ctx == null) //$NON-NLS-1$
                    + ",\"pages\":" + (ctx != null && ctx.pages != null ? ctx.pages.size() : -1) //$NON-NLS-1$
                    + ",\"pageIndex\":" + (ctx != null ? ctx.pageIndex : -1) //$NON-NLS-1$
                    + ",\"paramIndex\":" + (ctx != null ? ctx.paramIndex : -1) //$NON-NLS-1$
                    + ",\"hasMeta\":" + html.contains(COMFORT_META_MARKER) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        browser.setText(modified);
        scheduleScrollParamNameIntoView(browser);

        ContentAssistDebug.debugModeLog("ParamHintHtml", "modified", //$NON-NLS-1$ //$NON-NLS-2$
            "ok", //$NON-NLS-1$
            "{\"lenBefore\":" + html.length() //$NON-NLS-1$
                + ",\"lenAfter\":" + modified.length() //$NON-NLS-1$
                + ",\"hasMeta\":" + modified.contains(COMFORT_META_MARKER) //$NON-NLS-1$
                + ",\"ctxNull\":" + (ctx == null) //$NON-NLS-1$
                + ",\"pages\":" + (ctx != null && ctx.pages != null ? ctx.pages.size() : -1) //$NON-NLS-1$
                + "," + contentAssistIndexJson(html) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** После setText — прокрутить к {@code <b>} имени текущего параметра. */
    private static void scheduleScrollParamNameIntoView(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return;
        Display display = browser.getDisplay();
        if (display == null || display.isDisposed())
            return;
        ProgressListener once = new ProgressListener()
        {
            @Override
            public void completed(ProgressEvent event)
            {
                if (!browser.isDisposed())
                    browser.removeProgressListener(this);
                scrollParamNameIntoView(browser);
            }

            @Override
            public void changed(ProgressEvent event)
            {
                // не используется
            }
        };
        browser.addProgressListener(once);
        display.timerExec(120, () ->
        {
            if (!browser.isDisposed())
            {
                try
                {
                    browser.removeProgressListener(once);
                }
                catch (Exception ignored)
                {
                }
                scrollParamNameIntoView(browser);
            }
        });
    }

    private static void scrollParamNameIntoView(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return;
        try
        {
            browser.execute(
                "try{" //$NON-NLS-1$
                    + "var b=document.querySelector('.contentassist-heading-content b')" //$NON-NLS-1$
                    + "||document.querySelector('b');" //$NON-NLS-1$
                    + "if(b){b.scrollIntoView(true);}" //$NON-NLS-1$
                    + "}catch(e){}"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Патч заголовка + строки типа. {@code ctx} может быть {@code null}
     * (тогда только {@code <br>(} и разворот уже раскрытого описания).
     */
    static String modifyHtml(String html, HoverContext ctx)
    {
        if (html == null || html.isEmpty())
            return null;

        String result = html;
        String withParen = modifyHeadingHtml(result, ctx);
        if (withParen != null)
            result = withParen;

        if (result.contains(COMFORT_META_MARKER) || result.contains("data-comfort=\"1\"")) //$NON-NLS-1$
            return result.equals(html) ? null : result;

        String withContent = modifyContentHtml(result, ctx);
        if (withContent != null)
            result = withContent;

        return result.equals(html) ? null : result;
    }

    /**
     * Заголовок: {@code Владелец : Метод : Типы<br>(…параметры…)} вместо
     * {@code Функция|Процедура Типы Имя(…)}.
     */
    static String modifyHeadingHtml(String html, HoverContext ctx)
    {
        if (html == null || html.isEmpty())
            return null;

        String marker = "<span class=\"" + HEADING_CLASS + "\">"; //$NON-NLS-1$ //$NON-NLS-2$
        int spanStart = html.indexOf(marker);
        if (spanStart < 0)
            return null;

        int contentStart = spanStart + marker.length();

        int brPos = html.indexOf("<br>", contentStart); //$NON-NLS-1$
        int firstParen = html.indexOf('(', contentStart);
        if (firstParen < 0)
            return null;
        if (brPos >= 0 && brPos < firstParen)
            return null;

        int spanEnd = html.indexOf("</span>", contentStart); //$NON-NLS-1$
        if (spanEnd >= 0 && firstParen > spanEnd)
            return null;

        String before = html.substring(contentStart, firstParen);
        String rebuilt = rebuildHeadingPrefix(before, ctx);
        if (rebuilt == null)
            return html.substring(0, firstParen) + "<br>(" + html.substring(firstParen + 1); //$NON-NLS-1$
        return html.substring(0, contentStart) + rebuilt + "<br>(" //$NON-NLS-1$
            + html.substring(firstParen + 1);
    }

    /** {@code Владелец : Метод : Типы} (типы — HTML со ссылками, если есть). */
    private static String rebuildHeadingPrefix(String beforeHtml, HoverContext ctx)
    {
        String methodName = resolveHeadingMethodName(ctx, beforeHtml);
        if (methodName == null || methodName.isEmpty())
            return null;
        String owner = resolveHeadingOwner(ctx);
        String returnTypes = resolveHeadingReturnTypes(ctx, beforeHtml, methodName);
        StringBuilder sb = new StringBuilder();
        if (owner != null && !owner.isEmpty())
        {
            sb.append(escapeHtml(owner));
            sb.append(" : "); //$NON-NLS-1$
        }
        sb.append(escapeHtml(methodName));
        if (returnTypes != null && !returnTypes.isBlank())
        {
            sb.append(" : "); //$NON-NLS-1$
            sb.append(returnTypes.trim());
        }
        return sb.toString();
    }

    private static Object resolveViewPage(HoverContext ctx)
    {
        if (ctx == null || ctx.pages == null || ctx.pageIndex < 0
            || ctx.pageIndex >= ctx.pages.size())
            return null;
        Object page = ctx.pages.get(ctx.pageIndex);
        Object viewPage = Global.invoke(page, "getViewPage"); //$NON-NLS-1$
        if (viewPage != null)
            return viewPage;
        return Global.getField(page, "viewPage"); //$NON-NLS-1$
    }

    private static String resolveHeadingMethodName(HoverContext ctx, String beforeHtml)
    {
        Object viewPage = resolveViewPage(ctx);
        if (viewPage != null)
        {
            String name = asString(Global.invoke(viewPage, "getFirstName")); //$NON-NLS-1$
            if (name != null && !name.isBlank())
                return name.trim();
        }
        if (ctx != null && ctx.method != null)
        {
            String fromMethod = duallyNamedText(ctx.method);
            if (fromMethod != null && !fromMethod.isEmpty())
                return fromMethod;
        }
        if (ctx != null && ctx.pages != null && ctx.pageIndex >= 0
            && ctx.pageIndex < ctx.pages.size())
        {
            String name = asString(Global.invoke(ctx.pages.get(ctx.pageIndex), "getName")); //$NON-NLS-1$
            if (name != null && !name.isBlank())
                return name.trim();
        }
        return extractMethodNameFromHeading(beforeHtml);
    }

    private static String resolveHeadingOwner(HoverContext ctx)
    {
        String link = null;
        String moduleOwner = null;
        String fromTitle = null;
        String typeName = null;
        String containerName = null;
        String fallback = null;
        String source = "none"; //$NON-NLS-1$
        String picked = null;

        Object viewPage = resolveViewPage(ctx);
        if (viewPage != null)
        {
            link = asString(Global.invoke(viewPage, "getLink")); //$NON-NLS-1$
            if (link != null && !link.isBlank())
            {
                moduleOwner = GetRef.moduleOwnerFromDocumentationLink(link.trim());
                if (moduleOwner != null && !moduleOwner.isBlank())
                {
                    source = "moduleLink"; //$NON-NLS-1$
                    picked = moduleOwner;
                }
            }
            if (picked == null)
            {
                fromTitle = ownerFromExternalTitle(viewPage);
                if (isHumanOwnerName(fromTitle))
                {
                    source = "externalTitle"; //$NON-NLS-1$
                    picked = fromTitle;
                }
            }
            if (picked == null)
            {
                Object typeRef = Global.getField(viewPage, "typeReference"); //$NON-NLS-1$
                typeName = shortStringIfHuman(typeRef);
                if (typeName != null)
                {
                    source = "typeReference"; //$NON-NLS-1$
                    picked = typeName;
                }
            }
            if (picked == null)
            {
                Object container = Global.invoke(viewPage, "getContainer"); //$NON-NLS-1$
                containerName = shortStringIfHuman(container);
                if (containerName != null)
                {
                    source = "container"; //$NON-NLS-1$
                    picked = containerName;
                }
            }
        }
        if (picked == null)
        {
            fallback = resolveMethodOwnerName(ctx);
            if (fallback != null && !fallback.isBlank())
            {
                source = "methodAst"; //$NON-NLS-1$
                picked = fallback;
            }
        }
        logHeadingOwner(link, moduleOwner, fromTitle, typeName, containerName, fallback, source,
            picked);
        return picked;
    }

    private static void logHeadingOwner(String link, String moduleOwner, String fromTitle,
        String typeName, String containerName, String fallback, String source, String picked)
    {
        ContentAssistDebug.debugModeLog("H-owner", "resolveHeadingOwner", "pick", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"link\":\"" + ContentAssistDebug.jsonEscapeForLog(link != null ? link : "") //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"moduleOwner\":\"" //$NON-NLS-1$
                + ContentAssistDebug.jsonEscapeForLog(moduleOwner != null ? moduleOwner : "") //$NON-NLS-1$
                + "\",\"externalTitle\":\"" //$NON-NLS-1$
                + ContentAssistDebug.jsonEscapeForLog(fromTitle != null ? fromTitle : "") //$NON-NLS-1$
                + "\",\"typeRef\":\"" //$NON-NLS-1$
                + ContentAssistDebug.jsonEscapeForLog(typeName != null ? typeName : "") //$NON-NLS-1$
                + "\",\"container\":\"" //$NON-NLS-1$
                + ContentAssistDebug.jsonEscapeForLog(containerName != null ? containerName : "") //$NON-NLS-1$
                + "\",\"fallback\":\"" //$NON-NLS-1$
                + ContentAssistDebug.jsonEscapeForLog(fallback != null ? fallback : "") //$NON-NLS-1$
                + "\",\"source\":\"" + ContentAssistDebug.jsonEscapeForLog(source) //$NON-NLS-1$
                + "\",\"picked\":\"" //$NON-NLS-1$
                + ContentAssistDebug.jsonEscapeForLog(picked != null ? picked : "") + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String ownerFromExternalTitle(Object viewPage)
    {
        String ext = asString(Global.invoke(viewPage, "getExternalTitle")); //$NON-NLS-1$
        if (ext == null || ext.isBlank())
            return null;
        String plain = stripHtml(ext).trim();
        int dot = plain.lastIndexOf('.');
        if (dot > 0)
            return plain.substring(0, dot).trim();
        return null;
    }

    private static String shortStringIfHuman(Object obj)
    {
        if (obj == null)
            return null;
        String shortName = asString(Global.invoke(obj, "toShortString")); //$NON-NLS-1$
        return isHumanOwnerName(shortName) ? shortName.trim() : null;
    }

    private static boolean isHumanOwnerName(String name)
    {
        if (name == null || name.isBlank())
            return false;
        String trimmed = name.trim();
        return !trimmed.contains(":/") //$NON-NLS-1$
            && !trimmed.startsWith("platform") //$NON-NLS-1$
            && !trimmed.startsWith("v8help") //$NON-NLS-1$
            && !trimmed.startsWith("http:") //$NON-NLS-1$
            && !trimmed.startsWith("https:"); //$NON-NLS-1$
    }

    private static String resolveHeadingReturnTypes(HoverContext ctx, String beforeHtml,
        String methodName)
    {
        Object viewPage = resolveViewPage(ctx);
        if (viewPage != null)
        {
            Object returned = Global.invoke(viewPage, "getReturnedValue"); //$NON-NLS-1$
            if (returned != null)
            {
                String html = formatValueContentTypesHtml(returned);
                if (html != null && !html.isBlank())
                    return html;
            }
        }
        if (ctx != null && ctx.method != null && ctx.method.isRetVal())
        {
            EList<TypeItem> types = ctx.method.getRetValType();
            if (types != null && !types.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                for (TypeItem type : types)
                {
                    String name = formatTypeItem(type);
                    if (name == null || name.isEmpty())
                        continue;
                    if (sb.length() > 0)
                        sb.append(','); //$NON-NLS-1$
                    sb.append(escapeHtml(name));
                }
                if (sb.length() > 0)
                    return sb.toString();
            }
        }
        return extractReturnTypesHtmlFromHeading(beforeHtml, methodName);
    }

    private static String extractReturnTypesHtmlFromHeading(String beforeHtml, String methodName)
    {
        if (beforeHtml == null || methodName == null || methodName.isBlank())
            return null;
        String plain = stripHtml(beforeHtml);
        if (plain == null)
            return null;
        plain = stripKindKeyword(plain.trim());
        int methodPos = plain.lastIndexOf(methodName);
        if (methodPos < 0)
            return null;
        plain = plain.substring(0, methodPos).trim();
        if (plain.isEmpty())
            return null;
        return escapeHtml(plain);
    }

    private static String resolveMethodDisplayName(HoverContext ctx, String beforeHtml)
    {
        return resolveHeadingMethodName(ctx, beforeHtml);
    }

    private static String resolveMethodOwnerName(HoverContext ctx)
    {
        if (ctx == null || ctx.method == null)
            return null;
        for (EObject cur = ctx.method.eContainer(); cur != null; cur = cur.eContainer())
        {
            if (cur instanceof TypeItem typeItem)
            {
                String name = formatTypeItem(typeItem);
                if (name != null && !name.isEmpty())
                    return name;
            }
        }
        return null;
    }

    private static String extractMethodNameFromHeading(String beforeHtml)
    {
        String plain = stripHtml(beforeHtml);
        if (plain == null)
            return null;
        plain = plain.trim();
        plain = stripKindKeyword(plain);
        int sp = plain.lastIndexOf(' ');
        if (sp >= 0)
            plain = plain.substring(sp + 1).trim();
        return plain.isEmpty() ? null : plain;
    }

    private static String stripKindKeyword(String plain)
    {
        if (plain == null || plain.isEmpty())
            return plain;
        String[] kinds = {
            "Функция", "Процедура", "Function", "Procedure" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        for (String kind : kinds)
        {
            if (plain.length() >= kind.length()
                && plain.regionMatches(true, 0, kind, 0, kind.length()))
            {
                String rest = plain.substring(kind.length()).trim();
                return rest;
            }
        }
        return plain;
    }

    private static String duallyNamedText(DuallyNamedElement element)
    {
        if (element == null)
            return null;
        String ru = element.getNameRu();
        if (ru != null && !ru.isEmpty())
            return ru;
        String name = element.getName();
        return name != null && !name.isEmpty() ? name : null;
    }

    static String modifyContentHtml(String html, HoverContext ctx)
    {
        if (html == null || html.isEmpty())
            return null;

        int typeClassPos = indexOfClassAttribute(html, TYPE_CLASS);
        if (typeClassPos < 0)
            return null;

        int typeDivStart = html.lastIndexOf("<div", typeClassPos); //$NON-NLS-1$
        if (typeDivStart < 0)
            return null;
        int typeInnerStart = html.indexOf('>', typeClassPos);
        if (typeInnerStart < 0)
            return null;
        typeInnerStart++;
        int typeInnerEnd = html.indexOf("</div>", typeInnerStart); //$NON-NLS-1$
        if (typeInnerEnd < 0)
            return null;

        String oldTypeInner = html.substring(typeInnerStart, typeInnerEnd);
        // База — исходный HTML EDT («Тип:» + ссылки <a>), без пересборки текста типов
        String typeBase = stripComfortMeta(oldTypeInner);

        String description = null;
        String defaultDescription = null;
        Boolean isOut = null;
        String paramName = null;

        if (ctx != null && ctx.pages != null && !ctx.pages.isEmpty()
            && ctx.pageIndex >= 0 && ctx.pageIndex < ctx.pages.size()
            && ctx.paramIndex >= 0)
        {
            Object page = ctx.pages.get(ctx.pageIndex);
            Object paramContent = Global.invoke(page, "getParameter", //$NON-NLS-1$
                Integer.valueOf(ctx.paramIndex));
            if (paramContent != null)
            {
                paramName = asString(Global.invoke(paramContent, "getName")); //$NON-NLS-1$
                defaultDescription = asString(Global.invoke(paramContent, "getDefaultDescription")); //$NON-NLS-1$
                Object value = Global.invoke(paramContent, "getValue"); //$NON-NLS-1$
                description = resolveParamDescription(value, ctx.method, paramName);
                // Если в HTML типов нет <a>, пересобрать ссылки из IPageReference
                if (!typeBase.contains("href")) //$NON-NLS-1$
                {
                    String linkedTypes = formatParamContentTypesHtml(paramContent);
                    if (linkedTypes != null && !linkedTypes.isEmpty())
                    {
                        int colon = typeBase.indexOf(':');
                        String label = colon >= 0 ? typeBase.substring(0, colon + 1) : "Тип:"; //$NON-NLS-1$
                        typeBase = label + " " + linkedTypes; //$NON-NLS-1$
                    }
                    else if (stripHtml(extractTypesHtml(typeBase)).isEmpty())
                    {
                        String fallbackTypes = formatParamContentTypes(paramContent);
                        if (fallbackTypes != null && !fallbackTypes.isEmpty())
                        {
                            int colon = typeBase.indexOf(':');
                            String label = colon >= 0 ? typeBase.substring(0, colon + 1) : "Тип:"; //$NON-NLS-1$
                            typeBase = label + " " + escapeHtml(fallbackTypes); //$NON-NLS-1$
                        }
                    }
                }
            }
            isOut = resolveIsOut(ctx, paramName, paramContent);
        }

        if (description == null || description.isEmpty())
            description = normalizeDescription(extractExpandedDescription(html));

        String directionPrefix = buildDirectionPrefix(isOut);
        String suffix = buildMetaSuffix(defaultDescription, description);
        String newTypeInner = directionPrefix + typeBase + suffix;
        if (newTypeInner == null || newTypeInner.isEmpty())
            return null;
        boolean hasEnrichment = !extractTypesHtml(typeBase).isEmpty()
            || isOut != null
            || (defaultDescription != null && !defaultDescription.isBlank())
            || (description != null && !description.isBlank());
        if (!hasEnrichment)
            return null;
        ContentAssistDebug.debugModeLog("ParamHintHtml", "typeLine", //$NON-NLS-1$ //$NON-NLS-2$
            "meta", //$NON-NLS-1$
            "{\"paramName\":\"" + ContentAssistDebug.jsonEscapeForLog(paramName) //$NON-NLS-1$
                + "\",\"isOut\":" + isOut //$NON-NLS-1$
                + ",\"hasSuffix\":" + !suffix.isEmpty() //$NON-NLS-1$
                + ",\"hasHref\":" + typeBase.contains("href") //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"descLen\":" + (description == null ? -1 : description.length()) //$NON-NLS-1$
                + ",\"suffix\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                    suffix.length() > 120 ? suffix.substring(0, 120) : suffix) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        String typeOpen = html.substring(typeDivStart, typeInnerStart);
        if (!typeOpen.contains(COMFORT_META_MARKER))
        {
            String replaced = replaceClassAttribute(typeOpen, TYPE_CLASS,
                TYPE_CLASS + " " + COMFORT_META_MARKER); //$NON-NLS-1$
            if (replaced != null)
                typeOpen = replaced;
            else
            {
                int gt = typeOpen.lastIndexOf('>');
                if (gt >= 0)
                    typeOpen = typeOpen.substring(0, gt) + " data-comfort=\"1\"" //$NON-NLS-1$
                        + typeOpen.substring(gt);
            }
        }

        StringBuilder out = new StringBuilder(html.length() + 64);
        out.append(html, 0, typeDivStart);
        out.append(typeOpen);
        out.append(newTypeInner);
        out.append("</div>"); //$NON-NLS-1$

        int afterTypeDiv = typeInnerEnd + "</div>".length(); //$NON-NLS-1$
        // Убирать блок «Описание» только если текст уже в строке типа —
        // иначе остаётся штатный ►/▼ (платформенные методы).
        boolean inlinedDescription = description != null && !description.isBlank();
        int descClassPos = inlinedDescription
            ? indexOfClassAttribute(html, DESC_CLASS, afterTypeDiv)
            : -1;
        if (descClassPos >= 0)
        {
            int descDivStart = html.lastIndexOf("<div", descClassPos); //$NON-NLS-1$
            int descDivEnd = findMatchingDivEnd(html, descDivStart);
            if (descDivStart >= afterTypeDiv && descDivEnd > descDivStart)
            {
                out.append(html, afterTypeDiv, descDivStart);
                out.append(html, descDivEnd, html.length());
                return out.toString();
            }
        }

        out.append(html, afterTypeDiv, html.length());
        return out.toString();
    }

    /**
     * Описание параметра как в {@code PageUtil.generateSimpleHoverValueHtml}:
     * {@code ValueContent.getDescription()} и/или {@code TypeGroupContent.getDescription()}.
     */
    private static String resolveParamDescription(Object value, Object method, String paramName)
    {
        if (value == null)
            return null;
        String valueDesc = normalizeDescription(
            stripHtml(asString(Global.invoke(value, "getDescription")))); //$NON-NLS-1$
        Object groups = Global.invoke(value, "getGroups"); //$NON-NLS-1$
        if (groups instanceof List<?> groupList && !groupList.isEmpty())
        {
            if (groupList.size() == 1)
            {
                String groupDesc = normalizeDescription(
                    stripHtml(asString(Global.invoke(groupList.get(0), "getDescription")))); //$NON-NLS-1$
                if (valueDesc == null)
                    valueDesc = groupDesc;
                else if (groupDesc != null && !groupDesc.isEmpty()
                    && !valueDesc.contains(groupDesc))
                    valueDesc = valueDesc + " " + groupDesc; //$NON-NLS-1$
            }
            else if (valueDesc == null)
            {
                String hoverHtml = asString(Global.invoke(value, "toHoverHtml", //$NON-NLS-1$
                    "simple-typegroup", "simple-value-typegroups")); //$NON-NLS-1$ //$NON-NLS-2$
                valueDesc = normalizeDescription(stripHtml(hoverHtml));
            }
        }
        if (valueDesc == null && method != null && paramName != null)
        {
            valueDesc = normalizeDescription(
                BslDocCommentDescriptionFix.recoverParamDescription(method, paramName, value));
        }
        return valueDesc;
    }

    private static String normalizeDescription(String text)
    {
        if (text == null)
            return null;
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    /** Позиция атрибута {@code class="…className…"}, не вхождения в CSS. */
    private static int indexOfClassAttribute(String html, String className)
    {
        return indexOfClassAttribute(html, className, 0);
    }

    private static int indexOfClassAttribute(String html, String className, int fromIndex)
    {
        if (html == null || className == null || className.isEmpty())
            return -1;
        String[] needles = {
            "class=\"" + className + "\"", //$NON-NLS-1$ //$NON-NLS-2$
            "class='" + className + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "class=\"" + className + " ", //$NON-NLS-1$ //$NON-NLS-2$
            "class='" + className + " ", //$NON-NLS-1$ //$NON-NLS-2$
            "class=\"" + className + "\t", //$NON-NLS-1$ //$NON-NLS-2$
            "class=\"" + className + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        };
        int best = -1;
        for (String needle : needles)
        {
            int pos = html.indexOf(needle, fromIndex);
            if (pos >= 0 && (best < 0 || pos < best))
                best = pos;
        }
        return best;
    }

    private static String replaceClassAttribute(String tagOpen, String className, String newClassValue)
    {
        if (tagOpen == null || className == null || newClassValue == null)
            return null;
        String[] patterns = {
            "class=\"" + className + "\"", //$NON-NLS-1$ //$NON-NLS-2$
            "class='" + className + "'" //$NON-NLS-1$ //$NON-NLS-2$
        };
        String[] replacements = {
            "class=\"" + newClassValue + "\"", //$NON-NLS-1$ //$NON-NLS-2$
            "class='" + newClassValue + "'" //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (int i = 0; i < patterns.length; i++)
        {
            if (tagOpen.contains(patterns[i]))
                return tagOpen.replace(patterns[i], replacements[i]);
        }
        return null;
    }

    private static String contentAssistIndexJson(String html)
    {
        if (html == null)
            html = ""; //$NON-NLS-1$
        return "\"idxHeading\":" + html.indexOf(HEADING_CLASS) //$NON-NLS-1$
            + ",\"idxTypeRaw\":" + html.indexOf(TYPE_CLASS) //$NON-NLS-1$
            + ",\"idxTypeAttr\":" + indexOfClassAttribute(html, TYPE_CLASS) //$NON-NLS-1$
            + ",\"idxDescRaw\":" + html.indexOf(DESC_CLASS) //$NON-NLS-1$
            + ",\"idxDescAttr\":" + indexOfClassAttribute(html, DESC_CLASS); //$NON-NLS-1$
    }

    private static String buildDirectionPrefix(Boolean isOut)
    {
        if (isOut == null)
            return ""; //$NON-NLS-1$
        return isOut.booleanValue() ? "[Вых] " : "[Вх] "; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String buildMetaSuffix(String defaultDescription, String description)
    {
        StringBuilder sb = new StringBuilder();
        if (defaultDescription != null && !defaultDescription.isBlank())
        {
            sb.append(" [="); //$NON-NLS-1$
            sb.append(escapeHtml(stripHtml(defaultDescription)));
            sb.append(']');
        }
        if (description != null && !description.isBlank())
        {
            sb.append(" - "); //$NON-NLS-1$
            sb.append(escapeHtml(description));
        }
        return sb.toString();
    }

    /** Срезает ранее дописанный Comfort-мета (префикс Вх/Вых и суффикс), сохраняя «Тип:» и ссылки. */
    private static String stripComfortMeta(String typeInnerHtml)
    {
        if (typeInnerHtml == null || typeInnerHtml.isEmpty())
            return ""; //$NON-NLS-1$
        String s = typeInnerHtml;
        if (s.startsWith("[Вых]")) //$NON-NLS-1$
        {
            s = s.substring("[Вых]".length()); //$NON-NLS-1$
            if (s.startsWith(" ")) //$NON-NLS-1$
                s = s.substring(1);
        }
        else if (s.startsWith("[Вх]")) //$NON-NLS-1$
        {
            s = s.substring("[Вх]".length()); //$NON-NLS-1$
            if (s.startsWith(" ")) //$NON-NLS-1$
                s = s.substring(1);
        }
        int cut = s.length();
        int idx = s.indexOf(" [Вх]"); //$NON-NLS-1$
        if (idx >= 0 && idx < cut)
            cut = idx;
        idx = s.indexOf(" [Вых]"); //$NON-NLS-1$
        if (idx >= 0 && idx < cut)
            cut = idx;
        idx = s.indexOf(" [="); //$NON-NLS-1$
        if (idx >= 0 && idx < cut)
            cut = idx;
        idx = s.indexOf(" - "); //$NON-NLS-1$
        if (idx >= 0 && idx < cut)
            cut = idx;
        return s.substring(0, cut);
    }

    /** HTML фрагмент типов после «Тип:», со ссылками. */
    private static String extractTypesHtml(String typeInnerHtml)
    {
        if (typeInnerHtml == null || typeInnerHtml.isEmpty())
            return ""; //$NON-NLS-1$
        String cleaned = stripComfortMeta(typeInnerHtml);
        int colon = cleaned.indexOf(':');
        if (colon < 0)
            return cleaned.trim();
        return cleaned.substring(colon + 1).trim();
    }

    private static String extractExpandedDescription(String html)
    {
        int descPos = indexOfClassAttribute(html, DESC_CLASS);
        if (descPos < 0)
            return ""; //$NON-NLS-1$
        // Раскрыто: ▼ / &#9660; и вложенный div.unnamed с текстом
        int unnamed = html.indexOf("class=\"unnamed\"", descPos); //$NON-NLS-1$
        if (unnamed < 0)
            unnamed = html.indexOf("class='unnamed'", descPos); //$NON-NLS-1$
        if (unnamed < 0)
            return ""; //$NON-NLS-1$
        int innerStart = html.indexOf('>', unnamed);
        if (innerStart < 0)
            return ""; //$NON-NLS-1$
        innerStart++;
        int innerEnd = html.indexOf("</div>", innerStart); //$NON-NLS-1$
        if (innerEnd < 0)
            return ""; //$NON-NLS-1$
        return stripHtml(html.substring(innerStart, innerEnd));
    }

    private static String formatParamContentTypes(Object paramContent)
    {
        Object value = Global.invoke(paramContent, "getValue"); //$NON-NLS-1$
        return formatValueContentTypesPlain(value);
    }

    private static String formatValueContentTypesPlain(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        Object groups = Global.invoke(value, "getGroups"); //$NON-NLS-1$
        if (!(groups instanceof List<?> groupList))
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (Object group : groupList)
        {
            Object types = Global.invoke(group, "getTypeRefs"); //$NON-NLS-1$
            if (!(types instanceof List<?> typeList))
                continue;
            for (Object typeRef : typeList)
            {
                String name = asString(Global.invoke(typeRef, "toShortString")); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                    name = asString(Global.invoke(typeRef, "toFullString")); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                    continue;
                if (sb.length() > 0)
                    sb.append(", "); //$NON-NLS-1$
                sb.append(name);
            }
        }
        return sb.toString();
    }

    /** Типы с {@code <a href>} как в PageUtil.generateReferenceShortHtml. */
    private static String formatParamContentTypesHtml(Object paramContent)
    {
        Object value = Global.invoke(paramContent, "getValue"); //$NON-NLS-1$
        return formatValueContentTypesHtml(value);
    }

    private static String formatValueContentTypesHtml(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        Object groups = Global.invoke(value, "getGroups"); //$NON-NLS-1$
        if (!(groups instanceof List<?> groupList))
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (Object group : groupList)
        {
            Object types = Global.invoke(group, "getTypeRefs"); //$NON-NLS-1$
            if (!(types instanceof List<?> typeList))
                continue;
            for (Object typeRef : typeList)
            {
                String name = asString(Global.invoke(typeRef, "toShortString")); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                    name = asString(Global.invoke(typeRef, "toFullString")); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                    continue;
                if (sb.length() > 0)
                    sb.append(','); //$NON-NLS-1$
                String href = asString(Global.invoke(typeRef, "getHref")); //$NON-NLS-1$
                if (href != null && !href.isBlank())
                {
                    sb.append("<a href=\""); //$NON-NLS-1$
                    sb.append(escapeHtmlAttr(href));
                    sb.append("\">"); //$NON-NLS-1$
                    sb.append(escapeHtml(name));
                    sb.append("</a>"); //$NON-NLS-1$
                }
                else
                    sb.append(escapeHtml(name));
            }
        }
        return sb.toString();
    }

    private static String escapeHtmlAttr(String text)
    {
        if (text == null || text.isEmpty())
            return ""; //$NON-NLS-1$
        return escapeHtml(text).replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Boolean resolveIsOut(HoverContext ctx, String paramName, Object paramContent)
    {
        // Платформенные методы: mcore.Parameter.isOut()
        if (ctx != null && ctx.method != null && ctx.paramIndex >= 0
            && paramName != null && !paramName.isBlank())
        {
            Parameter matched = findParameter(ctx.method, ctx.pageIndex, ctx.paramIndex, paramName);
            if (matched != null)
                return Boolean.valueOf(matched.isOut());
        }
        // Методы модуля: FormalParam.isByValue() → ParamContent.getPassing()
        // Знач (byValue=true) = Вх; без Знач (byValue=false) = Вых
        if (paramContent != null)
        {
            Object passing = Global.invoke(paramContent, "getPassing"); //$NON-NLS-1$
            if (passing instanceof Boolean byValue)
                return Boolean.valueOf(!byValue.booleanValue());
        }
        return null;
    }

    private static Parameter findParameter(Method method, int pageIndex, int paramIndex,
        String paramName)
    {
        if (method == null || paramName == null || paramName.isBlank())
            return null;
        EList<ParamSet> sets = method.getParamSet();
        if (sets == null || sets.isEmpty())
            return null;

        if (pageIndex >= 0 && pageIndex < sets.size())
        {
            Parameter byIndex = parameterAt(sets.get(pageIndex), paramIndex);
            if (byIndex != null && parameterNameMatches(byIndex, paramName))
                return byIndex;
        }

        for (ParamSet set : sets)
        {
            Parameter candidate = parameterAt(set, paramIndex);
            if (candidate != null && parameterNameMatches(candidate, paramName))
                return candidate;
        }
        // Имя есть, индекс не совпал — искать по имени в любом ParamSet
        for (ParamSet set : sets)
        {
            if (set == null || set.getParams() == null)
                continue;
            for (Parameter candidate : set.getParams())
            {
                if (candidate != null && parameterNameMatches(candidate, paramName))
                    return candidate;
            }
        }
        return null;
    }

    private static Parameter parameterAt(ParamSet set, int paramIndex)
    {
        if (set == null || set.getParams() == null || paramIndex < 0
            || paramIndex >= set.getParams().size())
            return null;
        return set.getParams().get(paramIndex);
    }

    private static boolean parameterNameMatches(Parameter parameter, String paramName)
    {
        if (parameter == null || paramName == null)
            return false;
        String name = parameter.getName();
        if (paramName.equalsIgnoreCase(name))
            return true;
        String ru = parameter.getNameRu();
        return paramName.equalsIgnoreCase(ru);
    }

    private static int findMatchingDivEnd(String html, int divStart)
    {
        if (divStart < 0 || divStart >= html.length())
            return -1;
        int depth = 0;
        int i = divStart;
        while (i < html.length())
        {
            int nextOpen = html.indexOf("<div", i); //$NON-NLS-1$
            int nextClose = html.indexOf("</div>", i); //$NON-NLS-1$
            if (nextClose < 0)
                return -1;
            if (nextOpen >= 0 && nextOpen < nextClose)
            {
                depth++;
                i = nextOpen + 4;
                continue;
            }
            depth--;
            i = nextClose + "</div>".length(); //$NON-NLS-1$
            if (depth == 0)
                return i;
        }
        return -1;
    }

    private static HoverContext resolveHoverContext(Browser browser)
    {
        ResolveDiag diag = new ResolveDiag();
        Object parametersHover = findParametersHover(browser, diag);
        if (parametersHover == null)
        {
            ContentAssistDebug.debugModeLog("ParamHintHtml", "resolveCtx", "miss", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                diag.toJson());
            return null;
        }
        Object input = Global.invoke(parametersHover, "getInput"); //$NON-NLS-1$
        if (input == null)
        {
            Object control = Global.invoke(parametersHover, "getControl"); //$NON-NLS-1$
            input = Global.getField(control, "fInput"); //$NON-NLS-1$
        }
        if (input == null)
        {
            diag.inputNull = true;
            ContentAssistDebug.debugModeLog("ParamHintHtml", "resolveCtx", "inputNull", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                diag.toJson());
            return null;
        }

        Object pagesObj = Global.getField(input, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages) || pages.isEmpty())
        {
            diag.pagesEmpty = true;
            ContentAssistDebug.debugModeLog("ParamHintHtml", "resolveCtx", "pagesEmpty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                diag.toJson());
            return null;
        }

        int pageIndex = 0;
        Object indexObj = Global.invoke(input, "getIndex"); //$NON-NLS-1$
        if (indexObj instanceof Integer idx)
            pageIndex = idx.intValue();

        int paramIndex = 0;
        Object paramIndexObj = Global.getField(input, "paramIndex"); //$NON-NLS-1$
        if (paramIndexObj instanceof Integer pidx)
            paramIndex = pidx.intValue();

        HoverContext ctx = new HoverContext();
        ctx.parametersHover = parametersHover;
        ctx.input = input;
        @SuppressWarnings("unchecked")
        List<Object> pageList = (List<Object>) pages;
        ctx.pages = pageList;
        ctx.pageIndex = pageIndex;
        ctx.paramIndex = paramIndex;

        fillInvocationContext(ctx);
        ContentAssistDebug.debugModeLog("ParamHintHtml", "resolveCtx", "ok", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"pages\":" + ctx.pages.size() //$NON-NLS-1$
                + ",\"pageIndex\":" + ctx.pageIndex //$NON-NLS-1$
                + ",\"paramIndex\":" + ctx.paramIndex //$NON-NLS-1$
                + ",\"actualArgs\":" + ctx.actualArgCount //$NON-NLS-1$
                + ",\"via\":\"" + ContentAssistDebug.jsonEscapeForLog(diag.via) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        return ctx;
    }

    private static Object findParametersHover(Browser browser, ResolveDiag diag)
    {
        if (browser == null || browser.isDisposed())
            return null;

        Object fromBrowser = findParametersHoverFromBrowserListeners(browser, diag);
        if (fromBrowser != null)
        {
            diag.via = "browserListener"; //$NON-NLS-1$
            return fromBrowser;
        }

        Object fromHandler = findParametersHoverFromHandler(diag);
        if (fromHandler != null)
        {
            if (browserMatches(fromHandler, browser))
            {
                diag.via = "handler"; //$NON-NLS-1$
                return fromHandler;
            }
            diag.handlerBrowserMismatch = true;
        }

        Object fromLinked = findParametersHoverFromLinkedMode(browser, diag);
        if (fromLinked != null)
        {
            diag.via = "linkedMode"; //$NON-NLS-1$
            return fromLinked;
        }

        return null;
    }

    /**
     * Штатный {@code ParametersHoverInfoControl} вешает на Browser dispose/key listeners
     * (inner-классы с {@code this$0}). По ним достаём владельца без handler/LinkedMode.
     */
    private static Object findParametersHoverFromBrowserListeners(Browser browser, ResolveDiag diag)
    {
        try
        {
            int[] events = { SWT.Dispose, SWT.KeyDown, SWT.Traverse, SWT.FocusIn, SWT.FocusOut };
            for (int eventType : events)
            {
                org.eclipse.swt.widgets.Listener[] listeners = browser.getListeners(eventType);
                if (listeners == null || listeners.length == 0)
                    continue;
                diag.browserListenerEvents++;
                for (org.eclipse.swt.widgets.Listener listener : listeners)
                {
                    Object hover = extractParametersHoverFromListener(listener);
                    if (hover != null && browserMatches(hover, browser))
                    {
                        diag.browserListenerHit = true;
                        return hover;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            diag.browserListenerError = ex.getClass().getSimpleName();
        }
        return null;
    }

    private static Object extractParametersHoverFromListener(Object listener)
    {
        if (listener == null)
            return null;
        Object cur = listener;
        Object typed = Global.getField(cur, "eventListener"); //$NON-NLS-1$
        if (typed != null)
            cur = typed;
        for (int depth = 0; depth < 6 && cur != null; depth++)
        {
            if (PARAMETERS_HOVER_CLASS.equals(cur.getClass().getName()))
                return cur;
            Object outer = Global.getField(cur, "this$0"); //$NON-NLS-1$
            if (outer == null)
                break;
            cur = outer;
        }
        return null;
    }

    private static Object findParametersHoverFromHandler(ResolveDiag diag)
    {
        try
        {
            org.eclipse.core.commands.IHandler handler = resolveInvocationParametersHoverHandler();
            if (handler == null)
            {
                diag.handlerNull = true;
                return null;
            }
            Object infoControl = Global.getField(handler, "infoControl"); //$NON-NLS-1$
            if (infoControl == null)
            {
                diag.infoControlNull = true;
                return null;
            }
            diag.infoControlClass = infoControl.getClass().getName();
            if (!PARAMETERS_HOVER_CLASS.equals(infoControl.getClass().getName()))
            {
                diag.infoControlWrongClass = true;
                return null;
            }
            return infoControl;
        }
        catch (Exception ex)
        {
            diag.handlerError = ex.getClass().getSimpleName();
            return null;
        }
    }

    private static org.eclipse.core.commands.IHandler resolveInvocationParametersHoverHandler()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            org.eclipse.ui.commands.ICommandService commands =
                window.getService(org.eclipse.ui.commands.ICommandService.class);
            if (commands == null)
                return null;
            org.eclipse.core.commands.Command command =
                commands.getCommand(INVOCATION_PARAMETERS_HOVER_COMMAND);
            return command != null ? command.getHandler() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static Object findParametersHoverFromLinkedMode(Browser browser, ResolveDiag diag)
    {
        try
        {
            ActiveEditor active = resolveActiveBslEditor();
            if (active == null || active.document == null)
            {
                diag.activeEditorNull = true;
                return null;
            }
            LinkedModeModel model = null;
            if (active.caret >= 0)
                model = LinkedModeModel.getModel(active.document, active.caret);
            if (model == null && LinkedModeModel.hasInstalledModel(active.document))
            {
                int len = active.document.getLength();
                for (int probe : new int[] { active.caret, active.caret - 1, active.caret + 1,
                    Math.max(0, len - 1), 0 })
                {
                    if (probe < 0 || probe > len)
                        continue;
                    model = LinkedModeModel.getModel(active.document, probe);
                    if (model != null)
                        break;
                }
            }
            if (model == null)
            {
                diag.linkedModelNull = true;
                return null;
            }
            Object listeners = Global.getField(model, "fListeners"); //$NON-NLS-1$
            Iterable<?> iterable = asIterable(listeners);
            if (iterable == null)
            {
                diag.linkedListenersNull = true;
                return null;
            }
            for (Object listener : iterable)
            {
                if (listener == null)
                    continue;
                String cls = listener.getClass().getName();
                if (!BSL_SELECTION_LISTENER_CLASS.equals(cls))
                    continue;
                diag.linkedListenerFound = true;
                Object infoControl = Global.getField(listener, "infoControl"); //$NON-NLS-1$
                if (infoControl == null)
                    continue;
                if (browserMatches(infoControl, browser))
                    return infoControl;
                diag.linkedBrowserMismatch = true;
            }
        }
        catch (Exception ex)
        {
            diag.linkedError = ex.getClass().getSimpleName();
        }
        return null;
    }

    private static boolean browserMatches(Object parametersHover, Browser browser)
    {
        if (parametersHover == null || browser == null)
            return false;
        Object owned = Global.invoke(parametersHover, "getBrowser"); //$NON-NLS-1$
        if (owned == browser)
            return true;
        Object control = Global.invoke(parametersHover, "getControl"); //$NON-NLS-1$
        Object fBrowser = Global.getField(control, "fBrowser"); //$NON-NLS-1$
        return fBrowser == browser;
    }

    private static final class ResolveDiag
    {
        boolean browserListenerHit;
        int browserListenerEvents;
        String browserListenerError = ""; //$NON-NLS-1$
        String via = ""; //$NON-NLS-1$
        boolean handlerNull;
        boolean infoControlNull;
        boolean infoControlWrongClass;
        boolean handlerBrowserMismatch;
        boolean inputNull;
        boolean pagesEmpty;
        boolean activeEditorNull;
        boolean linkedModelNull;
        boolean linkedListenersNull;
        boolean linkedListenerFound;
        boolean linkedBrowserMismatch;
        String infoControlClass = ""; //$NON-NLS-1$
        String handlerError = ""; //$NON-NLS-1$
        String linkedError = ""; //$NON-NLS-1$

        String toJson()
        {
            return "{\"handlerNull\":" + handlerNull //$NON-NLS-1$
                + ",\"infoControlNull\":" + infoControlNull //$NON-NLS-1$
                + ",\"infoControlWrongClass\":" + infoControlWrongClass //$NON-NLS-1$
                + ",\"handlerBrowserMismatch\":" + handlerBrowserMismatch //$NON-NLS-1$
                + ",\"browserListenerHit\":" + browserListenerHit //$NON-NLS-1$
                + ",\"browserListenerEvents\":" + browserListenerEvents //$NON-NLS-1$
                + ",\"inputNull\":" + inputNull //$NON-NLS-1$
                + ",\"pagesEmpty\":" + pagesEmpty //$NON-NLS-1$
                + ",\"activeEditorNull\":" + activeEditorNull //$NON-NLS-1$
                + ",\"linkedModelNull\":" + linkedModelNull //$NON-NLS-1$
                + ",\"linkedListenersNull\":" + linkedListenersNull //$NON-NLS-1$
                + ",\"linkedListenerFound\":" + linkedListenerFound //$NON-NLS-1$
                + ",\"linkedBrowserMismatch\":" + linkedBrowserMismatch //$NON-NLS-1$
                + ",\"infoControlClass\":\"" + ContentAssistDebug.jsonEscapeForLog(infoControlClass) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"handlerError\":\"" + ContentAssistDebug.jsonEscapeForLog(handlerError) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"linkedError\":\"" + ContentAssistDebug.jsonEscapeForLog(linkedError) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"browserListenerError\":\"" + ContentAssistDebug.jsonEscapeForLog(browserListenerError) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"via\":\"" + ContentAssistDebug.jsonEscapeForLog(via) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void fillInvocationContext(HoverContext ctx)
    {
        ActiveEditor active = resolveActiveBslEditor();
        if (active == null || !(active.document instanceof IXtextDocument xdoc) || active.caret < 0)
            return;
        try
        {
            InvocationSnapshot snap = xdoc.readOnly(
                (IUnitOfWork<InvocationSnapshot, XtextResource>) resource -> {
                    if (resource == null)
                        return null;
                    return resolveInvocationSnapshot(resource, active.caret, ctx.paramIndex);
                });
            if (snap == null)
                return;
            ctx.actualArgCount = snap.actualArgCount;
            ctx.actualArgTypes = snap.actualArgTypes;
            ctx.method = snap.method;
        }
        catch (Exception ex)
        {
            ContentAssistDebug.debugModeLog("ParamHintHtml", "invocationCtx", //$NON-NLS-1$ //$NON-NLS-2$
                "error", //$NON-NLS-1$
                "{\"ex\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(ex)) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static InvocationSnapshot resolveInvocationSnapshot(XtextResource resource,
        int caret, int paramIndex)
    {
        EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
        EObject obj = helper.resolveContainedElementAt(resource, caret);
        EObject invocationLike = null;
        for (EObject cur = obj; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof Invocation || cur instanceof OperatorStyleCreator)
            {
                invocationLike = cur;
                break;
            }
        }
        if (invocationLike == null)
            return null;

        InvocationSnapshot snap = new InvocationSnapshot();
        EList<Expression> params;
        if (invocationLike instanceof Invocation invocation)
        {
            params = invocation.getParams();
            snap.method = resolveMethod(invocation.getMethodAccess());
        }
        else
        {
            params = ((OperatorStyleCreator) invocationLike).getParams();
        }
        snap.actualArgCount = params != null ? params.size() : 0;
        if (params != null && paramIndex >= 0 && paramIndex < params.size())
        {
            Expression arg = params.get(paramIndex);
            if (arg != null && arg.getTypes() != null && !arg.getTypes().isEmpty())
                snap.actualArgTypes = new ArrayList<>(arg.getTypes());
        }
        if (snap.actualArgTypes == null)
            snap.actualArgTypes = Collections.emptyList();
        return snap;
    }

    private static Method resolveMethod(FeatureAccess access)
    {
        if (access == null)
            return null;
        EList<FeatureEntry> entries = null;
        if (access instanceof DynamicFeatureAccess dynamic)
            entries = dynamic.getFeatureEntries();
        else if (access instanceof StaticFeatureAccess staticAccess)
            entries = staticAccess.getFeatureEntries();
        if (entries == null)
            return null;
        for (FeatureEntry entry : entries)
        {
            if (entry == null)
                continue;
            EObject feature = entry.getFeature();
            if (feature instanceof Method method)
                return method;
        }
        return null;
    }

    static int pickBestSignatureIndex(HoverContext ctx)
    {
        if (ctx == null || ctx.pages == null || ctx.pages.isEmpty())
            return -1;
        if (ctx.pages.size() == 1)
            return 0;

        int required = Math.max(0, ctx.actualArgCount);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < ctx.pages.size(); i++)
        {
            int paramCount = countPageParams(ctx.pages.get(i));
            if (paramCount >= required)
                candidates.add(Integer.valueOf(i));
        }
        if (candidates.isEmpty())
            return ctx.pageIndex >= 0 ? ctx.pageIndex : 0;

        List<TypeItem> actualTypes = ctx.actualArgTypes;
        if (actualTypes == null || actualTypes.isEmpty() || ctx.paramIndex < 0)
            return candidates.get(0).intValue();

        Set<String> actualNames = typeItemNames(actualTypes);
        if (actualNames.isEmpty())
            return candidates.get(0).intValue();

        for (Integer candidate : candidates)
        {
            int idx = candidate.intValue();
            Set<String> paramNames = resolveParamTypeNames(ctx.pages.get(idx), ctx.paramIndex);
            if (namesIntersect(actualNames, paramNames))
                return idx;
        }
        return candidates.get(0).intValue();
    }

    private static int countPageParams(Object page)
    {
        if (page == null)
            return 0;
        int count = 0;
        try
        {
            while (true)
            {
                Object param = Global.invoke(page, "getParameter", Integer.valueOf(count)); //$NON-NLS-1$
                if (param == null)
                    break;
                count++;
                if (count > 64)
                    break;
            }
        }
        catch (RuntimeException ignored)
        {
            // getParameter / reflection
        }
        return count;
    }

    private static Set<String> resolveParamTypeNames(Object page, int paramIndex)
    {
        Object paramContent = Global.invoke(page, "getParameter", Integer.valueOf(paramIndex)); //$NON-NLS-1$
        if (paramContent == null)
            return Collections.emptySet();
        String formatted = formatParamContentTypes(paramContent);
        if (formatted == null || formatted.isEmpty())
            return Collections.emptySet();
        Set<String> names = new LinkedHashSet<>();
        for (String part : formatted.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
                names.add(trimmed.toLowerCase());
        }
        return names;
    }

    private static Set<String> typeItemNames(List<TypeItem> types)
    {
        Set<String> names = new LinkedHashSet<>();
        if (types == null)
            return names;
        for (TypeItem type : types)
        {
            String name = formatTypeItem(type);
            if (!name.isEmpty())
                names.add(name.toLowerCase());
        }
        return names;
    }

    private static boolean namesIntersect(Set<String> actual, Set<String> expected)
    {
        if (actual == null || actual.isEmpty() || expected == null || expected.isEmpty())
            return false;
        for (String name : actual)
        {
            if (expected.contains(name))
                return true;
        }
        return false;
    }

    private static String formatTypeItem(TypeItem type)
    {
        if (type == null)
            return ""; //$NON-NLS-1$
        if (type instanceof DuallyNamedElement dually)
        {
            String ru = dually.getNameRu();
            if (ru != null && !ru.isEmpty())
                return ru;
        }
        String name = type.getName();
        return name != null ? name : ""; //$NON-NLS-1$
    }

    private static ActiveEditor resolveActiveBslEditor()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            BslXtextEditor bslEditor = GetRef.getActiveBslEditor(editor);
            if (bslEditor == null && page.getActivePart() instanceof IEditorPart activePart)
                bslEditor = GetRef.getActiveBslEditor(activePart);
            if (bslEditor == null)
                return null;
            IDocument document = bslEditor.getDocument();
            if (document == null)
                return null;
            ActiveEditor active = new ActiveEditor();
            active.document = document;
            active.caret = -1;
            ITextViewer viewer = bslEditor.getInternalSourceViewer();
            if (viewer != null && viewer.getTextWidget() instanceof StyledText st
                && !st.isDisposed())
                active.caret = st.getCaretOffset();
            return active;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static Iterable<?> asIterable(Object listeners)
    {
        if (listeners == null)
            return null;
        if (listeners instanceof Iterable<?> iterable)
            return iterable;
        Object array = Global.invoke(listeners, "getListeners"); //$NON-NLS-1$
        if (array instanceof Object[] objs)
        {
            List<Object> list = new ArrayList<>(objs.length);
            for (Object o : objs)
                list.add(o);
            return list;
        }
        return null;
    }

    private static String asString(Object value)
    {
        return value != null ? value.toString() : null;
    }

    private static String stripHtml(String html)
    {
        if (html == null || html.isEmpty())
            return ""; //$NON-NLS-1$
        String text = html
            .replaceAll("(?is)<br\\s*/?>", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("(?is)</p>", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("(?is)<[^>]+>", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&nbsp;", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&#160;", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&lt;", "<") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&amp;", "&") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&quot;", "\"") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&#9660;", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&#9658;", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("▼", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("►", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return text.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String escapeHtml(String text)
    {
        if (text == null || text.isEmpty())
            return ""; //$NON-NLS-1$
        return text
            .replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static final class HoverContext
    {
        Object parametersHover;
        Object input;
        List<Object> pages;
        int pageIndex;
        int paramIndex;
        int actualArgCount;
        List<TypeItem> actualArgTypes = Collections.emptyList();
        Method method;
    }

    private static final class InvocationSnapshot
    {
        int actualArgCount;
        List<TypeItem> actualArgTypes = Collections.emptyList();
        Method method;
    }

    private static final class ActiveEditor
    {
        IDocument document;
        int caret;
    }
}
