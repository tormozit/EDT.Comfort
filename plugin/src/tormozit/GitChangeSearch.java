package tormozit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.egit.core.RepositoryUtil;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Поиск текста в изменениях файлов истории Git внешним {@code git log -G} (как в обработке
 * «История Git» ИР). Найденные коммиты и файлы с совпадением копятся в {@link Session} по мере
 * вывода git; показывает их панель «История Git» ({@link GitHistoryHook}).
 *
 * <p>С {@code -G} git перечисляет в {@code --name-status} только файлы, в изменениях которых есть
 * совпадение, — повторный разбор содержимого коммита не нужен.
 */
public final class GitChangeSearch
{
    private static final String TAG = "GitChangeSearch"; //$NON-NLS-1$
    private static final int MAX_SESSIONS = 10;
    /** Признак строки коммита в выводе git ({@code %x01}). */
    private static final char COMMIT_MARK = '\u0001';
    private static final String ERE_SPECIAL = "\\.[]{}()*+?^$|"; //$NON-NLS-1$

    private static final List<Session> sessions = new CopyOnWriteArrayList<>();

    private GitChangeSearch()
    {
    }

    /** Параметры поиска со страницы «Поиск в Git». */
    public record Query(String text, String fileMask, boolean caseSensitive, boolean regex, boolean allBranches)
    {
        /** Выражение для подсветки вхождений; {@code null}, если текст не разбирается. */
        Pattern highlightPattern()
        {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            try
            {
                return Pattern.compile(regex ? text : Pattern.quote(text), flags);
            }
            catch (PatternSyntaxException e)
            {
                return null;
            }
        }
    }

    enum State
    {
        RUNNING, DONE, STOPPED, FAILED
    }

    /** Один поиск: копит результат, пока работает git. */
    public static final class Session
    {
        private final Repository repository;
        private final Query query;
        private final Map<ObjectId, Set<String>> found = new ConcurrentHashMap<>();
        private final AtomicInteger version = new AtomicInteger();
        private volatile State state = State.RUNNING;
        private volatile String error;
        private volatile Process process;

        private Session(Repository repository, Query query)
        {
            this.repository = repository;
            this.query = query;
        }

        Repository repository()
        {
            return repository;
        }

        Query query()
        {
            return query;
        }

        State state()
        {
            return state;
        }

        boolean isRunning()
        {
            return state == State.RUNNING;
        }

        String error()
        {
            return error;
        }

        /** Растёт с каждым новым найденным коммитом. */
        int version()
        {
            return version.get();
        }

        int commitCount()
        {
            return found.size();
        }

        boolean contains(AnyObjectId commit)
        {
            return commit != null && found.containsKey(commit);
        }

        /** Пути файлов коммита, в изменениях которых есть совпадение; пусто, если коммит не найден. */
        Set<String> paths(AnyObjectId commit)
        {
            Set<String> paths = commit != null ? found.get(commit) : null;
            return paths != null ? paths : Set.of();
        }

        void stop()
        {
            if (state != State.RUNNING)
                return;
            state = State.STOPPED;
            Process running = process;
            if (running != null)
                running.destroy();
        }

        String repositoryName()
        {
            return RepositoryUtil.INSTANCE.getRepositoryName(repository);
        }

        /** Подпись поиска для полосы режима и меню «Найденное». */
        String label()
        {
            String mask = query.fileMask();
            return "«" + query.text() + "»" //$NON-NLS-1$ //$NON-NLS-2$
                + (mask == null || mask.isBlank() ? "" : " в файлах " + mask.trim()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void run()
        {
            List<String> command = command(query);
            File directory = repository.isBare() ? repository.getDirectory() : repository.getWorkTree();
            try
            {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(directory);
                Process started0 = builder.start();
                process = started0;
                if (state != State.RUNNING)
                    started0.destroy();
                StringBuilder errors = new StringBuilder();
                Thread errorReader = new Thread(() -> drain(started0, errors), "Comfort git log stderr"); //$NON-NLS-1$
                errorReader.setDaemon(true);
                errorReader.start();

                Set<String> current = null;
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started0.getInputStream(), StandardCharsets.UTF_8)))
                {
                    for (String line = reader.readLine(); line != null; line = reader.readLine())
                    {
                        if (line.isEmpty())
                            continue;
                        if (line.charAt(0) == COMMIT_MARK)
                        {
                            current = commitPaths(line.substring(1).trim());
                            continue;
                        }
                        if (current == null)
                            continue;
                        // «M<TAB>путь», для переименования — «R100<TAB>старый<TAB>новый»
                        String[] parts = line.split("\t"); //$NON-NLS-1$
                        for (int i = 1; i < parts.length; i++)
                            current.add(parts[i]);
                    }
                }
                int exit = started0.waitFor();
                errorReader.join(2000);
                if (state == State.RUNNING)
                {
                    if (exit == 0)
                        state = State.DONE;
                    else
                    {
                        error = errors.toString().trim();
                        state = State.FAILED;
                    }
                }
            }
            catch (IOException e)
            {
                error = "Не удалось запустить git: " + e.getMessage(); //$NON-NLS-1$
                state = State.FAILED;
                Global.logError(TAG, "git start failed", e); //$NON-NLS-1$
            }
            catch (InterruptedException e)
            {
                state = State.STOPPED;
                Thread.currentThread().interrupt();
            }
        }

