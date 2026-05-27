import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
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
        EObject eObject = helper.resolveEObject(getSelection(editor), null);
        
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
}