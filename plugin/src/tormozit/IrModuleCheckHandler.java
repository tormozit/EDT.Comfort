package tormozit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.handlers.IHandlerService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.PlainEObjectMarker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;

/** Проверка модуля через приложение ИР ({@code ОткрытьПроверкуМодуля}). */
public final class IrModuleCheckHandler
{
    public static final String COMMAND_ID = "tormozit.IrModuleCheck"; //$NON-NLS-1$
    static final String MENU_LABEL = "Проверить модуль ИР"; //$NON-NLS-1$
    private static final long CTRL_ENTER_DELAY_MS = 200;
    /** Пауза после Ctrl+Enter перед {@code ТекущаяДатаЛкс}, чтобы проверка модуля в ИР успела
     *  реально встать в очередь COM-объекта — иначе {@code ТекущаяДатаЛкс} не дожидается её. */
    private static final long PRE_WAIT_DELAY_MS = 1_000;
    private static final long RESULT_WAIT_MINUTES = 5;

    /** {@code checkId} наших маркеров в {@link IMarkerManager} — панель «Ошибки конфигурации».
     *  По нему же {@link IrModuleCheckClearHandler} чистит все маркеры проверки ИР разом. */
    static final String CHECK_ID = "tormozit.irModuleCheck"; //$NON-NLS-1$

    /** Атрибут-метка обычных {@code IMarker.PROBLEM} (подчёркивания в самом редакторе — {@link IMarkerManager}
     *  их не создаёт, это отдельная система только для панели «Ошибки конфигурации»). */
    static final String EDITOR_MARKER_ATTR = "tormozit.source"; //$NON-NLS-1$
    static final String EDITOR_MARKER_ATTR_VALUE = "irModuleCheck"; //$NON-NLS-1$

    /** Штатная команда EDT «Проверить» (модельная валидация BSL). */
    private static final String EDT_VALIDATE_COMMAND_ID = "com._1c.g5.v8.dt.commands.validate"; //$NON-NLS-1$

    private IrModuleCheckHandler() {}

    public static void checkModule(BslXtextEditor editor)
    {
        if (editor == null)
        {
            ToastNotification.show(MENU_LABEL, "Откройте модуль BSL и повторите команду", 3000); //$NON-NLS-1$
            return;
        }

        runEdtValidateCommand(editor);

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String moduleName = GetRef.resolveSetTextModuleName(editor);
        IFile file = null;
        IEditorInput input = editor.getEditorInput();
        if (input != null)
            file = input.getAdapter(IFile.class);
        IProject project = dtProject.getWorkspaceProject();

        irSession.syncCodeEditorToIR(editor);
        Future<String> future = irSession.executor.submit(() -> checkModuleOnComThread(irSession, moduleName));
        scheduleResultJob(irSession, future, project, file, moduleName);
    }

