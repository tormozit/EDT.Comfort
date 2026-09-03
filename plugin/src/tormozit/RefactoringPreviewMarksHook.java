package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;

import com._1c.g5.v8.dt.lcore.refactoring.IFullTextSearchChange;

/**
 * Начальная расстановка пометок на странице «Вносимые изменения» мастера рефакторинга —
 * окна «Рефакторинг», «Переименовать элемент», «Мастер переименования».
 *
 * <p>Вхождения старого имени, найденные полнотекстовым поиском (комментарии, строковые литералы,
 * тексты запросов, справка), EDT показывает в дереве изменений без пометки — все до одного:
 * {@code CustomPreviewWizardPage.setChange} перед показом страницы обходит дерево изменений и
 * снимает пометку с каждого {@link IFullTextSearchChange}. Комфорт возвращает пометку тем из них,
 * где в тексте найдено <b>полное имя</b> объекта — имя со стоящим перед ним через точку типом
 * метаданных ({@code Справочник.Номенклатура}, {@code Справочники.Номенклатура},
 * {@code Catalog.Номенклатура}). Такое вхождение — почти наверняка ссылка на переименовываемый
 * объект, а не совпадение слова. Голое имя без типа и имя внутри более длинного слова
 * ({@code НоменклатураТоваров}) остаются без пометки.
 *
 * <p>Тип перед точкой проверяется по {@link MdTypeMapping#isMdTypeToken} — единому справочнику
 * написаний типов МД. Сам тип с типом переименовываемого объекта не сверяется: мастер знает только
 * готовый набор изменений, объекта в нём нет. То есть при переименовании справочника пометку
 * получит и вхождение вида {@code Документ.<староеИмя>}; пользователь видит его в дереве и может
 * снять пометку.
 *
 * <p>Пометки расставляются один раз на набор изменений: возврат на страницу предпросмотра ручной
 * выбор пользователя не перетирает. Изменение с запретом на редактирование
 * ({@code CustomSourceFileChange.isForbidden}) пометку не принимает — это делает сама EDT.
 *
 * <p>Отдельно от {@link RefactoringPreviewCurrentLinesHook} (панель «Текущая строка» в том же
 * окне): у того своя привязка к панели сравнения и свой жизненный цикл, здесь — набор изменений.
 */
public final class RefactoringPreviewMarksHook
{
    /** Набор изменений, для которого пометки уже расставлены. */
    private static final String PROCESSED_CHANGE_KEY = "tormozit.refactoringPreviewMarksChange"; //$NON-NLS-1$

    /**
     * Диалог мастера рефакторинга: LTK открывает {@code RefactoringWizardDialog2} (реже
     * {@code RefactoringWizardDialog}) — проверка та же, что в
     * {@link RefactoringPreviewCurrentLinesHook}.
     */
    private static final String DIALOG_NAME_PART_REFACTORING = "Refactoring"; //$NON-NLS-1$
    private static final String DIALOG_NAME_PART_DIALOG = "Dialog"; //$NON-NLS-1$
    /** Страница предпросмотра LTK — штатная {@code PreviewWizardPage} или её потомок EDT. */
    private static final String PREVIEW_PAGE_NAME_PART = "PreviewWizardPage"; //$NON-NLS-1$

    /**
     * Набор изменений мастер может подставить в страницу уже после её показа — тогда ждём его
     * с повторами. Ожидание конечное: страница предпросмотра без изменений так и останется пустой.
     */
    private static final int RETRY_DELAY_MS = 300;
    private static final int MAX_ATTEMPTS = 20;

    private static final String TEMP_LOG_TOPIC = "refactoring-preview-marks"; //$NON-NLS-1$

    private RefactoringPreviewMarksHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, RefactoringPreviewMarksHook::handleShow);
    }

    /**
     * Показ любого контрола внутри окна мастера: страница предпросмотра становится видимой именно
     * так ({@code WizardDialog.showPage} → {@code setVisible(true)}), отдельного события у неё нет.
     */
    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Shell shell = control.getShell();
        if (shell == null || shell.isDisposed() || !isRefactoringWizardDialog(shell))
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> scheduleApply(shell, 0));
    }

    private static void scheduleApply(Shell shell, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        if (applyMarks(shell))
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(RETRY_DELAY_MS, () -> scheduleApply(shell, attempt + 1));
    }

    private static boolean isRefactoringWizardDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(DIALOG_NAME_PART_REFACTORING) && name.contains(DIALOG_NAME_PART_DIALOG);
    }

    /**
     * @return {@code false}, если ждём набор изменений на открытой странице предпросмотра —
     *     тогда попытку надо повторить; {@code true} — сделано или ждать нечего
     */
    private static boolean applyMarks(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return true;
        if (!(shell.getData() instanceof IWizardContainer container))
            return true;
        IWizardPage page = container.getCurrentPage();
        // Не страница предпросмотра — её показ придёт своим событием
        if (page == null || !page.getClass().getName().contains(PREVIEW_PAGE_NAME_PART))
            return true;
        if (!(Global.getField(page, "fChange") instanceof Change change)) //$NON-NLS-1$
            return false;
        if (shell.getData(PROCESSED_CHANGE_KEY) == change)
            return true;
        shell.setData(PROCESSED_CHANGE_KEY, change);

        try
        {
            int marked = markFullNameChanges(change, new HashMap<>());
            if (marked > 0)
                refreshTree(page);
        }
        catch (RuntimeException e)
        {
            Global.tempLogException(TEMP_LOG_TOPIC, "applyMarks", e); //$NON-NLS-1$
        }
        return true;
    }

    /**
     * Обход дерева изменений тот же, что у {@code CustomPreviewWizardPage}: композит — вглубь,
     * лист — решение по одному изменению.
     *
     * @return сколько изменений получили пометку
     */
    private static int markFullNameChanges(Change change, Map<Object, String> contentCache)
    {
        if (change instanceof CompositeChange composite)
        {
            int marked = 0;
            for (Change child : composite.getChildren())
                marked += markFullNameChanges(child, contentCache);
            return marked;
        }
        if (!(change instanceof IFullTextSearchChange) || change.isEnabled())
            return 0;
        if (!isFullNameChange(change, contentCache))
            return 0;
        change.setEnabled(true);
        // Запрет на редактирование изменение гасит само — считаем только реально помеченные
        return change.isEnabled() ? 1 : 0;
    }

    /**
     * Все правки изменения попадают на полное имя? Изменений с несколькими правками у
     * полнотекстового поиска не бывает (EDT дробит их по одной на вхождение), но если такое
     * появится — помечаем только когда полным именем оказались все правки: пометка ставится на
     * изменение целиком.
     */
    private static boolean isFullNameChange(Change change, Map<Object, String> contentCache)
    {
        if (!(change instanceof TextEditBasedChange textChange))
            return false;
        List<TextEdit> edits = new ArrayList<>();
        collectLeafEdits(Global.invoke(change, "getEdit"), edits); //$NON-NLS-1$
        if (edits.isEmpty())
            return false;
        String content = currentContent(textChange, contentCache);
        if (content == null)
            return false;
        for (TextEdit edit : edits)
        {
            if (!isFullNameOccurrence(content, edit.getOffset(), edit.getLength()))
                return false;
        }
        return true;
    }

    private static void collectLeafEdits(Object edit, List<TextEdit> result)
    {
        if (!(edit instanceof TextEdit textEdit))
            return;
        if (textEdit instanceof MultiTextEdit)
        {
            for (TextEdit child : textEdit.getChildren())
                collectLeafEdits(child, result);
            return;
        }
        result.add(textEdit);
    }

    /**
     * Текущий текст, к которому относятся смещения правок: файл модуля, текст запроса в модели и
     * т.п. Читается один раз на изменяемый элемент — вхождений в одном файле бывают десятки.
     */
    private static String currentContent(TextEditBasedChange change, Map<Object, String> contentCache)
    {
        Object key = change.getModifiedElement();
        if (key != null && contentCache.containsKey(key))
            return contentCache.get(key);
        String content = null;
        try
        {
            content = change.getCurrentContent(new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_LOG_TOPIC, "getCurrentContent " + change.getName(), e); //$NON-NLS-1$
        }
        if (key != null)
            contentCache.put(key, content);
        return content;
    }

    /**
     * Вхождение {@code [offset, offset + length)} — полное имя объекта: перед ним точка, перед
     * точкой имя типа метаданных, а сразу за ним имя не продолжается.
     */
    static boolean isFullNameOccurrence(String content, int offset, int length)
    {
        if (content == null || length <= 0 || offset < 1 || offset + length > content.length())
            return false;
        int after = offset + length;
        if (after < content.length() && isNamePart(content.charAt(after)))
            return false;
        if (content.charAt(offset - 1) != '.')
            return false;
        int typeEnd = offset - 1;
        int typeStart = typeEnd;
        while (typeStart > 0 && isNamePart(content.charAt(typeStart - 1)))
            typeStart--;
        if (typeStart == typeEnd)
            return false;
        return MdTypeMapping.isMdTypeToken(content.substring(typeStart, typeEnd));
    }

    /** Символ имени 1С: буква (в том числе кириллическая), цифра или подчёркивание. */
    private static boolean isNamePart(char c)
    {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * Пометки в дереве берутся из состояния изменений при отрисовке строки
     * ({@code ChangeElementTreeViewer.applyCheckedState} по {@code PreviewNode.getActive()}),
     * поэтому после правки состояния достаточно перерисовать дерево.
     */
    private static void refreshTree(IWizardPage page)
    {
        if (!(Global.getField(page, "fTreeViewer") instanceof CheckboxTreeViewer viewer)) //$NON-NLS-1$
            return;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        viewer.refresh();
    }
}
