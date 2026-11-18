package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

/** Раздел «Вызывающие модули»: общий разбор для Ctrl+наведения и расчёта типов параметров. */
final class BslCallingModules
{
    static final String TITLE = "Вызывающие модули:";
    static final String LOG = "calling-modules";
    private static final Pattern MODULE_NAME = Pattern.compile(
        "[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private static final java.util.Map<URI, List<IFile>> DEPENDENCIES = new java.util.concurrent.ConcurrentHashMap<>();
    private record ReadyTypes(String stamp, List<List<com._1c.g5.v8.dt.mcore.TypeItem>> parameters) {}
    private static final java.util.Map<URI, ReadyTypes> READY_TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    private BslCallingModules() {}

    static List<String> names(Method method)
    {
        if (!(method.eResource() instanceof XtextResource resource))
            return List.of();
        var provider = resource.getResourceServiceProvider().get(BslMultiLineCommentDocumentationProvider.class);
        return names(provider.getCommentLines(method));
    }

    static List<String> names(List<String> lines)
    {
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> result = new ArrayList<>();
        boolean section = false;
        for (String line : lines)
        {
            String text = content(line);
            if (text.equalsIgnoreCase(TITLE))
            {
                section = true;
                continue;
            }
            if (!section || text.isEmpty())
                continue;
            if (!MODULE_NAME.matcher(text).matches())
                section = false;
            else if (seen.add(text))
                result.add(text);
        }
        return result;
    }

    private static String content(String line)
    {
        String text = line.strip();
        return text.startsWith("//") ? text.substring(2).strip() : text;
    }

    static boolean canAdd(Method method)
    {
        Module module = EcoreUtil2.getContainerOfType(method, Module.class);
        if (module == null)
            return false;
        var comment = BslStructureInsertCommentTypes.parseMethodComment(method, module);
        return comment == null || comment.getParametersSection() == null && comment.getReturnSection() == null;
    }

    /** Общий модуль — короткое имя; остальные — однозначное полное имя с видом модуля. */
    static String moduleName(IFile file)
    {
        if (file == null)
            return null;
        GetRef.ModuleRef ref = GetRef.pathToModuleRef(file.getProjectRelativePath().toPortableString());
        if (ref == null)
            return null;
        String name = ref.modulePath;
        if (name.startsWith("ОбщийМодуль.") && name.endsWith(".Модуль"))
            name = name.substring("ОбщийМодуль.".length(), name.length() - ".Модуль".length());
        else if (name.endsWith(".Форма"))
        {
            name = GetRef.stripLowValueModuleSuffix(name);
            Global.tempLog(LOG, "moduleName.form file=" + file + " raw=" + ref.modulePath + " name=" + name);
        }
        return ref.extensionName == null ? name : null;
    }

    /** Точный поиск по тому же преобразованию путей, которым команда записала имя модуля. */
    static List<IFile> files(Method method, List<String> names)
    {
        IFile target = ForeignModuleModel.fileOf(method.eResource().getURI());
        if (target == null || names.isEmpty())
            return List.of();
        Set<String> wanted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        wanted.addAll(names);
        List<IFile> result = new ArrayList<>();
        try
        {
            var src = target.getProject().getFolder("src");
            if (src.exists())
                src.accept(proxy -> {
                    BslNodeLookup.checkCanceled();
                    if (proxy.getType() == IResource.FILE && proxy.getName().endsWith(".bsl"))
                    {
                        IFile file = (IFile)proxy.requestResource();
                        String name = moduleName(file);
                        if (name != null && wanted.contains(name) && !file.equals(target))
                            result.add(file);
                    }
                    return true;
                }, IResource.NONE);
        }
        catch (CoreException error)
        {
            Global.tempLog(LOG, "resolve.error target=" + target + " error=" + error);
        }
        Global.tempLog(LOG, "resolve method=" + method.getName() + " names=" + names + " files=" + result);
        DEPENDENCIES.compute(method.eResource().getURI().trimFragment(), (uri, previous) -> {
            List<IFile> all = new ArrayList<>(previous == null ? List.of() : previous);
            for (IFile file : result)
                if (!all.contains(file))
                    all.add(file);
            return List.copyOf(all);
        });
        return result;
    }

    /** Проверка кэша читает только известные зависимости, без обхода проекта и расчёта модели. */
    static String dependencyStamp(URI module)
    {
        StringBuilder result = new StringBuilder();
        for (IFile file : DEPENDENCIES.getOrDefault(module.trimFragment(), List.of()))
        {
            var buffer = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager()
                .getTextFileBuffer(file.getFullPath(), org.eclipse.core.filebuffers.LocationKind.IFILE);
            var document = buffer != null && buffer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument live
                ? live : null;
            result.append('|').append(file.getFullPath()).append('=').append(ForeignModuleModel.stamp(file, document));
        }
        return result.toString();
    }

    static String typesStamp(Method method)
    {
        URI uri = method.eResource().getURI().trimFragment();
        IFile file = ForeignModuleModel.fileOf(uri);
        if (file == null)
            return null;
        var buffer = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager()
            .getTextFileBuffer(file.getFullPath(), org.eclipse.core.filebuffers.LocationKind.IFILE);
        var document = buffer != null && buffer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument live
            ? live : null;
        return ForeignModuleModel.stamp(file, document) + dependencyStamp(uri);
    }

    /** Не ждёт модель и не загружает модули. Запрос недостающих данных уходит отдельному заданию. */
    static List<com._1c.g5.v8.dt.mcore.TypeItem> readyTypes(Method method, int index)
    {
        if (names(method).isEmpty())
            return List.of();
        ReadyTypes ready = READY_TYPES.get(org.eclipse.emf.ecore.util.EcoreUtil.getURI(method));
        String stamp = typesStamp(method);
        Global.tempLog(LOG, "types.peek target=" + org.eclipse.emf.ecore.util.EcoreUtil.getURI(method)
            + " index=" + index + " cachedStamp=" + (ready == null ? null : ready.stamp()) + " stamp=" + stamp);
        if (ready != null && ready.stamp().equals(stamp) && index < ready.parameters().size())
            return copyTypes(ready.parameters().get(index));
        ParamHintHtmlModifier.expectedParameterTypes(method, method.getFormalParams().get(index).getName());
        return List.of();
    }

    static void rememberTypes(Method method, String stamp, List<List<com._1c.g5.v8.dt.mcore.TypeItem>> parameters)
    {
        Global.tempLog(LOG, "types.remember target=" + org.eclipse.emf.ecore.util.EcoreUtil.getURI(method)
            + " stamp=" + stamp + " currentStamp=" + typesStamp(method) + " parameters=" + parameters.size());
        if (stamp != null && stamp.equals(typesStamp(method)))
        {
            READY_TYPES.put(org.eclipse.emf.ecore.util.EcoreUtil.getURI(method),
                new ReadyTypes(stamp, parameters.stream().map(BslCallingModules::copyTypes).toList()));
            BslExpressionDetails.callingModuleTypesReady(org.eclipse.emf.ecore.util.EcoreUtil.getURI(method));
        }
    }

    /** Свободные структурные типы копируются; типы ресурсов сохраняются ссылками, как в memo комментариев. */
    private static List<com._1c.g5.v8.dt.mcore.TypeItem> copyTypes(List<com._1c.g5.v8.dt.mcore.TypeItem> types)
    {
        List<com._1c.g5.v8.dt.mcore.TypeItem> result = new ArrayList<>();
        for (var type : types)
        {
            if (type.eResource() == null)
                result.add(EcoreUtil2.cloneWithProxies(type));
            else
            {
                var proxy = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
                ((org.eclipse.emf.ecore.InternalEObject)proxy).eSetProxyURI(org.eclipse.emf.ecore.util.EcoreUtil.getURI(type));
                result.add(proxy);
            }
        }
        return List.copyOf(result);
    }

    record Edit(int offset, String text) {}

    /** Планируется по актуальному документу цели, не по модели, сохранённой при наведении. */
    static Edit addition(Method method, IDocument document, String moduleName) throws BadLocationException
    {
        if (!canAdd(method) || names(method).stream().anyMatch(moduleName::equalsIgnoreCase))
            return null;
        XtextResource resource = (XtextResource)method.eResource();
        var provider = resource.getResourceServiceProvider().get(BslMultiLineCommentDocumentationProvider.class);
        var nodes = provider.getDocumentationNodes(method);
        int methodOffset = NodeModelUtils.findActualNodeFor(method).getOffset();
        int methodLine = document.getLineOfOffset(methodOffset);
        var line = document.getLineInformation(methodLine);
        String indent = document.get(line.getOffset(), methodOffset - line.getOffset());
        if (!indent.isBlank())
            return null;
        String delimiter = document.getLineDelimiter(methodLine);
        if (delimiter == null)
            delimiter = document.getNumberOfLines() > 1 ? document.getLineDelimiter(0) : "\r\n";
        int last = -1;
        boolean section = false;
        for (var node : nodes)
        {
            int index = document.getLineOfOffset(node.getOffset());
            String text = content(node.getText());
            if (text.equalsIgnoreCase(TITLE))
            {
                section = true;
                last = index;
            }
            else if (section && (text.isEmpty() || MODULE_NAME.matcher(text).matches()))
                last = index;
            else
                section = false;
        }
        if (last >= 0)
        {
            var tail = document.getLineInformation(last);
            return new Edit(tail.getOffset() + tail.getLength(), delimiter + indent + "//\t\t" + moduleName);
        }
        return new Edit(line.getOffset(), indent + "// " + TITLE + delimiter
            + indent + "//\t\t" + moduleName + delimiter);
    }

    /** Сопоставление экземпляров AST разных ResourceSet — имя метода и URI модуля. */
    static boolean sameMethod(org.eclipse.emf.ecore.EObject feature, Method target)
    {
        URI source = feature instanceof com._1c.g5.v8.dt.bsl.model.BslContextDefMethod exported
            ? exported.getSourceUri() : feature instanceof Method method
                ? org.eclipse.emf.ecore.util.EcoreUtil.getURI(method) : null;
        String name = feature instanceof com._1c.g5.v8.dt.mcore.Method method ? method.getName()
            : feature instanceof Method method ? method.getName() : null;
        return source != null && name != null && name.equalsIgnoreCase(target.getName())
            && source.trimFragment().equals(target.eResource().getURI().trimFragment());
    }
}
