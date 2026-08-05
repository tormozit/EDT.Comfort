package tormozit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.compare.CompareEditorInput;

/**
 * Удаляет дубли child-записей в {@code Configuration.mdo} (плоские теги вида
 * {@code <reports>Report.Имя</reports>}), из‑за которых обновление конфигурации БД
 * 1С падает с непрозрачной ошибкой «Дублирование имени объекта метаданных».
 * <p>Вызывается перед штатной выгрузкой из диалогов
 * {@link DeployConfigurationFixHook} и {@link ExportConfigurationXmlFixHook}
 * (независимо от флажка Form.xml). Текстовая правка — тот же приём, что orphan-fix
 * в «Заменить на HEAD» ({@link GitChangedFileMenuHook}).
 */
public final class ConfigurationMdoFix
{
    private static final String TEMP_LOG_TOPIC = "ConfigurationMdoFix"; //$NON-NLS-1$
    private static final String TOAST_TITLE = "Комфорт"; //$NON-NLS-1$
    private static final String ACTION_LABEL = "Показать различия"; //$NON-NLS-1$
    private static final String CONFIGURATION_MDO = "Configuration.mdo"; //$NON-NLS-1$
    private static final Pattern CHILD_LINE = Pattern.compile(
        "^(\\s*)<([A-Za-z][A-Za-z0-9]*)>([^<]+)</\\2>\\s*$"); //$NON-NLS-1$

    private ConfigurationMdoFix()
    {
    }

    /**
     * Находит проект по UI диалога (или активный), снимает дубли во всех
     * {@code Configuration.mdo}, при успехе показывает тост со ссылкой на diff.
     */
    public static void fixBeforeUnload(Shell shell)
    {
        IProject project = resolveProject(shell);
        if (project == null || !project.isAccessible())
        {
            Global.tempLog(TEMP_LOG_TOPIC, "fixBeforeUnload: project not resolved"); //$NON-NLS-1$
            return;
        }
        List<FixedFile> fixed = fixProject(project);
        if (!fixed.isEmpty())
            showToast(fixed, shell);
    }

    /** @return список исправленных файлов (пусто, если дублей не было). */
    public static List<FixedFile> fixProject(IProject project)
    {
        List<FixedFile> fixed = new ArrayList<>();
        if (project == null || !project.isAccessible())
            return fixed;
        for (IFile mdo : findConfigurationMdos(project))
        {
            try
            {
                FixedFile one = removeDuplicatesInFile(mdo);
                if (one != null)
                {
                    fixed.add(one);
                    Global.tempLog(TEMP_LOG_TOPIC, "file=" + mdo.getFullPath() //$NON-NLS-1$
                        + " removed=" + one.removedCount()); //$NON-NLS-1$
                }
            }
            catch (Exception e)
            {
                Global.tempLog(TEMP_LOG_TOPIC, "fix failed for " + mdo.getFullPath() + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return fixed;
    }

    static List<IFile> findConfigurationMdos(IProject project)
    {
        List<IFile> result = new ArrayList<>();
        IFile main = project.getFile("src/Configuration/" + CONFIGURATION_MDO); //$NON-NLS-1$
        if (main.exists())
            result.add(main);
        IFolder extRoot = project.getFolder("src/ext"); //$NON-NLS-1$
        if (!extRoot.exists())
            return result;
        try
        {
            for (IResource child : extRoot.members())
            {
                if (!(child instanceof IFolder))
                    continue;
                IFile extMdo = ((IFolder) child).getFile("Configuration/" + CONFIGURATION_MDO); //$NON-NLS-1$
                if (extMdo.exists())
                    result.add(extMdo);
            }
        }
        catch (Exception e)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "findConfigurationMdos ext: " + e); //$NON-NLS-1$
        }
        return result;
    }

    static FixedFile removeDuplicatesInFile(IFile configurationMdo) throws Exception
    {
        configurationMdo.refreshLocal(IResource.DEPTH_ZERO, null);
        String content = readContent(configurationMdo);
        if (content == null || content.isEmpty())
            return null;

        Set<String> knownTags = knownChildTags();
        String eol = content.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\r\n|\n", -1))); //$NON-NLS-1$
        Set<String> seenFqn = new HashSet<>();
        List<String> kept = new ArrayList<>(lines.size());
        int removed = 0;

        for (String line : lines)
        {
            Matcher m = CHILD_LINE.matcher(line);
            if (!m.matches())
            {
                kept.add(line);
                continue;
            }
            String tag = m.group(2);
            String fqn = m.group(3).trim();
            if (!knownTags.contains(tag) || fqn.indexOf('.') < 0)
            {
                kept.add(line);
                continue;
            }
            if (!seenFqn.add(fqn))
            {
                removed++;
                continue;
            }
            kept.add(line);
        }

        if (removed == 0)
            return null;

        String newContent = String.join(eol, kept);
        if (!isWellFormedXml(newContent))
        {
            Global.tempLog(TEMP_LOG_TOPIC, "XML validation failed, file not written: " //$NON-NLS-1$
                + configurationMdo.getFullPath());
            return null;
        }

        try (ByteArrayInputStream in =
            new ByteArrayInputStream(newContent.getBytes(StandardCharsets.UTF_8)))
        {
            configurationMdo.setContents(in, IResource.FORCE, null);
        }
        return new FixedFile(configurationMdo, content, newContent, removed);
    }

    private static Set<String> knownChildTags()
    {
        Set<String> tags = new LinkedHashSet<>();
        for (String folder : MdTypeMapping.getRuToFolderMap().values())
        {
            if (folder == null || folder.isEmpty())
                continue;
            tags.add(Character.toLowerCase(folder.charAt(0)) + folder.substring(1));
        }
        return tags;
    }

    private static IProject resolveProject(Shell shell)
    {
        IProject fromUi = findProjectInShell(shell);
        if (fromUi != null)
            return fromUi;
        try
        {
            return Global.getActiveProject(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(),
                false);
        }
        catch (Exception e)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "resolveProject fallback: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static IProject findProjectInShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Set<String> openNames = new HashSet<>();
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (p.isOpen())
                openNames.add(p.getName());
        }
        if (openNames.isEmpty())
            return null;

        List<String> candidates = new ArrayList<>();
        collectProjectNameCandidates(shell, candidates);
        for (String name : candidates)
        {
            if (name == null)
                continue;
            String trimmed = name.trim();
            if (openNames.contains(trimmed))
                return ResourcesPlugin.getWorkspace().getRoot().getProject(trimmed);
        }
        return null;
    }

