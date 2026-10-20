package tormozit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.internal.text.html.HTMLPrinter;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.hover.IEObjectHoverProvider;
import org.eclipse.xtext.ui.editor.hover.html.DefaultEObjectHoverProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Меню метатипа в обеих подсказках; штатный клик по существующей ссылке сохраняется. */
final class MetadataTypeMenu
{
    private static final String FUNCTION = "comfortMetadataTypeAction"; //$NON-NLS-1$
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>"); //$NON-NLS-1$
    private static final Pattern TYPE_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)+"); //$NON-NLS-1$
    private static final Map<String, Request> REQUESTS = new LinkedHashMap<>();
    /** Конец открывающего тега обёртки: сразу за ним — обёрнутая ссылка. */
    private static final String WRAP_TAIL = "onmouseleave=\"comfortHideParamMenu(this)\">"; //$NON-NLS-1$

    private static final class Request
    {
        final TypeItem type;
        final MdObject object;
        final BslXtextEditor source;
        IHyperlink[] links = new IHyperlink[0];

        Request(TypeItem type, MdObject object, BslXtextEditor source)
        {
            this.type = type;
            this.object = object;
            this.source = source;
        }
    }

    private MetadataTypeMenu() {}

    static void install(Display display)
    {
        display.addFilter(SWT.Show, event -> {
            if (!(event.widget instanceof org.eclipse.swt.widgets.Shell shell))
                return;
            installBrowser(IrBslHoverHtml.findControlBrowser(shell));
        });
    }

    /** QL использует обычный Xtext-контрол с выключенным JavaScript, BSL — включает его сам. */
    static IInformationControlCreator queryHoverCreator(DefaultEObjectHoverProvider provider)
    {
        return new QueryHoverCreator(provider);
    }

    private static final class QueryHoverCreator extends DefaultEObjectHoverProvider.HoverControlCreator
    {
        QueryHoverCreator(DefaultEObjectHoverProvider provider)
        {
            // Презентер (закреплённая подсказка и боковое окно списка) тоже
            // создаётся через подготовку, до первого setInput / Browser.setText.
            provider.super(parent -> prepareControl(
                provider.getInformationPresenterControlCreator().createInformationControl(parent)));
        }

        @Override
        public IInformationControl doCreateInformationControl(Shell parent)
        {
            return prepareControl(super.doCreateInformationControl(parent));
        }

    }

    private static IInformationControl prepareControl(IInformationControl control)
    {
        Browser browser = IrBslHoverHtml.findControlBrowser(control);
        if (browser != null)
        {
            browser.setJavascriptEnabled(true);
            installBrowser(browser);
        }
        return control;
    }

    static void installBrowser(Browser browser)
    {
        if (browser == null || browser.isDisposed() || browser.getData(FUNCTION) != null)
            return;
        Display display = browser.getDisplay();
        browser.setData(FUNCTION, new BrowserFunction(browser, FUNCTION)
            {
                @Override
                public Object function(Object[] arguments)
                {
                    if (arguments == null || arguments.length != 2 || !(arguments[0] instanceof String token)
                        || !(arguments[1] instanceof String action))
                        return Boolean.FALSE;
                    Request request;
                    synchronized (REQUESTS)
                    {
                        request = REQUESTS.get(token);
                    }
                    if (request == null)
                        return Boolean.FALSE;
                    if ("menu".equals(action)) //$NON-NLS-1$
                    {
                        try
                        {
                            return menuHtml(request, token);
                        }
                        catch (RuntimeException | LinkageError error)
                        {
                            return ""; //$NON-NLS-1$
                        }
                    }
                    display.asyncExec(() -> {
                        if (browser.isDisposed())
                            return;
                        try
                        {
                            if ("type".equals(action)) //$NON-NLS-1$
                                openTypeDocumentation(request, browser);
                            else if ("object".equals(action)) //$NON-NLS-1$
                            {
                                IHyperlink[] links = BslEditorHyperlinkHook.metadataObjectLinks(request.object, request.source);
                                if (links.length > 0)
                                {
                                    browser.getShell().setVisible(false);
                                    links[links.length - 1].open();
                                }
                            }
                            else if (action.startsWith("open:")) //$NON-NLS-1$
                            {
                                int index = Integer.parseInt(action.substring(5));
                                if (index >= 0 && index < request.links.length)
                                {
                                    browser.getShell().setVisible(false);
                                    request.links[index].open();
                                }
                            }
                        }
                        catch (RuntimeException | LinkageError error)
                        {
                        }
                    });
                    return Boolean.TRUE;
                }
            });
    }

    /** Существующие ссылки BSL: добавляем только действие наведения вокруг исходного anchor. */
    static String decorate(String html, ITextViewer viewer, BslXtextEditor source)
    {
        // Подсказка параметров перестраивает строки поверх уже размеченного HTML:
        // новые ссылки размечаются, уже обёрнутые пропускаются.
        if (html == null || source == null || viewer == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return html;
        String decorated = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
            if (resource == null || resource.getContents().isEmpty())
                return html;
            var services = resource.getResourceServiceProvider();
            var scopes = services.get(IScopeProvider.class);
            var converter = services.get(IQualifiedNameConverter.class);
            if (scopes == null || converter == null)
                return html;
            var scope = scopes.getScope(resource.getContents().get(0), McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            if (scope == null)
                return html;
            var matcher = ANCHOR.matcher(html);
            StringBuilder result = new StringBuilder();
            while (matcher.find())
            {
                String name = matcher.group(1).replaceAll("<[^>]*>", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                var description = TYPE_NAME.matcher(name).matches()
                    ? scope.getSingleElement(converter.toQualifiedName(name)) : null;
                var object = description == null ? null : description.getEObjectOrProxy();
                if (object != null && object.eIsProxy())
                    object = EcoreUtil.resolve(object, resource);
                String replacement = matcher.group();
                if (html.startsWith(WRAP_TAIL, matcher.start() - WRAP_TAIL.length()))
                    object = null;
                if (object instanceof TypeItem type && BslEditorHyperlinkHook.typeProducer(type) != null)
                {
                    replacement = wrap(type, source, replacement);
                }
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        });
        return decorated == null || decorated.equals(html) ? html : style(decorated);
    }

    /** Типы QL ещё не имеют ссылки: клик открывает штатное описание типа EDT. */
    static String link(TypeItem type, String label, BslXtextEditor source)
    {
        String text = HTMLPrinter.convertToHTMLContent(label);
        if (source == null)
            return text;
        String token = register(type, source);
        String anchor = "<a href=\"#\" onclick=\"" + FUNCTION //$NON-NLS-1$
            + "('" + token + "','type');return false;\">" + text + "</a>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return wrap(token, anchor);
    }

    static String objectLink(MdObject object, String label, BslXtextEditor source)
    {
        String text = HTMLPrinter.convertToHTMLContent(label);
        if (object == null || source == null)
            return text;
        String token = register(null, object, source);
        return wrap(token, "<span class=\"comfort-param-link\" onclick=\"" + FUNCTION //$NON-NLS-1$
            + "('" + token + "','object');return false;\">" + text + "</span>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String wrap(TypeItem type, BslXtextEditor source, String anchor)
    {
        return wrap(register(type, source), anchor);
    }

    private static String wrap(String token, String anchor)
    {
        // Как меню ссылок «см.» в ParamHintHtmlModifier: остаётся в том же Browser
        // и не запускает смену hover-контрола из-за фокуса отдельного SWT.Menu.
        return "<span class=\"comfort-meta-type comfort-param-entry\" onmouseenter=\"" //$NON-NLS-1$
            + "comfortTrackParamSelection(event);if(comfortParamMenuBlocked(this))return;" //$NON-NLS-1$
            + "this.lastElementChild.innerHTML=" + FUNCTION + "('" + token + "','menu')||'';" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "if(this.lastElementChild.childElementCount){comfortShowParamMenu(this)}" //$NON-NLS-1$
            + "else{this.lastElementChild.style.display='none'}\" " + WRAP_TAIL //$NON-NLS-1$
            + anchor + "<span class=\"comfort-param-menu\"></span></span>"; //$NON-NLS-1$
    }

    static String style(String html)
    {
        String result = ParamHintHtmlModifier.withLinkStyle(html);
        if (result == null || result.contains("id=\"comfort-meta-menu-style\"")) //$NON-NLS-1$
            return result;
        int head = result.indexOf("</head>"); //$NON-NLS-1$
        if (head < 0)
            return result;
        // В короткой подсказке длинное меню прокручивается, сохраняя все пункты.
        return result.substring(0, head)
            + "<style id=\"comfort-meta-menu-style\">.comfort-meta-type>span.comfort-param-menu" //$NON-NLS-1$
            + "{max-height:calc(100vh - 8px);overflow:auto;}" //$NON-NLS-1$
            + ".comfort-meta-type .comfort-param-menu-item{padding:1px 7px;}</style>" //$NON-NLS-1$
            + result.substring(head);
    }

    private static String register(TypeItem type, BslXtextEditor source)
    {
        return register(type, null, source);
    }

    private static String register(TypeItem type, MdObject object, BslXtextEditor source)
    {
        String token = UUID.randomUUID().toString();
        synchronized (REQUESTS)
        {
            REQUESTS.put(token, new Request(type, object, source));
            while (REQUESTS.size() > 512)
                REQUESTS.remove(REQUESTS.keySet().iterator().next());
        }
        return token;
    }

    private static String menuHtml(Request request, String token)
    {
        request.links = request.type != null
            ? BslEditorHyperlinkHook.metadataTypeLinks(request.type, request.source)
            : BslEditorHyperlinkHook.metadataObjectLinks(request.object, request.source);
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < request.links.length; index++)
        {
            html.append("<span class=\"comfort-param-menu-item\" onclick=\"").append(FUNCTION) //$NON-NLS-1$
                .append("('").append(token).append("','open:").append(index) //$NON-NLS-1$ //$NON-NLS-2$
                .append("');event.stopPropagation();return false;\">") //$NON-NLS-1$
                .append(HTMLPrinter.convertToHTMLContent(request.links[index].getHyperlinkText()))
                .append("</span>"); //$NON-NLS-1$
        }
        return html.toString();
    }

    /** Открытие страницы тем же провайдером и браузером, что у штатного синтакс-помощника. */
    private static void openTypeDocumentation(Request request, Browser browser)
    {
        var source = request.source;
        if (!(source.getDocument() instanceof IXtextDocument document))
            return;
        var services = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(document.getResourceURI());
        var hover = services == null ? null : services.get(IEObjectHoverProvider.class);
        Object provider = Global.getField(hover, "documentationProvider"); //$NON-NLS-1$
        Object pageOrLink = document.readOnly(resource -> {
            if (resource == null || resource.getContents().isEmpty())
                return null;
            Object version = Global.invoke(provider, "getVersion", resource.getContents().get(0)); //$NON-NLS-1$
            // Штатные ссылки типов различают коды объектов и элементов языка.
            // getViewDocumentationPages(Type) этого не делает: код String=100023
            // попадает в getDocByType, где допустимы только коды менее 100000.
            Object reference = Global.invoke(provider, "convertTypeItemToReference", //$NON-NLS-1$
                request.type, resource, true, version, false);
            Object href = Global.invoke(reference, "getHref"); //$NON-NLS-1$
            if (href instanceof String link && !link.isBlank())
                return link;
            // Метатип без платформенной ссылки открывается как страница модели.
            Object group = Global.invoke(provider, "getViewDocumentationPages", request.type, "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            Object pages = Global.invoke(group, "getPages"); //$NON-NLS-1$
            return pages instanceof java.util.List<?> list && !list.isEmpty() ? list.get(0) : null;
        });
        if (pageOrLink == null)
            return;
        try
        {
            ClassLoader loader = hover.getClass().getClassLoader();
            Class<?> utility = Class.forName("com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.SyntaxAssistViewUtil", true, loader); //$NON-NLS-1$
            Object view = Global.invoke(utility, "showOrGetShowedView"); //$NON-NLS-1$
            Object panel = Global.invoke(view, "getDescriptionPanel"); //$NON-NLS-1$
            Object targetBrowser = Global.invoke(panel, "getBrowser"); //$NON-NLS-1$
            Object descriptor = Global.newInstance("com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.description.DocumentationPageDescriptor", //$NON-NLS-1$
                loader, pageOrLink, provider);
            browser.getShell().setVisible(false);
            Global.invokeVoid(targetBrowser, "openPage", descriptor); //$NON-NLS-1$
        }
        catch (ClassNotFoundException error)
        {
        }
    }
}
