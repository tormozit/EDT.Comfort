package tormozit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

/**
 * Индикация в дереве «Структура проекта» для папок, содержащих файл {@code .mdo}:
 * <ul>
 * <li>{@code <объект>} — объект привязан к конфигурации: его полное имя встречается
 * в {@code Configuration.mdo}
 * (см. {@link GitChangedFileMenuHook#isAttachedToConfiguration});</li>
 * <li>{@code <?>} — mdo-файл есть на диске, но объекта в конфигурации нет.</li>
 * </ul>
 * Папки дочерних объектов (внутри {@code Forms} / {@code Commands} / {@code Templates})
 * своего mdo не имеют — для них связь определяется по mdo объекта-владельца
 * (см. {@link #isChildObjectIntegrated}), суффиксы те же.
 * <p>
 * Для папок-групп без своего mdo (Tasks, Catalogs, Forms, Commands…) выводится RU мн.ч.
 * типа МД ({@link MdTypeMapping#ruSingularToGroupPlural}). Элементы этого дерева — обычные
 * {@link IFolder}/{@link IFile} (не модельные обёртки), поэтому проверка идёт не
 * через резолв модели элемента навигатора, а через путь к файлу на диске.
 */
public final class MdObjectUsageDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.mdObjectUsageDecorator"; //$NON-NLS-1$
    private static final String PREF_AUTO_ENABLED = "tormozit.mdObjectUsageDecorator.autoEnabled"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(MdObjectUsageDecorator::ensureEnabledOnce);
    }

    private static void ensureEnabledOnce()
    {
        Activator activator = Activator.getDefault();
        IPreferenceStore store = activator != null ? activator.getPreferenceStore() : null;
        if (store == null || store.getBoolean(PREF_AUTO_ENABLED))
            return;
        try
        {
            IDecoratorManager manager = PlatformUI.getWorkbench().getDecoratorManager();
            if (!manager.getEnabled(DECORATOR_ID))
                manager.setEnabled(DECORATOR_ID, true);
        }
        catch (Exception ex)
        {
            Global.log("MdObjectUsageDecorator ensureEnabledOnce error: " + ex); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PREF_AUTO_ENABLED, true);
        }
    }

    @Override
    public void decorate(Object element, IDecoration decoration)
    {
        IResource resource = NavigatorResourceResolver.resolve(element);
        IFile mdoFile = findMdoFile(resource);
        if (mdoFile == null)
        {
            if (resource instanceof IFolder folder)
            {
                Boolean childIntegrated = isChildObjectIntegrated(folder);
                if (childIntegrated != null)
                {
                    decoration.addSuffix(childIntegrated.booleanValue() ? " <объект>" : " <?>"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                String groupLabel = groupLabelFor(folder);
                if (groupLabel != null)
                    decoration.addSuffix(" <" + groupLabel + ">"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return;
        }

        boolean integrated = isIntegrated(mdoFile);
        decoration.addSuffix(integrated ? " <объект>" : " <?>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Папка дочернего объекта — формы, команды или макета (лежит внутри {@code Forms} /
     * {@code Commands} / {@code Templates} папки объекта-владельца). Своего mdo у неё нет,
     * поэтому связь с родителем определяется по mdo владельца
     * (см. {@link GitChangedFileMenuHook#isChildObjectDeclared}).
     *
     * @return {@code null}, если это не папка дочернего объекта — тогда применяется обычная
     *         логика подписи папки-группы
     */
    private static Boolean isChildObjectIntegrated(IFolder folder)
    {
        if (!(folder.getParent() instanceof IFolder collectionFolder))
            return null;
        String enSing = MdTypeMapping.folderToEnSing(collectionFolder.getName());
        String containerTag = enSing != null ? MdTypeMapping.subObjectTypeToEmfFeature(enSing) : null;
        if (containerTag == null)
            return null;

        if (!(collectionFolder.getParent() instanceof IFolder ownerFolder))
            return null;
        IFile ownerMdo = NavigatorResourceResolver.findMdoFileInFolder(ownerFolder);
        if (ownerMdo == null)
            return null;

        return Boolean.valueOf(
            GitChangedFileMenuHook.isChildObjectDeclared(ownerMdo, containerTag, folder.getName()));
    }

    /** RU мн.ч. типа МД для папки-группы (например «Tasks» → «Задачи»), либо {@code null}. */
    private static String groupLabelFor(IFolder folder)
    {
        String ru = MdTypeMapping.folderToRu(folder.getName());
        if (ru == null)
            return null;
        String plural = MdTypeMapping.ruSingularToGroupPlural(ru);
        return plural != null ? plural : ru;
    }

    private static IFile findMdoFile(IResource resource)
    {
        return resource instanceof IFolder folder ? NavigatorResourceResolver.findMdoFileInFolder(folder) : null;
    }

    /**
     * {@code Configuration.mdo} — сам корень конфигурации, у которого нет «объекта-родителя» —
     * считаем интегрированным всегда, отдельного смысла у «неинтегрированного» корня нет.
     */
    private static boolean isIntegrated(IFile mdoFile)
    {
        if ("Configuration.mdo".equalsIgnoreCase(mdoFile.getName())) //$NON-NLS-1$
            return true;
        return GitChangedFileMenuHook.isAttachedToConfiguration(mdoFile);
    }
}