    private static void collectProjectNameCandidates(Control root, List<String> out)
    {
        if (root == null || root.isDisposed())
            return;
        if (root instanceof Text)
            out.add(((Text) root).getText());
        else if (root instanceof Combo)
            out.add(((Combo) root).getText());
        if (root instanceof org.eclipse.swt.widgets.Composite)
        {
            for (Control child : ((org.eclipse.swt.widgets.Composite) root).getChildren())
                collectProjectNameCandidates(child, out);
        }
    }

    private static String readContent(IFile file) throws Exception
    {
        if (!file.exists())
            return null;
        try (InputStream in = file.getContents())
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isWellFormedXml(String content)
    {
        try
        {
            javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.parse(new org.xml.sax.InputSource(new StringReader(content)));
            return true;
        }
        catch (Exception e)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "isWellFormedXml: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static void showToast(List<FixedFile> fixed, Shell parentShell)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int removedCount = 0;
        for (FixedFile f : fixed)
            removedCount += f.removedCount();
        final int removed = removedCount;
        final List<FixedFile> snapshot = List.copyOf(fixed);
        // parentShell — кликабельность поверх modal Deploy/Export (см. ToastNotification).
        display.asyncExec(() ->
        {
            String message = "Удалено " + removed + " " //$NON-NLS-1$ //$NON-NLS-2$
                + Global.russianPlural(removed, "дубль", "дубля", "дублей") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " записей в Configuration.mdo"; //$NON-NLS-1$
            ToastNotification.show(TOAST_TITLE, message, 10_000,
                () -> openDiffs(snapshot), ACTION_LABEL, parentShell);
        });
    }

    private static void openDiffs(List<FixedFile> fixed)
    {
        for (FixedFile f : fixed)
        {
            try
            {
                String fileName = f.file().getName();
                TextCompareInput input = new TextCompareInput(
                    f.beforeContent(), f.afterContent(),
                    "До исправления", "После исправления", fileName); //$NON-NLS-1$ //$NON-NLS-2$
                CompareUI.openCompareEditor(input);
            }
            catch (Exception e)
            {
                Global.tempLog(TEMP_LOG_TOPIC, "openDiffs: " + e); //$NON-NLS-1$
                ToastNotification.show(TOAST_TITLE,
                    "Не удалось открыть сравнение: " + e.getMessage(), 5_000); //$NON-NLS-1$
            }
        }
    }

    record FixedFile(IFile file, String beforeContent, String afterContent, int removedCount)
    {
    }

    /** Двухстороннее сравнение строк — как SettingsFileCompareInput в CompareConfigMenuHook. */
    private static final class TextCompareInput extends CompareEditorInput
    {
        private final StringCompareElement leftElement;
        private final StringCompareElement rightElement;

        TextCompareInput(String leftText, String rightText, String leftLabel, String rightLabel,
            String fileName)
        {
            super(createConfiguration(leftLabel, rightLabel));
            String type = viewerType(fileName);
            leftElement = new StringCompareElement(fileName, leftText, type);
            rightElement = new StringCompareElement(fileName, rightText, type);
            setTitle(fileName);
        }

        private static CompareConfiguration createConfiguration(String leftLabel, String rightLabel)
        {
            CompareConfiguration config = new CompareConfiguration();
            config.setLeftEditable(false);
            config.setRightEditable(false);
            config.setLeftLabel(leftLabel != null ? leftLabel : "До"); //$NON-NLS-1$
            config.setRightLabel(rightLabel != null ? rightLabel : "После"); //$NON-NLS-1$
            return config;
        }

        private static String viewerType(String fileName)
        {
            if (fileName == null)
                return "txt"; //$NON-NLS-1$
            int dot = fileName.lastIndexOf('.');
            if (dot < 0 || dot == fileName.length() - 1)
                return "txt"; //$NON-NLS-1$
            return fileName.substring(dot + 1);
        }

        @Override
        protected Object prepareInput(IProgressMonitor monitor)
        {
            return new DiffNode(null, Differencer.CHANGE, null, leftElement, rightElement);
        }

        @Override
        public String getOKButtonLabel()
        {
            return IDialogConstants.CLOSE_LABEL;
        }

        @Override
        public boolean isSaveNeeded()
        {
            return false;
        }

        private static final class StringCompareElement
            implements ITypedElement, IStreamContentAccessor, IEncodedStreamContentAccessor
        {
            private final String name;
            private final String content;
            private final String type;

            StringCompareElement(String name, String content, String type)
            {
                this.name = name != null ? name : ""; //$NON-NLS-1$
                this.content = content != null ? content : ""; //$NON-NLS-1$
                this.type = type != null ? type : "txt"; //$NON-NLS-1$
            }

            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public Image getImage()
            {
                return null;
            }

            @Override
            public String getType()
            {
                return type;
            }

            @Override
            public InputStream getContents()
            {
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String getCharset()
            {
                return StandardCharsets.UTF_8.name();
            }
        }
    }
}
