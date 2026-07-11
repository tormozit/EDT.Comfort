// ParamHintHtmlModifier.java
package tormozit;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.osgi.framework.Bundle;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.BooleanLiteral;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DateLiteral;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.NullLiteral;
import com._1c.g5.v8.dt.bsl.model.NumberLiteral;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.UndefinedLiteral;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.Ctor;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;

/**
 * Подсказка параметров метода (CTRL+SHIFT+Space / LinkedMode):
 * заголовок {@code Владелец : Метод : Типы<br>(…)}; строка типа
 * {@code Вх.|Вых. - типы [=default] - описание}; выбор сигнатуры
 * по числу фактических аргументов и пересечению типов.
 */
public final class ParamHintHtmlModifier
{
    private static final String HEADING_CLASS = "contentassist-heading-content"; //$NON-NLS-1$
    /** Макс. число типов возврата в заголовке (через запятую); дальше — "...". */
    private static final int MAX_HEADING_RETURN_TYPE_ELEMENTS = 10;
    private static final String TYPE_CLASS = "contentassist-type"; //$NON-NLS-1$
    private static final String DESC_CLASS = "contentassist-description"; //$NON-NLS-1$
    private static final String COMFORT_META_MARKER = "comfort-param-meta"; //$NON-NLS-1$
    private static final String INVOCATION_PARAMETERS_HOVER_COMMAND =
        "com._1c.g5.v8.dt.bsl.ui.hover.InvocationParametersHover"; //$NON-NLS-1$
    private static final String PARAMETERS_HOVER_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.contentassist.ParametersHoverInfoControl"; //$NON-NLS-1$
    private static final String BSL_SELECTION_LISTENER_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.contentassist.BslSelectionChangedListener"; //$NON-NLS-1$
    /** Только для Find-miss fallback (unwrap), общий resolve не меняем. */
    private static final String PARAM_HOVER_HANDLER_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.contentassist.InvocationParametersHoverHandler"; //$NON-NLS-1$
    /** Маркер ProgressListener только для Find-miss browser. */
    private static final String FIND_MISS_COMFORT_PROGRESS =
        "tormozit.findMissComfortProgress"; //$NON-NLS-1$
    /** Маркер кнопки закрытия на нижней панели ParametersHoverInfoControl. */
    private static final String CLOSE_TOOLBAR_MARK = "tormozit.paramHintClose"; //$NON-NLS-1$
    /** Автовыбор сигнатуры: только при открытии или при сильном совпадении типа после смены текста. */
    private static final int SIG_PICK_STRONG_SCORE = 10;
    private static final String SIG_PICK_DONE_MARK = "tormozit.sigPickDone"; //$NON-NLS-1$
    private static final AtomicBoolean sigPickOnOpenPending = new AtomicBoolean(false);
    private static volatile String sigPickLastFingerprint = ""; //$NON-NLS-1$

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

