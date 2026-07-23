package tormozit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Команда «Сравнить ИР» — открывает сравнение двух произвольных текстов в форме
 * приложения ИР (не файлов/табличных документов, как {@code CompareConfigCompareInIRHandler}).
 *
 * <p>Порт вызова {@code ирКлиент.Сравнить2ЗначенияВФормеЛкс(...)}. Сигнатура метода 1С:
 * <pre>
 * Функция Сравнить2ЗначенияВФормеЛкс(Знач Значение1, Знач Значение2, Знач Модально = Ложь,
 *     Знач Название1 = Неопределено, Знач Название2 = Неопределено,
 *     ПолучатьXMLПредставлениеДляНеизвестныхТипов = Истина, ТекущееСвойство = "",
 *     КлючУникальностиФормы = Неопределено, РазрешитьКонвертациюВТаблицуЗначений = Ложь,
 *     ВариантСинтаксиса = "", Знач ОбщееНазвание = "", Знач КлючевыеКолонки = "",
 *     Знач СравниваемыеКолонки = "", Знач НомерСтроки1 = 0, Знач НомерСтроки2 = 0,
 *     Знач РежимВыбора = Ложь) Экспорт
 * </pre>
 * Заполняются: Значение1/2, Модально, Название1/2 (подписи сторон сравнения — при
 * отсутствии остаются {@code Неопределено}), ВариантСинтаксиса (подбирается вызывающей стороной
 * под тип сравниваемого текста — см. {@link #syntaxVariantFor}),
 * ОбщееНазвание (заголовок окна), НомерСтроки1/2 (текущая строка каждого поля текста —
 * ИР сразу прокрутит форму к нужному месту). Остальные параметры — их фактические
 * значения по умолчанию из сигнатуры 1С, а не {@code null}: {@link ComBridge#invoke}
 * передаёт позиционные аргументы через Jacob и не умеет отправлять специальное COM-значение
 * «параметр не передан» (аналог {@code <ВзятьЗначениеПоУмолчанию>}) — {@code null} годится
 * только там, где 1С-дефолт сам {@code Неопределено} (Название1/2, КлючУникальностиФормы);
 * для типизированных дефолтов ({@code Ложь}/{@code Истина}/{@code ""}) нужно явно передавать
 * само это значение, иначе типизированный параметр (например, булев {@code РежимВыбора})
 * получит недопустимое {@code Неопределено} вместо {@code Ложь}.
 */
public final class IrCompareValuesHandler
{
    public static final String MENU_LABEL = "Сравнить ИР"; //$NON-NLS-1$
    public static final String TOOLTIP = "Открыть сравнение активной пары текстов в приложении ИР"; //$NON-NLS-1$

    private IrCompareValuesHandler()
    {
    }

    /**
     * @param syntaxVariant {@code ВариантСинтаксиса} — {@code "ВстроенныйЯзык"}/{@code "ЯзыкЗапросов"}/
     *        {@code "ЯзыкКомпоновки"}/{@code "XML"}/{@code "JSON"}/{@code "Обычный"}/{@code ""}
     *        (см. {@link #syntaxVariantFor})
     * @param leftLabel  подпись левой стороны (Название1) — {@code null}/пусто оставляет {@code Неопределено}
     * @param rightLabel подпись правой стороны (Название2) — {@code null}/пусто оставляет {@code Неопределено}
     * @param leftLine  строка под кареткой слева, нумерация с 0 (как {@code StyledText.getLineAtOffset})
     * @param rightLine строка под кареткой справа, нумерация с 0
     */
    public static void compare(String leftText, String rightText, String windowTitle,
        String leftLabel, String rightLabel, String syntaxVariant, int leftLine, int rightLine)
    {
        compare(leftText, rightText, windowTitle, leftLabel, rightLabel, syntaxVariant,
            leftLine, rightLine, null, null);
    }

    /**
     * Как {@link #compare(String, String, String, String, String, String, int, int)}, но проект ИР
     * выбирается по {@link #resolveDtProjectForCompare(IFile, IFile)}.
     */
    public static void compare(String leftText, String rightText, String windowTitle,
        String leftLabel, String rightLabel, String syntaxVariant, int leftLine, int rightLine,
        IFile leftFile, IFile rightFile)
    {
        IDtProject dtProject = resolveDtProjectForCompare(leftFile, rightFile);
        if (dtProject == null)
        {
            ToastNotification.show(MENU_LABEL, "Не удалось определить активный проект EDT", 4_000); //$NON-NLS-1$
            return;
        }

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String title = windowTitle != null && !windowTitle.isBlank() ? windowTitle : MENU_LABEL;
        String variant = syntaxVariant != null ? syntaxVariant : ""; //$NON-NLS-1$
        String name1 = stripTrailingColon(leftLabel);
        String name2 = stripTrailingColon(rightLabel);
        // НомерСтроки1/2 в форме ИР нумеруются с 1, а не с 0, как у StyledText.
        int leftLine1Based = leftLine + 1;
        int rightLine1Based = rightLine + 1;
        irSession.executor.submit(() ->
            compareOnComThread(irSession, leftText, rightText, title, name1, name2, variant,
                leftLine1Based, rightLine1Based));
    }

    /**
     * Проект для сессии ИР при двухстороннем сравнении:
     * оба файла в одном workspace-проекте — он; только один файл резолвится
     * (типично rename/delete в списке коммита) — проект этой стороны; иначе активный.
     */
    public static IDtProject resolveDtProjectForCompare(IFile leftFile, IFile rightFile)
    {
        if (leftFile != null && rightFile != null)
        {
            IProject leftProject = leftFile.getProject();
            IProject rightProject = rightFile.getProject();
            if (leftProject != null && leftProject.equals(rightProject))
            {
                IDtProject fromFiles = Global.getDtProjectFromWorkspaceProject(leftProject);
                if (fromFiles != null)
                    return fromFiles;
            }
            return resolveActiveDtProject();
        }
        IFile one = leftFile != null ? leftFile : rightFile;
        if (one != null)
        {
            IProject project = one.getProject();
            if (project != null)
            {
                IDtProject fromFile = Global.getDtProjectFromWorkspaceProject(project);
                if (fromFile != null)
                    return fromFile;
            }
        }
        return resolveActiveDtProject();
    }

    /**
     * Сопоставляет тип сравниваемого текста (расширение файла/тип вьюера сравнения EDT —
     * {@code bsl}/{@code ql}/{@code xml} и т.п.) значению {@code ВариантСинтаксиса}. Неизвестный
     * или {@code null} тип — {@code ""} (тот же эффект, что и {@code "Обычный"}, но это
     * значение по умолчанию в сигнатуре 1С, ничего специально выбирать не нужно).
     */
    public static String syntaxVariantFor(String typeOrExtension)
    {
        if (typeOrExtension == null)
            return ""; //$NON-NLS-1$
        return switch (typeOrExtension.toLowerCase(java.util.Locale.ROOT))
        {
            case "bsl" -> "ВстроенныйЯзык"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ql" -> "ЯзыкЗапросов"; //$NON-NLS-1$ //$NON-NLS-2$
            case "xml", "dcs", "form", "mdo", "cmi" -> "XML"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "json" -> "JSON"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> ""; //$NON-NLS-1$
        };
    }

    private static void compareOnComThread(IRSession irSession, String leftText, String rightText, String title,
        String leftLabel, String rightLabel, String syntaxVariant, int leftLine, int rightLine)
    {
        try
        {
            Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
            irSession.showWindow();
            ComBridge.invoke(irClient, "Сравнить2ЗначенияВФормеЛкс", //$NON-NLS-1$
                leftText, rightText, false,                    // Значение1, Значение2, Модально = Ложь //$NON-NLS-1$
                leftLabel, rightLabel,                          // Название1, Название2 = Неопределено
                true, "", null, false,                           // ПолучатьXML..=Истина, ТекущееСвойство="", КлючУникальности=Неопределено, РазрешитьКонвертацию..=Ложь //$NON-NLS-1$
                syntaxVariant, title,                            // ВариантСинтаксиса, ОбщееНазвание
                "", "",                                          // КлючевыеКолонки="", СравниваемыеКолонки="" //$NON-NLS-1$ //$NON-NLS-2$
                leftLine, rightLine,                              // НомерСтроки1, НомерСтроки2
                false);                                           // РежимВыбора = Ложь
        }
        catch (Exception e)
        {
            Global.log("IrCompareValuesHandler: " + e.getMessage()); //$NON-NLS-1$
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() ->
                    ToastNotification.show(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000)); //$NON-NLS-1$
        }
    }

    /** Подписи сторон ({@code leftLabel}/{@code rightLabel}) обычно берутся из текста рядом с полем в EDT — с двоеточием на конце; для Название1/2 в форме ИР оно не нужно. */
    private static String stripTrailingColon(String label)
    {
        if (label == null || label.isBlank())
            return null;
        String trimmed = label.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed; //$NON-NLS-1$
    }

    private static IDtProject resolveActiveDtProject()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return null;
        IProject project = Global.getActiveProject(page, true);
        return project != null ? Global.getDtProjectFromWorkspaceProject(project) : null;
    }
}
