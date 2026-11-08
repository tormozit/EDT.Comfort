package tormozit.checks;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils;
import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.EmptyExpression;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.model.util.BslUtil;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.validation.IMessages;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.GeneralContextDef;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;
import tormozit.Global;

/**
 * Копия веток {@code BslJavaValidator.checkStaticFeatureAccess} из бандла {@code com._1c.g5.v8.dt.bsl 28.0.1}
 * (квалификатор при копировании не записан: {@code v202605050943} или {@code v202607090722})
 * и {@code BslJavaValidator.checkDynamicFeatureAccess} из {@code com._1c.g5.v8.dt.bsl 28.0.1.v202607090722}
 * (#936). Каждая вложенная проверка исполняет только свою группу. Позиции и тексты взяты из
 * штатных проверок; одинаковый результат разных FeatureEntry выдаётся один раз.
 */
public abstract class StaticFeatureAccessChecks extends BasicCheck<Object>
{
    private enum Group
    {
        PARAMETERS(ComfortCheckIds.STATIC_ACCESS_PARAMETERS, "Параметры вызова",
            "Число и заполненность параметров вызова; вызов процедуры как функции.", IssueSeverity.MAJOR,
            STATIC, DYNAMIC),
        OBSOLETE(ComfortCheckIds.STATIC_ACCESS_OBSOLETE, "Устаревшие методы и свойства",
            "Использование методов и свойств, объявленных устаревшими.", IssueSeverity.MINOR,
            STATIC, DYNAMIC),
        COMPATIBILITY(ComfortCheckIds.STATIC_ACCESS_COMPATIBILITY, "Режим совместимости",
            "Метод или свойство недоступно в режиме совместимости проекта.", IssueSeverity.MAJOR,
            STATIC, DYNAMIC),
        VARIABLE(ComfortCheckIds.STATIC_ACCESS_VARIABLE, "Доступ к переменной / свойству",
            "Недопустимый доступ к переменной или свойству, включая чтение и запись.", IssueSeverity.MAJOR,
            STATIC, DYNAMIC),
        EVENT_HANDLER(ComfortCheckIds.STATIC_ACCESS_EVENT_HANDLER, "Обработчик события",
            "Недопустимый объект, указанный как обработчик события.", IssueSeverity.MAJOR,
            STATIC),
        UNKNOWN_MEMBER(ComfortCheckIds.DYNAMIC_ACCESS_UNKNOWN_MEMBER, "Свойство/метод после точки не обнаружено",
            "Свойство или метод, к которому обращаются через точку, не найден у объекта в части окружений.",
            IssueSeverity.MAJOR, DYNAMIC);

        final String id;
        final String title;
        final String description;
        final IssueSeverity severity;
        final String[] replaces;

        Group(String id, String title, String description, IssueSeverity severity, String... replaces)
        {
            this.id = id;
            this.title = title;
            this.description = description;
            this.severity = severity;
            this.replaces = replaces;
        }

        String replacementNote()
        {
            return replaces.length == 1 ? "<br><br>Динамически замещает штатную проверку " + replaces[0] + "."
                : "<br><br>Динамически замещает штатные проверки " + String.join(", ", replaces) + ".";
        }
    }

    private static final String STATIC = "bsl-legacy-check-static-feature-access"; //$NON-NLS-1$
    private static final String DYNAMIC = "bsl-legacy-check-dynamic-feature-access"; //$NON-NLS-1$

    /** Функция раннего бандла (#1012): {текст, имя} → «Свойство/метод 'Имя' объекта не обнаружено». */
    private static final String UNKNOWN_MEMBER_MESSAGE = "tormozit.bslValidator.unknownMemberMessage"; //$NON-NLS-1$

    private static final IMessages MESSAGES = IMessages.INSTANCE;

    protected abstract Group group();

    @Override
    public final String getCheckId()
    {
        return group().id;
    }

