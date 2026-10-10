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
 * <li>{@code <?>} — папка объекта есть на диске, но объекта в конфигурации нет.</li>
 * </ul>
 * Папки дочерних объектов (внутри {@code Forms} / {@code Commands} / {@code Templates})
 * своего mdo не имеют — для них связь определяется по mdo объекта-владельца
 * (см. {@link #isChildObjectIntegrated}), суффиксы те же.
 * <p>
     * Для папок-групп без своего mdo (Tasks, Catalogs, Forms, Commands…) выводится RU мн.ч.
     * типа МД ({@link MdTypeMapping#folderToGroupPlural}). Элементы этого дерева — обычные
 * {@link IFolder}/{@link IFile} (не модельные обёртки), поэтому проверка идёт не
 * через резолв модели элемента навигатора, а через путь к файлу на диске.
 */
public final class MdObjectUsageDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.mdObjectUsageDecorator"; //$NON-NLS-1$
    /**
     * v2: прежний ключ {@code …autoEnabled} мог остаться {@code true} после экспериментального
     * {@code setEnabled(false)} (август 2026) — тогда повторно декоратор уже не включался, а в
     * {@code ENABLED_DECORATORS} рабочей области оставался {@code …mdObjectUsageDecorator:false}.
     * Новый ключ один раз снова принудительно включает декоратор.
     */
    private static final String PREF_AUTO_ENABLED = "tormozit.mdObjectUsageDecorator.autoEnabled.v2"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(MdObjectUsageDecorator::ensureEnabledOnce);
    }

    /**
     * Декоратор {@code state="true"} в plugin.xml задаёт значение по умолчанию только для
     * никогда не виденного id; если Eclipse (или наш код) однажды записал {@code false} в
     * настройки рабочей области — суффиксы {@code <объект>}/{@code <?>} пропадают и в
     * «Структуре проекта», и в дереве поиска по файлам, пока декоратор снова не включат.
     */
    private static void ensureEnabledOnce()
    {
        Activator activator = Activator.getDefault();
        IPreferenceStore store = activator != null ? activator.getPreferenceStore() : null;
        try
        {
            IDecoratorManager manager = PlatformUI.getWorkbench().getDecoratorManager();
            boolean enabled = manager.getEnabled(DECORATOR_ID);
            if (store != null && store.getBoolean(PREF_AUTO_ENABLED))
                return;
            if (!enabled)
                manager.setEnabled(DECORATOR_ID, true);
        }
        catch (Exception ex)
        {
            Global.log("MdObjectUsageDecorator ensureEnabledOnce error: " + ex); //$NON-NLS-1$
        }
        finally
        {
            if (store != null)
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
                IFile expectedMdo = expectedTopLevelMdo(folder);
                if (expectedMdo != null && !isIntegrated(expectedMdo))
                {
                    decoration.addSuffix(" <?>"); //$NON-NLS-1$
                    return;
                }
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

    /** Получит ли ресурс суффикс {@code <?>} (те же условия, что в {@link #decorate}). */
    static boolean isOrphan(IResource resource)
    {
        if (!(resource instanceof IFolder folder))
            return false;
        IFile mdoFile = findMdoFile(folder);
        if (mdoFile != null)
            return !isIntegrated(mdoFile);
        IFile expectedMdo = expectedTopLevelMdo(folder);
        if (expectedMdo != null && !isIntegrated(expectedMdo))
            return true;
        Boolean childIntegrated = isChildObjectIntegrated(folder);
        return childIntegrated != null && !childIntegrated.booleanValue();
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
        // Реквизиты формы (и её команды) лежат в Form.form, а не в mdo владельца — по mdo судить нельзя.
        if (!"forms".equals(containerTag) && !"templates".equals(containerTag) //$NON-NLS-1$ //$NON-NLS-2$
            && !"commands".equals(containerTag)) //$NON-NLS-1$
            return null;

        if (!(collectionFolder.getParent() instanceof IFolder ownerFolder))
            return null;
        if (ownerFolder.getParent() instanceof IFolder ownerGroup && "CommonForms".equals(ownerGroup.getName())) //$NON-NLS-1$
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
        return MdTypeMapping.folderToGroupPlural(folder.getName());
    }

    /** Путь описателя объекта верхнего уровня, даже если сам файл уже отсутствует. */
    private static IFile expectedTopLevelMdo(IFolder folder)
    {
        org.eclipse.core.runtime.IPath path = folder.getProjectRelativePath();
        int count = path.segmentCount();
        boolean configurationObject = count == 3 && "src".equals(path.segment(0)); //$NON-NLS-1$
        boolean extensionObject = count == 5 && "src".equals(path.segment(0)) //$NON-NLS-1$
            && "ext".equals(path.segment(1)); //$NON-NLS-1$
        if (!configurationObject && !extensionObject)
            return null;
        String type = MdTypeMapping.folderToEnSing(path.segment(count - 2));
        if (type == null || MdTypeMapping.subObjectTypeToEmfFeature(type) != null)
            return null;
        return folder.getFile(folder.getName() + ".mdo"); //$NON-NLS-1$
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