            ensureParamHintCloseButton(browser);

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
     * Fallback Ctrl+Shift+Space при промахе EDT (нет popup).
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
                    sigPickOnOpenPending.set(true);
                    // TypesComputer — только при нескольких сигнатурах (иначе сразу выход).
                    ensureFirstActualArgTypesComputed();
                }

                @Override
                public void notHandled(String commandId, NotHandledException exception)
                {
                }

                @Override
                public void postExecuteFailure(String commandId, ExecutionException exception)
                {
                }

                @Override
                public void postExecuteSuccess(String commandId, Object returnValue)
                {
                    if (!INVOCATION_PARAMETERS_HOVER_COMMAND.equals(commandId))
                        return;
                    boolean alreadyVisible = isParamHintAlreadyVisible();
                    // Синхронно и только при реальном промахе — без asyncExec (иначе
                    // подмена comfort→EDT через ~50мс в основном режиме).
                    if (!alreadyVisible)
                        tryOpenParamHintAfterEdtMiss();
                }
            };
            commands.addExecutionListener(paramHoverCommandListener);
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Ждёт/считает тип 1-го аргумента ТОЛЬКО при нескольких сигнатурах.
     * Одна сигнатура — сразу return, без {@link TypesComputer}.
     */
    public static void ensureFirstActualArgTypesComputed()
    {
        ActiveEditor active = resolveActiveBslEditor();
        if (active == null || !(active.document instanceof IXtextDocument xdoc) || active.caret < 0)
            return;
        try
        {
            xdoc.readOnly((IUnitOfWork<Void, XtextResource>) resource -> {
                if (resource == null)
                    return null;
                EObject invocationLike = findInvocationLikeAt(resource, active.caret);
                if (invocationLike == null)
                    return null;
                if (!callSiteHasMultipleSignatures(resource, invocationLike))
                    return null;
                EList<Expression> params = paramsOfInvocationLike(invocationLike);
                if (params == null || params.isEmpty())
                    return null;
                Expression firstArg = params.get(0);
                if (firstArg == null)
                    return null;
                forceComputeExpressionTypes(resource, firstArg, invocationLike);
                return null;
            });
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Вызов у каретки: при вложенности берём внешний с несколькими сигнатурами
     * (иначе каретка на {@code ФиксированнаяСтруктура} внутри {@code Структура(...)} даёт внутренний ctor).
     */
    private static EObject findInvocationLikeAt(XtextResource resource, int caret)
    {
        EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
        EObject obj = helper.resolveContainedElementAt(resource, caret);
        EObject innermost = null;
        EObject outermostMulti = null;
        for (EObject cur = obj; cur != null; cur = cur.eContainer())
        {
            if (!(cur instanceof Invocation || cur instanceof OperatorStyleCreator))
                continue;
            if (innermost == null)
                innermost = cur;
            if (callSiteHasMultipleSignatures(resource, cur))
                outermostMulti = cur;
        }
        return outermostMulti != null ? outermostMulti : innermost;
    }

    private static EList<Expression> paramsOfInvocationLike(EObject invocationLike)
    {
        if (invocationLike instanceof Invocation invocation)
            return invocation.getParams();
        if (invocationLike instanceof OperatorStyleCreator ctor)
            return ctor.getParams();
        return null;
    }

    /** {@code true} только если у вызова реально больше одной сигнатуры/ParamSet/Ctor. */
    private static boolean callSiteHasMultipleSignatures(XtextResource resource,
        EObject invocationLike)
    {
        if (invocationLike instanceof OperatorStyleCreator ctor)
        {
            Type type = ctor.getType();
            if (type == null)
                return false;
            EList<Ctor> ctors = type.getCtors();
            return ctors != null && ctors.size() > 1;
        }
        if (!(invocationLike instanceof Invocation invocation))
            return false;
        FeatureAccess methodAccess = invocation.getMethodAccess();
        if (methodAccess == null || resource.getURI() == null)
            return false;
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(
                    resource.getURI());
            if (rsp == null)
                return false;
            DynamicFeatureAccessComputer computer = rsp.get(DynamicFeatureAccessComputer.class);
            Environmental envOwner = EcoreUtil2.getContainerOfType(methodAccess,
                Environmental.class);
            Environments environments = envOwner != null ? envOwner.environments() : null;
            if (computer == null || environments == null)
                return false;
            Object plain = Global.invoke(computer, "resolveObject", //$NON-NLS-1$
                methodAccess, environments);
            if (!(plain instanceof List<?> entries) || entries.isEmpty())
                return false;
            int signatureCount = 0;
            for (Object entry : entries)
            {
                Object feature = entry instanceof FeatureEntry fe
                    ? fe.getFeature()
                    : Global.invoke(entry, "getFeature"); //$NON-NLS-1$
                if (!(feature instanceof Method method))
                    continue;
                EList<ParamSet> sets = method.getParamSet();
                if (sets == null || sets.isEmpty())
                    signatureCount++;
                else
                    signatureCount += sets.size();
                if (signatureCount > 1)
                    return true;
            }
            return false;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void forceComputeExpressionTypes(XtextResource resource, Expression arg,
        EObject invocationLike)
    {
        if (resource == null || arg == null || resource.getURI() == null)
            return;
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(
                    resource.getURI());
            if (rsp == null)
                return;
            TypesComputer typesComputer = rsp.get(TypesComputer.class);
            if (typesComputer == null)
                return;
            EObject envFrom = invocationLike != null ? invocationLike : arg;
            Environmental envOwner = EcoreUtil2.getContainerOfType(envFrom, Environmental.class);
            Environments environments = envOwner != null ? envOwner.environments() : null;
            if (environments == null)
                return;
            typesComputer.computeTypes(arg, environments);
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Gate Find-miss: popup уже виден (реальный infoControl или Browser с heading).
     * Не трогает resolve ctx основного пути.
     */
    private static boolean isParamHintAlreadyVisible()
    {
        try
        {
            // e4 lookUp только здесь/в Find-miss — общий resolve не трогаем.
            Object handler = resolveParamHoverHandlerForMiss();
            if (handler != null)
            {
                Object infoControl = Global.getField(handler, "infoControl"); //$NON-NLS-1$
                if (infoControl != null)
                    return true;
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            if (display == null || display.isDisposed())
                return false;
            for (Shell shell : display.getShells())
            {
                if (shell == null || shell.isDisposed() || !shell.isVisible())
                    continue;
                Browser browser = IrBslHoverHtml.findControlBrowser(shell);
                if (browser == null || browser.isDisposed())
                    continue;
                String text = browser.getText();
                if (text != null && text.indexOf(HEADING_CLASS) >= 0)
                    return true;
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    /**
     * Только промах EDT (нет popup): Error-страницы вместо CA, напр. Найти().
     * Основной modifyHtml/Esc не меняем. HTML — через tryModifyBrowserHtml этого control.
     */
    private static void tryOpenParamHintAfterEdtMiss()
    {
        try
        {
            if (isParamHintAlreadyVisible())
                return;
            // Реальный handler с инъекцией (e4). Command.getHandler() — оболочка без полей.
            Object handler = resolveParamHoverHandlerForMiss();
            if (handler == null)
            {
                return;
            }
            Object documentation = Global.getField(handler, "documentation"); //$NON-NLS-1$
            Object languageProvider = Global.getField(handler, "languageProvider"); //$NON-NLS-1$

            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null)
                return;
            BslXtextEditor editor = GetRef.getActiveBslEditor(window.getActivePage().getActiveEditor());
            if (editor == null)
                return;
            ITextViewer viewer = editor.getInternalSourceViewer();
            if (viewer == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
                return;
            IDocument document = viewer.getDocument();
            if (!(document instanceof IXtextDocument xdoc))
                return;
            int caret = viewer.getTextWidget().getCaretOffset();
            org.eclipse.ui.IWorkbenchSite site = editor.getSite();

            final Object handlerRef = handler;
            Boolean opened = xdoc.readOnly((IUnitOfWork<Boolean, XtextResource>) resource -> {
                if (resource == null)
                    return Boolean.FALSE;
                CallSiteInfo siteInfo = findCallSiteAt(resource, caret);
                if (siteInfo == null)
                {
                    return Boolean.FALSE;
                }
                EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
                EObject resolved = helper.resolveContainedElementAt(resource, caret);
                Invocation invocation = findInvocationNear(resolved);
                if (invocation == null || invocation.getMethodAccess() == null)
                {
                    return Boolean.FALSE;
                }
                FeatureAccess methodAccess = invocation.getMethodAccess();
                IResourceServiceProvider rsp = resource.getURI() != null
                    ? IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(
                        resource.getURI())
                    : null;
                Object computer = rsp != null ? rsp.get(DynamicFeatureAccessComputer.class) : null;
                Environmental envOwner = EcoreUtil2.getContainerOfType(methodAccess,
                    Environmental.class);
                Object environments = envOwner != null ? envOwner.environments() : null;
                if (computer == null || environments == null)
                {
                    return Boolean.FALSE;
                }

                Object documentationLocal = documentation;
                Object languageProviderLocal = languageProvider;
                if (documentationLocal == null && rsp != null)
                {
                    try
                    {
                        Class<?> docClass = Class.forName(
                            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"); //$NON-NLS-1$
                        documentationLocal = rsp.get(docClass);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                if (languageProviderLocal == null && rsp != null)
                {
                    try
                    {
                        Class<?> langClass = Class.forName(
                            "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.SyntaxAssistLanguageProvider"); //$NON-NLS-1$
                        languageProviderLocal = rsp.get(langClass);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                if (documentationLocal == null)
                {
                    return Boolean.FALSE;
                }
                String lang = asString(Global.invoke(languageProviderLocal, "getLanguage")); //$NON-NLS-1$
                if (lang == null || lang.isBlank())
                    lang = "ru"; //$NON-NLS-1$

                Object plain = Global.invoke(computer, "resolveObject", //$NON-NLS-1$
                    methodAccess, environments);
                if (!(plain instanceof List<?> entries) || entries.isEmpty())
                {
                    return Boolean.FALSE;
                }

                List<Object> caPages = new ArrayList<>();
                int paramSize = 0;
                int maxParam = 0;
                for (Object entry : entries)
                {
                    Object feature = entry instanceof FeatureEntry fe
                        ? fe.getFeature()
                        : Global.invoke(entry, "getFeature"); //$NON-NLS-1$
                    if (!(feature instanceof Method method))
                        continue;
                    Object group = Global.invoke(documentationLocal, "getHoverDocumentationPages", //$NON-NLS-1$
                        method, lang);
                    int before = caPages.size();
                    FindMissSupport.collectContentAssistPages(group, caPages);
                    // Реальные CA платформы: PlatformDocTreeNodeId.fromMethod → getHoverPages
                    // (Error от getHoverDocumentationPages(Method) часто без link/platformId)
                    if (caPages.size() == before)
                        FindMissSupport.collectCaPagesFromMethodPlatformId(documentationLocal,
                            method, lang, caPages);
                    if (caPages.size() == before)
                        FindMissSupport.recoverCaPagesFromErrorGroup(documentationLocal, group,
                            lang, caPages);
                    if (caPages.size() == before)
                        FindMissSupport.collectCaFromViewDocumentation(documentationLocal, method,
                            lang, caPages);
                    if (caPages.size() == before)
                    {
                        EList<ParamSet> setsDirect = method.getParamSet();
                        if (setsDirect != null)
                        {
                            for (ParamSet set : setsDirect)
                            {
                                if (set == null)
                                    continue;
                                Object setGroup = Global.invoke(documentationLocal,
                                    "getHoverDocumentationPages", set, lang); //$NON-NLS-1$
                                FindMissSupport.collectContentAssistPages(setGroup, caPages);
                                if (caPages.size() == before)
                                    FindMissSupport.recoverCaPagesFromErrorGroup(
                                        documentationLocal, setGroup, lang, caPages);
                            }
                        }
                    }
                    if (caPages.size() == before)
                        FindMissSupport.collectSyntheticCaPages(documentationLocal, method, lang,
                            caPages);
                    EList<ParamSet> sets = method.getParamSet();
                    if (sets != null)
                    {
                        for (ParamSet set : sets)
                        {
                            if (set == null || set.getParams() == null)
                                continue;
                            int n = set.getParams().size();
                            if (n > paramSize)
                                paramSize = n;
                            int max = set.getMaxParams();
                            if (max > maxParam)
                                maxParam = max;
                            if (n > maxParam)
                                maxParam = n;
                        }
                    }
                }
                if (caPages.isEmpty())
                {
                    return Boolean.FALSE;
                }

                Class<?> infoClass = Class.forName(
                    "com._1c.g5.v8.dt.bsl.ui.contentassist.InvocationParametersHoverHandler$ParameterInfo"); //$NON-NLS-1$
                java.lang.reflect.Constructor<?> ctor = infoClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object info = ctor.newInstance();
                int paramNumber = estimateParamNumberLikeEdt(siteInfo, caret);
                if (paramNumber < 0)
                    paramNumber = 0;
                Global.setFieldForce(info, "pages", caPages); //$NON-NLS-1$
                Global.setFieldForce(info, "paramNumber", Integer.valueOf(paramNumber)); //$NON-NLS-1$
                Global.setFieldForce(info, "paramSize", Integer.valueOf(paramSize)); //$NON-NLS-1$
                Global.setFieldForce(info, "maxParam", Integer.valueOf(maxParam)); //$NON-NLS-1$
                Global.setFieldForce(info, "commaPosition", new ArrayList<Integer>()); //$NON-NLS-1$
                Global.setFieldForce(info, "initialOffset", //$NON-NLS-1$
                    Integer.valueOf(siteInfo.methodAccessEnd));
                Global.setFieldForce(info, "firstAvailablePosition", //$NON-NLS-1$
                    Integer.valueOf(siteInfo.methodAccessEnd));
                Global.setFieldForce(info, "lastAvailablePosition", //$NON-NLS-1$
                    Integer.valueOf(siteInfo.callEnd));

                boolean shown = false;
                if (PARAM_HOVER_HANDLER_CLASS.equals(handlerRef.getClass().getName()))
                {
                    shown = Global.invokeVoid(handlerRef, "showControlInfo", //$NON-NLS-1$
                        viewer, info, Integer.valueOf(0), site);
                    if (shown)
                    {
                        Object control = Global.getField(handlerRef, "infoControl"); //$NON-NLS-1$
                        tryModifyFindMissBrowser(control);
                    }
                }
                if (!shown)
                {
                    shown = showParamHintControlDirect(viewer, documentationLocal,
                        languageProviderLocal, site, caPages, paramNumber);
                }
                return Boolean.valueOf(shown);
            });
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Только Find-miss: e4 {@code lookUpHandler} → реальный handler с полями.
     * Общий {@link #resolveInvocationParametersHoverHandler} не меняем.
     */
    private static Object resolveParamHoverHandlerForMiss()
    {
        try
        {
            Object context = null;
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null)
            {
                context = window.getService(Class.forName(
                    "org.eclipse.e4.core.contexts.IEclipseContext")); //$NON-NLS-1$
            }
            if (context == null && window != null && window.getActivePage() != null)
            {
                org.eclipse.ui.IWorkbenchPart part = window.getActivePage().getActivePart();
                if (part != null && part.getSite() != null)
                {
                    context = part.getSite().getService(Class.forName(
                        "org.eclipse.e4.core.contexts.IEclipseContext")); //$NON-NLS-1$
                }
            }
            if (context != null)
            {
                Class<?> impl = Class.forName(
                    "org.eclipse.e4.core.commands.internal.HandlerServiceImpl"); //$NON-NLS-1$
                java.lang.reflect.Method lookUp = impl.getMethod("lookUpHandler", //$NON-NLS-1$
                    Class.forName("org.eclipse.e4.core.contexts.IEclipseContext"), //$NON-NLS-1$
                    String.class);
                Object h = lookUp.invoke(null, context, INVOCATION_PARAMETERS_HOVER_COMMAND);
                if (h instanceof org.eclipse.core.commands.IHandler)
                    return unwrapParamHoverHandler(h);
                java.lang.reflect.Method get =
                    context.getClass().getMethod("get", String.class); //$NON-NLS-1$
                Object direct = get.invoke(context,
                    "handler::" + INVOCATION_PARAMETERS_HOVER_COMMAND); //$NON-NLS-1$
                if (direct instanceof org.eclipse.core.commands.IHandler)
                    return unwrapParamHoverHandler(direct);
            }
        }
        catch (Exception ignored)
        {
        }
        return unwrapParamHoverHandler(resolveInvocationParametersHoverHandler());
    }

    /** e4/Command часто отдают обёртку — достаём реальный handler (только Find-miss). */
    private static Object unwrapParamHoverHandler(Object handler)
    {
        if (handler == null)
            return null;
        Object cur = handler;
        for (int depth = 0; depth < 8 && cur != null; depth++)
        {
            if (PARAM_HOVER_HANDLER_CLASS.equals(cur.getClass().getName()))
                return cur;
            Object next = Global.getField(cur, "handler"); //$NON-NLS-1$
            if (next == null)
                next = Global.getField(cur, "delegate"); //$NON-NLS-1$
            if (next == null)
                next = Global.getField(cur, "proxiedHandler"); //$NON-NLS-1$
            if (next == null)
                next = Global.getField(cur, "object"); //$NON-NLS-1$
            if (next == null)
                next = Global.getField(cur, "instance"); //$NON-NLS-1$
            if (next == null || next == cur)
                break;
            cur = next;
        }
        for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            java.lang.reflect.Field[] fields;
            try
            {
                fields = c.getDeclaredFields();
            }
            catch (Exception ex)
            {
                break;
            }
            for (java.lang.reflect.Field f : fields)
            {
                try
                {
                    f.setAccessible(true);
                    Object v = f.get(handler);
                    if (v != null && PARAM_HOVER_HANDLER_CLASS.equals(v.getClass().getName()))
                        return v;
                }
                catch (Exception ignored)
                {
                }
            }
        }
        return handler;
    }

    /** Запасной показ без showControlInfo. Esc — Browser/Shell + KeyAdapter на редакторе. */
    private static boolean showParamHintControlDirect(ITextViewer viewer, Object documentation,
        Object languageProvider, org.eclipse.ui.IWorkbenchSite site, List<Object> pages,
        int paramNumber)
    {
        try
        {
            if (viewer == null || documentation == null || pages == null || pages.isEmpty())
                return false;
            Class<?> controlClass = Class.forName(PARAMETERS_HOVER_CLASS);
            Object control = null;
            for (java.lang.reflect.Constructor<?> ctor : controlClass.getDeclaredConstructors())
            {
                if (ctor.getParameterCount() != 4)
                    continue;
                try
                {
                    ctor.setAccessible(true);
                    control = ctor.newInstance(viewer, documentation, languageProvider, site);
                    break;
                }
                catch (Exception ignored)
                {
                }
            }
            if (control == null)
                return false;
            Global.invoke(control, "showPage", pages, //$NON-NLS-1$
                Integer.valueOf(paramNumber), Integer.valueOf(0));
            Object handler = resolveParamHoverHandlerForMiss();
            if (handler != null && PARAM_HOVER_HANDLER_CLASS.equals(handler.getClass().getName()))
                Global.setFieldForce(handler, "infoControl", control); //$NON-NLS-1$
            installDirectParamHintEscClose(viewer, control);
            ensureParamHintCloseButtonOnHover(control);
            tryModifyFindMissBrowser(control);
            return true;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    /**
     * Find-miss only: один immediate tryModify + ProgressListener на этот browser
     * (без Display.timerExec — иначе гонка с основным режимом).
     */
    private static void tryModifyFindMissBrowser(Object parametersHover)
    {
        Browser browser = findFindMissBrowser(parametersHover);
        if (browser == null || browser.isDisposed())
            return;
        if (browser.getData(FIND_MISS_COMFORT_PROGRESS) == null)
        {
            browser.setData(FIND_MISS_COMFORT_PROGRESS, Boolean.TRUE);
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
                }
            });
        }
        tryModifyBrowserHtml(browser);
    }

    private static Browser findFindMissBrowser(Object parametersHover)
    {
        if (parametersHover == null)
            return null;
        try
        {
            Object b = Global.invoke(parametersHover, "getBrowser"); //$NON-NLS-1$
            if (b instanceof Browser br)
                return br;
            Object control = Global.invoke(parametersHover, "getControl"); //$NON-NLS-1$
            Object fb = Global.getField(control, "fBrowser"); //$NON-NLS-1$
            if (fb instanceof Browser br2)
                return br2;
            Object shell = Global.invoke(parametersHover, "getShell"); //$NON-NLS-1$
            if (shell instanceof Shell s)
                return IrBslHoverHtml.findControlBrowser(s);
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static void installDirectParamHintEscClose(ITextViewer viewer, Object control)
    {
        if (viewer == null || control == null)
            return;
        StyledText textWidget = viewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;
        AtomicBoolean closed = new AtomicBoolean();
        Runnable disposeHint = () ->
        {
            if (!closed.compareAndSet(false, true))
                return;
            try
            {
                Global.invoke(control, "dispose"); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
            }
            try
            {
                Object handler = resolveParamHoverHandlerForMiss();
                if (handler != null)
                {
                    Object current = Global.getField(handler, "infoControl"); //$NON-NLS-1$
                    if (current == control)
                        Global.setFieldForce(handler, "infoControl", null); //$NON-NLS-1$
                }
            }
            catch (Exception ignored)
            {
            }
        };
        KeyAdapter editorEsc = new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.keyCode != SWT.ESC)
                    return;
                disposeHint.run();
                e.doit = false;
            }
        };
        // Фокус обычно в Browser popup — KeyAdapter на редакторе не получает Esc.
        Listener popupEsc = (Event e) ->
        {
            if (e.type == SWT.KeyDown && e.keyCode == SWT.ESC)
            {
                disposeHint.run();
                e.doit = false;
            }
            else if (e.type == SWT.Traverse && e.detail == SWT.TRAVERSE_ESCAPE)
            {
                disposeHint.run();
                e.detail = SWT.TRAVERSE_NONE;
                e.doit = false;
            }
        };
        textWidget.addKeyListener(editorEsc);
        Browser browser = findFindMissBrowser(control);
        Shell shell = null;
        if (browser != null && !browser.isDisposed())
        {
            browser.addListener(SWT.KeyDown, popupEsc);
            browser.addListener(SWT.Traverse, popupEsc);
            shell = browser.getShell();
        }
        if (shell == null)
        {
            Object sh = Global.invoke(control, "getShell"); //$NON-NLS-1$
            if (sh instanceof Shell s)
                shell = s;
        }
        if (shell != null && !shell.isDisposed())
        {
            shell.addListener(SWT.KeyDown, popupEsc);
            shell.addListener(SWT.Traverse, popupEsc);
        }
        final Shell shellRef = shell;
        final Browser browserRef = browser;
        DisposeListener cleanup = e ->
        {
            closed.set(true);
            if (!textWidget.isDisposed())
                textWidget.removeKeyListener(editorEsc);
            if (browserRef != null && !browserRef.isDisposed())
            {
                browserRef.removeListener(SWT.KeyDown, popupEsc);
                browserRef.removeListener(SWT.Traverse, popupEsc);
            }
            if (shellRef != null && !shellRef.isDisposed())
            {
                shellRef.removeListener(SWT.KeyDown, popupEsc);
                shellRef.removeListener(SWT.Traverse, popupEsc);
            }
        };
        if (!Global.invokeVoid(control, "addDisposeListener", cleanup)) //$NON-NLS-1$
        {
            textWidget.removeKeyListener(editorEsc);
            if (browserRef != null && !browserRef.isDisposed())
            {
                browserRef.removeListener(SWT.KeyDown, popupEsc);
                browserRef.removeListener(SWT.Traverse, popupEsc);
            }
            if (shellRef != null && !shellRef.isDisposed())
            {
                shellRef.removeListener(SWT.KeyDown, popupEsc);
                shellRef.removeListener(SWT.Traverse, popupEsc);
            }
        }
    }

    private static Invocation findInvocationNear(EObject resolved)
    {
        for (EObject cur = resolved; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof Invocation invocation)
                return invocation;
        }
        return null;
    }

    private static int estimateParamNumberLikeEdt(CallSiteInfo site, int caret)
    {
        if (site == null || caret < site.methodAccessEnd || caret >= site.callEnd)
            return -1;
        return 0;
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
                return site;
            }
            if (cur instanceof OperatorStyleCreator ctor)
            {
                CallSiteInfo site = callSiteForOperatorCtor(ctor);
                if (site != null)
                return site;
            }
        }
        if (obj instanceof Invocation invocation)
        {
            CallSiteInfo site = callSiteForInvocation(invocation);
            if (site != null)
            return site;
        }
        if (obj instanceof FeatureAccess)
        {
            EObject parent = obj.eContainer();
            if (parent instanceof Invocation invocation)
            {
                CallSiteInfo site = callSiteForInvocation(invocation);
                if (site != null)
                return site;
            }
        }
        CallSiteInfo fromNode = findCallSiteFromNodeModel(resource, caret);
        if (fromNode != null)
            return fromNode;
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

    /**
     * Кнопка закрытия на нижней ToolBarManager popup (иконка close_view Eclipse).
     * Идемпотентно: маркер на {@link ToolBar}.
     */
    private static void ensureParamHintCloseButton(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return;
        Object hover = findParametersHover(browser);
        if (hover != null)
            ensureParamHintCloseButtonOnHover(hover);
    }

    private static void ensureParamHintCloseButtonOnHover(Object parametersHover)
    {
        if (parametersHover == null)
            return;
        if (!PARAMETERS_HOVER_CLASS.equals(parametersHover.getClass().getName()))
            return;
        try
        {
            Object infoControl = Global.invoke(parametersHover, "getControl"); //$NON-NLS-1$
            if (infoControl == null)
                return;
            Object tbmObj = Global.getField(infoControl, "fToolBarManager"); //$NON-NLS-1$
            if (!(tbmObj instanceof ToolBarManager tbm))
                return;
            ToolBar toolBar = tbm.getControl();
            if (toolBar == null || toolBar.isDisposed())
                return;
            if (Boolean.TRUE.equals(toolBar.getData(CLOSE_TOOLBAR_MARK)))
                return;
            toolBar.setData(CLOSE_TOOLBAR_MARK, Boolean.TRUE);

            Action close = new Action()
            {
                @Override
                public void run()
                {
                    disposeParamHintControl(parametersHover);
                }
            };
            close.setImageDescriptor(closeViewImageDescriptor());
            close.setToolTipText("Закрыть" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            tbm.add(new Separator());
            tbm.add(close);
            tbm.update(true);
        }
        catch (Exception ignored)
        {
        }
    }

    private static ImageDescriptor closeViewImageDescriptor()
    {
        try
        {
            Bundle bundle = Platform.getBundle("org.eclipse.ui"); //$NON-NLS-1$
            if (bundle != null)
            {
                URL url = FileLocator.find(bundle,
                    new Path("icons/full/elcl16/close_view.png"), null); //$NON-NLS-1$
                if (url != null)
                    return ImageDescriptor.createFromURL(url);
            }
        }
        catch (Exception ignored)
        {
        }
        return PlatformUI.getWorkbench().getSharedImages()
            .getImageDescriptor(ISharedImages.IMG_TOOL_DELETE);
    }

    private static void disposeParamHintControl(Object parametersHover)
    {
        if (parametersHover == null)
            return;
        try
        {
            Global.invoke(parametersHover, "dispose"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
        }
        try
        {
            Object handler = resolveParamHoverHandlerForMiss();
            if (handler == null)
                handler = resolveInvocationParametersHoverHandler();
            if (handler != null)
            {
                Object current = Global.getField(handler, "infoControl"); //$NON-NLS-1$
                if (current == parametersHover)
                    Global.setFieldForce(handler, "infoControl", null); //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /** Закрыть видимые popup подсказки параметров (preShutdown). */
    public static void dismissAllVisible()
    {
        try
        {
            Object handler = resolveParamHoverHandlerForMiss();
            if (handler != null)
            {
                Object infoControl = Global.getField(handler, "infoControl"); //$NON-NLS-1$
                if (infoControl != null)
                    disposeParamHintControl(infoControl);
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            Object handler = resolveInvocationParametersHoverHandler();
            if (handler != null)
            {
                Object infoControl = Global.getField(handler, "infoControl"); //$NON-NLS-1$
                if (infoControl != null)
                    disposeParamHintControl(infoControl);
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            for (Shell shell : display.getShells())
            {
                if (shell == null || shell.isDisposed() || !shell.isVisible())
                    continue;
                Browser browser = IrBslHoverHtml.findControlBrowser(shell);
                if (browser == null || browser.isDisposed())
                    continue;
                String text = browser.getText();
                if (text != null && text.indexOf(HEADING_CLASS) >= 0)
                    shell.dispose();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /** Модифицировать HTML в браузере (сигнатура + формат строки типа). */
    private static void tryModifyBrowserHtml(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return;

        ensureParamHintCloseButton(browser);

        String html = browser.getText();
        if (html == null || html.isBlank())
            return;
        if (html.indexOf(HEADING_CLASS) < 0)
            return;

        HoverContext ctx = resolveHoverContext(browser);
        if (ctx != null && ctx.pages != null && ctx.pages.size() > 1)
        {
            SigPickResult pick = pickBestSignature(ctx);
            String fp = signaturePickFingerprint(ctx);
            boolean browserFirst = !Boolean.TRUE.equals(browser.getData(SIG_PICK_DONE_MARK));
            boolean onOpen = sigPickOnOpenPending.get() || browserFirst;
            boolean strongChanged = pick.score >= SIG_PICK_STRONG_SCORE
                && !fp.equals(sigPickLastFingerprint);
            if (onOpen || strongChanged)
            {
                sigPickOnOpenPending.set(false);
                browser.setData(SIG_PICK_DONE_MARK, Boolean.TRUE);
                sigPickLastFingerprint = fp;
                if (pick.index >= 0 && pick.index != ctx.pageIndex)
                {
                    boolean shown = Global.invokeVoid(ctx.parametersHover, "showPage", //$NON-NLS-1$
                        ctx.pages, Integer.valueOf(pick.index), Integer.valueOf(ctx.paramIndex));
                    if (shown)
                        return;
                }
            }
        }
        else
            sigPickOnOpenPending.set(false);

        String modified = modifyHtml(html, ctx);
        if (modified == null || modified.equals(html))
        {
            return;
        }

        browser.setText(modified);
        scheduleScrollParamNameIntoView(browser);

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
        String withParen;
        if (rebuilt == null)
            withParen = html.substring(0, firstParen) + "<br>(" + html.substring(firstParen + 1); //$NON-NLS-1$
        else
            withParen = html.substring(0, contentStart) + rebuilt + "<br>(" //$NON-NLS-1$
                + html.substring(firstParen + 1);
        String withOptional = rewriteHeadingOptionalParams(withParen, ctx);
        return withOptional != null ? withOptional : withParen;
    }

    /**
     * Необязательные параметры в сигнатуре: {@code (Колонки, Строки?)} вместо
     * штатных {@code [Строки]}.
     */
    private static String rewriteHeadingOptionalParams(String html, HoverContext ctx)
    {
        if (html == null || ctx == null || ctx.pages == null || ctx.pages.isEmpty()
            || ctx.pageIndex < 0 || ctx.pageIndex >= ctx.pages.size())
            return null;
        int open = html.indexOf("<br>("); //$NON-NLS-1$
        if (open < 0)
            return null;
        open += "<br>".length(); //$NON-NLS-1$
        // open указывает на '('
        int close = html.indexOf(')', open + 1);
        if (close < 0)
            return null;
        Object page = ctx.pages.get(ctx.pageIndex);
        Object paramsObj = Global.getField(page, "params"); //$NON-NLS-1$
        if (!(paramsObj instanceof List<?> params) || params.isEmpty())
            return null;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < params.size(); i++)
        {
            Object paramContent = params.get(i);
            if (paramContent == null)
                continue;
            String name = asString(Global.invoke(paramContent, "getName")); //$NON-NLS-1$
            if (name == null || name.isBlank())
                continue;
            if (list.length() > 0)
                list.append(", "); //$NON-NLS-1$
            boolean current = i == ctx.paramIndex;
            if (current)
                list.append("<b>"); //$NON-NLS-1$
            list.append(escapeHtml(name.trim()));
            if (isParamOptional(paramContent))
                list.append('?');
            if (current)
                list.append("</b>"); //$NON-NLS-1$
        }
        if (list.length() == 0)
            return null;
        return html.substring(0, open + 1) + list + html.substring(close);
    }

    private static boolean isParamOptional(Object paramContent)
    {
        if (paramContent == null)
            return false;
        Object required = Global.invoke(paramContent, "isRequired"); //$NON-NLS-1$
        if (required instanceof Boolean req)
            return !req.booleanValue();
        Object optional = Global.invoke(paramContent, "getOptional"); //$NON-NLS-1$
        if (optional instanceof Boolean opt && opt.booleanValue())
            return true;
        String def = asString(Global.invoke(paramContent, "getDefaultDescription")); //$NON-NLS-1$
        return def != null && !def.isBlank();
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
                    picked = moduleOwner;
                }
            }
            if (picked == null)
            {
                fromTitle = ownerFromExternalTitle(viewPage);
                if (isHumanOwnerName(fromTitle))
                {
                    picked = fromTitle;
                }
            }
            if (picked == null)
            {
                Object typeRef = Global.getField(viewPage, "typeReference"); //$NON-NLS-1$
                typeName = shortStringIfHuman(typeRef);
                if (typeName != null)
                {
                    picked = typeName;
                }
            }
            if (picked == null)
            {
                Object container = Global.invoke(viewPage, "getContainer"); //$NON-NLS-1$
                containerName = shortStringIfHuman(container);
                if (containerName != null)
                {
                    picked = containerName;
                }
            }
        }
        if (picked == null)
        {
            fallback = resolveMethodOwnerName(ctx);
            if (fallback != null && !fallback.isBlank())
            {
                picked = fallback;
            }
        }
        return picked;
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
                String html = formatValueContentTypesHtml(returned,
                    MAX_HEADING_RETURN_TYPE_ELEMENTS);
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
                int count = 0;
                boolean truncated = false;
                for (TypeItem type : types)
                {
                    String name = formatTypeItem(type);
                    if (name == null || name.isEmpty())
                        continue;
                    if (count >= MAX_HEADING_RETURN_TYPE_ELEMENTS)
                    {
                        truncated = true;
                        break;
                    }
                    if (sb.length() > 0)
                        sb.append(','); //$NON-NLS-1$
                    sb.append(escapeHtml(name));
                    count++;
                }
                if (truncated)
                    sb.append("..."); //$NON-NLS-1$
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
        return escapeHtml(limitCommaSeparatedPlain(plain, MAX_HEADING_RETURN_TYPE_ELEMENTS));
    }

    /**
     * Оставляет первые {@code maxElements} фрагментов через запятую; при обрезке — "...".
     */
    private static String limitCommaSeparatedPlain(String plain, int maxElements)
    {
        if (plain == null || plain.isBlank() || maxElements <= 0)
            return plain;
        int count = 0;
        int cut = -1;
        for (int i = 0; i < plain.length(); i++)
        {
            if (plain.charAt(i) != ',')
                continue;
            count++;
            if (count == maxElements)
            {
                cut = i;
                break;
            }
        }
        if (cut < 0)
            return plain;
        return plain.substring(0, cut) + "..."; //$NON-NLS-1$
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
        // База — только типы (без «Тип:» / «Типы:»), со ссылками если есть
        String typeBase = stripTypeLabel(stripComfortMeta(oldTypeInner));

        String description = null;
        String defaultDescription = null;
        Boolean isOut = null;
        String paramName = null;
        Object paramContent = null;

        if (ctx != null && ctx.pages != null && !ctx.pages.isEmpty()
            && ctx.pageIndex >= 0 && ctx.pageIndex < ctx.pages.size()
            && ctx.paramIndex >= 0)
        {
            Object page = ctx.pages.get(ctx.pageIndex);
            paramContent = Global.invoke(page, "getParameter", //$NON-NLS-1$
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
                        typeBase = linkedTypes;
                    else if (stripHtml(typeBase).isEmpty())
                    {
                        String fallbackTypes = formatParamContentTypes(paramContent);
                        if (fallbackTypes != null && !fallbackTypes.isEmpty())
                            typeBase = escapeHtml(fallbackTypes);
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
        boolean hasEnrichment = !stripHtml(typeBase).isEmpty()
            || isOut != null
            || (defaultDescription != null && !defaultDescription.isBlank())
            || (description != null && !description.isBlank());
        if (!hasEnrichment)
            return null;

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
        return isOut.booleanValue() ? "Вых. - " : "Вх. - "; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String buildMetaSuffix(String defaultDescription, String description)
    {
        StringBuilder sb = new StringBuilder();
        if (defaultDescription != null && !defaultDescription.isBlank())
        {
            sb.append(" [="); //$NON-NLS-1$
            sb.append(escapeHtml(formatDefaultValue(defaultDescription)));
            sb.append(']');
        }
        if (description != null && !description.isBlank())
        {
            sb.append(" - "); //$NON-NLS-1$
            sb.append(escapeHtml(description));
        }
        return sb.toString();
    }

    /** Штатное «Пустая строка.» → {@code ""}; хвостовая «.» у значения убирается. */
    private static String formatDefaultValue(String defaultDescription)
    {
        String plain = stripHtml(defaultDescription).trim();
        if (plain.equalsIgnoreCase("Пустая строка.") //$NON-NLS-1$
            || plain.equalsIgnoreCase("Пустая строка") //$NON-NLS-1$
            || plain.equalsIgnoreCase("Empty string.") //$NON-NLS-1$
            || plain.equalsIgnoreCase("Empty string")) //$NON-NLS-1$
            return "\"\""; //$NON-NLS-1$
        while (plain.endsWith(".")) //$NON-NLS-1$
            plain = plain.substring(0, plain.length() - 1).trim();
        return plain;
    }

    /** Убирает подпись «Тип:» / «Типы:» / Type:, оставляя только перечень типов. */
    private static String stripTypeLabel(String typeInnerHtml)
    {
        if (typeInnerHtml == null || typeInnerHtml.isEmpty())
            return ""; //$NON-NLS-1$
        String s = typeInnerHtml.trim();
        String plain = stripHtml(s);
        if (plain == null)
            return s;
        String[] labels = {
            "Типы:", "Тип:", "Types:", "Type:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        for (String label : labels)
        {
            if (plain.regionMatches(true, 0, label, 0, label.length()))
            {
                int colon = s.indexOf(':');
                if (colon >= 0)
                    return s.substring(colon + 1).trim();
            }
        }
        return s;
    }

    /** Срезает ранее дописанный Comfort-мета (префикс Вх/Вых и суффикс), сохраняя типы и ссылки. */
    private static String stripComfortMeta(String typeInnerHtml)
    {
        if (typeInnerHtml == null || typeInnerHtml.isEmpty())
            return ""; //$NON-NLS-1$
        String s = typeInnerHtml;
        if (s.startsWith("Вых. - ")) //$NON-NLS-1$
            s = s.substring("Вых. - ".length()); //$NON-NLS-1$
        else if (s.startsWith("Вх. - ")) //$NON-NLS-1$
            s = s.substring("Вх. - ".length()); //$NON-NLS-1$
        else if (s.startsWith("[Вых]")) //$NON-NLS-1$
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
        idx = s.indexOf("[="); //$NON-NLS-1$
        if (idx >= 0 && idx < cut)
            cut = idx;
        idx = s.indexOf(" - "); //$NON-NLS-1$
        if (idx >= 0 && idx < cut)
            cut = idx;
        return s.substring(0, cut);
    }

    /** HTML фрагмент типов (без подписи «Тип:»), со ссылками. */
    private static String extractTypesHtml(String typeInnerHtml)
    {
        if (typeInnerHtml == null || typeInnerHtml.isEmpty())
            return ""; //$NON-NLS-1$
        return stripTypeLabel(stripComfortMeta(typeInnerHtml)).trim();
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
        return formatValueContentTypesHtml(value, Integer.MAX_VALUE);
    }

    /**
     * @param maxElements лимит элементов через запятую; при превышении хвост заменяется на "..."
     */
    private static String formatValueContentTypesHtml(Object value, int maxElements)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        Object groups = Global.invoke(value, "getGroups"); //$NON-NLS-1$
        if (!(groups instanceof List<?> groupList))
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        int count = 0;
        boolean truncated = false;
        outer:
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
                if (count >= maxElements)
                {
                    truncated = true;
                    break outer;
                }
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
                count++;
            }
        }
        if (truncated)
            sb.append("..."); //$NON-NLS-1$
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
        Object parametersHover = findParametersHover(browser);
        if (parametersHover == null)
        {
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
            return null;
        }

        Object pagesObj = Global.getField(input, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages) || pages.isEmpty())
        {
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
        return ctx;
    }

    private static Object findParametersHover(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return null;

        Object fromBrowser = findParametersHoverFromBrowserListeners(browser);
        if (fromBrowser != null)
            return fromBrowser;

        Object fromHandler = findParametersHoverFromHandler();
        if (fromHandler != null)
        {
            if (browserMatches(fromHandler, browser))
            {
                return fromHandler;
            }
        }

        Object fromLinked = findParametersHoverFromLinkedMode(browser);
        if (fromLinked != null)
            return fromLinked;

        return null;
    }

    /**
     * Штатный {@code ParametersHoverInfoControl} вешает на Browser dispose/key listeners
     * (inner-классы с {@code this$0}). По ним достаём владельца без handler/LinkedMode.
     */
    private static Object findParametersHoverFromBrowserListeners(Browser browser)
    {
        try
        {
            int[] events = { SWT.Dispose, SWT.KeyDown, SWT.Traverse, SWT.FocusIn, SWT.FocusOut };
            for (int eventType : events)
            {
                org.eclipse.swt.widgets.Listener[] listeners = browser.getListeners(eventType);
                if (listeners == null || listeners.length == 0)
                    continue;
                for (org.eclipse.swt.widgets.Listener listener : listeners)
                {
                    Object hover = extractParametersHoverFromListener(listener);
                    if (hover != null && browserMatches(hover, browser))
                    {
                        return hover;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
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

    private static Object findParametersHoverFromHandler()
    {
        try
        {
            org.eclipse.core.commands.IHandler handler = resolveInvocationParametersHoverHandler();
            if (handler == null)
            {
                return null;
            }
            Object infoControl = Global.getField(handler, "infoControl"); //$NON-NLS-1$
            if (infoControl == null)
            {
                return null;
            }
            if (!PARAMETERS_HOVER_CLASS.equals(infoControl.getClass().getName()))
            {
                return null;
            }
            return infoControl;
        }
        catch (Exception ignored)
        {
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

    private static Object findParametersHoverFromLinkedMode(Browser browser)
    {
        try
        {
            ActiveEditor active = resolveActiveBslEditor();
            if (active == null || active.document == null)
            {
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
                return null;
            }
            Object listeners = Global.getField(model, "fListeners"); //$NON-NLS-1$
            Iterable<?> iterable = asIterable(listeners);
            if (iterable == null)
            {
                return null;
            }
            for (Object listener : iterable)
            {
                if (listener == null)
                    continue;
                String cls = listener.getClass().getName();
                if (!BSL_SELECTION_LISTENER_CLASS.equals(cls))
                    continue;
                Object infoControl = Global.getField(listener, "infoControl"); //$NON-NLS-1$
                if (infoControl == null)
                    continue;
                if (browserMatches(infoControl, browser))
                    return infoControl;
            }
        }
        catch (Exception ignored)
        {
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
            ctx.actualArgTypeNames = snap.actualArgTypeNames;
            ctx.method = snap.method;
        }
        catch (Exception ignored)
        {
        }
    }

    private static InvocationSnapshot resolveInvocationSnapshot(XtextResource resource,
        int caret, int paramIndex)
    {
        EObject invocationLike = findInvocationLikeAt(resource, caret);
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
        Set<String> typeNames = new LinkedHashSet<>();
        boolean multiSig = callSiteHasMultipleSignatures(resource, invocationLike);
        if (multiSig && params != null && !params.isEmpty())
        {
            Expression firstArg = params.get(0);
            if (firstArg != null)
            {
                // новый ФиксированнаяСтруктура() — тип создаваемого объекта, не getTypes() выражения.
                if (firstArg instanceof OperatorStyleCreator created)
                {
                    Type createdType = created.getType();
                    if (createdType != null)
                    {
                        typeNames.addAll(typeItemNames(Collections.singletonList(createdType)));
                        if (snap.actualArgTypes == null || snap.actualArgTypes.isEmpty())
                            snap.actualArgTypes = Collections.singletonList(createdType);
                    }
                }
                forceComputeExpressionTypes(resource, firstArg, invocationLike);
                if (firstArg.getTypes() != null && !firstArg.getTypes().isEmpty())
                {
                    snap.actualArgTypes = new ArrayList<>(firstArg.getTypes());
                    typeNames.addAll(typeItemNames(snap.actualArgTypes));
                }
                typeNames.addAll(inferLiteralTypeNames(firstArg));
            }
        }
        if (snap.actualArgTypes == null)
            snap.actualArgTypes = Collections.emptyList();
        snap.actualArgTypeNames = typeNames;
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
        return pickBestSignature(ctx).index;
    }

    private static SigPickResult pickBestSignature(HoverContext ctx)
    {
        if (ctx == null || ctx.pages == null || ctx.pages.isEmpty())
            return new SigPickResult(-1, 0);
        if (ctx.pages.size() == 1)
            return new SigPickResult(0, SIG_PICK_STRONG_SCORE);

        int required = Math.max(0, ctx.actualArgCount);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < ctx.pages.size(); i++)
        {
            int paramCount = countPageParams(ctx.pages.get(i));
            if (paramCount >= required)
                candidates.add(Integer.valueOf(i));
        }
        if (candidates.isEmpty())
        {
            int fallback = ctx.pageIndex >= 0 ? ctx.pageIndex : 0;
            return new SigPickResult(fallback, 0);
        }

        List<TypeItem> actualTypes = ctx.actualArgTypes;
        Set<String> actualNames = ctx.actualArgTypeNames;
        if (actualNames == null || actualNames.isEmpty())
            actualNames = typeItemNames(actualTypes);
        if (actualNames == null || actualNames.isEmpty())
            return new SigPickResult(preferWildcardSignatureIndex(candidates, ctx.pages), 1);

        int bestIdx = -1;
        int bestScore = -1;
        for (Integer candidate : candidates)
        {
            int idx = candidate.intValue();
            Set<String> paramNames = resolveParamTypeNames(ctx.pages.get(idx), 0);
            int score = typeNamesMatchScore(actualNames, paramNames);
            if (score > bestScore)
            {
                bestScore = score;
                bestIdx = idx;
            }
        }
        int resultIdx = bestScore > 0 ? bestIdx : preferWildcardSignatureIndex(candidates, ctx.pages);
        int resultScore = bestScore > 0 ? bestScore : 1;
        return new SigPickResult(resultIdx, resultScore);
    }

    private static String signaturePickFingerprint(HoverContext ctx)
    {
        if (ctx == null)
            return ""; //$NON-NLS-1$
        return ctx.actualArgCount + "|" //$NON-NLS-1$
            + String.valueOf(ctx.actualArgTypeNames);
    }

    private static final class SigPickResult
    {
        final int index;
        final int score;

        SigPickResult(int index, int score)
        {
            this.index = index;
            this.score = score;
        }
    }

    /**
     * Без фактического типа: первая сигнатура, у которой 1-й параметр «Произвольный»
     * (или тип в доке пуст); иначе — первый кандидат.
     */
    private static int preferWildcardSignatureIndex(List<Integer> candidates, List<Object> pages)
    {
        if (candidates == null || candidates.isEmpty())
            return 0;
        if (pages == null || pages.isEmpty())
            return candidates.get(0).intValue();
        for (Integer candidate : candidates)
        {
            int idx = candidate.intValue();
            if (idx < 0 || idx >= pages.size())
                continue;
            Set<String> paramNames = resolveParamTypeNames(pages.get(idx), 0);
            if (isWildcardParamTypeNames(paramNames))
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
            return wildcardParamTypeNames();
        String formatted = formatParamContentTypes(paramContent);
        if (formatted == null || formatted.isEmpty())
            return wildcardParamTypeNames();
        Set<String> names = new LinkedHashSet<>();
        for (String part : formatted.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
                addTypeMatchName(names, trimmed);
        }
        if (names.isEmpty())
            return wildcardParamTypeNames();
        return names;
    }

    /** Нет типа у параметра в доке = «Произвольный». */
    private static Set<String> wildcardParamTypeNames()
    {
        Set<String> names = new LinkedHashSet<>();
        addTypeMatchName(names, "Произвольный"); //$NON-NLS-1$
        addTypeMatchName(names, "Arbitrary"); //$NON-NLS-1$
        return names;
    }

    private static Set<String> typeItemNames(List<TypeItem> types)
    {
        Set<String> names = new LinkedHashSet<>();
        if (types == null)
            return names;
        for (TypeItem type : types)
        {
            if (type instanceof DuallyNamedElement dually)
            {
                addTypeMatchName(names, dually.getNameRu());
                addTypeMatchName(names, dually.getName());
            }
            addTypeMatchName(names, formatTypeItem(type));
            if (type != null)
                addTypeMatchName(names, type.getName());
        }
        return names;
    }

    /** Имена типов литерала, если {@code Expression#getTypes()} ещё пуст. */
    private static Set<String> inferLiteralTypeNames(Expression arg)
    {
        Set<String> names = new LinkedHashSet<>();
        if (arg instanceof StringLiteral)
        {
            addTypeMatchName(names, "Строка"); //$NON-NLS-1$
            addTypeMatchName(names, "String"); //$NON-NLS-1$
        }
        else if (arg instanceof NumberLiteral)
        {
            addTypeMatchName(names, "Число"); //$NON-NLS-1$
            addTypeMatchName(names, "Number"); //$NON-NLS-1$
        }
        else if (arg instanceof BooleanLiteral)
        {
            addTypeMatchName(names, "Булево"); //$NON-NLS-1$
            addTypeMatchName(names, "Boolean"); //$NON-NLS-1$
        }
        else if (arg instanceof DateLiteral)
        {
            addTypeMatchName(names, "Дата"); //$NON-NLS-1$
            addTypeMatchName(names, "Date"); //$NON-NLS-1$
        }
        else if (arg instanceof UndefinedLiteral)
        {
            addTypeMatchName(names, "Неопределено"); //$NON-NLS-1$
            addTypeMatchName(names, "Undefined"); //$NON-NLS-1$
        }
        else if (arg instanceof NullLiteral)
            addTypeMatchName(names, "Null"); //$NON-NLS-1$
        return names;
    }

    private static void addTypeMatchName(Set<String> names, String name)
    {
        if (names == null || name == null || name.isBlank())
            return;
        String n = name.trim().toLowerCase();
        names.add(n);
        switch (n)
        {
            case "строка": //$NON-NLS-1$
                names.add("string"); //$NON-NLS-1$
                break;
            case "string": //$NON-NLS-1$
                names.add("строка"); //$NON-NLS-1$
                break;
            case "число": //$NON-NLS-1$
                names.add("number"); //$NON-NLS-1$
                break;
            case "number": //$NON-NLS-1$
                names.add("число"); //$NON-NLS-1$
                break;
            case "булево": //$NON-NLS-1$
                names.add("boolean"); //$NON-NLS-1$
                break;
            case "boolean": //$NON-NLS-1$
                names.add("булево"); //$NON-NLS-1$
                break;
            case "дата": //$NON-NLS-1$
                names.add("date"); //$NON-NLS-1$
                break;
            case "date": //$NON-NLS-1$
                names.add("дата"); //$NON-NLS-1$
                break;
            case "неопределено": //$NON-NLS-1$
                names.add("undefined"); //$NON-NLS-1$
                break;
            case "undefined": //$NON-NLS-1$
                names.add("неопределено"); //$NON-NLS-1$
                break;
            case "фиксированнаяструктура": //$NON-NLS-1$
                names.add("fixedstructure"); //$NON-NLS-1$
                break;
            case "fixedstructure": //$NON-NLS-1$
                names.add("фиксированнаяструктура"); //$NON-NLS-1$
                break;
            default:
                break;
        }
    }

    /**
     * Сопоставление типов аргумента с типами параметра сигнатуры.
     * Точное имя — 10; «Произвольный»/Any/пустой тип в доке — слабый матч 1.
     */
    private static int typeNamesMatchScore(Set<String> actual, Set<String> expected)
    {
        if (actual == null || actual.isEmpty())
            return 0;
        if (expected == null || expected.isEmpty())
            return 1;
        int score = 0;
        for (String name : actual)
        {
            if (expected.contains(name))
                score += 10;
        }
        if (score == 0 && isWildcardParamTypeNames(expected))
            score = 1;
        return score;
    }

    private static boolean isWildcardParamTypeNames(Set<String> expected)
    {
        if (expected == null || expected.isEmpty())
            return true;
        for (String name : expected)
        {
            if (name == null)
                continue;
            switch (name)
            {
                case "произвольный": //$NON-NLS-1$
                case "arbitrary": //$NON-NLS-1$
                case "any": //$NON-NLS-1$
                case "variant": //$NON-NLS-1$
                case "unknown": //$NON-NLS-1$
                    return true;
                default:
                    break;
            }
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
        Set<String> actualArgTypeNames = Collections.emptySet();
        Method method;
    }

    private static final class InvocationSnapshot
    {
        int actualArgCount;
        List<TypeItem> actualArgTypes = Collections.emptyList();
        Set<String> actualArgTypeNames = Collections.emptySet();
        Method method;
    }

    private static final class FindMissSupport
    {
        private static final String CA_PAGE =
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.IBslDocumentationContentAssistPage"; //$NON-NLS-1$

        private FindMissSupport() {}

        static void collectContentAssistPages(Object group, List<Object> out)
        {
            List<?> pages = pagesOf(group);
            if (pages == null || pages.isEmpty())
                return;
            Class<?> caClass = caPageClass();
            if (caClass == null)
                return;
            for (Object page : pages)
            {
                if (caClass.isInstance(page))
                    out.add(page);
            }
        }

        /**
         * Как EDT: {@code PlatformDocTreeNodeId.fromMethod(version, typeCode, methodCode)}
         * → {@code getPlatformDocumentationPage} → {@code getHoverPages()} (реальные CA).
         * Если у Method/Type {@code getCode()==-1} (часто у resolve из AST) —
         * переразрешаем через {@code getTypeItemByName} / дерево platform.doc по имени.
         */
        static void collectCaPagesFromMethodPlatformId(Object provider, Method method,
            String language, List<Object> out)
        {
            if (provider == null || method == null || out == null)
                return;
            String lang = language != null && !language.isBlank() ? language : "ru"; //$NON-NLS-1$
            int before = out.size();
            try
            {
                Type ownerType = ownerTypeOf(method);
                if (ownerType == null)
                {
                    return;
                }
                ClassLoader cl = provider.getClass().getClassLoader();
                Class<?> idClass = Class.forName(
                    "com._1c.g5.v8.dt.platform.doc.PlatformDocTreeNodeId", true, cl); //$NON-NLS-1$
                java.lang.reflect.Method fromMethod = null;
                for (java.lang.reflect.Method m : idClass.getMethods())
                {
                    if ("fromMethod".equals(m.getName()) && m.getParameterCount() == 3) //$NON-NLS-1$
                    {
                        fromMethod = m;
                        break;
                    }
                }
                if (fromMethod == null)
                {
                    return;
                }
                Class<?> versionType = fromMethod.getParameterTypes()[0];
                Object version = Global.invoke(provider, "getVersion", method); //$NON-NLS-1$
                Object versionArg = coerceVersionArg(version, versionType);
                if (versionArg == null)
                {
                    return;
                }

                int typeCode = ownerType.getCode();
                int methodCode = method.getCode();
                if (typeCode <= 0 || methodCode <= 0)
                {
                    int[] resolved = resolvePlatformCodes(provider, method, ownerType);
                    if (resolved != null)
                    {
                        typeCode = resolved[0];
                        methodCode = resolved[1];
                    }
                }

                if (typeCode > 0 && methodCode > 0)
                {
                    Object nodeId;
                    try
                    {
                        nodeId = fromMethod.invoke(null, versionArg, Integer.valueOf(typeCode),
                            Integer.valueOf(methodCode));
                    }
                    catch (Exception ex)
                    {
                        nodeId = null;
                    }
                    if (nodeId != null
                        && tryCollectCaFromPlatformNode(provider, nodeId, versionArg, lang, out)
                        && out.size() > before)
                    {
                        return;
                    }
                }

                // Запасной путь: дерево platform.doc по именам владельца/метода
                if (collectCaPagesFromDocTreeByName(provider, versionArg, ownerType, method, lang,
                    out) && out.size() > before)
                {
                    return;
                }
            }
            catch (Exception ignored)
            {
            }
        }

        /** @return int[]{typeCode, methodCode} или null */
        private static int[] resolvePlatformCodes(Object provider, Method method, Type ownerType)
        {
            try
            {
                Resource res = method.eResource();
                if (res == null)
                    res = ownerType.eResource();
                String[] typeNames = {
                    ownerType.getNameRu(), ownerType.getName()
                };
                String methodRu = method.getNameRu();
                String methodEn = method.getName();
                for (String typeName : typeNames)
                {
                    if (typeName == null || typeName.isBlank())
                        continue;
                    Object item = Global.invoke(provider, "getTypeItemByName", typeName, res); //$NON-NLS-1$
                    if (!(item instanceof Type platformType) || platformType.getCode() <= 0)
                        continue;
                    Object ctx = platformType.getContextDef();
                    if (ctx == null)
                        continue;
                    Object all = Global.invoke(ctx, "allMethods"); //$NON-NLS-1$
                    if (!(all instanceof List<?> methods))
                        all = Global.invoke(ctx, "getMethods"); //$NON-NLS-1$
                    if (!(all instanceof List<?> methodList))
                        continue;
                    for (Object raw : methodList)
                    {
                        if (!(raw instanceof Method cand) || cand.getCode() <= 0)
                            continue;
                        if (methodNameMatches(cand, methodRu, methodEn))
                            return new int[] { platformType.getCode(), cand.getCode() };
                    }
                }
                // compositeId типа как запасной typeCode
                Object composite = ownerType.getCompositeId();
                if (composite != null)
                {
                    Object idObj = Global.invoke(composite, "getId"); //$NON-NLS-1$
                    if (idObj instanceof Integer id && id.intValue() > 0 && method.getCode() > 0)
                        return new int[] { id.intValue(), method.getCode() };
                }
            }
            catch (Exception ignored)
            {
            }
            return null;
        }

        private static boolean methodNameMatches(Method cand, String ru, String en)
        {
            if (cand == null)
                return false;
            String cRu = cand.getNameRu();
            String cEn = cand.getName();
            if (ru != null && !ru.isBlank() && ru.equalsIgnoreCase(cRu))
                return true;
            if (en != null && !en.isBlank() && en.equalsIgnoreCase(cEn))
                return true;
            if (ru != null && !ru.isBlank() && ru.equalsIgnoreCase(cEn))
                return true;
            if (en != null && !en.isBlank() && en.equalsIgnoreCase(cRu))
                return true;
            return false;
        }

        private static boolean tryCollectCaFromPlatformNode(Object provider, Object nodeId,
            Object versionArg, String lang, List<Object> out)
        {
            try
            {
                Object viewPage = Global.invoke(provider, "getPlatformDocumentationPage", //$NON-NLS-1$
                    nodeId, versionArg, lang);
                if (viewPage == null)
                    return false;
                String viewCls = viewPage.getClass().getName();
                if (viewCls != null && viewCls.indexOf("ErrorBslDocumentationPage") >= 0) //$NON-NLS-1$
                {
                    String link = asString(Global.invoke(viewPage, "getLink")); //$NON-NLS-1$
                    if (link == null || link.isBlank())
                        link = extractLinkFromErrorEntries(viewPage);
                    if (link != null && !link.isBlank())
                    {
                        Object byLink = Global.invoke(provider, "getHoverDocumentationPages", //$NON-NLS-1$
                            link, lang);
                        int before = out.size();
                        collectContentAssistPages(byLink, out);
                        return out.size() > before;
                    }
                    return false;
                }
                int before = out.size();
                collectCaFromViewOrHoverPage(viewPage, out);
                return out.size() > before;
            }
            catch (Exception ex)
            {
                return false;
            }
        }

        private static boolean collectCaPagesFromDocTreeByName(Object provider, Object versionArg,
            Type ownerType, Method method, String lang, List<Object> out)
        {
            try
            {
                Object docProv = Global.getField(provider, "platformDocumentationProvider"); //$NON-NLS-1$
                if (docProv == null)
                    return false;
                Object tree = Global.invoke(docProv, "getTree", versionArg); //$NON-NLS-1$
                if (tree == null)
                    return false;
                Object root = Global.invoke(tree, "getRootNode"); //$NON-NLS-1$
                if (root == null)
                    return false;
                String[] typeNames = { ownerType.getNameRu(), ownerType.getName() };
                String[] methodNames = { method.getNameRu(), method.getName() };
                Object typeNode = findDocTreeNodeByNames(root, typeNames, 0, 12);
                if (typeNode == null)
                    return false;
                Object methodNode = findDocTreeMethodNode(typeNode, methodNames);
                if (methodNode == null)
                    return false;
                Object nodeId = Global.invoke(methodNode, "getId"); //$NON-NLS-1$
                if (nodeId != null
                    && tryCollectCaFromPlatformNode(provider, nodeId, versionArg, lang, out))
                    return true;
                String path = asString(Global.invoke(methodNode, "getPath")); //$NON-NLS-1$
                if (path != null && !path.isBlank())
                {
                    int before = out.size();
                    Object byPath = Global.invoke(provider, "getHoverDocumentationPages", //$NON-NLS-1$
                        path, lang);
                    collectContentAssistPages(byPath, out);
                    return out.size() > before;
                }
            }
            catch (Exception ex)
            {
            }
            return false;
        }

        private static Object findDocTreeMethodNode(Object typeNode, String[] methodNames)
        {
            if (typeNode == null)
                return null;
            Object direct = findDocTreeNodeByNames(typeNode, methodNames, 0, 4);
            if (direct != null)
                return direct;
            Object children = Global.invoke(typeNode, "getChildren"); //$NON-NLS-1$
            if (!(children instanceof Iterable<?> it))
                return null;
            for (Object child : it)
            {
                if (child == null)
                    continue;
                String nameRu = asString(Global.invoke(child, "getName", "ru")); //$NON-NLS-1$ //$NON-NLS-2$
                String nameEn = asString(Global.invoke(child, "getName", "en")); //$NON-NLS-1$ //$NON-NLS-2$
                String elem = asString(Global.invoke(child, "getElementName", Boolean.TRUE)); //$NON-NLS-1$
                if (isMethodsFolderName(nameRu) || isMethodsFolderName(nameEn)
                    || isMethodsFolderName(elem))
                {
                    Object under = findDocTreeNodeByNames(child, methodNames, 0, 3);
                    if (under != null)
                        return under;
                }
            }
            return null;
        }

        private static boolean isMethodsFolderName(String name)
        {
            if (name == null || name.isBlank())
                return false;
            return "methods".equalsIgnoreCase(name) //$NON-NLS-1$
                || "методы".equalsIgnoreCase(name) //$NON-NLS-1$
                || name.toLowerCase().contains("method"); //$NON-NLS-1$
        }

        private static Object findDocTreeNodeByNames(Object node, String[] names, int depth,
            int maxDepth)
        {
            if (node == null || names == null || depth > maxDepth)
                return null;
            String nameRu = asString(Global.invoke(node, "getName", "ru")); //$NON-NLS-1$ //$NON-NLS-2$
            String nameEn = asString(Global.invoke(node, "getName", "en")); //$NON-NLS-1$ //$NON-NLS-2$
            String elem = asString(Global.invoke(node, "getElementName", Boolean.TRUE)); //$NON-NLS-1$
            for (String want : names)
            {
                if (want == null || want.isBlank())
                    continue;
                if (want.equalsIgnoreCase(nameRu) || want.equalsIgnoreCase(nameEn)
                    || want.equalsIgnoreCase(elem))
                    return node;
            }
            Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
            if (!(children instanceof Iterable<?> it))
                return null;
            for (Object child : it)
            {
                Object hit = findDocTreeNodeByNames(child, names, depth + 1, maxDepth);
                if (hit != null)
                    return hit;
            }
            return null;
        }

        /** Version из provider и тип параметра fromMethod могут быть с разных CL. */
        private static Object coerceVersionArg(Object version, Class<?> versionType)
        {
            if (versionType == null)
                return version;
            if (version != null && versionType.isInstance(version))
                return version;
            try
            {
                if (version != null && versionType.isEnum())
                {
                    String name = version instanceof Enum<?> e ? e.name() : String.valueOf(version);
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    Object asEnum = Enum.valueOf((Class) versionType, name);
                    return asEnum;
                }
                java.lang.reflect.Field latest = versionType.getField("LATEST"); //$NON-NLS-1$
                return latest.get(null);
            }
            catch (Exception ignored)
            {
            }
            return version;
        }

        private static String extractLinkFromErrorEntries(Object errorPage)
        {
            if (errorPage == null)
                return null;
            try
            {
                Object entries = Global.getField(errorPage, "entries"); //$NON-NLS-1$
                if (!(entries instanceof List<?> list))
                    return null;
                for (Object entry : list)
                {
                    if (entry == null)
                        continue;
                    String key = asString(Global.invoke(entry, "getKey")); //$NON-NLS-1$
                    String value = asString(Global.invoke(entry, "getValue")); //$NON-NLS-1$
                    if (value != null && (value.contains(".html") || value.contains("v8help:") //$NON-NLS-1$ //$NON-NLS-2$
                        || value.contains("SyntaxHelper"))) //$NON-NLS-1$
                        return value.trim();
                    if (key != null && (key.contains("link") || key.contains("path") //$NON-NLS-1$ //$NON-NLS-2$
                        || key.contains("page")) && value != null && !value.isBlank()) //$NON-NLS-1$
                        return value.trim();
                }
                String description = asString(Global.getField(errorPage, "description")); //$NON-NLS-1$
                if (description != null && description.contains(".html")) //$NON-NLS-1$
                    return description.trim();
            }
            catch (Exception ignored)
            {
            }
            return null;
        }

        private static Type ownerTypeOf(Method method)
        {
            if (method == null)
                return null;
            for (EObject cur = method.eContainer(); cur != null; cur = cur.eContainer())
            {
                if (cur instanceof Type type)
                    return type;
            }
            return null;
        }

        /**
         * EDT для части платформенных Method отдаёт {@code ErrorBslDocumentationPage}
         * вместо CA. Достаём реальные CA: link → platformId/view → hoverPages.
         * Исключения глотаем — иначе NPE в resolveHover валит весь fallback.
         */
        static void recoverCaPagesFromErrorGroup(Object provider, Object group, String language,
            List<Object> out)
        {
            if (provider == null || out == null)
                return;
            List<?> pages = pagesOf(group);
            if (pages == null || pages.isEmpty())
                return;
            String lang = language != null && !language.isBlank() ? language : "ru"; //$NON-NLS-1$
            int before = out.size();
            for (Object page : pages)
            {
                if (page == null)
                    continue;
                String cls = page.getClass().getName();
                if (cls == null || cls.indexOf("ErrorBslDocumentationPage") < 0) //$NON-NLS-1$
                    continue;
                try
                {
                    String link = asString(Global.invoke(page, "getLink")); //$NON-NLS-1$
                if (link == null || link.isBlank())
                    link = extractLinkFromErrorEntries(page);
                if (link != null && !link.isBlank())
                {
                    try
                    {
                        Object byLink = Global.invoke(provider, "getHoverDocumentationPages", //$NON-NLS-1$
                            link, lang);
                        collectContentAssistPages(byLink, out);
                        if (out.size() > before)
                        {
                            return;
                        }
                    }
                    catch (Exception ex)
                    {
                    }
                    // resolveHover внутри EDT делает link.lastIndexOf — только при non-null link
                    try
                    {
                        // временно подставим link в Error, если поле пустое
                        if (asString(Global.invoke(page, "getLink")) == null //$NON-NLS-1$
                            || asString(Global.invoke(page, "getLink")).isBlank()) //$NON-NLS-1$
                            Global.setFieldForce(page, "link", link); //$NON-NLS-1$
                        Object resolved = Global.invoke(provider,
                            "resolveHoverDocumentationPages", page, lang); //$NON-NLS-1$
                        collectContentAssistPages(resolved, out);
                        if (out.size() > before)
                        {
                            return;
                        }
                    }
                    catch (Exception ex)
                    {
                    }
                }
                else
                {
                }

                // 2) platformId → MethodLike view → getHoverPages() = реальные CA
                Object platformId = Global.invoke(page, "getPlatformId"); //$NON-NLS-1$
                if (platformId == null)
                    platformId = Global.getField(page, "nodeId"); //$NON-NLS-1$
                Object version = Global.invoke(page, "getVersion"); //$NON-NLS-1$
                if (platformId != null)
                {
                    try
                    {
                        Object viewPage = Global.invoke(provider, "getPlatformDocumentationPage", //$NON-NLS-1$
                            platformId, version, lang);
                        collectCaFromViewOrHoverPage(viewPage, out);
                        if (out.size() > before)
                        {
                            return;
                        }
                    }
                    catch (Exception ex)
                    {
                    }
                }
                }
                catch (Exception ex)
                {
                }
            }
        }

        /** View-страницы Method (не Error) → CA через {@code getHoverPages()}. */
        static void collectCaFromViewDocumentation(Object provider, Method method, String language,
            List<Object> out)
        {
            if (provider == null || method == null || out == null)
                return;
            String lang = language != null && !language.isBlank() ? language : "ru"; //$NON-NLS-1$
            int before = out.size();
            try
            {
                Object viewGroup = Global.invoke(provider, "getViewDocumentationPages", //$NON-NLS-1$
                    method, lang);
                List<?> pages = pagesOf(viewGroup);
                if (pages == null || pages.isEmpty())
                    return;
                for (Object page : pages)
                {
                    if (page == null)
                        continue;
                    String cls = page.getClass().getName();
                    if (cls != null && cls.indexOf("ErrorBslDocumentationPage") >= 0) //$NON-NLS-1$
                        continue;
                    collectCaFromViewOrHoverPage(page, out);
                }
                if (out.size() > before)
                {
                }
            }
            catch (Exception ex)
            {
            }
        }

        private static void collectCaFromViewOrHoverPage(Object page, List<Object> out)
        {
            if (page == null || out == null)
                return;
            Class<?> caClass = caPageClass();
            if (caClass != null && caClass.isInstance(page))
            {
                out.add(page);
                return;
            }
            Object hoverPages = Global.invoke(page, "getHoverPages"); //$NON-NLS-1$
            if (!(hoverPages instanceof List<?> list) || list.isEmpty())
                return;
            if (caClass == null)
                return;
            for (Object hover : list)
            {
                if (caClass.isInstance(hover))
                    out.add(hover);
            }
        }

        static void collectSyntheticCaPages(Object provider, Method method, String language,
            List<Object> out)
        {
            if (method == null || out == null)
                return;
            EList<ParamSet> sets = method.getParamSet();
            if (sets == null || sets.isEmpty())
                return;
            try
            {
                Class<?> localeClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationLocale"); //$NON-NLS-1$
                Class<?> viewPageClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.MethodLikeBslDocumentationPage"); //$NON-NLS-1$
                Class<?> setPageClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.MethodLikeParamSetBslDocumentationPage"); //$NON-NLS-1$
                Class<?> paramContentClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.ParamContent"); //$NON-NLS-1$
                Class<?> valueContentClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.ValueContent"); //$NON-NLS-1$
                Class<?> versionClass = Class.forName(
                    "com._1c.g5.v8.dt.platform.version.Version"); //$NON-NLS-1$
                Class<?> typeGroupClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.TypeGroupContent"); //$NON-NLS-1$
                Class<?> footnoteClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.Footnote"); //$NON-NLS-1$
                Class<?> pageRefClass = Class.forName(
                    "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.IPageReference"); //$NON-NLS-1$

                String lang = language != null && !language.isBlank() ? language : "ru"; //$NON-NLS-1$
                boolean ru = "ru".equalsIgnoreCase(lang); //$NON-NLS-1$
                Object locale = null;
                if (provider != null)
                {
                    Object localeProvider = Global.getField(provider, "localeProvider"); //$NON-NLS-1$
                    if (localeProvider != null)
                        locale = Global.invoke(localeProvider, "getLocale", lang); //$NON-NLS-1$
                }
                if (locale == null)
                {
                    locale = localeClass.getConstructor(String.class, String.class, boolean.class)
                        .newInstance(lang, ru ? "Русский" : "English", Boolean.valueOf(ru)); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Object version = provider != null
                    ? Global.invoke(provider, "getVersion", method) //$NON-NLS-1$
                    : null;
                if (version == null)
                    version = versionClass.getField("LATEST").get(null); //$NON-NLS-1$

                String nameRu = method.getNameRu();
                String nameEn = method.getName();
                String display = ru
                    ? (nameRu != null && !nameRu.isEmpty() ? nameRu : nameEn)
                    : (nameEn != null && !nameEn.isEmpty() ? nameEn : nameRu);
                if (display == null)
                    display = ""; //$NON-NLS-1$

                // kind = ключ LocalizedConstants (function/procedure), не язык
                String kind = method.isRetVal() ? "function" : "procedure"; //$NON-NLS-1$
                Object viewPage = viewPageClass
                    .getConstructor(localeClass, String.class, versionClass, String.class)
                    .newInstance(locale, null, version, kind);
                Global.invoke(viewPage, "setName", //$NON-NLS-1$
                    nameRu != null ? nameRu : display,
                    nameEn != null ? nameEn : display);

                // Владелец для comfort-заголовка (resolveHeadingOwner → container)
                String ownerName = ownerNameFromMethod(method, ru);
                if (ownerName != null && !ownerName.isEmpty())
                {
                    Object ownerRef = newPlainPageReference(pageRefClass, ownerName);
                    if (ownerRef != null)
                        Global.invoke(viewPage, "setContainer", ownerRef); //$NON-NLS-1$
                }

                Resource methodResource = method.eResource();
                Object returnedValue = buildSyntheticValueContent(provider, method.getRetValType(),
                    methodResource, version, ru, valueContentClass, typeGroupClass, footnoteClass,
                    pageRefClass);
                if (returnedValue != null)
                    Global.invoke(viewPage, "setReturnedValue", returnedValue); //$NON-NLS-1$

                int added = 0;
                for (ParamSet set : sets)
                {
                    if (set == null || set.getParams() == null || set.getParams().isEmpty())
                        continue;
                    Object setPage = setPageClass.getConstructor(viewPageClass).newInstance(viewPage);
                    Global.invoke(setPage, "setName", display); //$NON-NLS-1$
                    Global.invoke(setPage, "setCode", Integer.valueOf(set.getCode())); //$NON-NLS-1$

                    List<Object> paramContents = new ArrayList<>();
                    for (Object rawParam : set.getParams())
                    {
                        if (!(rawParam instanceof Parameter parameter))
                            continue;
                        String pRu = parameter.getNameRu();
                        String pEn = parameter.getName();
                        String pName = ru
                            ? (pRu != null && !pRu.isEmpty() ? pRu : pEn)
                            : (pEn != null && !pEn.isEmpty() ? pEn : pRu);
                        if (pName == null)
                            pName = ""; //$NON-NLS-1$

                        Object value = buildSyntheticValueContent(provider, parameter.getType(),
                            methodResource, version, ru, valueContentClass, typeGroupClass,
                            footnoteClass, pageRefClass);
                        if (value == null)
                        {
                            value = valueContentClass
                                .getConstructor(String.class, List.class)
                                .newInstance("", Collections.emptyList()); //$NON-NLS-1$
                        }
                        Object paramContent = paramContentClass
                            .getConstructor(String.class, valueContentClass, String.class,
                                Boolean.class, Boolean.class)
                            .newInstance(pName, value, null, Boolean.FALSE, null);
                        paramContents.add(paramContent);
                    }
                    Global.invoke(setPage, "setParams", paramContents); //$NON-NLS-1$
                    out.add(setPage);
                    added++;
                }
            }
            catch (Exception ex)
            {
                Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : ex;
            }
        }

        private static String ownerNameFromMethod(Method method, boolean ru)
        {
            if (method == null)
                return null;
            for (EObject cur = method.eContainer(); cur != null; cur = cur.eContainer())
            {
                if (cur instanceof TypeItem typeItem)
                {
                    String name = typeItem.getNameRu();
                    if (!ru || name == null || name.isEmpty())
                        name = typeItem.getName();
                    if (name != null && !name.isEmpty())
                        return name;
                }
                if (cur instanceof DuallyNamedElement dually)
                {
                    String name = dually.getNameRu();
                    if (!ru || name == null || name.isEmpty())
                        name = dually.getName();
                    if (name != null && !name.isEmpty())
                        return name;
                }
            }
            return null;
        }

        private static Object buildSyntheticValueContent(Object provider, EList<TypeItem> types,
            Resource resource, Object version, boolean ru, Class<?> valueContentClass,
            Class<?> typeGroupClass, Class<?> footnoteClass, Class<?> pageRefClass)
            throws Exception
        {
            if (types == null || types.isEmpty())
                return null;
            List<Object> refs = new ArrayList<>();
            for (TypeItem typeItem : types)
            {
                if (typeItem == null)
                    continue;
                String typeName = typeItem.getNameRu();
                if (!ru || typeName == null || typeName.isEmpty())
                    typeName = typeItem.getName();
                if (typeName == null || typeName.isEmpty())
                    continue;
                Object ref = null;
                if (provider != null)
                {
                    try
                    {
                        ref = Global.invoke(provider, "convertTypeItemToReference", //$NON-NLS-1$
                            typeItem, resource, Boolean.TRUE, version, Boolean.valueOf(ru));
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                if (ref == null)
                    ref = newPlainPageReference(pageRefClass, typeName);
                if (ref != null)
                    refs.add(ref);
            }
            if (refs.isEmpty())
                return null;
            Object group = typeGroupClass
                .getConstructor(String.class, List.class, footnoteClass)
                .newInstance(null, refs, null);
            return valueContentClass
                .getConstructor(String.class, List.class)
                .newInstance(null, Collections.singletonList(group));
        }

        private static Object newPlainPageReference(Class<?> pageRefClass, String name)
        {
            if (pageRefClass == null || name == null)
                return null;
            final String label = name;
            return java.lang.reflect.Proxy.newProxyInstance(
                pageRefClass.getClassLoader(),
                new Class<?>[] { pageRefClass },
                (proxy, method, args) ->
                {
                    String m = method.getName();
                    if ("toShortString".equals(m) || "toFullString".equals(m) //$NON-NLS-1$ //$NON-NLS-2$
                        || "toString".equals(m)) //$NON-NLS-1$
                        return label;
                    if ("getHref".equals(m)) //$NON-NLS-1$
                        return null;
                    if ("hashCode".equals(m)) //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    if ("equals".equals(m)) //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class)
                        return Boolean.FALSE;
                    if (rt == int.class)
                        return Integer.valueOf(0);
                    return null;
                });
        }

        private static List<?> pagesOf(Object group)
        {
            Object pages = Global.invoke(group, "getPages"); //$NON-NLS-1$
            return pages instanceof List<?> list ? list : null;
        }

        private static Class<?> caPageClass()
        {
            try
            {
                return Class.forName(CA_PAGE);
            }
            catch (Exception ex)
            {
                return null;
            }
        }
    }

    private static final class ActiveEditor
    {
        IDocument document;
        int caret;
    }
}