    @Override
    protected final void configureCheck(CheckConfigurer configurer)
    {
        Group group = group();
        configurer.title(group.title).description(group.description + group.replacementNote())
            .complexity(CheckComplexity.NORMAL).severity(group.severity)
            .issueType(group == Group.OBSOLETE || group == Group.UNKNOWN_MEMBER ? IssueType.WARNING : IssueType.ERROR)
            .module().checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected final void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module))
            return;
        IV8ProjectManager projectManager = Global.getOsgiService(IV8ProjectManager.class);
        XtextResource resource = module.eResource() instanceof XtextResource xtext ? xtext : null;
        IBslPreferences preferences = resource == null ? null
            : resource.getResourceServiceProvider().get(IBslPreferences.class);
        DynamicFeatureAccessComputer computer = resource == null ? null
            : resource.getResourceServiceProvider().get(DynamicFeatureAccessComputer.class);
        Group group = group();
        TreeIterator<EObject> all = module.eAllContents();
        while (all.hasNext() && !monitor.isCanceled())
        {
            EObject next = all.next();
            if (next instanceof StaticFeatureAccess access)
            {
                if (group != Group.UNKNOWN_MEMBER)
                    new Copy(access, access.getFeatureEntries(), acceptor, group, projectManager, preferences).check();
                continue;
            }
            if (!(next instanceof DynamicFeatureAccess access))
                continue;
            if (group == Group.PARAMETERS && BslUtil.getInvocation(access) != null)
            {
                List<FeatureEntry> entries = access.getFeatureEntries();
                if (entries.isEmpty() && computer != null)
                    entries = DynamicFeatureAccessComputer.resolveDynamicFeatureAccess(access, computer);
                // EDT проверяет число параметров динамического вызова только у mcore.Method.
                // Для BSL Method используем ту же проверку, что и при вызове без точки.
                new Copy(access, entries, acceptor, group, projectManager, preferences).check();
            }
            if (group != Group.EVENT_HANDLER && computer != null && preferences != null && isUpperDynamicFeature(access))
                checkDynamic(access, computer, acceptor, group, projectManager, preferences);
        }
    }

    /** Ветка {@code BslJavaValidator.checkDynamicFeatureAccess}: проверяется только последнее звено цепочки. */
    private static void checkDynamic(DynamicFeatureAccess access, DynamicFeatureAccessComputer computer,
        ResultAcceptor acceptor, Group group, IV8ProjectManager projectManager, IBslPreferences preferences)
    {
        Environmental environmental = EcoreUtil2.getContainerOfType(access, Environmental.class);
        if (environmental == null)
            return;
        Environments actualEnvs = preferences.getLoadEnvs(access).intersect(environmental.environments());
        if (actualEnvs.isEmpty())
            return;
        List<FeatureEntry> objects = computer.getLastObject(access, actualEnvs, true);
        if (!objects.isEmpty())
            new Copy(access, objects, acceptor, group, projectManager, preferences).checkDynamic(actualEnvs);
    }

    private static boolean isUpperDynamicFeature(DynamicFeatureAccess access)
    {
        EObject container = access.eContainer();
        return !(container instanceof DynamicFeatureAccess)
            && (!(container instanceof Invocation) || !(container.eContainer() instanceof DynamicFeatureAccess));
    }

    private static final class Copy
    {
        private final FeatureAccess access;
        private final List<FeatureEntry> entries;
        private final ResultAcceptor acceptor;
        private final Group group;
        private final IV8ProjectManager projectManager;
        private final IBslPreferences preferences;
        private final Set<String> reported = new HashSet<>();

        Copy(FeatureAccess access, List<FeatureEntry> entries, ResultAcceptor acceptor, Group group,
            IV8ProjectManager projectManager, IBslPreferences preferences)
        {
            this.access = access;
            this.entries = entries;
            this.acceptor = acceptor;
            this.group = group;
            this.projectManager = projectManager;
            this.preferences = preferences;
        }

        void check()
        {
            Invocation invocation = BslUtil.getInvocation(access);
            if (invocation != null)
            {
                if (group == Group.EVENT_HANDLER)
                    return;
                checkInvocation(invocation);
            }
            else if (access instanceof StaticFeatureAccess staticAccess && BslUtil.isEventHandler(staticAccess))
            {
                if (group == Group.EVENT_HANDLER)
                    checkEventHandler();
            }
            else if (group == Group.VARIABLE || group == Group.COMPATIBILITY
                || group == Group.OBSOLETE)
            {
                checkVariable();
            }
        }

        /** {@code entries} — результат {@code getLastObject}; порядок проверок как у штатной. */
        void checkDynamic(Environments actualEnvs)
        {
            Invocation invocation = BslUtil.getInvocation(access);
            boolean correctParamSet = false;
            boolean writableProperty = false;
            Environments notCovered = new Environments(actualEnvs.toArray());
            for (FeatureEntry entry : entries)
            {
                EObject feature = entry.getFeature();
                if (feature instanceof com._1c.g5.v8.dt.mcore.Method method)
                {
                    if (invocation != null)
                    {
                        int actual = invocation.getParams().size();
                        ParamSet set = method.actualParamSet(actual);
                        correctParamSet = correctParamSet || (actual <= set.getMaxParams() || set.getMaxParams() == -1)
                            && actual >= set.getMinParams();
                    }
                    if (group == Group.OBSOLETE && feature instanceof BslContextDefMethod contextMethod
                        && contextMethod.isDeprecated())
                        issue(MESSAGES.deprecated_method(), access, null, -1, method.environments());
                }
                else if (feature instanceof Method method && BslCommentUtils.isDeprecated(method))
                {
                    if (group == Group.OBSOLETE)
                        issue(MESSAGES.deprecated_method(), access, null, -1, method.environments());
                }
                else if (feature instanceof Property property)
                    writableProperty = writableProperty || property.isWritable();
                if (feature instanceof Environmental)
                    notCovered = notCovered.remove(entry.getEnvironments());
            }
            if (group == Group.UNKNOWN_MEMBER)
            {
                if (!notCovered.isEmpty())
                    issue(unknownMemberMessage(), access, BslPackage.Literals.FEATURE_ACCESS__NAME, -1, notCovered);
                return;
            }
            for (FeatureEntry entry : entries)
                checkDynamicFeature(correctParamSet, writableProperty, entry, invocation);
        }

        private String unknownMemberMessage()
        {
            String message = MESSAGES.unknown_feature_for_left_part_of_expression();
            if (System.getProperties().get(UNKNOWN_MEMBER_MESSAGE) instanceof java.util.function.Function<?, ?> format)
            {
                @SuppressWarnings("unchecked")
                Object formatted = ((java.util.function.Function<Object[], Object>)format)
                    .apply(new Object[] {message, access.getName()});
                if (formatted instanceof String text)
                    return text;
            }
            return message;
        }

        private void checkDynamicFeature(boolean correctParamSet, boolean writableProperty, FeatureEntry entry,
            Invocation invocation)
        {
            EObject feature = entry.getFeature();
            if (feature == null || feature.eIsProxy())
                return;
            Environments envs = entry.getEnvironments();
            if (feature instanceof com._1c.g5.v8.dt.mcore.Method method)
            {
                if (invocation == null)
                    return;
                if (group == Group.PARAMETERS)
                {
                    if (!correctParamSet)
                        checkContextMethodInvocation(method, invocation, envs);
                    else
                        checkContextMethodRetVal(method, invocation, envs);
                }
                else if (group == Group.COMPATIBILITY)
                    checkCompatibility(method.getCompatibilityMode(), invocation,
                        BslPackage.Literals.INVOCATION__METHOD_ACCESS);
                else if (group == Group.OBSOLETE)
                    checkDeprecated(method.getDeprecatedSince(), invocation,
                        BslPackage.Literals.INVOCATION__METHOD_ACCESS);
            }
            else if (feature instanceof Property property)
            {
                if (group == Group.VARIABLE && !writableProperty && !property.isWritable()
                    && access.eContainer() instanceof SimpleStatement
                    && access.eContainmentFeature() == BslPackage.Literals.SIMPLE_STATEMENT__LEFT)
                {
                    boolean russian = projectManager != null
                        && com._1c.g5.v8.dt.bsl.util.BslUtil.isRussian(access, projectManager);
                    issue(MESSAGES.property_is_not_writable(russian ? property.getNameRu() : property.getName(),
                        typeName(property, russian)), access, BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
                }
                else if (group == Group.COMPATIBILITY)
                    checkCompatibility(property.getCompatibilityMode(), access,
                        BslPackage.Literals.FEATURE_ACCESS__NAME);
                else if (group == Group.OBSOLETE)
                    checkDeprecated(property.getDeprecatedSince(), access,
                        BslPackage.Literals.FEATURE_ACCESS__NAME);
            }
        }

        private void checkInvocation(Invocation invocation)
        {
            for (FeatureEntry entry : entries)
            {
                EObject feature = entry.getFeature();
                if (feature == null || feature.eIsProxy())
                    continue;
                if (access instanceof DynamicFeatureAccess && !(feature instanceof Method))
                    continue;
                Environments envs = entry.getEnvironments();
                if (feature instanceof Method method)
                {
                    if (group == Group.PARAMETERS)
                        checkMethodInvocation(method, invocation, envs);
                }
                else if (feature instanceof com._1c.g5.v8.dt.mcore.Method method)
                {
                    if (group == Group.PARAMETERS)
                        checkContextMethodInvocation(method, invocation, envs);
                    else if (group == Group.COMPATIBILITY)
                        checkCompatibility(method.getCompatibilityMode(), invocation,
                            BslPackage.Literals.INVOCATION__METHOD_ACCESS);
                    else if (group == Group.OBSOLETE)
                        checkDeprecated(method.getDeprecatedSince(), invocation,
                            BslPackage.Literals.INVOCATION__METHOD_ACCESS);
                }
                else if (group == Group.VARIABLE)
                {
                    issue(MESSAGES.only_proc_func_bltnfunc_cntxtmethod_can_be_target_of_invocation(), access,
                        BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
                }
            }
        }

        private void checkMethodInvocation(Method method, Invocation invocation, Environments envs)
        {
            int actual = invocation.getParams().size();
            int formal = method.getFormalParams().size();
            if (actual > formal)
                issue(MESSAGES.too_many_actual_parameters(), invocation.getMethodAccess(),
                    BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
            else if (actual < formal)
                for (int i = actual; i < formal; i++)
                {
                    FormalParam parameter = method.getFormalParams().get(i);
                    if (parameter.getDefaultValue() == null)
                    {
                        issue(MESSAGES.not_enough_actual_parameters(), invocation.getMethodAccess(),
                            BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
                        break;
                    }
                }
            if (!(method instanceof Function) && (!BslUtil.isProcedureInvocation(invocation)
                || invocation.eContainingFeature() == BslPackage.Literals.AWAIT_EXPRESSION__EXPRESSION))
                issue(MESSAGES.procedure_called_as_function(), invocation.getMethodAccess(),
                    BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
        }

        private void checkContextMethodInvocation(com._1c.g5.v8.dt.mcore.Method method,
            Invocation invocation, Environments envs)
        {
            int actual = invocation.getParams().size();
            ParamSet set = method.actualParamSet(actual);
            TypeItem type = method.eContainer() != null && method.eContainer().eContainer() instanceof TypeItem item
                ? item : null;
            String typeLine = type == null ? null : com._1c.g5.v8.dt.bsl.util.BslUtil.createTypesLineStr(
                List.of(type), projectManager != null
                    && com._1c.g5.v8.dt.bsl.util.BslUtil.isRussian(invocation, projectManager));
            if (actual > set.getMaxParams() && set.getMaxParams() != -1)
                issue(typeLine == null ? MESSAGES.too_many_actual_parameters()
                    : MESSAGES.too_many_actual_parameters_for_types(typeLine), invocation.getMethodAccess(),
                    BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
            else if (actual < set.getMinParams() || hasMissingRequiredParameter(set, actual))
                issue(typeLine == null ? MESSAGES.not_enough_actual_parameters()
                    : MESSAGES.not_enough_actual_parameters_for_types(typeLine), invocation.getMethodAccess(),
                    BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
            if (access instanceof StaticFeatureAccess && isBuiltInFunction())
                for (Expression parameter : invocation.getParams())
                    if (parameter == null || parameter instanceof EmptyExpression)
                        issue(MESSAGES.not_enough_actual_parameters(), invocation.getMethodAccess(),
                            BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
            checkContextMethodRetVal(method, invocation, envs);
        }

        private void checkContextMethodRetVal(com._1c.g5.v8.dt.mcore.Method method, Invocation invocation,
            Environments envs)
        {
            if (!method.isRetVal() && (!BslUtil.isProcedureInvocation(invocation)
                || invocation.eContainingFeature() == BslPackage.Literals.AWAIT_EXPRESSION__EXPRESSION))
                issue(MESSAGES.procedure_called_as_function(), invocation.getMethodAccess(),
                    BslPackage.Literals.FEATURE_ACCESS__NAME, -1, envs);
        }

        /** minParams считает обязательные параметры, но не учитывает их позиции. */
        private static boolean hasMissingRequiredParameter(ParamSet set, int actual)
        {
            for (int i = actual; i < set.getParams().size(); i++)
                if (!set.getParams().get(i).isDefaultValue())
                    return true;
            return false;
        }

        private boolean isBuiltInFunction()
        {
            for (FeatureEntry entry : entries)
                if (entry.getFeature() instanceof com._1c.g5.v8.dt.mcore.Method method
                    && method.eContainer() instanceof GeneralContextDef && method.isRetVal()
                    && access.getName() != null
                    && BUILTIN_NAMES.contains(access.getName().toLowerCase(Locale.ROOT)))
                    return true;
            return false;
        }

        private void checkEventHandler()
        {
            for (FeatureEntry entry : entries)
            {
                EObject feature = entry.getFeature();
                if (!(feature instanceof Method) && !(feature instanceof com._1c.g5.v8.dt.mcore.Method))
                    issue(MESSAGES.only_proc_func_bltnfunc_cntxtmethod_can_be_used_as_event_handler(), access,
                        BslPackage.Literals.FEATURE_ACCESS__NAME, -1, entry.getEnvironments());
            }
        }

        private void checkVariable()
        {
            if (!(access instanceof StaticFeatureAccess staticAccess))
                return;
            if (group == Group.VARIABLE && "?".equals(access.getName()))
            {
                Environmental environmental = EcoreUtil2.getContainerOfType(access, Environmental.class);
                issue(MESSAGES.invalid_variable_name(), access, BslPackage.Literals.FEATURE_ACCESS__NAME,
                    -1, environmental == null ? null : environmental.environments());
            }
            for (FeatureEntry entry : entries)
            {
                EObject feature = entry.getFeature();
                if (feature instanceof Property property)
                {
                    if (group == Group.VARIABLE)
                    {
                        boolean russian = projectManager != null
                            && com._1c.g5.v8.dt.bsl.util.BslUtil.isRussian(feature, projectManager);
                        String name = russian ? property.getNameRu() : property.getName();
                        String typeName = typeName(property, russian);
                        if (BslUtil.isTargetOfAssignment(staticAccess) && !property.isWritable())
                            issue(MESSAGES.property_is_not_writable(name, typeName), access,
                                BslPackage.Literals.FEATURE_ACCESS__NAME, -1, entry.getEnvironments());
                        else if (!BslUtil.isTargetOfAssignment(staticAccess) && !property.isReadable())
                            issue(MESSAGES.property_is_not_readable(name, typeName), access,
                                BslPackage.Literals.FEATURE_ACCESS__NAME, -1, entry.getEnvironments());
                    }
                    else if (group == Group.COMPATIBILITY)
                        checkCompatibility(property.getCompatibilityMode(), access,
                            BslPackage.Literals.FEATURE_ACCESS__NAME);
                    else if (group == Group.OBSOLETE)
                        checkDeprecated(property.getDeprecatedSince(), access,
                            BslPackage.Literals.FEATURE_ACCESS__NAME);
                }
                else if (group == Group.VARIABLE && feature != null && !feature.eIsProxy()
                    && !(feature instanceof Variable))
                    issue(MESSAGES.only_formparam_variable_cntxtprop_can_be_accessed_as_variable(), access,
                        BslPackage.Literals.FEATURE_ACCESS__NAME, -1, entry.getEnvironments());
            }
        }

        private String typeName(Property property, boolean russian)
        {
            if (property.eContainer() != null && property.eContainer().eContainer() instanceof TypeItem type)
                return russian ? type.getNameRu() : type.getName();
            return "";
        }

        private void checkCompatibility(String since, EObject source, EStructuralFeature feature)
        {
            if (since == null || access.getName() == null || access.getName().isEmpty()
                || projectManager == null)
                return;
            CompatibilityMode required = CompatibilityMode.get(since);
            IV8Project project = projectManager.getProject(source);
            if (required == null || project == null || project.getCompatibilityMode() == null
                || required.getValue() <= project.getCompatibilityMode().getValue())
                return;
            String mode = project.getCompatibilityMode().getLiteral();
            issue(source instanceof FeatureAccess
                ? MESSAGES.property_is_not_accessible_in_compatiblity_mode_0(mode)
                : MESSAGES.method_is_not_accessible_in_compatiblity_mode_0(mode), source, feature, -1, null);
        }

        private void checkDeprecated(String since, EObject source, EStructuralFeature feature)
        {
            if (since == null || access.getName() == null || access.getName().isEmpty()
                || projectManager == null)
                return;
            CompatibilityMode deprecated = CompatibilityMode.get(since);
            IV8Project project = projectManager.getProject(source);
            if (deprecated == null || project == null)
                return;
            CompatibilityMode mode = project.getCompatibilityMode();
            if (project instanceof IExtensionProject extension && extension.getConfiguration() != null)
            {
                Configuration configuration = extension.getConfiguration();
                if (configuration.getConfigurationExtensionCompatibilityMode() != null)
                    mode = configuration.getConfigurationExtensionCompatibilityMode();
            }
            if (mode != null && deprecated.getValue() <= mode.getValue())
                issue(source instanceof FeatureAccess ? MESSAGES.deprecated_property()
                    : MESSAGES.deprecated_method(), source, feature, -1, null);
        }

        private void issue(String message, EObject source, EStructuralFeature feature, int index,
            Environments environments)
        {
            if (environments != null && !environments.isEmpty())
            {
                Environments loaded = preferences == null ? null : preferences.getLoadEnvs(source);
                Environments actual = loaded == null ? environments : loaded.intersect(environments);
                if (actual.isEmpty())
                    return;
                StringBuilder suffix = new StringBuilder(" [");
                for (Environment environment : actual.toArray())
                {
                    if (suffix.length() > 2)
                        suffix.append(", ");
                    suffix.append(McoreUtil.getEnvironmentText(environment));
                }
                message += suffix.append(']').toString();
            }
            if (!reported.add(message))
                return;
            if (feature == null)
                acceptor.addIssue(message, source);
            else if (index >= 0)
                acceptor.addIssue(message, source, feature, index);
            else
                acceptor.addIssue(message, source, feature);
        }
    }

    private static final Set<String> BUILTIN_NAMES = Set.of("strlen", "стрдлина", "triml", "сокрл",
        "trimr", "сокрп", "trimall", "сокрлп", "left", "лев", "right", "прав", "mid", "сред",
        "find", "найти", "upper", "врег", "lower", "нрег", "title", "трег", "char", "символ",
        "charcode", "кодсимвола", "isblankstring", "пустаястрока", "int", "цел", "round", "окр",
        "boolean", "булево", "number", "число", "string", "строка", "date", "дата", "addmonth",
        "добавитьмесяц", "begofmonth", "началомесяца", "endofmonth", "конецмесяца", "begofquarter",
        "началоквартала", "endofquarter", "конецквартала", "begofyear", "началогода", "endofyear",
        "конецгода", "year", "год", "month", "месяц", "day", "день", "hour", "час", "minute",
        "минута", "second", "секунда", "dayofyear", "деньгода", "weekday", "деньнедели",
        "begofweek", "началонедели", "endofweek", "конецнедели", "begofday", "началодня",
        "endofday", "конецдня", "begofhour", "началочаса", "endofhour", "конецчаса",
        "begofminute", "началоминуты", "endofminute", "конецминуты", "currentdate", "текущаядата",
        "weekofyear", "неделягода", "strreplace", "стрзаменить", "strlinecount", "стрчислострок",
        "strgetline", "стрполучитьстроку", "min", "мин", "max", "макс", "stroccurrencecount",
        "стрчисловхождений", "errordescription", "описаниеошибки", "errorinfo", "информацияобошибке",
        "typeof", "типзнч", "type", "тип", "eval", "вычислить", "format", "формат", "acos",
        "asin", "atan", "cos", "exp", "log", "log10", "pow", "sin", "sqrt", "tan");

    public static final class Parameters extends StaticFeatureAccessChecks
    {
        @Override protected Group group() { return Group.PARAMETERS; }
    }

    public static final class Obsolete extends StaticFeatureAccessChecks
    {
        @Override protected Group group() { return Group.OBSOLETE; }
    }

    public static final class Compatibility extends StaticFeatureAccessChecks
    {
        @Override protected Group group() { return Group.COMPATIBILITY; }
    }

    public static final class VariableAccess extends StaticFeatureAccessChecks
    {
        @Override protected Group group() { return Group.VARIABLE; }
    }

    public static final class EventHandler extends StaticFeatureAccessChecks
    {
        @Override protected Group group() { return Group.EVENT_HANDLER; }
    }

    public static final class UnknownMember extends StaticFeatureAccessChecks
    {
        @Override protected Group group() { return Group.UNKNOWN_MEMBER; }
    }
}
