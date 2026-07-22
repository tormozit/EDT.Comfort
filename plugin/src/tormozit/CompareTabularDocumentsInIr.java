package tormozit;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.compare.IResourceProvider;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Сравнение макетов табличного документа (файлы {@code .mxlx}) в приложении ИР —
 * COM {@code СравнитьТабличныеДокументыИмпортЛкс}. Общая точка для дерева сравнения
 * конфигураций и окон сравнения текстов.
 */
public final class CompareTabularDocumentsInIr
{
    /** Подпись в окнах сравнения текстов / 3-way диалоге макета. */
    public static final String MENU_LABEL = "Сравнить таблично ИР"; //$NON-NLS-1$

    public static final String TOOLTIP =
        "Открыть сравнение табличных документов (.mxlx) в приложении ИР"; //$NON-NLS-1$

    private CompareTabularDocumentsInIr()
    {
    }

    public static boolean isMxlxFileName(String name)
    {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mxlx"); //$NON-NLS-1$
    }

    /**
     * Расширение берём из {@link ITypedElement#getName()}, {@link ITypedElement#getType()},
     * поля {@code path} ({@code TextDocumentTemplateTypedElement}) или workspace-{@link IFile}
     * через {@link IResourceProvider}.
     */
    public static boolean isMxlxTypedElement(ITypedElement element)
    {
        if (element == null)
            return false;
        if (isMxlxFileName(element.getName()) || isMxlxFileName(dotExtension(element.getType())))
            return true;
        Object pathObj = Global.getField(element, "path"); //$NON-NLS-1$
        if (pathObj instanceof Path path)
        {
            Path fileName = path.getFileName();
            if (fileName != null && isMxlxFileName(fileName.toString()))
                return true;
        }
        IFile file = fileFromTypedElement(element);
        return file != null && isMxlxFileName(file.getName());
    }

    private static String dotExtension(String typeOrExt)
    {
        if (typeOrExt == null || typeOrExt.isBlank())
            return null;
        return typeOrExt.startsWith(".") ? typeOrExt : "." + typeOrExt; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static IFile fileFromTypedElement(ITypedElement element)
    {
        if (!(element instanceof IResourceProvider resourceProvider))
            return null;
        IResource resource = resourceProvider.getResource();
        return resource instanceof IFile file ? file : null;
    }

    /**
     * Существующий файл на диске из typed element, иначе содержимое во временный {@code .mxlx}.
     */
    public static Path resolveSideFile(ITypedElement element, String sideTag)
    {
        if (element == null)
            return null;
        Object pathObj = Global.getField(element, "path"); //$NON-NLS-1$
        if (pathObj instanceof Path path && Files.isRegularFile(path))
            return path;
        IFile workspaceFile = fileFromTypedElement(element);
        if (workspaceFile != null && workspaceFile.getLocation() != null)
        {
            Path location = workspaceFile.getLocation().toFile().toPath();
            if (Files.isRegularFile(location))
                return location;
        }
        if (!(element instanceof IStreamContentAccessor accessor))
            return null;
        String fileName = element.getName();
        if (!isMxlxFileName(fileName))
            fileName = "content.mxlx"; //$NON-NLS-1$
        String prefix = "tormozit_" + sideTag + "_"; //$NON-NLS-1$ //$NON-NLS-2$
        String suffix = "_" + fileName; //$NON-NLS-1$
        try (InputStream stream = accessor.getContents())
        {
            if (stream == null)
                return null;
            Path tempFile = Files.createTempFile(prefix, suffix);
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
        catch (CoreException | java.io.IOException e)
        {
            Global.log("CompareTabularDocumentsInIr: не удалось получить файл стороны: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Двухстороннее сравнение: в ИР первым идёт «Текущий», вторым — более новый «Новый».
     * Если в UI более новый слева (порядок отличается от ИР) — стороны меняются местами
     * и показывается уведомление с обозначениями.
     *
     * @param uiLeftIsNewer {@code true}, если левая сторона UI — более новый файл
     */
    public static void runCompareTwoSide(IDtProject dtProject, Path uiLeft, Path uiRight,
        String uiLeftLabel, String uiRightLabel, boolean uiLeftIsNewer, boolean connectIfAbsent)
    {
        Path current;
        Path newer;
        String currentLabel;
        String newerLabel;
        boolean swapped;
        if (uiLeftIsNewer)
        {
            current = uiRight;
            newer = uiLeft;
            currentLabel = uiRightLabel;
            newerLabel = uiLeftLabel;
            swapped = true;
        }
        else
        {
            current = uiLeft;
            newer = uiRight;
            currentLabel = uiLeftLabel;
            newerLabel = uiRightLabel;
            swapped = false;
        }
        runCompare(dtProject, current, newer, null, connectIfAbsent);
        if (swapped)
        {
            String currentDisplay = CompareCurrentLinesPanel.sideLabelForCurrentLines(currentLabel);
            String newerDisplay = CompareCurrentLinesPanel.sideLabelForCurrentLines(newerLabel);
            ToastNotification.show("Обозначения сторон в ИР:", //$NON-NLS-1$
                "Текущий - " + currentDisplay + ", Новый - " + newerDisplay); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static void runCompare(IDtProject dtProject, Path pathMain, Path pathOther, Path pathAncestor,
        boolean connectIfAbsent)
    {
        if (pathMain == null || pathOther == null)
        {
            if (connectIfAbsent)
                ToastNotification.show(MENU_LABEL, "Не удалось получить файлы сторон сравнения"); //$NON-NLS-1$
            return;
        }
        if (dtProject == null)
        {
            if (connectIfAbsent)
                ToastNotification.show(MENU_LABEL, "Не удалось определить активный проект EDT"); //$NON-NLS-1$
            return;
        }
        IRSession irSession = IRApplication.getSession(dtProject, connectIfAbsent);
        if (irSession == null || irSession.executor == null)
            return;
        String ancestor = pathAncestor != null ? pathAncestor.toString() : null;
        irSession.executor.submit(() ->
        {
            try
            {
                Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                irSession.showWindow();
                ComBridge.invoke(irClient, "СравнитьТабличныеДокументыИмпортЛкс", //$NON-NLS-1$
                    pathMain.toString(), pathOther.toString(), ancestor);
            }
            catch (Exception e)
            {
                Global.log("CompareTabularDocumentsInIr: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }
}
