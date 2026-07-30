package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * «Показать в навигаторе» для файла, открытого в редакторе XML (CTRL+T).
 *
 * <p>Объект метаданных определяется по пути файла в проекте — тем же резолвером, что и для
 * git-представлений ({@link GitChangedFileMenuHook#resolveEObject(IFile)}), поэтому команда
 * работает для любого файла объекта ({@code .mdo}, {@code .form}, {@code .xml} и т.п.),
 * открытого в редакторе XML.
 *
 * <p>Пункт в контекстном меню добавляет {@link XmlEditorShowInNavigatorHook}.
 */
public class XmlEditorShowInNavigatorHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.xmlEditor.showInNavigator"; //$NON-NLS-1$

    /** Контекст привязки клавиш редактора структурированного текста WST (в т.ч. XML). */
    public static final String BINDING_CONTEXT_ID =
        "org.eclipse.wst.sse.ui.structuredTextEditorScope"; //$NON-NLS-1$

    static final String MENU_LABEL = "Показать в навигаторе"; //$NON-NLS-1$

    static final String MENU_TOOLTIP =
        "Показать в навигаторе объект метаданных, которому принадлежит файл"; //$NON-NLS-1$

    /** Редактор XML WST (страницы Design + Source). */
    private static final String XML_EDITOR_ID =
        "org.eclipse.wst.xml.ui.internal.tabletree.XMLMultiPageEditorPart"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (isXmlEditor(editor))
            showInNavigator(editor);
        return null;
    }

    /**
     * Редактор XML: multipage-редактор WST либо обычный текстовый редактор Workbench
     * с открытым в нём {@code .xml}.
     */
    static boolean isXmlEditor(IWorkbenchPart part)
    {
        if (!(part instanceof IEditorPart editor))
            return false;
        if (editor.getSite() != null && XML_EDITOR_ID.equals(editor.getSite().getId()))
            return true;
        if (!(editor instanceof ITextEditor))
            return false;
        IFile file = resolveFile(editor);
        return file != null && "xml".equalsIgnoreCase(file.getFileExtension()); //$NON-NLS-1$
    }

    static IFile resolveFile(IEditorPart editor)
    {
        if (editor == null)
            return null;
        IEditorInput input = editor.getEditorInput();
        return input != null ? input.getAdapter(IFile.class) : null;
    }

    /** Объект метаданных, которому принадлежит открытый файл, либо {@code null}. */
    static EObject resolveTarget(IEditorPart editor)
    {
        IFile file = resolveFile(editor);
        if (file == null || !file.exists())
            return null;
        return GitChangedFileMenuHook.resolveEObject(file);
    }

    static void showInNavigator(IEditorPart editor)
    {
        showInNavigator(resolveTarget(editor));
    }

    static void showInNavigator(EObject target)
    {
        if (target == null)
        {
            ToastNotification.show(MENU_LABEL,
                "Не удалось определить объект метаданных для файла"); //$NON-NLS-1$
            return;
        }
        NavigatorReveal.reveal(target, true);
    }
}
