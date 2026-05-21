

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
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.util.MergeUiUtils;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com.google.inject.Inject;

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
        Path pathMain = getSideFile(editor, element, ComparisonSide.MAIN); // mxlx
        Path pathOther = getSideFile(editor, element, ComparisonSide.OTHER); // mxlx
        Path exportDirectory;
        try
        {
            exportDirectory = Files.createTempDirectory("tormozit_");
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return;
        }
        IComparisonSession compSession = CompareConfigSelectionProvider.getSession(editor);
        IRApplicationRegistry.IrSession irSession = IRApplicationRegistry.getSession(compSession.getDataSource(ComparisonSide.MAIN).getDtProject());
        Object irClient = irSession.getModule("ирКлиент"); 
        ComJacobBridge.invoke(irClient, "СравнитьТабличныеДокументыИмпортЛкс", pathMain, pathOther);
    }

    /**
     * Читает содержимое xmxl-файла через {@link ExternalPropertyUtils#getContentStream},
     * сохраняет его во временный файл и возвращает путь к нему.
     * <p>Имя временного файла строится по шаблону {@code tormozit_<side>_<имяФайла>.xmxl},
     * где имя файла берётся из относительного пути, полученного от
     * {@link ComparisonUtils#getFilePathBySymlink}. Это упрощает отладку.
     * @return абсолютный путь к временному файлу, или {@code null} если поток недоступен
     */
    public static Path getSideFile(IEditorPart editor, Object element, ComparisonSide side)
    {
        IComparisonSession session = CompareConfigSelectionProvider.getSession(editor);
        ExternalPropertyComparisonNode matchedNode = (ExternalPropertyComparisonNode) CompareConfigSelectionProvider.resolveMatchedNode(element);
        BundleContext ctx = Reflect.ourContext();
        ServiceReference<?> ref = ctx.getServiceReference(IComparisonManager.class);
        Object manager = ctx.getService(ref);
        IQualifiedNameFilePathConverter filePathConverter = (IQualifiedNameFilePathConverter) Reflect.getField(manager, "qualifiedNameFilePathConverter");

        InputStream stream = ExternalPropertyUtils.getContentStream(matchedNode, session, side, filePathConverter);
        if (stream == null)
            return null;

        // Определяем имя файла для временного файла
        String symlink = matchedNode.getSymlink(side);
        String qualifyingType = ((SolidResourceComparisonNode) matchedNode).getQualifyingType(side);
        Path relativePath = (Path) ComparisonUtils.getFilePathBySymlink(symlink, qualifyingType, filePathConverter);
        String fileName = relativePath != null ? relativePath.getFileName().toString() : "content.xmxl"; //$NON-NLS-1$
        String prefix = "tormozit_" + side.name().toLowerCase() + "_"; //$NON-NLS-1$ //$NON-NLS-2$
        String suffix = "_" + fileName; //$NON-NLS-1$
        try {
            Path tempFile = Files.createTempFile(prefix, suffix);
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (IOException e) {
            Reflect.log("getSideFile: не удалось записать временный файл: " + e.getMessage()); //$NON-NLS-1$
            return null;
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    public static ISelection getSelection(IEditorPart editor) {
        // Через comparisonView -> treeViewer -> selection
        ISelection sel = null;
        Object view = Reflect.getField(editor, "comparisonView");
        if (view instanceof DtComparisonView) {
            Object treeControl = ((DtComparisonView) view).getTreeControl();
            if (treeControl != null) {
                Object viewer = Reflect.call(treeControl, "getTreeViewer");
                if (viewer != null)
                {
                    sel = (ISelection) Reflect.call(viewer, "getSelection");
                }
            }
        }
        return sel;
    }

    private static void showError(Shell shell, String msg) {
        try {
            MessageDialog.openInformation(shell, "Открыть объект", msg);
        } catch (Exception ignored) {}
    }
}