        /** Коммит слияния с {@code -m} выводится по разу на родителя — пути копятся в одном наборе. */
        private Set<String> commitPaths(String hash)
        {
            if (!ObjectId.isId(hash))
                return null;
            ObjectId id = ObjectId.fromString(hash);
            Set<String> paths = found.get(id);
            if (paths == null)
            {
                paths = ConcurrentHashMap.newKeySet();
                found.put(id, paths);
                version.incrementAndGet();
            }
            return paths;
        }
    }

    /** Запустить поиск; результат копится в возвращённой сессии. */
    static Session start(Repository repository, Query query)
    {
        Session session = new Session(repository, query);
        sessions.add(0, session);
        while (sessions.size() > MAX_SESSIONS)
            sessions.remove(sessions.size() - 1).stop();
        Thread thread = new Thread(session::run, "Comfort git log -G"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
        return session;
    }

    /** Поиски этого сеанса EDT, свежие — первыми. */
    static List<Session> sessions()
    {
        return List.copyOf(sessions);
    }

    static void clearSessions(Session keep)
    {
        for (Session session : sessions)
        {
            if (session != keep)
            {
                session.stop();
                sessions.remove(session);
            }
        }
    }

    private static void drain(Process process, StringBuilder target)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)))
        {
            for (String line = reader.readLine(); line != null; line = reader.readLine())
            {
                if (target.length() < 2000)
                    target.append(line).append('\n');
            }
        }
        catch (IOException e)
        {
            // процесс остановлен
        }
    }

    private static List<String> command(Query query)
    {
        List<String> command = new ArrayList<>();
        command.add("git"); //$NON-NLS-1$
        command.add("-c"); //$NON-NLS-1$
        command.add("core.quotepath=false"); //$NON-NLS-1$
        command.add("log"); //$NON-NLS-1$
        command.add("--format=%x01%H"); //$NON-NLS-1$
        command.add("-m"); //$NON-NLS-1$
        command.add("--name-status"); //$NON-NLS-1$
        command.add(argument("-G" + gitRegex(query))); //$NON-NLS-1$
        if (!query.caseSensitive())
            command.add("-i"); //$NON-NLS-1$
        if (query.allBranches())
            command.add("--all"); //$NON-NLS-1$
        List<String> masks = pathspecs(query.fileMask());
        if (!masks.isEmpty())
        {
            command.add("--full-history"); //$NON-NLS-1$
            command.add("--"); //$NON-NLS-1$
            for (String mask : masks)
                command.add(argument(mask));
        }
        return command;
    }

    /**
     * Аргумент с двойными кавычками для командной строки Windows. {@code ProcessBuilder} кавычки
     * внутри аргумента не экранирует, а программа разбирает строку по правилам среды выполнения C:
     * кавычка передаётся как {@code \"}, обратные косые перед ней удваиваются. Проверено на
     * {@code git rev-parse --sq-quote}: кавычки, пробелы и обратные косые доходят без искажений.
     * В остальных системах аргументы передаются как есть.
     */
    private static String argument(String value)
    {
        if (File.separatorChar != '\\' || value.indexOf('"') < 0)
            return value;
        StringBuilder result = new StringBuilder();
        int slashes = 0;
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '\\')
            {
                slashes++;
                result.append(c);
                continue;
            }
            if (c == '"')
            {
                for (int k = 0; k < slashes; k++)
                    result.append('\\');
                result.append('\\');
            }
            slashes = 0;
            result.append(c);
        }
        return result.toString();
    }

    /**
     * Выражение для {@code -G} (расширенный синтаксис POSIX). Обычный текст экранируется.
     * Без учёта регистра буква вне ASCII раскрывается в пару вариантов — {@code -i} git-а для
     * кириллицы не действует.
     */
    private static String gitRegex(Query query)
    {
        String text = query.text();
        if (query.regex())
            return text;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            char lower = Character.toLowerCase(c);
            char upper = Character.toUpperCase(c);
            if (ERE_SPECIAL.indexOf(c) >= 0)
                result.append('\\').append(c);
            else if (!query.caseSensitive() && c > 127 && lower != upper)
                result.append('(').append(lower).append('|').append(upper).append(')');
            else
                result.append(c);
        }
        return result.toString();
    }

    /** Маски через «|», как в ИР; маска без подстановочных знаков ищется как часть пути. */
    private static List<String> pathspecs(String fileMask)
    {
        List<String> result = new ArrayList<>();
        if (fileMask == null)
            return result;
        for (String part : fileMask.split("\\|")) //$NON-NLS-1$
        {
            String mask = part.trim().replace('\\', '/');
            if (mask.isEmpty())
                continue;
            if (mask.indexOf('*') < 0 && mask.indexOf('?') < 0)
                mask = "*" + mask + "*"; //$NON-NLS-1$ //$NON-NLS-2$
            result.add(":(top,icase)" + mask); //$NON-NLS-1$
        }
        return result;
    }
}
