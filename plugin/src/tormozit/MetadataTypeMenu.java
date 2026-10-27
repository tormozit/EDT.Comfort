package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.hover.IEObjectHoverProvider;
import org.eclipse.xtext.ui.editor.hover.html.DefaultEObjectHoverProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/** Меню метатипа в обеих подсказках; клик по ссылке — основной переход Ctrl-ссылки (редактор объекта). */
final class MetadataTypeMenu
{
    private static final String FUNCTION = "comfortMetadataTypeAction"; //$NON-NLS-1$
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>"); //$NON-NLS-1$
    private static final Pattern TYPE_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)+"); //$NON-NLS-1$
    private static final String METADATA_OBJECT_PREFIX = "ОбъектМетаданных:"; //$NON-NLS-1$
    /** Ссылка, текст которой начинается с «ОбъектМетаданных: »; группа 1 — всё до приставки. */
    private static final Pattern METADATA_OBJECT_ANCHOR = Pattern.compile(
        "(?i)(<a\\b[^>]*>(?:\\s|<[^>]*>)*)ОбъектМетаданных:(?:\\s|&nbsp;)*"); //$NON-NLS-1$
    /** Цепочка ссылок подряд: между ними только теги, пробелы и разделители типов. */
    private static final Pattern TYPE_ANCHOR_RUN = Pattern.compile(
        "(?is)<a\\b[^>]*>.*?</a>(?:(?:\\s|&nbsp;|[|,]|</?(?:span|font|b|i|wbr)\\b[^>]*>)*<a\\b[^>]*>.*?</a>)*"); //$NON-NLS-1$
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

    /** Разрешение полного имени типа для нижней панели списка; без пересчёта документа. */
    static TypeItem resolveType(ITextViewer viewer, String name)
    {
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        return ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
            if (resource == null || resource.getContents().isEmpty())
                return null;
            var services = resource.getResourceServiceProvider();
            var scopes = services.get(IScopeProvider.class);
            var converter = services.get(IQualifiedNameConverter.class);
            if (scopes == null || converter == null)
                return null;
            var scope = scopes.getScope(resource.getContents().get(0), McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            var description = scope == null ? null : scope.getSingleElement(converter.toQualifiedName(name));
            if (description == null)
            {
                var platform = platformTypes(resource);
                description = platform == null ? null : platform.getEObjectDescription(name);
            }
            var object = description == null ? null : description.getEObjectOrProxy();
            if (object != null && object.eIsProxy())
                object = EcoreUtil.resolve(object, resource);
            return object instanceof TypeItem resolved && !resolved.eIsProxy() ? resolved : null;
        });
    }

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
                            if ("primary".equals(action)) //$NON-NLS-1$
                                openPrimary(request, browser);
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
    static String decorate(String sourceHtml, ITextViewer viewer, BslXtextEditor source)
    {
        String abbreviated = abbreviateMetadataObjectTypes(sourceHtml);
        // Подсказка параметров перестраивает строки поверх уже размеченного HTML:
        // новые ссылки размечаются, уже обёрнутые пропускаются.
        if (sourceHtml == null || source == null || viewer == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return abbreviated;
        String decorated = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
            if (resource == null || resource.getContents().isEmpty())
                return abbreviated;
            var services = resource.getResourceServiceProvider();
            var scopes = services.get(IScopeProvider.class);
            var converter = services.get(IQualifiedNameConverter.class);
            if (scopes == null || converter == null)
                return abbreviated;
            var scope = scopes.getScope(resource.getContents().get(0), McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            if (scope == null)
                return abbreviated;
            // Порядок определяется по полным именам, поэтому сортировка — до сокращения «ОМ: ».
            String html = abbreviateMetadataObjectTypes(sortTypeRuns(sourceHtml, resource, scope, converter));
            var matcher = ANCHOR.matcher(html);
            StringBuilder result = new StringBuilder();
            while (matcher.find())
            {
                String name = matcher.group(1).replaceAll("<[^>]*>", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                String formBase = BslFormDataTypes.baseTypeName(matcher.group());
                if (formBase != null)
                    name = formBase;
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
        return decorated == null || decorated.equals(abbreviated) ? abbreviated : style(decorated);
    }

    /**
     * Ссылки каждой цепочки типов переставляются в порядке ячеек и полей ввода
     * ({@link TypeDescriptionPresentationHook#displayOrder}); разделители остаются на месте.
     * Цепочка, где хоть одна ссылка не тип, или уже обёрнутая меню, не меняется.
     */
    private static String sortTypeRuns(String html, XtextResource resource, IScope scope,
        IQualifiedNameConverter converter)
    {
        var runs = TYPE_ANCHOR_RUN.matcher(html);
        StringBuilder result = new StringBuilder();
        while (runs.find())
        {
            String run = runs.group();
            boolean wrapped = run.contains(WRAP_TAIL) || html.startsWith(WRAP_TAIL, runs.start() - WRAP_TAIL.length());
            String replacement = wrapped ? run : sortRun(run, resource, scope, converter);
            runs.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        runs.appendTail(result);
        return result.toString();
    }

    private static String sortRun(String run, XtextResource resource, IScope scope, IQualifiedNameConverter converter)
    {
        var anchors = ANCHOR.matcher(run);
        List<int[]> bounds = new ArrayList<>();
        List<TypeItem> types = new ArrayList<>();
        IEObjectProvider platformTypes = null;
        while (anchors.find())
        {
            String name = anchors.group(1).replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (name.isEmpty())
                return run;
            var description = scope.getSingleElement(converter.toQualifiedName(name));
            // Типы вроде «ОбъектМетаданных: ОпределяемыйТип» не входят в область описания типов
            // реквизита — их ищем среди платформенных типов версии проекта. В модели имя слитное:
            // «ОбъектМетаданныхОпределяемыйТип» / MetadataObjectDefinedType.
            if (description == null)
            {
                if (platformTypes == null)
                    platformTypes = platformTypes(resource);
                if (platformTypes != null)
                {
                    description = platformTypes.getEObjectDescription(name);
                    if (description == null)
                        description = platformTypes.getEObjectDescription(name.replaceFirst(":\\s*", "")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            var object = description == null ? null : description.getEObjectOrProxy();
            if (object != null && object.eIsProxy())
                object = EcoreUtil.resolve(object, resource);
            if (!(object instanceof TypeItem type) || object.eIsProxy())
                return run;
            bounds.add(new int[] {anchors.start(), anchors.end()});
            types.add(type);
        }
        if (types.size() < 2)
            return run;
        int[] order = TypeDescriptionPresentationHook.displayOrder(types, resource.getContents().get(0));
        if (order == null)
            return run;
        StringBuilder result = new StringBuilder();
        int tail = 0;
        for (int i = 0; i < bounds.size(); i++)
        {
            result.append(run, tail, bounds.get(i)[0]);
            int[] moved = bounds.get(order[i]);
            result.append(run, moved[0], moved[1]);
            tail = bounds.get(i)[1];
        }
        result.append(run.substring(tail));
        return result.toString();
    }

    /** Платформенные типы версии проекта ({@code null}, если версия или провайдер недоступны). */
    private static IEObjectProvider platformTypes(XtextResource resource)
    {
        IRuntimeVersionSupport versions = resource.getResourceServiceProvider().get(IRuntimeVersionSupport.class);
        Version version = versions == null ? Version.LATEST
            : versions.getRuntimeVersionOrDefault(resource.getContents().get(0), Version.LATEST);
        return IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
    }

    /**
     * Если в тексте ссылок на типы «ОбъектМетаданных: X» встречается больше одного раза подряд
     * (ссылки разделены только тегами, пробелами и «|»/«,»), приставка в этих ссылках сокращается до «ОМ: ».
     */
    private static String abbreviateMetadataObjectTypes(String html)
    {
        if (html == null || html.indexOf(METADATA_OBJECT_PREFIX) < 0)
            return html;
        var matcher = TYPE_ANCHOR_RUN.matcher(html);
        StringBuilder result = new StringBuilder();
        while (matcher.find())
        {
            String run = matcher.group();
            var prefixes = METADATA_OBJECT_ANCHOR.matcher(run);
            int count = 0;
            while (prefixes.find())
                count++;
            String replacement = count < 2 ? run : METADATA_OBJECT_ANCHOR.matcher(run).replaceAll("$1ОМ: "); //$NON-NLS-1$
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Типы QL ещё не имеют ссылки: клик — тот же основной переход, что у типов BSL. */
    static String link(TypeItem type, String label, BslXtextEditor source)
    {
        String text = HTMLPrinter.convertToHTMLContent(label);
        if (source == null)
            return text;
        String token = register(type, source);
        String anchor = "<a href=\"#\" " + primaryClick(token) + ">" + text + "</a>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return wrap(token, anchor);
    }

    private static String primaryClick(String token)
    {
        return "onclick=\"" + FUNCTION + "('" + token + "','primary');return false;\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Клик по ссылке объекта метаданных — основной переход Ctrl-ссылки (редактор объекта).
     * У типа без объекта-производителя (платформенный) — штатное описание типа.
     */
    private static void openPrimary(Request request, Browser browser)
    {
        for (IHyperlink link : links(request))
        {
            if (BslEditorHyperlinkHook.isPrimary(link))
            {
                browser.getShell().setVisible(false);
                link.open();
                return;
            }
        }
        if (request.type != null)
            openTypeDocumentation(request, browser);
    }

    private static IHyperlink[] links(Request request)
    {
        return request.type != null
            ? BslEditorHyperlinkHook.metadataTypeLinks(request.type, request.source)
            : BslEditorHyperlinkHook.metadataObjectLinks(request.object, request.source);
    }

    static String objectLink(MdObject object, String label, BslXtextEditor source)
    {
        String text = HTMLPrinter.convertToHTMLContent(label);
        if (object == null || source == null)
            return text;
        String token = register(null, object, source);
        return wrap(token, "<span class=\"comfort-param-link\" " + primaryClick(token) + ">" //$NON-NLS-1$ //$NON-NLS-2$
            + text + "</span>"); //$NON-NLS-1$
    }

    /** Штатная ссылка типа: клик вместо описания типа открывает редактор объекта-производителя. */
    private static String wrap(TypeItem type, BslXtextEditor source, String anchor)
    {
        String token = register(type, source);
        // Свой onclick — первым атрибутом: при повторе атрибута браузер берёт первый.
        return wrap(token, "<a " + primaryClick(token) + anchor.substring(2)); //$NON-NLS-1$
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
        request.links = links(request);
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < request.links.length; index++)
        {
            // Основной переход — клик по самой ссылке.
            if (BslEditorHyperlinkHook.isPrimary(request.links[index]))
                continue;
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
