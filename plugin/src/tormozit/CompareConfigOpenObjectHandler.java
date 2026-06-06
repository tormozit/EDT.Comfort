package tormozit;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IActiveComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

/**
 * Открывает объект конфигурации выбранный в дереве сравнения EDT.
 *
 * Алгоритм:
 * 1. Получаем IComparisonSession из поля comparisonArtifactsList редактора
 * 2. Получаем MatchedObjectsComparisonNode из comparisonView
 * 3. Берём mainObjectId (bmId) из узла
 * 4. Получаем EObject через IActiveComparisonDataSource.getObjectById()
 * 5. Открываем через OpenHelper
 */
public class CompareConfigOpenObjectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        openObject(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
        return null;
    }

    /**
     * Основная логика вынесена в static, чтобы её можно было вызывать
     * напрямую с захваченным IEditorPart — без зависимости
     * от фокуса / activeContexts / activeWhen.
     */
    public static void openObject(IEditorPart editor, Shell shell) {
        // Создаем временный экземпляр-хелпер, так как он больше не зарегистрирован как SelectionProvider
        CompareConfigSelectionListener helper = new CompareConfigSelectionListener(editor);
        EObject eObject = helper.resolveEObject(getSelection(editor), null, true);
        
        if (eObject != null) {
            openInEditor(eObject, editor, shell);
        } else {
            // Тихо выходим или можно добавить showError(shell, "Не удалось получить объект для узла");
        }
    }
    
    public static void showInNavigator(IEditorPart editor, Shell shell) {
        // Аналогично используем как хелпер для вызова логики подсветки в Навигаторе
        CompareConfigSelectionListener helper = new CompareConfigSelectionListener(editor);
        helper.showObjectInNavigator(getSelection(editor), true);
    }

    public static ISelection getSelection(IEditorPart editor) {
        // Через comparisonView -> treeViewer -> selection
        ISelection sel = null;
        Object view = Global.getField(editor, "comparisonView");
        if (view instanceof DtComparisonView) {
            Object treeControl = ((DtComparisonView) view).getTreeControl();
            if (treeControl != null) {
                Object viewer = Global.call(treeControl, "getTreeViewer");
                if (viewer != null)
                {
                    sel = (ISelection) Global.call(viewer, "getSelection");
                }
            }
        }
        return sel;
    }
    
    public static EObject getEObject(IComparisonSession session, long bmId, MatchedObjectsComparisonNode node) {

        // Определяем сторону: MAIN если mainObjectId есть, иначе OTHER
        ComparisonSide side = (node.getMainObjectId() != null && node.getMainObjectId() != -1L)
            ? ComparisonSide.MAIN
            : ComparisonSide.OTHER;

        IComparisonDataSource dataSource = session.getDataSource(side);
        if (dataSource instanceof IActiveComparisonDataSource) {
            EObject obj = ((IActiveComparisonDataSource) dataSource).getObjectById(bmId);
            if (obj != null) return obj;
        }

        // Попробуем другую сторону
        ComparisonSide otherSide = (side == ComparisonSide.MAIN)
            ? ComparisonSide.OTHER : ComparisonSide.MAIN;
        IComparisonDataSource otherSource = session.getDataSource(otherSide);
        if (otherSource instanceof IActiveComparisonDataSource) {
            Long otherId = (otherSide == ComparisonSide.MAIN)
                ? node.getMainObjectId() : node.getOtherObjectId();
            if (otherId != null && otherId != -1L) {
                return ((IActiveComparisonDataSource) otherSource).getObjectById(otherId);
            }
        }
        return null;
    }

    public static void openInEditor(EObject eObject, IEditorPart editor, Shell shell) {
        // Используем OpenHelper из EDT UI
        try {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper");
            Object helper = cls.getConstructor().newInstance();
            // openEditor(EObject) — основной метод
            for (Method m : cls.getMethods()) {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isAssignableFrom(eObject.getClass())) {
                    Object result = m.invoke(helper, eObject);
                    if (result != null) return;
                }
            }
            // openEditor(EObject, IWorkbenchPage)
            for (Method m : cls.getMethods()) {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2) continue;
                try {
                    m.invoke(helper, eObject, editor.getSite().getPage());
                    return;
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException e) {
            showError(shell, "OpenHelper не найден: " + e.getMessage());
            return;
        } catch (Exception e) {
            showError(shell, "Ошибка OpenHelper: " + e.getMessage());
            return;
        }

        showError(shell, "Объект найден, но редактор не открылся.\nТип: "
            + eObject.getClass().getName());
    }

    // ---- Utility ----

    private static void showError(Shell shell, String msg) {
        try {
            MessageDialog.openInformation(shell, "Открыть объект", msg);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Получение IProject из редактора сравнения
    // =========================================================================

    /**
     * Возвращает {@link IProject} основной (MAIN) стороны сравнения.
     *
     * <p>Пробует три стратегии (в порядке убывания надёжности):
     * <ol>
     *   <li>Вызов {@code getProject()} / {@code getDtProject().getWorkspaceProject()}
     *       на {@link IComparisonDataSource} каждой из сторон.</li>
     *   <li>Вызов {@code getProject()} на самой сессии.</li>
     *   <li>Извлечение имени проекта из URI ресурса выбранного EObject.</li>
     * </ol>
     *
     * @return проект или {@code null} если не удалось определить
     */
    public static IProject getProjectFromEditor(IEditorPart editor)
    {
        IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
        if (session == null)
            return null;

        // Стратегия 1: через источник данных каждой из сторон
        for (ComparisonSide side : ComparisonSide.values())
        {
            IProject p = projectFromDataSource(session.getDataSource(side));
            if (p != null)
                return p;
        }

        // Стратегия 2: через метод сессии
        Object proj = Global.call(session, "getProject"); //$NON-NLS-1$
        if (proj instanceof IProject)
            return (IProject)proj;

        // Стратегия 3: из URI ресурса выделенного EObject
        ISelection sel = getSelection(editor);
        if (sel instanceof IStructuredSelection)
        {
            Object element = ((IStructuredSelection)sel).getFirstElement();
            MatchedObjectsComparisonNode node = CompareConfigSelectionListener.resolveMatchedNode(element);
            if (node != null)
            {
                Long bmId = node.getMainObjectId();
                if (bmId == null || bmId == -1L)
                    bmId = node.getOtherObjectId();
                if (bmId != null && bmId != -1L)
                {
                    EObject eObj = getEObject(session, bmId, node);
                    IProject p = projectFromEObject(eObj);
                    if (p != null)
                        return p;
                }
            }
        }

        return null;
    }
    /** Получает {@link IProject} из источника данных сессии через рефлексию. */
    private static IProject projectFromDataSource(IComparisonDataSource ds)
    {
        if (ds == null) return null;

        Object proj = Global.call(ds, "getProject"); //$NON-NLS-1$
        if (proj instanceof IProject) return (IProject) proj;

        Object dtProj = Global.call(ds, "getDtProject"); //$NON-NLS-1$
        if (dtProj != null)
        {
            Object wp = Global.call(dtProj, "getWorkspaceProject"); //$NON-NLS-1$
            if (wp instanceof IProject) return (IProject) wp;
        }

        Object ns = Global.call(ds, "getNamespace"); //$NON-NLS-1$
        if (ns != null)
        {
            Object p = Global.call(ns, "getProject"); //$NON-NLS-1$
            if (p instanceof IProject) return (IProject) p;
        }
        return null;
    }

    /** Извлекает {@link IProject} из platform-resource URI ресурса EObject. */
    private static IProject projectFromEObject(EObject obj)
    {
        if (obj == null || obj.eResource() == null) return null;
        URI uri = obj.eResource().getURI();
        if (!uri.isPlatformResource()) return null;

        // "/ProjectName/src/..."
        String platformPath = uri.toPlatformString(true);
        if (platformPath == null) return null;

        // Берём сегмент после ведущего "/"
        String name = platformPath.startsWith("/") ? platformPath.substring(1) : platformPath; //$NON-NLS-1$
        int slash = name.indexOf('/');
        if (slash > 0) name = name.substring(0, slash);

        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        return (p != null && p.exists()) ? p : null;
    }
}