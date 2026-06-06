package tormozit;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import org.eclipse.compare.internal.CompareEditorSelectionProvider;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.eclipse.emf.common.util.URI;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

import com._1c.g5.v8.dt.compare.core.ComparisonUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IActiveComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.merge.ExternalPropertyUtils;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.ExternalPropertyComparisonNode;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.ComparisonTreeControl;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.util.MergeUiUtils;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com.google.common.net.HttpHeaders.ReferrerPolicyValues;
import com.google.inject.Inject;

import javafx.scene.control.TreeView;

import com._1c.g5.v8.dt.compare.model.SolidResourceComparisonNode;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;

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
public class CompareConfigCompareInIRHandler extends AbstractHandler {
    @Inject
    private static IExportOperationFactory exportOperationFactory;
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        runCompare(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
        return null;
    }
    
    public static void runCompare(IEditorPart editor, Shell shell) {
        ISelection selection = getSelection(editor);
        Object element = ((IStructuredSelection) selection).getFirstElement();
        if (element == null)
            return;
        Path pathMain = getPropertySideFile(editor, element, ComparisonSide.MAIN); // mxlx
        if (pathMain == null)
        {
            ToastNotification.show("Сравнение метаданных ИР", "Поддерживаются свойства: ТабличныйДокумент.Макет");
            return;
        }
        Path pathOther = getPropertySideFile(editor, element, ComparisonSide.OTHER); // mxlx
        Path pathAncestor = getPropertySideFile(editor, element, ComparisonSide.COMMON_ANCESTOR); // mxlx
        IComparisonSession compSession = CompareConfigSelectionListener.getSession(editor);
        IRSession irSession = IRApplication.getSession(compSession.getDataSource(ComparisonSide.MAIN).getDtProject());
        if (irSession == null || irSession.executor == null) {
            return;
        }
        String ancestor = pathAncestor != null ?pathAncestor.toString() : null;
        irSession.executor.submit(() -> {
            try 
            {
                // Здесь мы находимся в родном потоке для этого COM-объекта. 
                Object irClient = irSession.getModule("ирКлиент");
                irSession.showWindow();
                ComBridge.invoke(irClient, "СравнитьТабличныеДокументыИмпортЛкс", pathMain.toString(), pathOther.toString(), ancestor);
            } 
            catch (Exception e) 
            {
                Global.log("Ошибка вызова ИР: " + e.getMessage());
            }
        });
    }

    /**
     * Читает содержимое xmxl-файла через {@link ExternalPropertyUtils#getContentStream},
     * сохраняет его во временный файл и возвращает путь к нему.
     * <p>Имя временного файла строится по шаблону {@code tormozit_<side>_<имяФайла>.xmxl},
     * где имя файла берётся из относительного пути, полученного от
     * {@link ComparisonUtils#getFilePathBySymlink}. Это упрощает отладку.
     * @return абсолютный путь к временному файлу, или {@code null} если поток недоступен
     */
    public static Path getPropertySideFile(IEditorPart editor, Object element, ComparisonSide side)
    {
        IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
        MatchedObjectsComparisonNode matchedNode = CompareConfigSelectionListener.resolveMatchedNode(element);
        ExternalPropertyComparisonNode properyNode;
        try
        {
            properyNode = (ExternalPropertyComparisonNode) matchedNode;
        }
        catch (Exception e)
        {
            return null;
        }
        BundleContext ctx = Global.ourContext();
        ServiceReference<?> ref = ctx.getServiceReference(IComparisonManager.class);
        Object manager = ctx.getService(ref);
        IQualifiedNameFilePathConverter filePathConverter = (IQualifiedNameFilePathConverter) Global.getField(manager, "qualifiedNameFilePathConverter");
        InputStream stream = ExternalPropertyUtils.getContentStream(properyNode, session, side, filePathConverter);
        if (stream == null)
            return null;
        String symlink = properyNode.getSymlink(side);
        String qualifyingType = ((SolidResourceComparisonNode) properyNode).getQualifyingType(side);
        Path relativePath = (Path) ComparisonUtils.getFilePathBySymlink(symlink, qualifyingType, filePathConverter);
        String fileName = relativePath != null ? relativePath.getFileName().toString() : "content.xmxl"; //$NON-NLS-1$
        String prefix = "tormozit_" + side.name().toLowerCase() + "_"; //$NON-NLS-1$ //$NON-NLS-2$
        String suffix = "_" + fileName; //$NON-NLS-1$
        try {
            Path tempFile = Files.createTempFile(prefix, suffix);
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (IOException e) {
            Global.log("getSideFile: не удалось записать временный файл: " + e.getMessage()); //$NON-NLS-1$
            return null;
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    public static ISelection getSelection(IEditorPart editor) {
        ISelection sel = null;
        DtComparisonView view = (DtComparisonView) Global.getField(editor, "comparisonView");
        if (view != null) {
            ComparisonTreeControl treeControl = view.getTreeControl();
            if (treeControl != null) {
                TreeViewer viewer = treeControl.getTreeViewer();
                if (viewer != null)
                {
                    sel = viewer.getSelection();
                }
            }
        }
        return sel;
    }
}
