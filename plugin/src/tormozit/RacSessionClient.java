package tormozit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.DebugPlugin;

import com._1c.g5.v8.dt.debug.core.model.IDebugSession;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;

/**
 * Синтаксис проверен по rac 8.3.27.2074: help cluster / help session.
 * UUID EDT берётся из DebugTargetId.getSeanceId(), номер — из getSeanceNo()
 * (debug.core 19.0.1.v202607090722, RuntimeDebugClientTarget).
 */
record RacSessionClient(IProject project, InfobaseReference infobase, String address, String user, String password)
{
    private static final Pattern FIELD = Pattern.compile("^([a-z][a-z0-9-]*)\\s*:\\s*(.*)$"); //$NON-NLS-1$
    private static final int OUTPUT_LIMIT = 2 * 1024 * 1024;

    /** Завершает все сеансы базы, кроме конфигуратора, и проверяет отсутствие новых подключений. */
    void deleteAllInfobaseSessions(IProgressMonitor monitor) throws Exception
    {
        if (!(infobase.getConnectionString() instanceof ServerConnectionString server))
            throw new IOException("Удаление сеансов через RAS доступно только для серверной информационной базы.");
        String reference = server.getReference();
        if (reference == null || reference.isBlank())
            throw new IOException("В выбранной конфигурации не указано имя информационной базы на сервере.");
        java.net.URI serverAddress = new java.net.URI("tcp://" + server.getServer()); //$NON-NLS-1$
        if (serverAddress.getHost() == null || serverAddress.getRawUserInfo() != null
            || !serverAddress.getRawPath().isEmpty())
            throw new IOException("Не удалось определить сервер кластера из строки соединения выбранной базы.");
        int clusterPort = serverAddress.getPort() < 0 ? 1541 : serverAddress.getPort();
        String executable = racExecutable();
        Database database = null;
        Global.tempLog("debug-session-rac", "delete all requested base=" + reference + " server=" + server.getServer()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Map<String, String> clusterRow : records(run(executable, List.of("cluster", "list"), monitor), "cluster")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            if (!serverAddress.getHost().equalsIgnoreCase(clusterRow.get("host")) //$NON-NLS-1$
                || !Integer.toString(clusterPort).equals(clusterRow.get("port"))) //$NON-NLS-1$
                continue;
            String cluster = UUID.fromString(clusterRow.get("cluster")).toString(); //$NON-NLS-1$
            List<String> arguments = withAuthentication(new ArrayList<>(
                List.of("infobase", "summary", "list", "--cluster=" + cluster))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            for (Map<String, String> row : records(run(executable, arguments, monitor), "infobase")) //$NON-NLS-1$
            {
                if (!reference.equalsIgnoreCase(row.get("name"))) //$NON-NLS-1$
                    continue;
                if (database != null)
                    throw new IOException("В RAS найдено несколько баз с именем «" + reference
                        + "». Не удалось однозначно определить базу; сеансы не завершались.");
                database = new Database(cluster, UUID.fromString(row.get("infobase")).toString()); //$NON-NLS-1$
            }
        }
        if (database == null)
            throw new IOException("База «" + reference + "» не найдена в RAS. Проверьте выбранную конфигурацию и адрес RAS.");
        List<String> list = sessionArguments("list", database.cluster()); //$NON-NLS-1$
        list.add("--infobase=" + database.uuid()); //$NON-NLS-1$
        List<Map<String, String>> sessions = records(run(executable, list, monitor), "session"); //$NON-NLS-1$
        List<Session> targets = new ArrayList<>();
        // Проверяем весь ответ до первой команды удаления.
        for (Map<String, String> row : sessions)
        {
            if (!database.uuid().equalsIgnoreCase(row.get("infobase"))) //$NON-NLS-1$
                throw new IOException("RAS вернул сеанс другой базы. Удаление отменено.");
            String applicationId = row.get("app-id"); //$NON-NLS-1$
            if (applicationId == null || applicationId.isBlank())
                throw new IOException("RAS не передал тип приложения сеанса. Нельзя исключить конфигуратор; удаление отменено.");
            if (isDesigner(row))
            {
                Global.tempLog("debug-session-rac", "batch preserve designer data=" + redact(row.toString())); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            targets.add(new Session(UUID.fromString(row.get("session")), new BigDecimal(row.get("session-id")))); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog("debug-session-rac", "batch session before terminate data=" + redact(row.toString())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Session session : targets)
            terminateAndVerify(executable, database.cluster(), session.uuid(), session.number(), monitor);
        List<Map<String, String>> remaining = records(run(executable, list, monitor), "session"); //$NON-NLS-1$
        int blockers = 0;
        for (Map<String, String> row : remaining)
        {
            if (row.get("app-id") == null || row.get("app-id").isBlank()) //$NON-NLS-1$ //$NON-NLS-2$
                throw new IOException("RAS не передал тип приложения оставшегося сеанса. Обновление не запущено.");
            if (isDesigner(row))
                Global.tempLog("debug-session-rac", "batch designer remains data=" + redact(row.toString())); //$NON-NLS-1$ //$NON-NLS-2$
            else
                blockers++;
        }
        Global.tempLog("debug-session-rac", "batch verification base=" + database.uuid() //$NON-NLS-1$ //$NON-NLS-2$
            + " deleted=" + targets.size() + " remainingNonDesigner=" + blockers); //$NON-NLS-1$ //$NON-NLS-2$
        if (blockers > 0)
            throw new IOException("В базе «" + reference + "» остаются сеансы, кроме конфигуратора: " + blockers
                + ". Возможно, появились новые подключения. Обновление не запущено.");
    }

    private static boolean isDesigner(Map<String, String> row)
    {
        return "Designer".equalsIgnoreCase(row.get("app-id")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private record Database(String cluster, String uuid) {}

    private record Session(UUID uuid, BigDecimal number) {}

    void delete(UUID session, BigDecimal number, IProgressMonitor monitor) throws Exception
    {
        Global.tempLog("debug-session-rac", "delete requested session=" + session + " number=" + number); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String executable = racExecutable();
        List<String> clusters = new ArrayList<>();
        for (Map<String, String> row : records(run(executable, List.of("cluster", "list"), monitor), "cluster")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            clusters.add(UUID.fromString(row.get("cluster")).toString()); //$NON-NLS-1$
        String foundCluster = null;
        for (String candidate : clusters)
        {
            List<String> arguments = sessionArguments("list", candidate); //$NON-NLS-1$
            for (Map<String, String> row : records(run(executable, arguments, monitor), "session")) //$NON-NLS-1$
            {
                if (!session.toString().equalsIgnoreCase(row.get("session"))) //$NON-NLS-1$
                    continue;
                Global.tempLog("debug-session-rac", "session before terminate cluster=" + candidate //$NON-NLS-1$ //$NON-NLS-2$
                    + " data=" + redact(row.toString())); //$NON-NLS-1$
                String actualNumber = row.get("session-id"); //$NON-NLS-1$
                if (actualNumber == null || number.compareTo(new BigDecimal(actualNumber)) != 0)
                    throw new IOException("UUID найден, но номер сеанса в RAS отличается от номера в EDT. Удаление отменено.");
                if (foundCluster != null)
                    throw new IOException("Сеанс найден более одного раза. Не удалось однозначно определить кластер; удаление отменено.");
                foundCluster = candidate;
            }
        }
        if (foundCluster == null)
            throw new IOException("Сеанс " + number.toPlainString() + " (" + session
                + ") не найден в RAS. Проверьте адрес RAS в конфигурации отладки.");
        terminateAndVerify(executable, foundCluster, session, number, monitor);
    }

    private void terminateAndVerify(String executable, String foundCluster, UUID session,
        BigDecimal number, IProgressMonitor monitor) throws Exception
    {
        disconnectSession(session, number, monitor);
        List<String> terminate = sessionArguments("terminate", foundCluster); //$NON-NLS-1$
        terminate.add("--session=" + session); //$NON-NLS-1$
        long verificationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        IOException terminateFailure = null;
        try
        {
            run(executable, terminate, monitor);
            Global.tempLog("debug-session-rac", "terminate accepted session=" + session + " cluster=" + foundCluster); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (IOException e)
        {
            // Процесс может не выйти вовремя уже после удаления. Результат устанавливаем
            // отдельным запросом к RAS, повторную команду удаления не отправляем.
            terminateFailure = e;
            Global.tempLog("debug-session-rac", "terminate command failed; checking session=" + session //$NON-NLS-1$ //$NON-NLS-2$
                + " error=" + redact(e.toString())); //$NON-NLS-1$
        }
        while (true)
        {
            List<Map<String, String>> after;
            try
            {
                after = records(run(executable, sessionArguments("list", foundCluster), monitor), "session"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (IOException e)
            {
                throw new IOException("Не удалось проверить удаление сеанса " + number.toPlainString()
                    + " через RAS. Результат удаления неизвестен. " + redact(e.getLocalizedMessage()), e);
            }
            Map<String, String> remaining = null;
            for (Map<String, String> row : after)
                if (session.toString().equalsIgnoreCase(row.get("session"))) //$NON-NLS-1$
                    remaining = row;
            if (remaining == null)
            {
                Global.tempLog("debug-session-rac", "session deletion verified: UUID absent session=" + session //$NON-NLS-1$ //$NON-NLS-2$
                    + " cluster=" + foundCluster + " sessionsAfter=" + after.size()); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Global.tempLog("debug-session-rac", "session still present cluster=" + foundCluster //$NON-NLS-1$ //$NON-NLS-2$
                + " data=" + redact(remaining.toString())); //$NON-NLS-1$
            if (terminateFailure != null || System.nanoTime() >= verificationDeadline)
            {
                String detail = terminateFailure != null
                    ? " " + redact(terminateFailure.getLocalizedMessage()) : " Истекло время ожидания удаления (60 секунд)."; //$NON-NLS-1$
                throw new IOException("Удаление не подтверждено: сеанс " + number.toPlainString()
                    + " (" + session + ") всё ещё присутствует в RAS." + detail);
            }
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            Thread.sleep(500);
        }
    }

    /** Штатное отключение всех предметов выбранного сеанса до завершения через RAS. */
    private static void disconnectSession(UUID uuid, BigDecimal number, IProgressMonitor monitor) throws Exception
    {
        Global.tempLog("debug-session-rac", "disconnect requested session=" + uuid); //$NON-NLS-1$ //$NON-NLS-2$
        DebugPlugin debug = DebugPlugin.getDefault();
        if (debug == null)
            throw new IOException("Не удалось получить модель отладки для отключения сеанса.");
        int disconnected = 0;
        for (var launch : debug.getLaunchManager().getLaunches())
            for (var debugTarget : launch.getDebugTargets())
            {
                if (!(debugTarget instanceof IRuntimeDebugClientTarget target)
                    || target.isTerminated() || target.isDisconnected())
                    continue;
                for (var group : target.getThreadGroups())
                {
                    if (!(group instanceof IDebugSession session) || !uuid.equals(session.getSessionUuid())
                        || number.compareTo(session.getSessionId()) != 0)
                        continue;
                    // Перебираем снимок предметов: модель может обновляться при отключении.
                    for (var thread : session.getThreads())
                    {
                        if (monitor.isCanceled())
                            throw new OperationCanceledException();
                        if (thread.isTerminated() || thread.isDisconnected())
                            continue;
                        Global.tempLog("debug-session-rac", "disconnect target session=" + uuid //$NON-NLS-1$ //$NON-NLS-2$
                            + " target=" + thread.getRuntimeDebugTarget()); //$NON-NLS-1$
                        thread.disconnect();
                        disconnected++;
                    }
                }
            }
        Global.tempLog("debug-session-rac", "disconnect completed session=" + uuid //$NON-NLS-1$ //$NON-NLS-2$
            + " targets=" + disconnected); //$NON-NLS-1$
    }

    /** Та же установка конфигуратора приложения, что используется при подключении ИР. */
    private String racExecutable() throws IOException
    {
        var installation = ApplicationsViewHook.getRuntimeInstallation(project, infobase);
        if (installation == null || installation.getLocation() == null
            || !"file".equalsIgnoreCase(installation.getLocation().getScheme())) //$NON-NLS-1$
            throw new IOException("Не удалось определить локальный каталог конфигуратора приложения.");
        Path executable = Path.of(installation.getLocation()).resolve("rac.exe"); //$NON-NLS-1$
        Global.tempLog("debug-session-rac", "rac from designer=" + executable); //$NON-NLS-1$ //$NON-NLS-2$
        if (!Files.isRegularFile(executable))
            throw new IOException("В каталоге конфигуратора приложения не найдена утилита RAC: " + executable);
        return executable.toString();
    }

    private List<String> sessionArguments(String command, String clusterId)
    {
        return withAuthentication(new ArrayList<>(List.of("session", command, "--cluster=" + clusterId))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private List<String> withAuthentication(List<String> arguments)
    {
        if (!user.isEmpty())
            arguments.add("--cluster-user=" + user); //$NON-NLS-1$
        if (!password.isEmpty())
            arguments.add("--cluster-pwd=" + password); //$NON-NLS-1$
        return arguments;
    }

    String redact(String text)
    {
        if (text == null)
            return "Ошибка выполнения RAC.";
        return password.isEmpty() ? text : text.replace(password, "***"); //$NON-NLS-1$
    }

    private String run(String executable, List<String> arguments, IProgressMonitor monitor) throws Exception
    {
        if (monitor.isCanceled())
            throw new OperationCanceledException();
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        command.add(address);
        List<String> logged = new ArrayList<>();
        for (String argument : arguments)
            logged.add(argument.startsWith("--cluster-pwd=") ? "--cluster-pwd=***" : argument); //$NON-NLS-1$ //$NON-NLS-2$
        long timeoutSeconds = "session".equals(arguments.get(0)) && "terminate".equals(arguments.get(1)) ? 60 : 30; //$NON-NLS-1$ //$NON-NLS-2$
        Global.tempLog("debug-session-rac", "start " + logged + " ras=" + address //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " timeoutSeconds=" + timeoutSeconds); //$NON-NLS-1$
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        long started = System.nanoTime();
        String processTag = "pid=" + process.pid() + " command=" + arguments.get(0) + "/" + arguments.get(1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Global.tempLog("debug-session-rac", "process started " + processTag); //$NON-NLS-1$ //$NON-NLS-2$
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicBoolean outputEof = new AtomicBoolean();
        AtomicLong bytesRead = new AtomicLong();
        Thread reader = new Thread(() ->
        {
            Global.tempLog("debug-session-rac", "output reader started " + processTag); //$NON-NLS-1$ //$NON-NLS-2$
            try (var stream = process.getInputStream())
            {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1)
                {
                    int remaining = OUTPUT_LIMIT - output.size();
                    output.write(buffer, 0, Math.min(remaining, count));
                    if (count > remaining)
                        truncated.set(true);
                    long total = bytesRead.addAndGet(count);
                    Global.tempLog("debug-session-rac", "output chunk " + processTag + " bytes=" + count + " total=" + total); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
                outputEof.set(true);
                Global.tempLog("debug-session-rac", "output EOF " + processTag + " total=" + bytesRead.get()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            catch (IOException e)
            {
                readFailure.set(e);
                Global.tempLog("debug-session-rac", "output failed " + processTag + " error=" + redact(e.toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }, "Comfort RAC output"); //$NON-NLS-1$
        reader.setDaemon(true);
        reader.start();
        try
        {
            process.getOutputStream().close();
            Global.tempLog("debug-session-rac", "stdin closed " + processTag); //$NON-NLS-1$ //$NON-NLS-2$
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS))
            {
                Global.tempLog("debug-session-rac", "wait " + processTag //$NON-NLS-1$ //$NON-NLS-2$
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) //$NON-NLS-1$
                    + " alive=" + process.isAlive() + " reader=" + reader.getState() //$NON-NLS-1$ //$NON-NLS-2$
                    + " bytes=" + bytesRead.get() + " eof=" + outputEof.get() //$NON-NLS-1$ //$NON-NLS-2$
                    + " cpuMs=" + process.info().totalCpuDuration().map(Duration::toMillis).orElse(-1L)); //$NON-NLS-1$
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                if (System.nanoTime() >= deadline)
                    throw new IOException("Процесс RAC не завершился за " + timeoutSeconds + " секунд. "
                        + "RAS мог уже выполнить команду удаления. Проверьте наличие сеанса перед повтором.");
            }
            Global.tempLog("debug-session-rac", "process exited " + processTag + " exit=" + process.exitValue() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); //$NON-NLS-1$
            reader.join(5000);
            Global.tempLog("debug-session-rac", "reader joined " + processTag + " alive=" + reader.isAlive() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " bytes=" + bytesRead.get() + " eof=" + outputEof.get()); //$NON-NLS-1$ //$NON-NLS-2$
            if (reader.isAlive())
                throw new IOException("Не удалось получить полный ответ RAC.");
            if (readFailure.get() != null)
                throw readFailure.get();
            if (truncated.get())
                throw new IOException("Ответ RAC превышает 2 МБ. Не удалось получить полный список сеансов.");
            // У установленного Windows RAC вывод OEM (проверено на help session).
            // Ключи, UUID и номера ASCII; UTF-8 поддерживаем для других выпусков RAC.
            byte[] bytes = output.toByteArray();
            String result;
            try
            {
                result = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            }
            catch (java.nio.charset.CharacterCodingException e)
            {
                result = new String(bytes, Charset.forName("IBM866")); //$NON-NLS-1$
            }
            Global.tempLog("debug-session-rac", "exit=" + process.exitValue() + " bytes=" + bytes.length); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (process.exitValue() != 0)
                throw new IOException("RAC: " + redact(result.strip())); //$NON-NLS-1$
            return result;
        }
        finally
        {
            if (process.isAlive())
            {
                Global.tempLog("debug-session-rac", "destroy process " + processTag); //$NON-NLS-1$ //$NON-NLS-2$
                process.destroyForcibly();
            }
        }
    }

    /** Разделение по ключу записи не зависит от количества пустых строк в ответе RAC. */
    private static List<Map<String, String>> records(String output, String firstField)
    {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : output.split("\\R")) //$NON-NLS-1$
        {
            Matcher field = FIELD.matcher(line.strip());
            if (!field.matches())
                continue;
            String key = field.group(1);
            if (firstField.equals(key))
            {
                current = new LinkedHashMap<>();
                result.add(current);
            }
            if (current != null)
                current.put(key, field.group(2).strip());
        }
        return result;
    }
}

