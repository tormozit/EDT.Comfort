package tormozit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.swt.widgets.Display;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Правит один {@code Form.xml} в формате конфигуратора 8.5: убирает лишний
 * {@code ButtonImportance}, добавленный конвертером кнопкам, у которых его нет
 * в исходной EDT-форме ({@code Form.form}) (issue 1C-Company/1c-edt-issues#2157).
 * Используется двумя источниками дампа: диалогом «Обновление конфигурации
 * в приложениях» ({@link DeployConfigurationFixHook}) и мастером «Экспорт»
 * ({@link ExportConfigurationXmlFixHook}).
 */
public final class FormXmlPatcher
{
    private static final String EXT_SEGMENT = "Ext"; //$NON-NLS-1$
    private static final String FORM_FILE_NAME = "Form.form"; //$NON-NLS-1$
    private static final String TEMP_LOG_TOPIC = "DeployConfigFix"; //$NON-NLS-1$
    private static final String TOAST_TITLE = "Комфорт"; //$NON-NLS-1$

    private FormXmlPatcher()
    {
    }

    /**
     * Тост с итогом патча — общий для {@link DeployConfigurationFixHook} и
     * {@link ExportConfigurationXmlFixHook}, чтобы не дублировать формулировку и
     * склонение в обоих хуках.
     */
    public static void showResultToast(int fixedCount, int errorCount)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            String message = fixedCount == 0
                ? "Проблем, требующих исправления, не найдено" //$NON-NLS-1$
                : "Исправлено " + fixedCount + " выгруженных xml " //$NON-NLS-1$ //$NON-NLS-2$
                    + Global.russianPlural(fixedCount, "файл", "файла", "файлов"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (errorCount > 0)
                message += "\nОшибок при исправлении: " + errorCount; //$NON-NLS-1$
            ToastNotification.show(TOAST_TITLE, message, 6_000);
        });
    }

    /** @return {@code true}, если файл реально был изменён и перезаписан. */
    public static boolean patch(Path formXmlPath) throws Exception
    {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc;
        try (var in = Files.newInputStream(formXmlPath))
        {
            doc = db.parse(in);
        }

        boolean changed = removeSpuriousButtonImportance(doc, formXmlPath);

        if (!changed)
            return false;

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); //$NON-NLS-1$
        try (var out = Files.newOutputStream(formXmlPath))
        {
            transformer.transform(new DOMSource(doc), new StreamResult(out));
        }
        Global.tempLog(TEMP_LOG_TOPIC, "patched " + formXmlPath); //$NON-NLS-1$
        return true;
    }

    private static boolean removeSpuriousButtonImportance(Document doc, Path formXmlPath)
    {
        NodeList buttons = doc.getElementsByTagName("Button"); //$NON-NLS-1$
        if (buttons.getLength() == 0)
            return false;

        Document sourceForm = loadSourceForm(formXmlPath);
        boolean changed = false;
        for (int i = 0; i < buttons.getLength(); i++)
        {
            Element button = (Element) buttons.item(i);
            Element importance = findDirectChild(button, "ButtonImportance"); //$NON-NLS-1$
            if (importance == null)
                continue;
            String name = button.getAttribute("name"); //$NON-NLS-1$
            if (sourceForm != null && sourceButtonHasImportance(sourceForm, name))
                continue; // в исходнике buttonImportance реально задан - не трогаем
            if (sourceForm == null)
                continue; // не нашли исходную форму - на всякий случай не рискуем и не трогаем
            button.removeChild(importance);
            changed = true;
        }
        return changed;
    }

    private static Element findDirectChild(Element parent, String localName)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (!(n instanceof Element))
                continue;
            Element el = (Element) n;
            String local = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            if (localName.equals(local))
                return el;
        }
        return null;
    }

    /**
     * Путь дампа: {@code .../<Категория>/<Объект>/Forms/<Форма>/Ext/Form.xml} (обычный
     * объект) или {@code .../CommonForms/<Форма>/Ext/Form.xml} (общая форма).
     * Путь исходника EDT: тот же хвост минус {@code Ext}, с расширением {@code .form}
     * вместо {@code .xml} — предположение, не проверено на живом проекте.
     */
    private static Document loadSourceForm(Path formXmlPath)
    {
        Path parent = formXmlPath.getParent(); // .../Forms/<Форма>/Ext
        if (parent == null || !EXT_SEGMENT.equals(parent.getFileName().toString()))
            return null;
        Path formDir = parent.getParent(); // .../Forms/<Форма>
        if (formDir == null)
            return null;

        IFile formFile = findFormFormByTailPath(formDir);
        if (formFile == null || !formFile.exists())
            return null;

        try (var in = formFile.getContents())
        {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            return dbf.newDocumentBuilder().parse(in);
        }
        catch (Exception e)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "loadSourceForm: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Хвост пути формы в дампе (без {@code Ext/Form.xml}) бывает разной длины:
     * обычный объект метаданных — {@code <Категория>/<Объект>/Forms/<Форма>}
     * (4 сегмента), общая форма — {@code CommonForms/<Форма>} (2 сегмента, без
     * промежуточного объекта и без сегмента {@code Forms}). Пробуем оба варианта
     * длины хвоста, начиная с более специфичного (длинного).
     */
    private static IFile findFormFormByTailPath(Path dumpFormDir)
    {
        List<String> fullTail = tailSegments(dumpFormDir, 4);
        if (fullTail.size() == 4)
        {
            IFile found = findUniqueByTail(fullTail);
            if (found != null)
                return found;
        }
        List<String> shortTail = tailSegments(dumpFormDir, 2);
        if (shortTail.size() == 2)
            return findUniqueByTail(shortTail);
        return null;
    }

    private static List<String> tailSegments(Path dir, int count)
    {
        List<String> tail = new ArrayList<>();
        Path cur = dir;
        for (int i = 0; i < count && cur != null; i++)
        {
            tail.add(0, cur.getFileName().toString());
            cur = cur.getParent();
        }
        return tail;
    }

    private static IFile findUniqueByTail(List<String> tail)
    {
        List<IFile> found = new ArrayList<>();
        try
        {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (!project.isOpen())
                    continue;
                project.accept((IResourceVisitor) resource ->
                {
                    if (resource.getType() != IResource.FILE)
                        return true;
                    if (!FORM_FILE_NAME.equals(resource.getName()))
                        return true;
                    IPath path = resource.getFullPath();
                    int n = path.segmentCount();
                    int need = tail.size() + 1; // +1 на сам файл Form.form
                    if (n < need)
                        return true;
                    boolean match = true;
                    for (int i = 0; i < tail.size(); i++)
                    {
                        if (!path.segment(n - 2 - i).equals(tail.get(tail.size() - 1 - i)))
                        {
                            match = false;
                            break;
                        }
                    }
                    if (match)
                        found.add((IFile) resource);
                    return true;
                });
            }
        }
        catch (Exception e)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "findUniqueByTail: " + e.getMessage()); //$NON-NLS-1$
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    private static boolean sourceButtonHasImportance(Document sourceForm, String buttonName)
    {
        if (buttonName == null || buttonName.isEmpty())
            return true; // не смогли сопоставить - на всякий случай не трогаем
        NodeList items = sourceForm.getElementsByTagName("items"); //$NON-NLS-1$
        for (int i = 0; i < items.getLength(); i++)
        {
            Element item = (Element) items.item(i);
            Element nameEl = findDirectChild(item, "name"); //$NON-NLS-1$
            if (nameEl == null || !buttonName.equals(nameEl.getTextContent()))
                continue;
            return findDirectChild(item, "buttonImportance") != null; //$NON-NLS-1$
        }
        return true; // кнопка не найдена в исходнике - не трогаем на всякий случай
    }
}
