package tormozit;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com.e1c.g5.dt.core.api.naming.INamingService;
import com.e1c.g5.dt.core.api.platform.BmOperationContext;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScopeProvider;

/** Типы параметров из актуального комментария для подсказок и проверок вызова. */
public final class BslMethodParameterTypes
{
    private BslMethodParameterTypes() {}

    /** Возвращает null, если описание параметра или контекст его вычисления отсутствуют. */
    public static List<TypeItem> fromComment(Method method, FormalParam parameter)
    {
        List<TypeItem> documented = null;
        Global.tempLog("parameter-type-check", "comment.begin method=" + method.getName() //$NON-NLS-1$ //$NON-NLS-2$
            + " parameter=" + parameter.getName() + " resource=" + method.eResource()); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            if (method.eResource() instanceof XtextResource resource)
            {
                var services = resource.getResourceServiceProvider();
                var comments = services.get(BslMultiLineCommentDocumentationProvider.class);
                var projects = services.get(IV8ProjectManager.class);
                var project = projects.getProject(resource.getURI());
                boolean oldFormat = project == null || project.getProject() == null
                    || services.get(IBslPreferences.class)
                        .getDocumentCommentProperties(project.getProject()).oldCommentFormat();
                // Именно эта перегрузка используется в штатной подсказке параметров.
                var comment = BslCommentUtils.parseTemplateComment(comments.getCommentLines(method), method,
                    oldFormat);
                var field = comment.getParametersSection() != null
                    ? comment.getParametersSection().getParameterByName(parameter.getName()) : null;
                Global.tempLog("parameter-type-check", "comment.parsed oldFormat=" + oldFormat //$NON-NLS-1$ //$NON-NLS-2$
                    + " field=" + (field != null) + " lines=" + comments.getCommentLines(method)); //$NON-NLS-1$ //$NON-NLS-2$
                if (field != null)
                {
                    var models = services.get(IBmModelManager.class);
                    var naming = services.get(INamingService.class);
                    var scopeProvider = services.get(IScopeProvider.class);
                    var scope = scopeProvider.getScope(method, McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
                    var converter = services.get(IQualifiedNameConverter.class);
                    var model = models.getModel(resource.getURI());
                    if (model != null)
                    {
                        var task = new AbstractBmTask<List<TypeItem>>("comfort.expectedParameterTypes") //$NON-NLS-1$
                        {
                            @Override
                            public List<TypeItem> execute(IBmTransaction transaction, IProgressMonitor monitor)
                            {
                                var context = new BmOperationContext(naming, models, transaction);
                                List<TypeItem> result = new ArrayList<>();
                                for (var section : field.getTypeSections())
                                    result.addAll(comment.computeTypeSectionTypes(section, scope, scopeProvider,
                                        converter, comments, projects, oldFormat, method, context));
                                return result;
                            }
                        };
                        if (Global.invoke(model.getEngine(), "getCurrentTransaction") //$NON-NLS-1$
                            instanceof IBmTransaction currentTransaction)
                            documented = task.execute(currentTransaction, null);
                        else
                            documented = model.executeReadonlyTask(task, true);
                    }
                }
            }
        }
        catch (RuntimeException error)
        {
            Global.tempLogException("parameter-type-check", "comment.error method=" + method.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + " parameter=" + parameter.getName(), error); //$NON-NLS-1$
            // Контекст недоступен: вызывающий код использует прежний путь расчёта типов.
        }
        Global.tempLog("parameter-type-check", "comment.end method=" + method.getName() //$NON-NLS-1$ //$NON-NLS-2$
            + " parameter=" + parameter.getName() + " types=" + documented); //$NON-NLS-1$ //$NON-NLS-2$
        return documented;
    }
}