    /** Штатная команда EDT «Проверить» ({@link #EDT_VALIDATE_COMMAND_ID}) — вызывается первой, до ИР. */
    private static void runEdtValidateCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
                handlerService.executeCommand(EDT_VALIDATE_COMMAND_ID, null);
        }
        catch (Exception e)
        {
            IrModuleCheckDebug.problem("runEdtValidateCommand: " + e); //$NON-NLS-1$
        }
    }

    /** COM-поток {@link IRSession#executor}; sync уже выполнен через {@link IRSession#syncCodeEditorToIR}. */
    private static String checkModuleOnComThread(IRSession irSession, String moduleName)
    {
        try
        {
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            irSession.showWindow();
            irSession.invokeCodeEditor("ОткрытьПроверкуМодуля"); //$NON-NLS-1$
            WinWindowActivator.sendCtrlEnterToIrAfterDelay(irSession, CTRL_ENTER_DELAY_MS);
            Thread.sleep(PRE_WAIT_DELAY_MS);

            Object irCommon = irSession.getModule("ирОбщий"); //$NON-NLS-1$
            IrModuleCheckDebug.log("wait ТекущаяДатаЛкс module=" + moduleName); //$NON-NLS-1$
            long waitStart = System.currentTimeMillis();
            Object dateRaw = ComBridge.invoke(irCommon, "ТекущаяДатаЛкс"); // блокирует до завершения проверки в ИР //$NON-NLS-1$
            long waitMs = System.currentTimeMillis() - waitStart;
            Global.tempLog("irModuleCheck", "ТекущаяДатаЛкс waitMs=" + waitMs //$NON-NLS-1$ //$NON-NLS-2$
                + " resultClass=" + (dateRaw == null ? "null" : dateRaw.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + " resultToString=" + dateRaw); //$NON-NLS-1$

            Object ixdRaw = ComBridge.invoke(irSession.codeEditor, "ИмяФайлаМодуляИзИмениМодуля", moduleName, "ixd"); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog("irModuleCheck", "ИмяФайлаМодуляИзИмениМодуля(" + moduleName + ",ixd)" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " resultClass=" + (ixdRaw == null ? "null" : ixdRaw.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + " resultToString=" + ixdRaw); //$NON-NLS-1$
            String ixdPath = ComBridge.toString(ixdRaw);
            Global.tempLog("irModuleCheck", "ixdPath after ComBridge.toString=" + ixdPath); //$NON-NLS-1$ //$NON-NLS-2$
            logIxdFileState("checkModuleOnComThread (сразу после получения пути)", ixdPath); //$NON-NLS-1$
            IrModuleCheckDebug.log("done ixd=" + ixdPath); //$NON-NLS-1$
            return ixdPath;
        }
        catch (Exception e)
        {
            Global.log("IrModuleCheckHandler: " + e.getMessage()); //$NON-NLS-1$
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /** Ждёт результат в фоновой {@link Job} (не на UI-потоке — ожидание может занять до {@link #RESULT_WAIT_MINUTES} минут). */
    private static void scheduleResultJob(
        IRSession irSession, Future<String> future, IProject project, IFile file, String moduleName)
    {
        Job.create(MENU_LABEL + ": " + moduleName, monitor -> //$NON-NLS-1$
        {
            String ixdPath;
            try
            {
                ixdPath = future.get(RESULT_WAIT_MINUTES, TimeUnit.MINUTES);
            }
            catch (TimeoutException e)
            {
                future.cancel(true);
                toastAsync("Таймаут ожидания проверки модуля в ИР (" + RESULT_WAIT_MINUTES + " мин)"); //$NON-NLS-1$ //$NON-NLS-2$
                return Status.CANCEL_STATUS;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            }
            catch (ExecutionException e)
            {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                toastAsync("Ошибка вызова ИР: " + msg); //$NON-NLS-1$
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "IrModuleCheck: " + msg); //$NON-NLS-1$ //$NON-NLS-2$
            }

            if (ixdPath == null || ixdPath.isBlank())
            {
                toastAsync("ИР не вернул файл результатов проверки"); //$NON-NLS-1$
                return Status.CANCEL_STATUS;
            }

            logIxdFileState("scheduleResultJob (перед чтением/парсингом)", ixdPath); //$NON-NLS-1$
            List<IxdErrorTableParser.CheckError> errors;
            try
            {
                errors = IxdErrorTableParser.parseErrors(Path.of(ixdPath));
            }
            catch (Exception e)
            {
                IrModuleCheckDebug.problem("parse " + ixdPath + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
                toastAsync("Не удалось разобрать результаты проверки: " + e.getMessage()); //$NON-NLS-1$
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "IrModuleCheck parse: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }

            IrModuleCheckDebug.log("parsed errors=" + errors.size()); //$NON-NLS-1$
            Global.tempLog("irModuleCheck", "parsed errors=" + errors.size()); //$NON-NLS-1$ //$NON-NLS-2$
            String rawText = irSession.lastSyncedRawText;
            Display.getDefault().asyncExec(() -> applyMarkers(project, file, moduleName, rawText, errors));
            return Status.OK_STATUS;
        }).schedule();
    }

    /** Временное безусловное логирование размера/mtime {@code .ixd}-файла в ключевой точке — для диагностики гонки с записью файла со стороны ИР. */
    private static void logIxdFileState(String point, String ixdPath)
    {
        if (ixdPath == null || ixdPath.isBlank())
        {
            Global.tempLog("irModuleCheck", point + ": ixdPath пуст"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        try
        {
            Path p = Path.of(ixdPath);
            if (!Files.exists(p))
            {
                Global.tempLog("irModuleCheck", point + ": файл не существует: " + ixdPath); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            long size = Files.size(p);
            Object mtime = Files.getLastModifiedTime(p);
            Global.tempLog("irModuleCheck", point + ": size=" + size + " lastModified=" + mtime); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.tempLog("irModuleCheck", point + ": ошибка чтения атрибутов файла: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void toastAsync(String message)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> ToastNotification.show(MENU_LABEL, message, 5_000));
    }

    // -------------------------------------------------------------------------
    // Маркеры EDT
    // -------------------------------------------------------------------------

    private static void applyMarkers(
        IProject project, IFile file, String moduleName, String snapshotRawText,
        List<IxdErrorTableParser.CheckError> errors)
    {
        if (project == null || file == null || !file.exists())
        {
            ToastNotification.show(MENU_LABEL, "Файл модуля недоступен для маркеров", 4_000); //$NON-NLS-1$
            return;
        }
        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        if (markerManager == null)
        {
            IrModuleCheckDebug.problem("IMarkerManager недоступен как OSGi-сервис"); //$NON-NLS-1$
            ToastNotification.show(MENU_LABEL, "Сервис маркеров EDT недоступен", 4_000); //$NON-NLS-1$
            return;
        }

        URI fileUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        String sourceKey = fileUri.toString();

        try
        {
            // Обычные IMarker.PROBLEM для подчёркивания прямо в редакторе (JFace подхватывает их сам
            // через MarkerAnnotationModel) — своя, отдельная от IMarkerManager система.
            for (IMarker m : file.findMarkers(IMarker.PROBLEM, false, IResource.DEPTH_ZERO))
            {
                if (EDITOR_MARKER_ATTR_VALUE.equals(m.getAttribute(EDITOR_MARKER_ATTR, null)))
                    m.delete();
            }

            Document doc = snapshotRawText != null ? new Document(snapshotRawText) : new Document(""); //$NON-NLS-1$
            List<Marker> markers = new ArrayList<>();
            for (IxdErrorTableParser.CheckError err : errors)
            {
                if (err.module != null && !err.module.isBlank() && !err.module.equals(moduleName))
                    continue; // ошибка в другом модуле — вне текущего файла

                // Позиция ИР — 1-based, Global.remapOffsetFromLf ждёт 0-based LF-офсет.
                int lfOffset = err.position != null ? Math.max(0, err.position - 1) : 0;
                int rawOffset = Global.remapOffsetFromLf(snapshotRawText != null ? snapshotRawText : "", lfOffset); //$NON-NLS-1$
                int lineNumber = 1;
                try
                {
                    lineNumber = doc.getLineOfOffset(rawOffset) + 1;
                }
                catch (BadLocationException ignored) {}

                // Длина подсветки — по длине слова из ошибки (Слово). ИР иногда добавляет к слову
                // синтаксический хвост (например, открывающую скобку вызова метода — "Записать("),
                // такие нецидентификаторные хвостовые символы в подсветку включать не нужно.
                int length = highlightLength(err.word);

                String actualSubstring;
                try
                {
                    actualSubstring = doc.get(rawOffset, Math.min(length, Math.max(0, doc.getLength() - rawOffset)));
                }
                catch (Exception ex)
                {
                    actualSubstring = "<из диапазона>"; //$NON-NLS-1$
                }
                Global.tempLog("irModuleCheck", "marker word=" + err.word + " pos=" + err.position //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " lfOffset=" + lfOffset + " rawOffset=" + rawOffset + " length=" + length //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " line=" + lineNumber + " docSubstring=[" + actualSubstring + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                IrModuleCheckDebug.log("marker pos=" + err.position + " rawOffset=" + rawOffset //$NON-NLS-1$ //$NON-NLS-2$
                    + " line=" + lineNumber + " length=" + length); //$NON-NLS-1$ //$NON-NLS-2$

                String message = buildMessage(err);

                PlainEObjectMarker marker = new PlainEObjectMarker();
                marker.setUri(fileUri);
                marker.setProject(project);
                marker.setCheckId(CHECK_ID);
                // "BslEditor" — sourceType, под который зарегистрирован BslMarkerDynamicDataProvider
                // (бандл com._1c.g5.v8.dt.bsl, extension markerDynamicDataProvider): он умеет резолвить
                // PlainEObjectMarker с platform-URI на .bsl-файл. Пустой sourceType уходит в дефолтный
                // BmMarkerDynamicDataProvider, рассчитанный на BM-объекты, а не на «плоские» маркеры —
                // с ним падает NullPointerException внутри V8ProjectManager.getProject(null).
                marker.setSourceType("BslEditor"); //$NON-NLS-1$
                marker.setSeverity(MarkerSeverity.MINOR); // TODO: пока все ошибки ИР — MINOR, уточнить сопоставление //$NON-NLS-1$
                marker.setMessage(message);
                marker.setCreatedAt(err.detectedAt != null ? err.detectedAt : System.currentTimeMillis());
                StandardExtraInfo.TEXT_OFFSET.put(marker, rawOffset);
                StandardExtraInfo.TEXT_LENGTH.put(marker, length);
                StandardExtraInfo.TEXT_LINE.put(marker, lineNumber);
                // Без TEXT_URI_TO_PROBLEM BslMarkerUiHandler.showMarker(...) выходит сразу же —
                // двойной клик по строке в панели «Ошибки конфигурации» ничего не делает.
                StandardExtraInfo.TEXT_URI_TO_PROBLEM.put(marker, sourceKey);
                markers.add(marker);

                IMarker editorMarker = file.createMarker(IMarker.PROBLEM);
                editorMarker.setAttribute(IMarker.MESSAGE, message);
                editorMarker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
                editorMarker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
                editorMarker.setAttribute(IMarker.CHAR_START, rawOffset);
                editorMarker.setAttribute(IMarker.CHAR_END, rawOffset + length);
                editorMarker.setAttribute(EDITOR_MARKER_ATTR, EDITOR_MARKER_ATTR_VALUE);
            }

            Marker[] existing = markerManager.getMarkers(project, sourceKey);
            int removedCount = existing != null ? existing.length : 0;

            markerManager.removeMarkers(project, sourceKey);
            if (!markers.isEmpty())
                markerManager.setMarkers(project, sourceKey, markers.toArray(new Marker[0]));

            ToastNotification.show(MENU_LABEL,
                "Модуль " + moduleName + ": добавлено ошибок " + markers.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + ", удалено " + removedCount, 5_000); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            IrModuleCheckDebug.problem("applyMarkers: " + e); //$NON-NLS-1$
            Global.tempLogException("irModuleCheck", "applyMarkers", e); //$NON-NLS-1$ //$NON-NLS-2$
            ToastNotification.show(MENU_LABEL, "Ошибка применения маркеров: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    /**
     * Длина подсветки маркера по слову ошибки: обрезает хвостовые нецидентификаторные символы
     * (скобки, точки и т.п. — например, ИР отдаёт «Записать(» для вызова метода). Пустое/{@code null}
     * слово или слово целиком из таких символов — точка (1 символ).
     */
    private static int highlightLength(String word)
    {
        if (word == null || word.isBlank())
            return 1;
        int end = word.length();
        while (end > 0)
        {
            char c = word.charAt(end - 1);
            if (Character.isLetterOrDigit(c) || c == '_')
                break;
            end--;
        }
        return end > 0 ? end : 1;
    }

    private static String buildMessage(IxdErrorTableParser.CheckError err)
    {
        StringBuilder sb = new StringBuilder();
        if (err.method != null && !err.method.isBlank())
            sb.append(err.method).append(": "); //$NON-NLS-1$
        if (err.comment != null && !err.comment.isBlank())
            sb.append(err.comment.strip());
        else
        {
            if (err.errorType != null && !err.errorType.isBlank())
                sb.append(err.errorType);
            if (err.word != null && !err.word.isBlank())
                sb.append(" «").append(err.word).append('»'); //$NON-NLS-1$
            if (err.parentType != null && !err.parentType.isBlank())
                sb.append(" (").append(err.parentType).append(')'); //$NON-NLS-1$
        }
        return sb.length() > 0 ? sb.toString() : "Ошибка проверки модуля"; //$NON-NLS-1$
    }

    // -------------------------------------------------------------------------
    // Парсер .ixd (общий формат сериализации значений 1С)
    // -------------------------------------------------------------------------

    /**
     * Разбор файла результатов проверки модуля (расширение {@code .ixd}), который ИР
     * возвращает через {@code ПолеТекстаПрограммы.ИмяФайлаМодуляИзИмениМодуля(ИмяМодуля, "ixd")}.
     * <p>
     * Формат — рекурсивная сериализация значений 1С ({@code {"#",GUID,{N,...}}},
     * {@code {"S","x"}}, {@code {"N",5}}, {@code {"U"}} и т.п.), общая для всех табличных полей
     * файла (Переменные, Методы, ОбластиГруппировки, Ошибки). Разбирается только раздел «Ошибки».
     */
    private static final class IxdErrorTableParser
    {
        private IxdErrorTableParser() {}

        static final class CheckError
        {
            String comment;
            String method;
            String module;
            Integer position;
            Integer positionInMethod;
            String word;
            String errorType;
            String parentType;
            String language;
            /** {@code ДатаОбнаружения} из .ixd — эпоха мс, локальная TZ. {@code null}, если не задано. */
            Long detectedAt;
        }

        static List<CheckError> parseErrors(Path ixdFile) throws Exception
        {
            String text = Files.readString(ixdFile, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿')
                text = text.substring(1);

            Tokenizer t = new Tokenizer(text);
            List<?> root = (List<?>) t.parseValue(); // {"#",guid,{N,entry1,...}}
            List<?> innerGroup = (List<?>) root.get(2);
            List<?> entries = innerGroup.subList(1, innerGroup.size());

            Object errorsValue = null;
            for (Object entryObj : entries)
            {
                List<?> entry = (List<?>) entryObj;
                List<?> nameGroup = (List<?>) entry.get(0);
                String name = (String) nameGroup.get(1);
                if ("Ошибки".equals(name)) //$NON-NLS-1$
                {
                    errorsValue = entry.get(1);
                    break;
                }
            }
            if (errorsValue == null)
                return List.of();

            List<?> errorsGroup = (List<?>) errorsValue;
            if ("U".equals(errorsGroup.get(0))) //$NON-NLS-1$
                return List.of();

            List<?> tableRoot = (List<?>) errorsGroup.get(2); // {marker, colDefsGroup, tableGroup}
            List<?> colDefsGroup = (List<?>) tableRoot.get(1);
            List<?> tableGroup = (List<?>) tableRoot.get(2);

            List<String> colNames = new ArrayList<>();
            for (int i = 1; i < colDefsGroup.size(); i++)
            {
                List<?> colDef = (List<?>) colDefsGroup.get(i);
                colNames.add((String) colDef.get(1));
            }

            List<?> rowsGroup = null;
            for (Object el : tableGroup)
            {
                if (el instanceof List<?> lst && !lst.isEmpty() && "1".equals(lst.get(0))) //$NON-NLS-1$
                {
                    rowsGroup = lst;
                    break;
                }
            }
            if (rowsGroup == null)
                return List.of();

            List<CheckError> result = new ArrayList<>();
            for (int i = 2; i < rowsGroup.size(); i++)
            {
                List<?> row = (List<?>) rowsGroup.get(i);
                int fieldCount = Integer.parseInt((String) row.get(2));
                CheckError err = new CheckError();
                for (int f = 0; f < fieldCount && f < colNames.size(); f++)
                {
                    List<?> valueGroup = (List<?>) row.get(3 + f);
                    applyField(err, colNames.get(f), valueGroup);
                }
                result.add(err);
            }
            return result;
        }

        private static void applyField(CheckError err, String name, List<?> valueGroup)
        {
            switch (name)
            {
                case "Комментарий": err.comment = str(valueGroup); break; //$NON-NLS-1$
                case "Метод": err.method = str(valueGroup); break; //$NON-NLS-1$
                case "Модуль": err.module = str(valueGroup); break; //$NON-NLS-1$
                case "Позиция": err.position = num(valueGroup); break; //$NON-NLS-1$
                case "ПозицияВМетоде": err.positionInMethod = num(valueGroup); break; //$NON-NLS-1$
                case "Слово": err.word = str(valueGroup); break; //$NON-NLS-1$
                case "ТипОшибки": err.errorType = str(valueGroup); break; //$NON-NLS-1$
                case "ТипРодителя": err.parentType = str(valueGroup); break; //$NON-NLS-1$
                case "Язык": err.language = str(valueGroup); break; //$NON-NLS-1$
                case "ДатаОбнаружения": err.detectedAt = date(valueGroup); break; //$NON-NLS-1$
                default: break;
            }
        }

        private static String str(List<?> valueGroup)
        {
            if (valueGroup == null || valueGroup.isEmpty() || !"S".equals(valueGroup.get(0))) //$NON-NLS-1$
                return null;
            return valueGroup.size() > 1 ? (String) valueGroup.get(1) : ""; //$NON-NLS-1$
        }

        private static Integer num(List<?> valueGroup)
        {
            if (valueGroup == null || valueGroup.isEmpty() || !"N".equals(valueGroup.get(0))) //$NON-NLS-1$
                return null;
            try
            {
                return valueGroup.size() > 1 ? Integer.parseInt((String) valueGroup.get(1)) : null;
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }

        /** {@code {"D","20260719183411"}} → эпоха мс (локальная TZ, формат ГГГГММДДЧЧММСС). */
        private static Long date(List<?> valueGroup)
        {
            if (valueGroup == null || valueGroup.isEmpty() || !"D".equals(valueGroup.get(0))) //$NON-NLS-1$
                return null;
            if (valueGroup.size() <= 1)
                return null;
            String raw = (String) valueGroup.get(1);
            if (raw == null || raw.length() < 14)
                return null;
            try
            {
                java.time.LocalDateTime dt = java.time.LocalDateTime.of(
                    Integer.parseInt(raw.substring(0, 4)),
                    Integer.parseInt(raw.substring(4, 6)),
                    Integer.parseInt(raw.substring(6, 8)),
                    Integer.parseInt(raw.substring(8, 10)),
                    Integer.parseInt(raw.substring(10, 12)),
                    Integer.parseInt(raw.substring(12, 14)));
                return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            catch (Exception e)
            {
                return null;
            }
        }

        /** Общий рекурсивный токенайзер формата {@code {..,..}} / {@code "строка"} / голый токен. */
        private static final class Tokenizer
        {
            private final String s;
            private int pos;

            Tokenizer(String s)
            {
                this.s = s;
            }

            Object parseValue()
            {
                skipWs();
                char c = s.charAt(pos);
                if (c == '{')
                    return parseGroup();
                if (c == '"')
                    return parseString();
                return parseBare();
            }

            private List<Object> parseGroup()
            {
                pos++; // '{'
                List<Object> result = new ArrayList<>();
                skipWs();
                if (s.charAt(pos) == '}')
                {
                    pos++;
                    return result;
                }
                while (true)
                {
                    result.add(parseValue());
                    skipWs();
                    char c = s.charAt(pos);
                    if (c == ',')
                    {
                        pos++;
                        continue;
                    }
                    if (c == '}')
                    {
                        pos++;
                        break;
                    }
                    throw new IllegalStateException("Некорректный формат .ixd: ожидался ',' или '}' на позиции " + pos); //$NON-NLS-1$
                }
                return result;
            }

            private String parseString()
            {
                pos++; // '"'
                StringBuilder sb = new StringBuilder();
                while (true)
                {
                    char c = s.charAt(pos);
                    if (c == '"')
                    {
                        if (pos + 1 < s.length() && s.charAt(pos + 1) == '"')
                        {
                            sb.append('"');
                            pos += 2;
                            continue;
                        }
                        pos++;
                        break;
                    }
                    sb.append(c);
                    pos++;
                }
                return sb.toString();
            }

            private String parseBare()
            {
                int start = pos;
                while (pos < s.length())
                {
                    char c = s.charAt(pos);
                    if (c == ',' || c == '}' || c == '{' || Character.isWhitespace(c))
                        break;
                    pos++;
                }
                return s.substring(start, pos);
            }

            private void skipWs()
            {
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
                    pos++;
            }
        }
    }
}
