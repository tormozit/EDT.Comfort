package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Автогруппы общих модулей в списке автодополнения без ИР (issue 891) — перенос из ИР: ветка
 * {@code ОбщиеМодули} глобального контекста {@code ирПлатформа.ТаблицаСловИзСтруктурыТипа} и
 * {@code ирКлсПолеТекстаПрограммы.ЛиВГруппеОбщихМодулей}. В глобальном списке — слово {@code База_} на каждую
 * группу доступных модулей ({@link #groupWords}), после {@code База_.} — экспортные методы всех модулей группы
 * ({@link #members}); вставка заменяет {@code База_.} именем модуля строки.
 *
 * <p>Группы — по наборам суффиксов настроек плагина ({@link CommonModuleGrouping}, как в навигаторе), а не по
 * списку суффиксов ИР (решение пользователя). Строка члена — штатная строка EDT, не подпись ИР
 * {@code Метод - Модуль}: родителя показывает часть {@code ~ ОбщийМодуль.Имя}.
 */
final class CommonModuleAssistGroups
{
    /** {@code МаксРазмерТаблицыТиповДляЗаполненияТаблицыСлов}: большая группа не раскрывается. */
    private static final int MAX_GROUP_SIZE = 15;
    private static final Pattern GROUP_WORD = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*_"); //$NON-NLS-1$

    private CommonModuleAssistGroups()
    {
    }

    /** Слова {@code База_} и описания всех доступных участников; группы и модули — по алфавиту. */
    static Map<String, String> groupWords(XtextResource resource, int offset)
    {
        Map<String, String> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<CommonModule>> group : groups(resource, offset).entrySet())
        {
            String word = group.getKey() + "_"; //$NON-NLS-1$
            List<String> names = new ArrayList<>();
            for (CommonModule module : group.getValue())
                names.add(module.getName());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            result.put(word, word + " - группа общих модулей (" + names.size() + "):\n" //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("\n", names)); //$NON-NLS-1$
        }
        return result;
    }

    /** Область виртуального имени, которое не разрешается моделью EDT. */
    static org.eclipse.jface.text.IRegion groupHoverRegion(ITextViewer viewer, int offset)
    {
        if (viewer == null || viewer.getDocument() == null)
            return null;
        var document = viewer.getDocument();
        if (offset < 0 || offset > document.getLength()
            || SmartContentAssistProcessor.isCommentAssistContext(document, offset)
            || SmartContentAssistProcessor.isStringLiteralAssistContext(document, offset))
            return null;
        try
        {
            int start = offset;
            int end = offset;
            while (start > 0 && SmartContentAssistProcessor.isFilterChar(document.getChar(start - 1)))
                start--;
            while (end < document.getLength() && SmartContentAssistProcessor.isFilterChar(document.getChar(end)))
                end++;
            if (start == end || start > 0 && document.getChar(start - 1) == '.'
                || !GROUP_WORD.matcher(document.get(start, end - start)).matches())
                return null;
            return new org.eclipse.jface.text.Region(start, end - start);
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            Global.tempLog("common-module-group-hint", "region error " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /** То же описание, что у строки группы в автодополнении. */
    static BslEditorHoverHook.ContentHover groupHover(ITextViewer viewer, int offset)
    {
        var region = groupHoverRegion(viewer, offset);
        if (region == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        return document.readOnly(resource ->
        {
            if (resource == null)
                return null;
            String word = document.get(region.getOffset(), region.getLength());
            String info = groupWords(resource, region.getOffset()).get(word);
            Global.tempLog("common-module-group-hint", "hover word=" + word + " found=" + (info != null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return info == null ? null : new BslEditorHoverHook.ContentHover(info,
                parent -> new org.eclipse.jface.text.DefaultInformationControl(parent));
        });
    }

    /**
     * Экспортные методы модулей группы {@code База_} — идентификатора перед точкой {@code dot} — для списка после
     * неё. Имя читается из текста: в модели такого имени нет, и адресат точки по модели пуст.
     *
     * @return {@code null} — перед точкой не группа (список считает EDT); пусто — группа больше
     *         {@value #MAX_GROUP_SIZE} модулей
     */
    static ICompletionProposal[] members(ITextViewer viewer, IXtextDocument document, int dot)
    {
        if (document == null || dot <= 0)
            return null;
        int receiverStart = dot;
        String receiver;
        try
        {
            while (receiverStart > 0 && SmartContentAssistProcessor.isFilterChar(document.getChar(receiverStart - 1)))
                receiverStart--;
            receiver = document.get(receiverStart, dot - receiverStart);
            // «Объект.База_.» — не глобальный контекст
            if (receiverStart > 0 && document.getChar(receiverStart - 1) == '.')
                return null;
        }
        catch (Exception e)
        {
            return null;
        }
        if (receiver.length() < 2 || !GROUP_WORD.matcher(receiver).matches())
            return null;
        int start = receiverStart;
        String base = receiver.substring(0, receiver.length() - 1);
        return ContentAssistSessionReloader.readOnlyForContentAssist(document, resource ->
        {
            if (resource == null)
                return null;
            List<CommonModule> modules = null;
            for (Map.Entry<String, List<CommonModule>> group : groups(resource, dot).entrySet())
                if (group.getKey().equalsIgnoreCase(base))
                    modules = group.getValue();
            if (modules == null)
                return null;
            if (modules.size() > MAX_GROUP_SIZE)
                return new ICompletionProposal[0];
            String ownerPrefix = russian(resource) ? "ОбщийМодуль." : "CommonModule."; //$NON-NLS-1$ //$NON-NLS-2$
            IResourceServiceProvider services = resource.getURI() != null
                ? IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(resource.getURI()) : null;
            IEObjectHover hover = services != null ? services.get(IEObjectHover.class) : null;
            List<ICompletionProposal> result = new ArrayList<>();
            for (CommonModule common : modules)
            {
                Module module = moduleOf(common, resource);
                if (module == null)
                    continue;
                for (Method method : module.allMethods())
                {
                    if (!method.isExport() || method.getName() == null)
                        continue;
                    result.add(memberProposal(viewer, resource, hover, common.getName(), ownerPrefix, method,
                        start, dot + 1 - start));
                }
            }
            return result.toArray(new ICompletionProposal[result.size()]);
        });
    }

    /**
     * Строка метода модуля группы — штатная строка Xtext, как у EDT после {@code Модуль.}: описание — сам метод
     * (тип функции в строке и боковая подсказка — штатные), подпись {@code Метод(Параметры) ~ ОбщийМодуль.Имя}.
     * Вставка заменяет {@code База_.} и набранное начало на {@code Модуль.Метод()}, каретка — в скобках, если
     * параметры есть.
     */
    private static ConfigurableCompletionProposal memberProposal(ITextViewer viewer, XtextResource resource,
        IEObjectHover hover, String moduleName, String ownerPrefix, Method method, int receiverStart, int typedLength)
    {
        StringBuilder params = new StringBuilder();
        for (FormalParam param : method.getFormalParams())
        {
            if (params.length() > 0)
                params.append(", "); //$NON-NLS-1$
            params.append(param.getName());
        }
        String replacement = moduleName + "." + method.getName() + "()"; //$NON-NLS-1$ //$NON-NLS-2$
        String display = method.getName() + "(" + params + ") ~ " + ownerPrefix + moduleName; //$NON-NLS-1$ //$NON-NLS-2$
        Image image = BslSharedImages.getImage(method instanceof Function ? BslSharedImages.IMG_EXTERNAL_FUNC
            : BslSharedImages.IMG_EXTERNAL_PROC);
        ConfigurableCompletionProposal proposal = new ConfigurableCompletionProposal(replacement, receiverStart,
            typedLength, params.length() > 0 ? replacement.length() - 1 : replacement.length(), image,
            new StyledString(display), null, null);
        proposal.setAdditionalProposalInfo(method);
        proposal.setProposalContextResource(resource);
        if (hover != null)
            proposal.setHover(hover);
        // Описание EDT строит по вьюеру строки; штатные строки получают его при создании
        Global.setField(proposal, "viewer", viewer); //$NON-NLS-1$
        return proposal;
    }

    /**
     * Группы доступных модулей: база → модули (два и больше). База — имя без хвоста суффиксов
     * ({@link CommonModuleGrouping#stripCombinedSuffixes}).
     */
    private static Map<String, List<CommonModule>> groups(XtextResource resource, int offset)
    {
        List<CommonModule> available = available(resource, offset);
        Map<String, List<CommonModule>> byBase = new LinkedHashMap<>();
        List<String> set1 = ComfortSettings.getGroupCommonModulesSuffixes1();
        List<String> set2 = ComfortSettings.getGroupCommonModulesSuffixes2();
        Map<String, String> spelling = new LinkedHashMap<>();
        for (CommonModule module : available)
        {
            String base = CommonModuleGrouping.stripCombinedSuffixes(module.getName(), set1, set2);
            String key = base.toLowerCase(Locale.ROOT);
            spelling.putIfAbsent(key, base);
            byBase.computeIfAbsent(key, k -> new ArrayList<>()).add(module);
        }
        Map<String, List<CommonModule>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<CommonModule>> entry : byBase.entrySet())
            if (entry.getValue().size() > 1)
                result.put(spelling.get(entry.getKey()), entry.getValue());
        return result;
    }

    /**
     * {@code ирКэш.ДоступныеОбщиеМодулиЛкс(НаСервере, КлиентОбычноеПриложение, КлиентУправляемоеПриложение)}:
     * неглобальные модули, доступные в контекстах компиляции места.
     */
    private static List<CommonModule> available(XtextResource resource, int offset)
    {
        List<CommonModule> result = new ArrayList<>();
        Configuration configuration = configuration(resource);
        if (configuration == null)
            return result;
        Method method = methodAt(resource, offset);
        Module sourceModule = !resource.getContents().isEmpty()
            && resource.getContents().get(0) instanceof Module module ? module : null;
        Environments moduleEnvironments = sourceModule != null ? sourceModule.environments() : null;
        Environments environments = method != null ? method.environments() : moduleEnvironments;
        // «НаКлиенте» у метода содержит все клиенты; модуль формы определяет вид приложения.
        if (environments != null && moduleEnvironments != null)
            environments = environments.intersect(moduleEnvironments);
        IBslPreferences preferences = resource.getResourceServiceProvider().get(IBslPreferences.class);
        Environments loadedEnvironments = preferences != null && !resource.getContents().isEmpty()
            ? preferences.getLoadEnvs(resource.getContents().get(0)) : null;
        // environments() включает и контексты, не выбранные в настройках проекта EDT.
        if (environments != null && loadedEnvironments != null)
            environments = loadedEnvironments.intersect(environments);
        boolean server = environments == null || environments.containsAny(Environments.ALL_SERVERS);
        boolean managedClient = environments != null && environments.containsAny(Environments.MNG_CLIENTS);
        boolean ordinaryClient = environments != null && environments.contains(Environment.CLIENT);
        for (CommonModule module : configuration.getCommonModules())
        {
            if (module == null || module.getName() == null || module.isGlobal())
                continue;
            boolean moduleAvailable = module.isServerCall() || server && module.isServer()
                || managedClient && module.isClientManagedApplication()
                || ordinaryClient && module.isClientOrdinaryApplication();
            if (moduleAvailable)
                result.add(module);
        }
        return result;
    }

    private static Module moduleOf(CommonModule common, XtextResource resource)
    {
        Object module = common.getModule();
        if (module instanceof EObject proxy && proxy.eIsProxy())
            module = EcoreUtil.resolve(proxy, resource);
        return module instanceof Module bsl && !bsl.eIsProxy() ? bsl : null;
    }

    private static Method methodAt(XtextResource resource, int offset)
    {
        if (resource.getParseResult() == null)
            return null;
        INode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        if (leaf == null && offset > 0)
            leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset - 1);
        EObject semantic = leaf != null ? leaf.getSemanticElement() : null;
        return semantic != null ? EcoreUtil2.getContainerOfType(semantic, Method.class) : null;
    }

    private static Configuration configuration(XtextResource resource)
    {
        IV8Project project = project(resource);
        return project instanceof IConfigurationAware aware ? aware.getConfiguration() : null;
    }

    private static boolean russian(XtextResource resource)
    {
        IV8Project project = project(resource);
        return project == null || project.getScriptVariant() != ScriptVariant.ENGLISH;
    }

    private static IV8Project project(XtextResource resource)
    {
        var projects = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        return projects != null && resource != null ? projects.getProject(resource.getURI()) : null;
    }
}
