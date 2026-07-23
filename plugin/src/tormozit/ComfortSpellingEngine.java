package tormozit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckIterator;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellChecker;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Загрузчик словарей Hunspell/MySpell и единые правила токенизации Comfort.
 * Единственный источник диапазонов ошибок — {@link #findMisspelledRanges}
 * (BSL, свойства, сообщение коммита): {@link SpellCheckIterator} + при необходимости
 * {@link #splitIdentifierSegments} (CamelCase / цифры) по флагам «Орфография»;
 * «Игнорировать слова ЗАГЛАВНЫМИ» — и к сегментам после дробления.
 * {@link #isCorrect} — boolean для штатного DefaultSpellChecker (Java-комментарии и т.п.):
 * те же сегменты, без своих аннотаций.
 * Пути словарей — {@link ComfortSettings#getSpellingDictionaryBasePaths()}.
 * Дополнение Comfort — {@code dictionaries/hunspell/comfort-extra-ru.dic} (UTF-8, леммы + флаги AOT)
 * с морфологией из {@code russian-aot-ieyo.aff}; большой {@code .dic} AOT не правим.
 * Старые точные слова — {@code spelling-user-dictionary.txt}; morph из UI —
 * общий {@code spelling-comfort-common.dic} (stateLocation или путь из настроек)
 * и проектный {@code .comfort/spelling-comfort-project.dic} (оба в проверке).
 */
public final class ComfortSpellingEngine
{
    private static final String USER_DICT_FILE = "spelling-user-dictionary.txt"; //$NON-NLS-1$
    private static final String USER_MORPH_DICT_FILE_COMMON = "spelling-comfort-common.dic"; //$NON-NLS-1$
    private static final String USER_MORPH_DICT_FILE_PROJECT = "spelling-comfort-project.dic"; //$NON-NLS-1$
    private static final String PROJECT_MORPH_DIR = ".comfort"; //$NON-NLS-1$
    private static final String AOT_RU_BASE = "dictionaries/hunspell/russian-aot-ieyo"; //$NON-NLS-1$
    /** Леммы IT/1С; .aff берём у AOT (не дублируем). */
    private static final String EXTRA_RU_DIC_BASE = "dictionaries/hunspell/comfort-extra-ru"; //$NON-NLS-1$
    /** ISO 639 languages + ISO 3166 countries (lowercase). */
    private static volatile Set<String> LOCALE_CODES;
    private static final int SUGGEST_CACHE_MAX = 512;
    private static final int MORPH_PREVIEW_MAX = 8;

    private static volatile List<HunspellDictionary> dictionaries;
    private static final Object USER_LOCK = new Object();
    private static final Object USER_MORPH_LOCK = new Object();
    private static volatile Set<String> userWords;
    /** Общий morph-dic (stateLocation). */
    private static volatile HunspellDictionary userMorphDictionary;
    /** Проектный morph-dic активного проекта. */
    private static volatile HunspellDictionary projectMorphDictionary;
    private static volatile String projectMorphProjectName;
    private static final ConcurrentHashMap<String, List<String>> suggestCache =
        new ConcurrentHashMap<>();
    private static volatile boolean projectMorphListenerInstalled;

    private ComfortSpellingEngine()
    {
    }

    /** Общий кэш словарей для Platform dictionary. */
    static List<HunspellDictionary> sharedDictionaries()
    {
        List<HunspellDictionary> result = dictionaries;
        if (result == null)
        {
            synchronized (ComfortSpellingEngine.class)
            {
                result = dictionaries;
                if (result == null)
                {
                    result = loadAll();
                    dictionaries = result;
                }
            }
        }
        return result;
    }

    /**
     * Подсказки исправления: сначала словарь того же алфавита (латиница → EN, кириллица → RU),
     * с кэшем. Не гоняем RU-словарь для {@code http} и т.п. — иначе UI подвисает на d2.
     */
    static List<String> suggest(String word, int max)
    {
        if (word == null || word.isEmpty() || max <= 0)
            return List.of();
        String cacheKey = suggestCacheKey(word, max);
        List<String> cached = suggestCache.get(cacheKey);
        if (cached != null)
            return cached;
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        suggestStreaming(word, max, merged::add, null);
        List<String> result = merged.isEmpty() ? List.of() : List.copyOf(merged);
        return cacheSuggest(cacheKey, result);
    }

    /** Кэш hit или {@code null}, если ещё не считали. */
    static List<String> peekSuggestCache(String word, int max)
    {
        if (word == null || word.isEmpty() || max <= 0)
            return null;
        return suggestCache.get(suggestCacheKey(word, max));
    }

    /**
     * Потоковый suggest для фонового Job. По завершении (если не отменён) кладёт
     * накопленный список в кэш.
     */
    static void suggestStreaming(String word, int max, Consumer<String> onSuggestion,
        IProgressMonitor monitor)
    {
        if (word == null || word.isEmpty() || max <= 0 || onSuggestion == null)
            return;
        String cacheKey = suggestCacheKey(word, max);
        List<String> cached = suggestCache.get(cacheKey);
        if (cached != null)
        {
            for (String s : cached)
                onSuggestion.accept(s);
            return;
        }
        HunspellDictionary.ScriptKind script = detectWordScript(word);
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        List<HunspellDictionary> all = dictionariesForCheck();
        for (HunspellDictionary dict : all)
        {
            if (monitor != null && monitor.isCanceled())
                return;
            if (!dict.matchesScript(script))
                continue;
            dict.suggestStreaming(word, max, s ->
            {
                if (merged.add(s))
                    onSuggestion.accept(s);
            }, monitor);
            if (merged.size() >= max)
                break;
        }
        if (monitor == null || !monitor.isCanceled())
            cacheSuggest(cacheKey, merged.isEmpty() ? List.of() : List.copyOf(merged));
    }

    private static String suggestCacheKey(String word, int max)
    {
        return word.toLowerCase(Locale.ROOT) + '#' + max;
    }

    private static List<String> cacheSuggest(String key, List<String> value)
    {
        if (suggestCache.size() >= SUGGEST_CACHE_MAX)
            suggestCache.clear();
        suggestCache.put(key, value);
        return value;
    }

    static HunspellDictionary.ScriptKind detectWordScript(String word)
    {
        boolean cyrillic = false;
        boolean latin = false;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c))
                continue;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CYRILLIC
                || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY)
                cyrillic = true;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
                latin = true;
        }
        if (cyrillic && latin)
            return HunspellDictionary.ScriptKind.ANY;
        if (cyrillic)
            return HunspellDictionary.ScriptKind.CYRILLIC;
        if (latin)
            return HunspellDictionary.ScriptKind.LATIN;
        return HunspellDictionary.ScriptKind.ANY;
    }

    private static HunspellDictionary.ScriptKind scriptFromDictPath(String basePath)
    {
        String lower = basePath.toLowerCase(Locale.ROOT);
        if (lower.contains("en_") || lower.contains("/en") || lower.contains("\\en") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.contains("english")) //$NON-NLS-1$
            return HunspellDictionary.ScriptKind.LATIN;
        if (lower.contains("ru_") || lower.contains("russian") || lower.contains("aot") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.contains("/ru") || lower.contains("\\ru")) //$NON-NLS-1$ //$NON-NLS-2$
            return HunspellDictionary.ScriptKind.CYRILLIC;
        return HunspellDictionary.ScriptKind.ANY;
    }

    static boolean isCorrect(String word)
    {
        if (word == null || word.isEmpty())
            return true;
        if (isShortAllCapsWord(word))
            return true;
        if (isLocaleCode(word))
            return true;
        if (isUserWord(word))
            return true;
        List<HunspellDictionary> dicts = dictionariesForCheck();
        if (isCorrect(dicts, word))
            return true;
        // DefaultSpellChecker не дробит CamelCase/цифры — те же условия, что findMisspelledRanges.
        if (!shouldSplitIdentifierToken(word, false))
            return false;
        return isCorrectByIdentifierSegments(word, dicts);
    }

    /** Штатные словари + общий и проектный morph-dic (если загружены). */
    private static List<HunspellDictionary> dictionariesForCheck()
    {
        ensureProjectMorphListener();
        List<HunspellDictionary> base = sharedDictionaries();
        HunspellDictionary common = sharedCommonUserMorphDictionary();
        HunspellDictionary project = sharedProjectUserMorphDictionary();
        if (common == null && project == null)
            return base;
        List<HunspellDictionary> all = new ArrayList<>(base.size() + 2);
        all.addAll(base);
        if (common != null)
            all.add(common);
        if (project != null)
            all.add(project);
        return all;
    }

    private static HunspellDictionary sharedCommonUserMorphDictionary()
    {
        HunspellDictionary result = userMorphDictionary;
        if (result != null)
            return result;
        synchronized (USER_MORPH_LOCK)
        {
            result = userMorphDictionary;
            if (result == null)
            {
                result = loadUserMorphDictionary(getCommonUserMorphDictionaryFile());
                userMorphDictionary = result;
            }
            return result;
        }
    }

    private static HunspellDictionary sharedProjectUserMorphDictionary()
    {
        IProject project = resolveSpellingProject();
        String name = project != null ? project.getName() : null;
        synchronized (USER_MORPH_LOCK)
        {
            if (Objects.equals(name, projectMorphProjectName))
                return projectMorphDictionary;
            projectMorphProjectName = name;
            projectMorphDictionary = name == null ? null
                : loadUserMorphDictionary(getProjectUserMorphDictionaryFile(project));
            return projectMorphDictionary;
        }
    }

    private static IProject resolveSpellingProject()
    {
        return Global.getActiveProject((IWorkbenchPage) null, false);
    }

    /** Сброс кэша проектного словаря и пересчёт орфографии (смена активного проекта). */
    static void onActiveProjectChanged()
    {
        synchronized (USER_MORPH_LOCK)
        {
            projectMorphProjectName = null;
            projectMorphDictionary = null;
        }
        suggestCache.clear();
        Runnable ui = ComfortSpellingEngine::refreshSpellingAfterUserDictionaryReload;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            ui.run();
        else
            display.asyncExec(ui);
    }

    private static void ensureProjectMorphListener()
    {
        if (projectMorphListenerInstalled)
            return;
        synchronized (ComfortSpellingEngine.class)
        {
            if (projectMorphListenerInstalled)
                return;
            projectMorphListenerInstalled = true;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable install = () ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            {
                if (window == null)
                    continue;
                for (IWorkbenchPage page : window.getPages())
                {
                    ActiveProjectTracker.bootstrapPage(page);
                    ActiveProjectTracker.addListener(page,
                        (p, previous, current) -> onActiveProjectChanged());
                }
            }
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow w)
                {
                    if (w == null)
                        return;
                    for (IWorkbenchPage page : w.getPages())
                    {
                        ActiveProjectTracker.bootstrapPage(page);
                        ActiveProjectTracker.addListener(page,
                            (p, previous, current) -> onActiveProjectChanged());
                    }
                }

                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
        };
        if (display.getThread() == Thread.currentThread())
            install.run();
        else
            display.asyncExec(install);
    }

    /**
     * Когда Comfort дробит токен iterator’а на сегменты (как в findMisspelledRangesViaJdt).
     */
    private static boolean shouldSplitIdentifierToken(String word, boolean startsSentence)
    {
        if (word == null || word.isEmpty())
            return false;
        IPreferenceStore prefs = PreferenceConstants.getPreferenceStore();
        if (containsDigit(word) && !prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_DIGITS))
            return true;
        return isMixedCaseWord(word, startsSentence)
            && !prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_MIXED);
    }

    /**
     * {@code ТолстыйКлиент} / {@code item2Name}: все буквенные сегменты ≥2 верны в словаре.
     * Один сегмент или нет буквенных частей — {@code false} (целое уже проверено выше).
     */
    private static boolean isCorrectByIdentifierSegments(String word, List<HunspellDictionary> dicts)
    {
        if (word == null || word.length() < 2 || dicts == null || dicts.isEmpty())
            return false;
        List<int[]> segments = splitIdentifierSegments(word, 0, word.length());
        if (segments.size() <= 1)
            return false;
        boolean anyLetterSeg = false;
        for (int[] seg : segments)
        {
            int segStart = seg[0];
            int segEnd = seg[1];
            if (segEnd - segStart < 2)
                continue;
            String part = word.substring(segStart, segEnd);
            if (!hasLetter(part))
                continue;
            anyLetterSeg = true;
            if (shouldIgnoreUpperSegment(part) || isShortAllCapsWord(part) || isLocaleCode(part)
                || isUserWord(part))
                continue;
            if (!isCorrect(dicts, part))
            {
                if (ComfortSettings.isSpellingIgnoreCamelCaseAbbreviations()
                    && isCamelCaseAbbreviation(word, segments, dicts))
                    return true;
                return false;
            }
        }
        return anyLetterSeg;
    }

    /**
     * CamelCase-аббревиатура: каждый сегмент (кроме последнего) является префиксом
     * слова из словаря, последний — полное слово. {@code ФизЛицо} → {@code ФизическоеЛицо}.
     *
     * @param segments сегменты всего {@code word} (вычислены вызывающим кодом)
     */
    private static boolean isCamelCaseAbbreviation(String word, List<int[]> segments,
        List<HunspellDictionary> dicts)
    {
        if (segments.size() <= 1)
            return false;
        int lastIdx = segments.size() - 1;
        boolean anyIncorrect = false;
        for (int idx = 0; idx < segments.size(); idx++)
        {
            int[] seg = segments.get(idx);
            int segStart = seg[0];
            int segEnd = seg[1];
            if (segEnd - segStart < 2)
                continue;
            String part = word.substring(segStart, segEnd);
            if (!hasLetter(part))
                continue;
            if (shouldIgnoreUpperSegment(part) || isShortAllCapsWord(part) || isLocaleCode(part)
                || isUserWord(part))
                continue;
            if (isCorrect(dicts, part))
                continue;
            if (idx == lastIdx)
                return false;
            anyIncorrect = true;
            boolean foundPrefix = false;
            for (HunspellDictionary dict : dicts)
            {
                if (dict.hasWordStartingWith(part))
                {
                    foundPrefix = true;
                    break;
                }
            }
            if (!foundPrefix)
                return false;
        }
        return anyIncorrect;
    }

    /** Слова только из заглавных букв длиной ≤ 3 (аббревиатуры вроде ИД, XML) не проверяем. */
    private static boolean isShortAllCapsWord(String word)
    {
        int len = word.length();
        if (len == 0 || len > 3)
            return false;
        for (int i = 0; i < len; i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c) || !Character.isUpperCase(c))
                return false;
        }
        return true;
    }

    /** Двухбуквенные ISO-коды языков/стран ({@code ru}, {@code en}, {@code ua}, {@code kz}). */
    private static boolean isLocaleCode(String word)
    {
        return word != null && word.length() == 2
            && localeCodeSet().contains(word.toLowerCase(Locale.ROOT));
    }

    private static Set<String> localeCodeSet()
    {
        Set<String> cached = LOCALE_CODES;
        if (cached != null)
            return cached;
        synchronized (ComfortSpellingEngine.class)
        {
            if (LOCALE_CODES != null)
                return LOCALE_CODES;
            Set<String> set = new HashSet<>();
            for (String lang : Locale.getISOLanguages())
            {
                if (lang != null && !lang.isEmpty())
                    set.add(lang.toLowerCase(Locale.ROOT));
            }
            for (String country : Locale.getISOCountries())
            {
                if (country != null && !country.isEmpty())
                    set.add(country.toLowerCase(Locale.ROOT));
            }
            LOCALE_CODES = Set.copyOf(set);
            return LOCALE_CODES;
        }
    }

    /** Слово уже в пользовательском словаре Comfort (без учёта регистра). */
    static boolean isUserWord(String word)
    {
        if (word == null || word.isEmpty())
            return false;
        return userWordSet().contains(normalizeUserWord(word));
    }

    /**
     * Добавить слово в пользовательский словарь (persist в stateLocation).
     *
     * @return {@code true}, если слово было новым
     */
    static boolean addUserWord(String word)
    {
        if (word == null || word.isBlank())
            return false;
        String key = normalizeUserWord(word.trim());
        if (key.isEmpty())
            return false;
        Set<String> set = userWordSet();
        synchronized (USER_LOCK)
        {
            if (!set.add(key))
                return false;
            persistUserWords(set);
            return true;
        }
    }

    /**
     * UI-добавление: диалог морфологии → общий или проектный morph-dic + тост
     * «Отменить» + пересчёт подчёркиваний.
     *
     * @return {@code true}, если запись была новой
     */
    static boolean addUserWordFromUi(String word)
    {
        if (word == null || word.isBlank())
            return false;
        String seed = word.trim();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return false;
        MorphAddResult[] box = new MorphAddResult[1];
        Runnable openDialog = () ->
        {
            Shell shell = display.getActiveShell();
            if (shell == null || shell.isDisposed())
            {
                Shell[] shells = display.getShells();
                if (shells != null)
                {
                    for (Shell s : shells)
                    {
                        if (s != null && !s.isDisposed())
                        {
                            shell = s;
                            break;
                        }
                    }
                }
            }
            Rectangle hoverBounds = captureSpellingHoverBounds(display);
            MorphAddWordDialog dialog = new MorphAddWordDialog(shell, seed, hoverBounds);
            if (dialog.open() == Window.OK)
                box[0] = dialog.result();
        };
        if (display.getThread() == Thread.currentThread())
            openDialog.run();
        else
            display.syncExec(openDialog);
        MorphAddResult chosen = box[0];
        if (chosen == null || chosen.lemma == null || chosen.lemma.isBlank())
            return false;
        String lemma = chosen.lemma.trim();
        String flag = chosen.morphology ? chosen.flag : null;
        boolean projectScoped = chosen.projectScoped;
        if (projectScoped && resolveSpellingProject() == null)
            projectScoped = false;
        if (!addUserMorphEntry(lemma, flag, projectScoped))
            return false;
        suggestCache.clear();
        String toastLemma = lemma;
        String toastFlag = flag;
        boolean toastProject = projectScoped;
        int dictSize = userMorphDictionarySize(projectScoped);
        Runnable ui = () ->
        {
            refreshSpellingAfterUserWordChange(toastLemma, true);
            ToastNotification.show(
                "Орфография", //$NON-NLS-1$
                "Слово «" + toastLemma + "» добавлено в словарь. Всего слов: " //$NON-NLS-1$ //$NON-NLS-2$
                    + dictSize + ".", //$NON-NLS-1$
                10_000,
                () -> undoUserMorphAdd(toastLemma, toastFlag, toastProject),
                "Отменить добавление"); //$NON-NLS-1$
        };
        if (display.getThread() == Thread.currentThread())
            ui.run();
        else
            display.asyncExec(ui);
        return true;
    }

    /** Границы hover орфографии (live или кэш BSL) для позиционирования диалога. */
    private static Rectangle captureSpellingHoverBounds(Display display)
    {
        Rectangle fromBsl = BslModuleSpellCheckHook.peekSpellingHoverShellBounds();
        if (fromBsl != null)
            return fromBsl;
        if (display == null || display.isDisposed())
            return null;
        Control focus = display.getFocusControl();
        if (focus != null && !focus.isDisposed())
        {
            Shell focused = focus.getShell();
            if (MorphAddWordDialog.isLikelySpellingHoverShell(focused, true))
                return focused.getBounds();
        }
        Shell active = display.getActiveShell();
        if (MorphAddWordDialog.isLikelySpellingHoverShell(active, true))
            return active.getBounds();
        Shell lastVisible = null;
        Shell lastHidden = null;
        for (Shell s : display.getShells())
        {
            if (!MorphAddWordDialog.isLikelySpellingHoverShell(s, true))
                continue;
            if (s.isVisible())
                lastVisible = s;
            else
                lastHidden = s;
        }
        Shell pick = lastVisible != null ? lastVisible : lastHidden;
        return pick != null ? pick.getBounds() : null;
    }

    /** Удалить слово из пользовательского словаря. */
    static boolean removeUserWord(String word)
    {
        if (word == null || word.isBlank())
            return false;
        String key = normalizeUserWord(word.trim());
        if (key.isEmpty())
            return false;
        Set<String> set = userWordSet();
        synchronized (USER_LOCK)
        {
            if (!set.remove(key))
                return false;
            persistUserWords(set);
            return true;
        }
    }

    private static void undoUserWordAdd(String word)
    {
        if (!removeUserWord(word))
            return;
        suggestCache.clear();
        refreshSpellingAfterUserWordChange(word, false);
    }

    private static void undoUserMorphAdd(String lemma, String flagOrNull, boolean projectScoped)
    {
        if (!removeUserMorphEntry(lemma, flagOrNull, projectScoped))
            return;
        suggestCache.clear();
        refreshSpellingAfterUserWordChange(lemma, false);
    }

    /**
     * @param added {@code true} — слово только что добавлено (снять ошибки с этим словом);
     *              {@code false} — отмена (пересчитать заново).
     */
    private static void refreshSpellingAfterUserWordChange(String word, boolean added)
    {
        BslModuleSpellCheckHook.onUserDictionaryChanged(word, added);
        PropertySheetSpellCheckHook.onUserDictionaryChanged(word, added);
        CommitMessageSpellCheckHook.onUserDictionaryChanged(word, added);
    }

    /** Полный пересчёт орфографии после перечитывания файла словаря. */
    private static void refreshSpellingAfterUserDictionaryReload()
    {
        BslModuleSpellCheckHook.onUserDictionaryChanged(null, false);
        PropertySheetSpellCheckHook.onUserDictionaryChanged(null, false);
        CommitMessageSpellCheckHook.onUserDictionaryChanged(null, false);
    }

    private static String normalizeUserWord(String word)
    {
        return word.toLowerCase(Locale.ROOT);
    }

    private static Set<String> userWordSet()
    {
        Set<String> result = userWords;
        if (result == null)
        {
            synchronized (USER_LOCK)
            {
                result = userWords;
                if (result == null)
                {
                    result = ConcurrentHashMap.newKeySet();
                    loadUserWords(result);
                    userWords = result;
                }
            }
        }
        return result;
    }

    private static void loadUserWords(Set<String> into)
    {
        File file = userDictionaryFile();
        if (file == null || !file.isFile())
            return;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) //$NON-NLS-1$
                    continue;
                into.add(normalizeUserWord(trimmed));
            }
        }
        catch (IOException e)
        {
        }
    }

    /**
     * Запись на диск всегда в нижнем регистре и по возрастанию
     * ({@link Collections#sort(List)}).
     */
    private static void persistUserWords(Set<String> words)
    {
        File file = userDictionaryFile();
        if (file == null)
            return;
        try
        {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            List<String> sorted = new ArrayList<>(words);
            Collections.sort(sorted);
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)))
            {
                for (String w : sorted)
                {
                    writer.write(w);
                    writer.newLine();
                }
            }
        }
        catch (IOException e)
        {
        }
    }

    /** Файл пользовательского словаря Comfort (может ещё не существовать на диске). */
    static File getUserDictionaryFile()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return null;
        IPath location = activator.getStateLocation();
        if (location == null)
            return null;
        return location.append(USER_DICT_FILE).toFile();
    }

    /**
     * Создать файл словаря при отсутствии (пустое отсортированное содержимое)
     * и вернуть путь.
     */
    static File ensureUserDictionaryFile()
    {
        File file = getUserDictionaryFile();
        if (file == null)
            return null;
        synchronized (USER_LOCK)
        {
            Set<String> set = userWordSet();
            if (!file.isFile())
                persistUserWords(set);
        }
        return file;
    }

    /**
     * Перечитать файл в память, нормализовать, перезаписать отсортированным,
     * сбросить suggest-кэш и обновить орфографию в UI.
     */
    static void reloadUserDictionaryFromDisk()
    {
        synchronized (USER_LOCK)
        {
            Set<String> fresh = ConcurrentHashMap.newKeySet();
            loadUserWords(fresh);
            userWords = fresh;
            persistUserWords(fresh);
        }
        suggestCache.clear();
        Runnable ui = ComfortSpellingEngine::refreshSpellingAfterUserDictionaryReload;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            ui.run();
        else
            display.asyncExec(ui);
    }

    private static File userDictionaryFile()
    {
        return getUserDictionaryFile();
    }

    /**
     * Ошибочные диапазоны в {@code text} (относительные offset/length).
     * Штатный {@link SpellCheckIterator} (URL и т.п.) + проверка Hunspell;
     * при выключенном {@code SPELLING_IGNORE_MIXED} / {@code SPELLING_IGNORE_DIGITS} —
     * дробление CamelCase и по цифрам через {@link #splitIdentifierSegments}.
     * Fallback — legacy-разбор.
     */
    static List<int[]> findMisspelledRanges(String text)
    {
        if (text == null || text.isEmpty())
            return List.of();
        List<int[]> viaJdt = findMisspelledRangesViaJdt(text);
        if (viaJdt != null)
            return viaJdt;
        return findMisspelledRangesLegacy(text);
    }

    /**
     * @return список диапазонов или {@code null}, если штатный checker/iterator недоступен
     */
    private static List<int[]> findMisspelledRangesViaJdt(String text)
    {
        try
        {
            ISpellChecker checker = SpellCheckEngine.getInstance().getSpellChecker();
            if (checker == null)
                return null;
            Locale locale = checker.getLocale();
            IPreferenceStore prefs = PreferenceConstants.getPreferenceStore();
            if (locale == null)
            {
                String localeKey = prefs.getString(PreferenceConstants.SPELLING_LOCALE);
                locale = SpellCheckEngine.convertToLocale(localeKey);
            }
            if (locale == null)
                locale = new Locale("ru", "RU"); //$NON-NLS-1$ //$NON-NLS-2$
            IDocument document = new Document(maskAmpersand(text));
            SpellCheckIterator iterator = new SpellCheckIterator(document,
                new Region(0, text.length()), locale, null);
            if (prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_SINGLE_LETTERS))
                iterator.setIgnoreSingleLetters(true);
            boolean ignoreMixed = prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_MIXED);
            boolean ignoreUpper = prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_UPPER);
            boolean ignoreDigits = prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_DIGITS);
            List<int[]> result = new ArrayList<>();
            while (iterator.hasNext())
            {
                String word = iterator.next();
                if (word == null || word.isEmpty())
                    continue;
                int begin = iterator.getBegin();
                int end = iterator.getEnd();
                if (begin < 0 || end < begin || begin >= text.length())
                    continue;
                int tokenEnd = Math.min(end + 1, text.length());
                if (tokenEnd <= begin)
                    continue;
                boolean hasDigit = containsDigit(word);
                if (ignoreDigits && hasDigit)
                    continue;
                if (ignoreUpper && isAllUpperLetters(word))
                    continue;
                boolean startsSentence = iterator.startsSentence();
                boolean mixed = isMixedCaseWord(word, startsSentence);
                if (mixed && ignoreMixed)
                    continue;
                if (shouldSplitIdentifierToken(word, startsSentence))
                {
                    List<int[]> segs = splitIdentifierSegments(text, begin, tokenEnd);
                    boolean segMiss = false;
                    for (int[] seg : segs)
                    {
                        int segStart = seg[0];
                        int segEnd = seg[1];
                        if (segEnd - segStart < 2)
                            continue;
                        String part = text.substring(segStart, segEnd);
                        if (!hasLetter(part))
                            continue;
                        if (shouldIgnoreUpperSegment(part) || isCorrect(part))
                            continue;
                        segMiss = true;
                        break;
                    }
                    if (segMiss)
                    {
                        boolean ignore = ComfortSettings.isSpellingIgnoreCamelCaseAbbreviations();
                        List<HunspellDictionary> dicts = dictionariesForCheck();
                        boolean abbrev = isCamelCaseAbbreviation(text, segs, dicts);
                        if (ignore && abbrev)
                            continue;
                        for (int[] seg : segs)
                        {
                            int segStart = seg[0];
                            int segEnd = seg[1];
                            if (segEnd - segStart < 2)
                                continue;
                            String part = text.substring(segStart, segEnd);
                            if (!hasLetter(part))
                                continue;
                            if (shouldIgnoreUpperSegment(part) || isCorrect(part))
                                continue;
                            result.add(new int[] { segStart, segEnd - segStart });
                        }
                    }
                }
                else if (word.length() >= 2 && !isCorrect(word))
                {
                    result.add(new int[] { begin, tokenEnd - begin });
                }
            }
            return result;
        }
        catch (IllegalStateException | LinkageError e)
        {
            return null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * {@link SpellCheckIterator} рассчитан на Javadoc и трактует {@code &<буква>} без
     * завершающей {@code ;} как незакрытую HTML-сущность - в этом случае склеивает {@code &}
     * со следующим словом в один токен ({@code "&Ссылка"} вместо {@code "Ссылка"}), и токен не
     * находится в словаре. В BSL {@code &} - синтаксис параметра запроса/директивы, а не начало
     * сущности, поэтому гасим его пробелом (та же длина строки, смещения диапазонов не съезжают)
     * до передачи текста в итератор.
     */
    private static String maskAmpersand(String text)
    {
        if (text.indexOf('&') < 0)
            return text;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++)
        {
            if (chars[i] == '&')
                chars[i] = ' ';
        }
        return new String(chars);
    }

    /** Как DefaultSpellChecker.isMixedCase: первая заглавная в начале предложения не считается. */
    private static boolean isMixedCaseWord(String word, boolean startsSentence)
    {
        if (word == null || word.length() < 2)
            return false;
        boolean hasLower = false;
        boolean hasUpper = false;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c))
                continue;
            if (Character.isLowerCase(c))
                hasLower = true;
            else if (Character.isUpperCase(c) && (i > 0 || !startsSentence))
                hasUpper = true;
        }
        return hasLower && hasUpper;
    }

    private static boolean isAllUpperLetters(String word)
    {
        boolean any = false;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c))
                continue;
            any = true;
            if (!Character.isUpperCase(c))
                return false;
        }
        return any;
    }

    /** Сегмент CamelCase: флаг «Игнорировать слова ЗАГЛАВНЫМИ» (к целому токену не применялся). */
    private static boolean shouldIgnoreUpperSegment(String part)
    {
        if (part == null || part.isEmpty())
            return false;
        IPreferenceStore prefs = PreferenceConstants.getPreferenceStore();
        return prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_UPPER) && isAllUpperLetters(part);
    }

    private static boolean containsDigit(String word)
    {
        for (int i = 0; i < word.length(); i++)
        {
            if (Character.isDigit(word.charAt(i)))
                return true;
        }
        return false;
    }

    /** Fallback без JDT iterator — camelCase-сегменты, без флагов «Орфография». */
    private static List<int[]> findMisspelledRangesLegacy(String text)
    {
        List<int[]> result = new ArrayList<>();
        List<HunspellDictionary> dicts = dictionariesForCheck();
        if (dicts.isEmpty())
            return result;
        int start = -1;
        int length = text.length();
        for (int i = 0; i <= length; i++)
        {
            boolean wordChar = i < length && isWordChar(text.charAt(i));
            if (wordChar)
            {
                if (start < 0)
                    start = i;
                continue;
            }
            if (start < 0)
                continue;
            addIfMisspelledLegacy(text, start, i, dicts, result);
            start = -1;
        }
        return result;
    }

    private static void addIfMisspelledLegacy(String text, int start, int end,
        List<HunspellDictionary> dicts, List<int[]> out)
    {
        while (start < end && isTrimChar(text.charAt(start)))
            start++;
        while (end > start && isTrimChar(text.charAt(end - 1)))
            end--;
        if (start >= end)
            return;
        String whole = text.substring(start, end);
        if (!hasLetter(whole))
            return;
        List<int[]> segments = splitIdentifierSegments(text, start, end);
        if (segments.size() <= 1)
        {
            if (whole.length() >= 2 && !isCorrect(dicts, whole))
                out.add(new int[] { start, end - start });
            return;
        }
        for (int[] seg : segments)
        {
            int segStart = seg[0];
            int segEnd = seg[1];
            if (segEnd - segStart < 2)
                continue;
            String word = text.substring(segStart, segEnd);
            if (!hasLetter(word))
                continue;
            if (shouldIgnoreUpperSegment(word) || isCorrect(dicts, word))
                continue;
            if (ComfortSettings.isSpellingIgnoreCamelCaseAbbreviations()
                && isCamelCaseAbbreviation(text, segments, dicts))
                return;
            out.add(new int[] { segStart, segEnd - segStart });
        }
    }

    /** Слово в диапазоне помечено штатной проверкой как ошибка. */
    static boolean isMisspelledAt(String text, int offset, int length)
    {
        if (text == null || length <= 0 || offset < 0 || offset + length > text.length())
            return false;
        for (int[] r : findMisspelledRanges(text))
        {
            if (r[0] == offset && r[1] == length)
                return true;
            if (offset < r[0] + r[1] && offset + length > r[0])
                return true;
        }
        return false;
    }

    static List<int[]> splitIdentifierSegments(String text, int start, int end)
    {
        List<int[]> segments = new ArrayList<>();
        if (text == null || start >= end)
            return segments;
        int segStart = start;
        for (int i = start + 1; i < end; i++)
        {
            char prev = text.charAt(i - 1);
            char cur = text.charAt(i);
            boolean boundary = false;
            if (isLowerLetter(prev) && isUpperLetter(cur))
                boundary = true;
            else if (Character.isLetter(prev) && Character.isDigit(cur))
                boundary = true;
            else if (Character.isDigit(prev) && Character.isLetter(cur))
                boundary = true;
            else if (isUpperLetter(prev) && isUpperLetter(cur)
                && i + 1 < end && isLowerLetter(text.charAt(i + 1)))
                boundary = true;
            if (boundary)
            {
                segments.add(new int[] { segStart, i });
                segStart = i;
            }
        }
        segments.add(new int[] { segStart, end });
        return segments;
    }

    private static boolean isCorrect(List<HunspellDictionary> dicts, String word)
    {
        if (isShortAllCapsWord(word))
            return true;
        if (isLocaleCode(word))
            return true;
        if (isUserWord(word))
            return true;
        for (HunspellDictionary dict : dicts)
        {
            if (dict.isCorrect(word))
                return true;
        }
        return false;
    }

    private static boolean isUpperLetter(char c)
    {
        return Character.isLetter(c) && Character.isUpperCase(c);
    }

    private static boolean isLowerLetter(char c)
    {
        return Character.isLetter(c) && Character.isLowerCase(c);
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '-' || c == '\'';
    }

    private static boolean isTrimChar(char c)
    {
        return c == '-' || c == '\'';
    }

    private static boolean hasLetter(String word)
    {
        for (int i = 0; i < word.length(); i++)
        {
            if (Character.isLetter(word.charAt(i)))
                return true;
        }
        return false;
    }

    private static List<HunspellDictionary> loadAll()
    {
        List<HunspellDictionary> result = new ArrayList<>();
        for (String basePath : ComfortSettings.getSpellingDictionaryBasePaths())
        {
            if (basePath == null || basePath.isBlank())
                continue;
            String trimmed = basePath.trim();
            try
            {
                File aff = resolveDictionaryFile(trimmed, ".aff"); //$NON-NLS-1$
                File dic = resolveDictionaryFile(trimmed, ".dic"); //$NON-NLS-1$
                if (aff == null || dic == null || !aff.isFile() || !dic.isFile())
                {
                    continue;
                }
                HunspellDictionary.ScriptKind script = scriptFromDictPath(trimmed);
                result.add(HunspellDictionary.load(aff, dic, script));
            }
            catch (Exception e)
            {
            }
        }
        loadComfortExtraRu(result);
        return result.isEmpty() ? Collections.emptyList() : List.copyOf(result);
    }

    /**
     * Леммы Comfort + аффиксы AOT (без своей копии .aff). {@code .dic} — UTF-8.
     */
    private static void loadComfortExtraRu(List<HunspellDictionary> result)
    {
        try
        {
            File aff = resolveDictionaryFile(AOT_RU_BASE, ".aff"); //$NON-NLS-1$
            File dic = resolveDictionaryFile(EXTRA_RU_DIC_BASE, ".dic"); //$NON-NLS-1$
            if (aff == null || dic == null || !aff.isFile() || !dic.isFile())
            {
                return;
            }
            result.add(HunspellDictionary.load(aff, dic, HunspellDictionary.ScriptKind.CYRILLIC,
                StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
        }
    }

    /**
     * Абсолютный путь на диске или относительный путь ресурса бандла
     * {@code tormozit.comfort} (без расширения).
     */
    private static File resolveDictionaryFile(String basePath, String extension)
    {
        File direct = new File(basePath + extension);
        if (direct.isFile())
            return direct;
        Bundle bundle = FrameworkUtil.getBundle(ComfortSpellingEngine.class);
        if (bundle == null)
            return null;
        try
        {
            URL found = FileLocator.find(bundle, new Path(basePath + extension), null);
            if (found == null)
                return null;
            URL fileUrl = FileLocator.toFileURL(found);
            URI uri = new URI(fileUrl.getProtocol(), fileUrl.getPath(), null);
            return new File(uri);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // --- Пользовательский morph-словарь (общий + проектный) ---

    private static File getCommonUserMorphDictionaryFile()
    {
        String custom = ComfortSettings.getSpellingCommonMorphDictionaryPath();
        if (custom != null && !custom.isBlank())
            return new File(custom);
        Activator activator = Activator.getDefault();
        if (activator == null)
            return null;
        IPath location = activator.getStateLocation();
        if (location == null)
            return null;
        return location.append(USER_MORPH_DICT_FILE_COMMON).toFile();
    }

    private static File getProjectUserMorphDictionaryFile(IProject project)
    {
        if (project == null || !project.isAccessible())
            return null;
        IPath location = project.getLocation();
        if (location == null)
            return null;
        return location.append(PROJECT_MORPH_DIR).append(USER_MORPH_DICT_FILE_PROJECT).toFile();
    }

    /** Путь общего словаря по умолчанию (stateLocation), без учёта custom pref. */
    static File defaultCommonUserMorphDictionaryFile()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return null;
        IPath location = activator.getStateLocation();
        if (location == null)
            return null;
        return location.append(USER_MORPH_DICT_FILE_COMMON).toFile();
    }

    /** Создать общий morph-dic при отсутствии и вернуть путь. */
    static File ensureCommonUserMorphDictionaryFile()
    {
        return ensureMorphDictionaryFile(getCommonUserMorphDictionaryFile());
    }

    /** Создать проектный {@code .comfort/spelling-comfort-project.dic} и вернуть путь. */
    static File ensureProjectUserMorphDictionaryFile(IProject project)
    {
        return ensureMorphDictionaryFile(getProjectUserMorphDictionaryFile(project));
    }

    private static File morphDictionaryFile(boolean projectScoped)
    {
        if (projectScoped)
            return getProjectUserMorphDictionaryFile(resolveSpellingProject());
        return getCommonUserMorphDictionaryFile();
    }

    private static File ensureMorphDictionaryFile(File file)
    {
        if (file == null)
            return null;
        synchronized (USER_MORPH_LOCK)
        {
            try
            {
                ensureUserMorphFile(file);
            }
            catch (IOException e)
            {
            }
        }
        return file;
    }

    /**
     * Перечитать общий и проектный morph-dic с диска, нормализовать счётчик,
     * перезагрузить Hunspell и обновить орфографию в UI.
     */
    static void reloadUserMorphDictionaryFromDisk()
    {
        synchronized (USER_MORPH_LOCK)
        {
            File common = getCommonUserMorphDictionaryFile();
            if (common != null && common.isFile())
            {
                List<String> entries = readUserMorphEntries(common);
                writeUserMorphEntries(common, entries);
            }
            IProject project = resolveSpellingProject();
            File projectFile = getProjectUserMorphDictionaryFile(project);
            if (projectFile != null && projectFile.isFile())
            {
                List<String> entries = readUserMorphEntries(projectFile);
                writeUserMorphEntries(projectFile, entries);
                refreshProjectMorphResource(project);
            }
            userMorphDictionary = loadUserMorphDictionary(common);
            projectMorphProjectName = project != null ? project.getName() : null;
            projectMorphDictionary = loadUserMorphDictionary(projectFile);
        }
        suggestCache.clear();
        Runnable ui = ComfortSpellingEngine::refreshSpellingAfterUserDictionaryReload;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            ui.run();
        else
            display.asyncExec(ui);
    }

    private static HunspellDictionary loadUserMorphDictionary(File dic)
    {
        try
        {
            File aff = resolveDictionaryFile(AOT_RU_BASE, ".aff"); //$NON-NLS-1$
            if (aff == null || !aff.isFile())
                return null;
            if (dic == null)
                return null;
            if (!dic.isFile())
                return null;
            return HunspellDictionary.load(aff, dic, HunspellDictionary.ScriptKind.CYRILLIC,
                StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void ensureUserMorphFile(File dic) throws IOException
    {
        if (dic.isFile())
            return;
        File parent = dic.getParentFile();
        if (parent != null && !parent.isDirectory())
            parent.mkdirs();
        Files.writeString(dic.toPath(), "0\n", StandardCharsets.UTF_8); //$NON-NLS-1$
    }

    private static void reloadCommonUserMorphDictionaryLocked()
    {
        userMorphDictionary = loadUserMorphDictionary(getCommonUserMorphDictionaryFile());
    }

    private static void reloadProjectUserMorphDictionaryLocked()
    {
        IProject project = resolveSpellingProject();
        projectMorphProjectName = project != null ? project.getName() : null;
        projectMorphDictionary = loadUserMorphDictionary(
            getProjectUserMorphDictionaryFile(project));
        refreshProjectMorphResource(project);
    }

    private static void refreshProjectMorphResource(IProject project)
    {
        if (project == null || !project.isAccessible())
            return;
        try
        {
            IResource folder = project.findMember(PROJECT_MORPH_DIR);
            if (folder != null)
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            else
                project.refreshLocal(IResource.DEPTH_ONE, null);
        }
        catch (Exception e)
        {
        }
    }

    /**
     * @param flagOrNull {@code null}/пусто — только лемма без морфологии
     * @param projectScoped {@code true} — проектный dic активного проекта
     * @return {@code true}, если запись новая
     */
    static boolean addUserMorphEntry(String lemma, String flagOrNull, boolean projectScoped)
    {
        if (lemma == null || lemma.isBlank())
            return false;
        if (projectScoped && resolveSpellingProject() == null)
            return false;
        String stem = lemma.trim();
        String flag = normalizeMorphFlag(flagOrNull);
        String line = formatMorphLine(stem, flag);
        synchronized (USER_MORPH_LOCK)
        {
            File file = morphDictionaryFile(projectScoped);
            if (file == null)
                return false;
            try
            {
                ensureUserMorphFile(file);
            }
            catch (IOException e)
            {
                return false;
            }
            List<String> entries = readUserMorphEntries(file);
            String stemKey = stem.toLowerCase(Locale.ROOT);
            List<String> kept = new ArrayList<>(entries.size());
            boolean same = false;
            for (String existing : entries)
            {
                MorphLineParsed parsed = parseMorphLine(existing);
                if (parsed == null)
                    continue;
                if (parsed.lemma.toLowerCase(Locale.ROOT).equals(stemKey))
                {
                    if (flagsEqual(parsed.flag, flag))
                        same = true;
                    continue;
                }
                kept.add(existing);
            }
            if (same)
                return false;
            kept.add(line);
            writeUserMorphEntries(file, kept);
            if (projectScoped)
                reloadProjectUserMorphDictionaryLocked();
            else
                reloadCommonUserMorphDictionaryLocked();
            return true;
        }
    }

    /** Число записей в выбранном morph-dic. */
    static int userMorphDictionarySize(boolean projectScoped)
    {
        synchronized (USER_MORPH_LOCK)
        {
            File file = morphDictionaryFile(projectScoped);
            return readUserMorphEntries(file).size();
        }
    }

    static boolean removeUserMorphEntry(String lemma, String flagOrNull, boolean projectScoped)
    {
        if (lemma == null || lemma.isBlank())
            return false;
        String stemKey = lemma.trim().toLowerCase(Locale.ROOT);
        String flag = normalizeMorphFlag(flagOrNull);
        synchronized (USER_MORPH_LOCK)
        {
            File file = morphDictionaryFile(projectScoped);
            if (file == null || !file.isFile())
                return false;
            List<String> entries = readUserMorphEntries(file);
            boolean removed = false;
            List<String> kept = new ArrayList<>(entries.size());
            for (String existing : entries)
            {
                MorphLineParsed parsed = parseMorphLine(existing);
                if (parsed != null
                    && parsed.lemma.toLowerCase(Locale.ROOT).equals(stemKey)
                    && flagsEqual(parsed.flag, flag))
                {
                    removed = true;
                    continue;
                }
                kept.add(existing);
            }
            if (!removed)
                return false;
            writeUserMorphEntries(file, kept);
            if (projectScoped)
                reloadProjectUserMorphDictionaryLocked();
            else
                reloadCommonUserMorphDictionaryLocked();
            return true;
        }
    }

    /**
     * Существующая запись с той же леммой (без учёта регистра), или {@code null}.
     */
    private static MorphLineParsed findUserMorphEntry(String lemma, boolean projectScoped)
    {
        if (lemma == null || lemma.isBlank())
            return null;
        String stemKey = lemma.trim().toLowerCase(Locale.ROOT);
        synchronized (USER_MORPH_LOCK)
        {
            for (String existing : readUserMorphEntries(morphDictionaryFile(projectScoped)))
            {
                MorphLineParsed parsed = parseMorphLine(existing);
                if (parsed != null && parsed.lemma.toLowerCase(Locale.ROOT).equals(stemKey))
                    return parsed;
            }
        }
        return null;
    }

    /**
     * @return существующая запись, если новая (лемма+флаг) её заместит; {@code null},
     *         если конфликта нет или запись уже совпадает
     */
    private static MorphLineParsed findMorphReplaceConflict(String lemma, String flagOrNull,
        boolean projectScoped)
    {
        MorphLineParsed existing = findUserMorphEntry(lemma, projectScoped);
        if (existing == null)
            return null;
        if (flagsEqual(existing.flag, normalizeMorphFlag(flagOrNull)))
            return null;
        return existing;
    }

    private static String describeMorphEntry(MorphLineParsed entry)
    {
        if (entry == null)
            return ""; //$NON-NLS-1$
        if (entry.flag == null)
            return entry.lemma + " (без морфологии)"; //$NON-NLS-1$
        MorphParadigm byFlag = paradigmByFlag(entry.flag);
        if (byFlag != null)
            return entry.lemma + " — " + byFlag.describe(); //$NON-NLS-1$
        return entry.lemma + "/" + entry.flag; //$NON-NLS-1$
    }

    private static MorphParadigm paradigmByFlag(String flag)
    {
        if (flag == null)
            return null;
        for (MorphParadigm p : MORPH_PARADIGMS)
        {
            if (p.flag.equals(flag))
                return p;
        }
        return null;
    }

    private static String normalizeMorphFlag(String flagOrNull)
    {
        if (flagOrNull == null)
            return null;
        String t = flagOrNull.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean flagsEqual(String a, String b)
    {
        if (a == null)
            return b == null;
        return a.equals(b);
    }

    private static String formatMorphLine(String lemma, String flagOrNull)
    {
        if (flagOrNull == null)
            return lemma;
        return lemma + "/" + flagOrNull; //$NON-NLS-1$
    }

    private static MorphLineParsed parseMorphLine(String line)
    {
        if (line == null)
            return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) //$NON-NLS-1$
            return null;
        int slash = trimmed.indexOf('/');
        if (slash < 0)
            return new MorphLineParsed(trimmed, null);
        String lemma = trimmed.substring(0, slash).trim();
        String flag = trimmed.substring(slash + 1).trim();
        if (lemma.isEmpty())
            return null;
        return new MorphLineParsed(lemma, flag.isEmpty() ? null : flag);
    }

    private static List<String> readUserMorphEntries(File file)
    {
        List<String> entries = new ArrayList<>();
        if (file == null || !file.isFile())
            return entries;
        try
        {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            boolean countSkipped = false;
            for (String raw : lines)
            {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) //$NON-NLS-1$
                    continue;
                if (!countSkipped)
                {
                    countSkipped = true;
                    if (line.chars().allMatch(Character::isDigit))
                        continue;
                }
                MorphLineParsed parsed = parseMorphLine(line);
                if (parsed != null)
                    entries.add(formatMorphLine(parsed.lemma, parsed.flag));
            }
        }
        catch (IOException e)
        {
        }
        return entries;
    }

    private static void writeUserMorphEntries(File file, List<String> entries)
    {
        if (file == null)
            return;
        try
        {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            List<String> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)))
            {
                writer.write(Integer.toString(sorted.size()));
                writer.newLine();
                for (String entry : sorted)
                {
                    writer.write(entry);
                    writer.newLine();
                }
            }
        }
        catch (IOException e)
        {
        }
    }

    private static List<String> previewMorphForms(String lemma, String flagOrNull)
    {
        if (lemma == null || lemma.isBlank())
            return List.of();
        HunspellDictionary dict = sharedCommonUserMorphDictionary();
        if (dict == null)
            dict = sharedProjectUserMorphDictionary();
        if (dict == null)
        {
            // Aff для превью — из бандла; dic может быть пустым
            File common = getCommonUserMorphDictionaryFile();
            try
            {
                if (common != null)
                    ensureUserMorphFile(common);
            }
            catch (IOException e)
            {
            }
            dict = loadUserMorphDictionary(common);
        }
        if (dict == null)
            return List.of(lemma.trim());
        return dict.expandForms(lemma.trim(), flagOrNull, MORPH_PREVIEW_MAX);
    }

    private static final class MorphLineParsed
    {
        final String lemma;
        final String flag;

        MorphLineParsed(String lemma, String flag)
        {
            this.lemma = lemma;
            this.flag = flag;
        }
    }

    private static final class MorphAddResult
    {
        final String lemma;
        final boolean morphology;
        final String flag;
        final boolean projectScoped;

        MorphAddResult(String lemma, boolean morphology, String flag, boolean projectScoped)
        {
            this.lemma = lemma;
            this.morphology = morphology;
            this.flag = flag;
            this.projectScoped = projectScoped;
        }
    }

    private enum MorphPos
    {
        NOUN("Существительное"), //$NON-NLS-1$
        ADJ("Прилагательное"), //$NON-NLS-1$
        VERB("Глагол"); //$NON-NLS-1$

        final String label;

        MorphPos(String label)
        {
            this.label = label;
        }
    }

    private enum MorphGender
    {
        MASC("мужской"), //$NON-NLS-1$
        FEM("женский"), //$NON-NLS-1$
        NEUT("средний"); //$NON-NLS-1$

        final String label;

        MorphGender(String label)
        {
            this.label = label;
        }
    }

    private enum MorphNumber
    {
        SING("единственное"), //$NON-NLS-1$
        PLUR("множественное"); //$NON-NLS-1$

        final String label;

        MorphNumber(String label)
        {
            this.label = label;
        }
    }

    /** Тип склонения / окончания (для сущ.) или шаблон (прил./глаг.). */
    private enum MorphDeclension
    {
        KA("на -ка"), //$NON-NLS-1$
        A_YA("на -а/-я"), //$NON-NLS-1$
        ZERO("нулевое окончание"), //$NON-NLS-1$
        ADJ_YI("на -ый"), //$NON-NLS-1$
        ADJ_II("на -ий"), //$NON-NLS-1$
        ADJ_OI("на -ой"), //$NON-NLS-1$
        VERB_T("на -ть"), //$NON-NLS-1$
        VERB_TSYA("на -ться"); //$NON-NLS-1$

        final String label;

        MorphDeclension(String label)
        {
            this.label = label;
        }
    }

    /**
     * Курируемая парадигма UI → флаг AOT. Для существительных AOT-лемма — ед.ч.;
     * мн.ч. в UI — признак формы, флаг тот же (от ед.ч. основы).
     */
    private static final class MorphParadigm
    {
        final String id;
        final MorphPos pos;
        final MorphGender gender;
        final MorphNumber number;
        final MorphDeclension declension;
        final String flag;

        MorphParadigm(String id, MorphPos pos, MorphGender gender, MorphNumber number,
            MorphDeclension declension, String flag)
        {
            this.id = id;
            this.pos = pos;
            this.gender = gender;
            this.number = number;
            this.declension = declension;
            this.flag = flag;
        }

        String describe()
        {
            if (pos == MorphPos.NOUN)
                return pos.label + ", " + gender.label + ", " + number.label + ", " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + declension.label;
            return pos.label + ", " + declension.label; //$NON-NLS-1$
        }
    }

    private static final List<MorphParadigm> MORPH_PARADIGMS = List.of(
        // сущ. ед.ч.
        new MorphParadigm("noun_fem_sing_ka", MorphPos.NOUN, MorphGender.FEM, MorphNumber.SING,
            MorphDeclension.KA, "15"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("noun_fem_sing_a", MorphPos.NOUN, MorphGender.FEM, MorphNumber.SING,
            MorphDeclension.A_YA, "50"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("noun_masc_sing_zero", MorphPos.NOUN, MorphGender.MASC, MorphNumber.SING,
            MorphDeclension.ZERO, "32"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("noun_neut_sing_zero", MorphPos.NOUN, MorphGender.NEUT, MorphNumber.SING,
            MorphDeclension.ZERO, "45"), //$NON-NLS-1$ //$NON-NLS-2$
        // сущ. мн.ч. — те же флаги AOT (лемма должна быть ед.ч.)
        new MorphParadigm("noun_fem_plur_ka", MorphPos.NOUN, MorphGender.FEM, MorphNumber.PLUR,
            MorphDeclension.KA, "15"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("noun_fem_plur_a", MorphPos.NOUN, MorphGender.FEM, MorphNumber.PLUR,
            MorphDeclension.A_YA, "50"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("noun_masc_plur_zero", MorphPos.NOUN, MorphGender.MASC, MorphNumber.PLUR,
            MorphDeclension.ZERO, "32"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("noun_neut_plur_zero", MorphPos.NOUN, MorphGender.NEUT, MorphNumber.PLUR,
            MorphDeclension.ZERO, "45"), //$NON-NLS-1$ //$NON-NLS-2$
        // прил. / глаг.
        new MorphParadigm("adj_yi", MorphPos.ADJ, null, null, MorphDeclension.ADJ_YI, "8"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("adj_ii", MorphPos.ADJ, null, null, MorphDeclension.ADJ_II, "2"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("adj_oi", MorphPos.ADJ, null, null, MorphDeclension.ADJ_OI, "5"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("verb_t", MorphPos.VERB, null, null, MorphDeclension.VERB_T, "2433"), //$NON-NLS-1$ //$NON-NLS-2$
        new MorphParadigm("verb_tsya", MorphPos.VERB, null, null, MorphDeclension.VERB_TSYA,
            "151")); //$NON-NLS-1$ //$NON-NLS-2$

    private static MorphParadigm paradigmById(String id)
    {
        for (MorphParadigm p : MORPH_PARADIGMS)
        {
            if (p.id.equals(id))
                return p;
        }
        return MORPH_PARADIGMS.get(2);
    }

    private static MorphParadigm findParadigm(MorphPos pos, MorphGender gender, MorphNumber number,
        MorphDeclension declension)
    {
        for (MorphParadigm p : MORPH_PARADIGMS)
        {
            if (p.pos != pos)
                continue;
            if (pos == MorphPos.NOUN)
            {
                if (p.gender == gender && p.number == number && p.declension == declension)
                    return p;
            }
            else if (p.declension == declension)
                return p;
        }
        return null;
    }

    private static List<MorphDeclension> declensionsFor(MorphPos pos, MorphNumber number,
        MorphGender gender)
    {
        LinkedHashSet<MorphDeclension> set = new LinkedHashSet<>();
        for (MorphParadigm p : MORPH_PARADIGMS)
        {
            if (p.pos != pos)
                continue;
            if (pos == MorphPos.NOUN)
            {
                if (number != null && p.number != number)
                    continue;
                if (gender != null && p.gender != gender)
                    continue;
            }
            set.add(p.declension);
        }
        return new ArrayList<>(set);
    }

    /**
     * Сводит форму прилагательного к словарной (им.п. м.р. ед.ч.): куколдного → куколдный.
     * {@code null}, если не похоже на прилагательное.
     */
    private static MorphGuess guessAdjective(String raw, String lower)
    {
        if (raw == null || lower == null || lower.length() < 3)
            return null;
        MorphGuess g = new MorphGuess();
        g.morphology = true;
        g.pos = MorphPos.ADJ;
        g.gender = MorphGender.MASC;
        g.number = MorphNumber.SING;
        if (lower.endsWith("ый")) //$NON-NLS-1$
        {
            g.lemma = raw;
            g.declension = MorphDeclension.ADJ_YI;
            g.paradigmId = "adj_yi"; //$NON-NLS-1$
            return g;
        }
        if (lower.endsWith("ий")) //$NON-NLS-1$
        {
            g.lemma = raw;
            g.declension = MorphDeclension.ADJ_II;
            g.paradigmId = "adj_ii"; //$NON-NLS-1$
            return g;
        }
        if (lower.endsWith("ой") && !lower.endsWith("ого") && !lower.endsWith("ому") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            && !lower.endsWith("ою")) //$NON-NLS-1$
        {
            // им.п. м.р. на -ой (большой) или спорные формы — предпочитаем -ой
            g.lemma = raw;
            g.declension = MorphDeclension.ADJ_OI;
            g.paradigmId = "adj_oi"; //$NON-NLS-1$
            return g;
        }
        // Косвенные падежи → словарная форма
        String stem;
        MorphDeclension decl;
        String paradId;
        if (lower.endsWith("его") || lower.endsWith("ему") || lower.endsWith("ими")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            stem = raw.substring(0, raw.length() - 3);
            decl = MorphDeclension.ADJ_II;
            paradId = "adj_ii"; //$NON-NLS-1$
            g.lemma = stem + "ий"; //$NON-NLS-1$
        }
        else if (lower.endsWith("им") || lower.endsWith("ем") || lower.endsWith("яя") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.endsWith("юю") || lower.endsWith("ее") || lower.endsWith("ие") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.endsWith("их")) //$NON-NLS-1$
        {
            stem = raw.substring(0, raw.length() - 2);
            decl = MorphDeclension.ADJ_II;
            paradId = "adj_ii"; //$NON-NLS-1$
            g.lemma = stem + "ий"; //$NON-NLS-1$
        }
        else if (lower.endsWith("ого") || lower.endsWith("ому") || lower.endsWith("ыми")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            stem = raw.substring(0, raw.length() - 3);
            char last = stem.isEmpty() ? 0 : Character.toLowerCase(stem.charAt(stem.length() - 1));
            if (isAdjOyStemFinal(last))
            {
                decl = MorphDeclension.ADJ_OI;
                paradId = "adj_oi"; //$NON-NLS-1$
                g.lemma = stem + "ой"; //$NON-NLS-1$
            }
            else
            {
                decl = MorphDeclension.ADJ_YI;
                paradId = "adj_yi"; //$NON-NLS-1$
                g.lemma = stem + "ый"; //$NON-NLS-1$
            }
        }
        else if (lower.endsWith("ым") || lower.endsWith("ом") || lower.endsWith("ая") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.endsWith("ую") || lower.endsWith("ое") || lower.endsWith("ые") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.endsWith("ых") || lower.endsWith("ою")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            stem = raw.substring(0, raw.length() - 2);
            char last = stem.isEmpty() ? 0 : Character.toLowerCase(stem.charAt(stem.length() - 1));
            if (isAdjOyStemFinal(last))
            {
                decl = MorphDeclension.ADJ_OI;
                paradId = "adj_oi"; //$NON-NLS-1$
                g.lemma = stem + "ой"; //$NON-NLS-1$
            }
            else
            {
                decl = MorphDeclension.ADJ_YI;
                paradId = "adj_yi"; //$NON-NLS-1$
                g.lemma = stem + "ый"; //$NON-NLS-1$
            }
        }
        else
            return null;
        g.declension = decl;
        g.paradigmId = paradId;
        return g;
    }

    private static boolean isAdjOyStemFinal(char last)
    {
        return last == 'г' || last == 'к' || last == 'х' || last == 'ж' || last == 'ш'
            || last == 'ч' || last == 'щ' || last == 'ц';
    }

    /**
     * Сводит форму глагола к инфинитиву: куколдил → куколдить, улыбался → улыбаться.
     */
    private static MorphGuess guessVerb(String raw, String lower)
    {
        if (raw == null || lower == null || lower.length() < 2)
            return null;
        MorphGuess g = new MorphGuess();
        g.morphology = true;
        g.pos = MorphPos.VERB;
        g.gender = MorphGender.MASC;
        g.number = MorphNumber.SING;
        if (lower.endsWith("ться")) //$NON-NLS-1$
        {
            g.lemma = raw;
            g.declension = MorphDeclension.VERB_TSYA;
            g.paradigmId = "verb_tsya"; //$NON-NLS-1$
            return g;
        }
        if (lower.endsWith("тись")) //$NON-NLS-1$
        {
            g.lemma = raw.substring(0, raw.length() - 4) + "ться"; //$NON-NLS-1$
            g.declension = MorphDeclension.VERB_TSYA;
            g.paradigmId = "verb_tsya"; //$NON-NLS-1$
            return g;
        }
        if (lower.endsWith("ть")) //$NON-NLS-1$
        {
            g.lemma = raw;
            g.declension = MorphDeclension.VERB_T;
            g.paradigmId = "verb_t"; //$NON-NLS-1$
            return g;
        }
        // прош. вр. возвратные: -лся/-лась/-лось/-лись
        if (lower.endsWith("лся") || lower.endsWith("лась") || lower.endsWith("лось") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.endsWith("лись")) //$NON-NLS-1$
        {
            int cut = lower.endsWith("лся") ? 3 : 4; //$NON-NLS-1$
            g.lemma = raw.substring(0, raw.length() - cut) + "ться"; //$NON-NLS-1$
            g.declension = MorphDeclension.VERB_TSYA;
            g.paradigmId = "verb_tsya"; //$NON-NLS-1$
            return g;
        }
        // прош. вр.: -ла/-ло/-ли, затем -л (куколдил → куколдить)
        if (lower.endsWith("ла") || lower.endsWith("ло") || lower.endsWith("ли")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            g.lemma = raw.substring(0, raw.length() - 2) + "ть"; //$NON-NLS-1$
            g.declension = MorphDeclension.VERB_T;
            g.paradigmId = "verb_t"; //$NON-NLS-1$
            return g;
        }
        if (lower.endsWith("л") && lower.length() > 2) //$NON-NLS-1$
        {
            g.lemma = raw.substring(0, raw.length() - 1) + "ть"; //$NON-NLS-1$
            g.declension = MorphDeclension.VERB_T;
            g.paradigmId = "verb_t"; //$NON-NLS-1$
            return g;
        }
        return null;
    }

    /** Эвристика: лемма + часть речи + род/число/склонение по окончанию. */
    private static MorphGuess guessMorphology(String seed)
    {
        String raw = seed == null ? "" : seed.trim(); //$NON-NLS-1$
        String w = raw.toLowerCase(Locale.ROOT);
        MorphGuess verb = guessVerb(raw, w);
        if (verb != null)
            return verb;
        MorphGuess adj = guessAdjective(raw, w);
        if (adj != null)
            return adj;
        return guessMorphologyForPos(seed, MorphPos.NOUN);
    }

    /**
     * Эвристика обрезки окончания от исходного слова при выбранной части речи
     * (смена POS в диалоге — снова от оригинала, не от уже урезанной леммы).
     */
    private static MorphGuess guessMorphologyForPos(String seed, MorphPos pos)
    {
        String raw = seed == null ? "" : seed.trim(); //$NON-NLS-1$
        String w = raw.toLowerCase(Locale.ROOT);
        MorphGuess g = new MorphGuess();
        g.lemma = raw.isEmpty() ? w : raw;
        g.morphology = true;
        g.pos = pos != null ? pos : MorphPos.NOUN;
        g.gender = MorphGender.MASC;
        g.number = MorphNumber.SING;
        g.declension = MorphDeclension.ZERO;
        if (g.pos == MorphPos.VERB)
        {
            MorphGuess verb = guessVerb(raw, w);
            if (verb != null)
                return verb;
            g.declension = MorphDeclension.VERB_T;
            g.paradigmId = "verb_t"; //$NON-NLS-1$
            return g;
        }
        if (g.pos == MorphPos.ADJ)
        {
            MorphGuess adj = guessAdjective(raw, w);
            if (adj != null)
                return adj;
            g.declension = MorphDeclension.ADJ_YI;
            g.paradigmId = "adj_yi"; //$NON-NLS-1$
            return g;
        }
        // NOUN
        if (w.endsWith("ка")) //$NON-NLS-1$
        {
            g.gender = MorphGender.FEM;
            g.declension = MorphDeclension.KA;
            g.paradigmId = "noun_fem_sing_ka"; //$NON-NLS-1$
            return g;
        }
        if (w.endsWith("ки") && w.length() > 2) //$NON-NLS-1$
        {
            g.gender = MorphGender.FEM;
            g.declension = MorphDeclension.KA;
            g.lemma = raw.substring(0, raw.length() - 1) + "а"; //$NON-NLS-1$
            g.paradigmId = "noun_fem_sing_ka"; //$NON-NLS-1$
            return g;
        }
        if (w.endsWith("а") || w.endsWith("я")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            g.gender = MorphGender.FEM;
            g.declension = MorphDeclension.A_YA;
            g.paradigmId = "noun_fem_sing_a"; //$NON-NLS-1$
            return g;
        }
        if (w.endsWith("ы") || w.endsWith("и")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            g.gender = MorphGender.MASC;
            g.declension = MorphDeclension.ZERO;
            g.lemma = raw.substring(0, raw.length() - 1);
            g.paradigmId = "noun_masc_sing_zero"; //$NON-NLS-1$
            return g;
        }
        if (w.endsWith("о") || w.endsWith("е") || w.endsWith("ё")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            g.gender = MorphGender.NEUT;
            g.declension = MorphDeclension.ZERO;
            g.paradigmId = "noun_neut_sing_zero"; //$NON-NLS-1$
            return g;
        }
        g.gender = MorphGender.MASC;
        g.declension = MorphDeclension.ZERO;
        g.paradigmId = "noun_masc_sing_zero"; //$NON-NLS-1$
        return g;
    }

    private static final class MorphGuess
    {
        String lemma;
        boolean morphology;
        MorphPos pos;
        MorphGender gender;
        MorphNumber number;
        MorphDeclension declension;
        String paradigmId;
    }

    private static final class MorphAddWordDialog extends Dialog
    {
        private final String seedWord;
        /** Границы hover на момент открытия; {@code null} — искать shell заново / default. */
        private final Rectangle hoverAnchorBounds;
        private Button projectDictCheck;
        private Text lemmaText;
        private Button morphCheck;
        private Combo posCombo;
        private Label genderLabel;
        private Combo genderCombo;
        private Label numberLabel;
        private Combo numberCombo;
        private Label declensionLabel;
        private Combo declensionCombo;
        private Label previewLabel;
        private Label typeHintLabel;
        private Label replaceWarningLabel;
        private Composite dialogArea;
        private MorphAddResult result;
        private boolean updating;
        /** Высота по полной компоновке (существительное); не сжимаем при смене POS. */
        private int preferredShellHeight;

        MorphAddWordDialog(Shell parentShell, String seedWord, Rectangle hoverAnchorBounds)
        {
            super(parentShell);
            this.seedWord = seedWord != null ? seedWord : ""; //$NON-NLS-1$
            this.hoverAnchorBounds = hoverAnchorBounds != null
                ? new Rectangle(hoverAnchorBounds.x, hoverAnchorBounds.y,
                    hoverAnchorBounds.width, hoverAnchorBounds.height)
                : null;
            setShellStyle(SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        }

        MorphAddResult result()
        {
            return result;
        }

        private boolean isProjectDictSelected()
        {
            return projectDictCheck != null && !projectDictCheck.isDisposed()
                && projectDictCheck.getEnabled() && projectDictCheck.getSelection();
        }

        @Override
        protected void configureShell(Shell shell)
        {
            super.configureShell(shell);
            shell.setText(Global.withPluginWindowTitle("Добавить в словарь")); //$NON-NLS-1$
        }

        @Override
        protected Point getInitialSize()
        {
            // Высота — по максимальной компоновке (сущ.: число + род + склонение).
            setNounFieldsVisible(true);
            if (dialogArea != null && !dialogArea.isDisposed())
                dialogArea.layout(true, true);
            getShell().layout(true, true);
            Point computed = getShell().computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
            updateNounControlsVisible();
            if (dialogArea != null && !dialogArea.isDisposed())
                dialogArea.layout(true, true);
            preferredShellHeight = computed.y;
            return new Point(Math.max(420, computed.x), computed.y);
        }

        @Override
        protected Point getInitialLocation(Point initialSize)
        {
            Rectangle hb = hoverAnchorBounds;
            if (hb == null)
            {
                Shell anchor = findSpellingHoverAnchorShell();
                if (anchor != null && !anchor.isDisposed())
                    hb = anchor.getBounds();
            }
            if (hb == null)
                return super.getInitialLocation(initialSize);
            Shell ref = getShell();
            Monitor monitor = ref != null && !ref.isDisposed() ? ref.getMonitor() : null;
            if (monitor == null && getParentShell() != null)
                monitor = getParentShell().getMonitor();
            Rectangle area = monitor != null ? monitor.getClientArea()
                : Display.getDefault().getClientArea();
            // Левый верхний угол hover.
            int x = hb.x;
            int y = hb.y;
            if (x + initialSize.x > area.x + area.width)
                x = Math.max(area.x, area.x + area.width - initialSize.x);
            if (x < area.x)
                x = area.x;
            if (y + initialSize.y > area.y + area.height)
                y = area.y + area.height - initialSize.y;
            if (y < area.y)
                y = area.y;
            return new Point(x, y);
        }

        private Shell findSpellingHoverAnchorShell()
        {
            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            if (display == null)
                return null;
            Control focus = display.getFocusControl();
            if (focus != null && !focus.isDisposed())
            {
                Shell focused = focus.getShell();
                if (isLikelySpellingHoverShell(focused, true))
                    return focused;
            }
            Shell active = display.getActiveShell();
            if (isLikelySpellingHoverShell(active, true))
                return active;
            Shell parent = getParentShell();
            if (isLikelySpellingHoverShell(parent, true))
                return parent;
            Shell best = null;
            for (Shell s : display.getShells())
            {
                if (isLikelySpellingHoverShell(s, true))
                    best = s;
            }
            return best;
        }

        static boolean isLikelySpellingHoverShell(Shell shell)
        {
            return isLikelySpellingHoverShell(shell, false);
        }

        /**
         * @param allowHidden {@code true} — shell уже скрыт после клика по proposal
         */
        static boolean isLikelySpellingHoverShell(Shell shell, boolean allowHidden)
        {
            if (shell == null || shell.isDisposed())
                return false;
            if (!allowHidden && !shell.isVisible())
                return false;
            String title = shell.getText();
            if (title != null && title.contains("Добавить в словарь")) //$NON-NLS-1$
                return false;
            int style = shell.getStyle();
            if ((style & SWT.APPLICATION_MODAL) != 0)
                return false;
            Rectangle b = shell.getBounds();
            if (b.width < 80 || b.height < 40)
                return false;
            if (b.width >= 700 || b.height >= 500)
                return false;
            return true;
        }

        @Override
        protected boolean isResizable()
        {
            return true;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, IDialogConstants.OK_ID, "Добавить", true); //$NON-NLS-1$
            createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
            updateAddButtonAndTypeHint();
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite) super.createDialogArea(parent);
            dialogArea = area;
            area.setLayout(new GridLayout(2, false));

            IProject project = resolveSpellingProject();
            projectDictCheck = new Button(area, SWT.CHECK);
            projectDictCheck.setText("Словарь в проекте"); //$NON-NLS-1$
            GridData projectGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            projectGd.horizontalSpan = 2;
            projectDictCheck.setLayoutData(projectGd);
            boolean canUseProject = project != null;
            projectDictCheck.setEnabled(canUseProject);
            projectDictCheck.setSelection(
                canUseProject && ComfortSettings.isSpellingAddToProjectDictionary());
            projectDictCheck.setToolTipText(canUseProject
                ? "Писать в .comfort/spelling-comfort-project.dic активного проекта (для git)." //$NON-NLS-1$
                : "Нет активного проекта — доступен только общий словарь."); //$NON-NLS-1$
            projectDictCheck.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    ComfortSettings.setSpellingAddToProjectDictionary(
                        projectDictCheck.getSelection());
                    if (!updating)
                        refreshPreviewAndWarning();
                }
            });

            Label lemmaLabel = new Label(area, SWT.NONE);
            lemmaLabel.setText("Лемма:"); //$NON-NLS-1$
            lemmaText = new Text(area, SWT.BORDER);
            lemmaText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            morphCheck = new Button(area, SWT.CHECK);
            morphCheck.setText("Морфология"); //$NON-NLS-1$
            GridData morphGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            morphGd.horizontalSpan = 2;
            morphCheck.setLayoutData(morphGd);
            morphCheck.setSelection(true);

            Label posLabel = new Label(area, SWT.NONE);
            posLabel.setText("Часть речи:"); //$NON-NLS-1$
            posCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
            posCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            for (MorphPos pos : MorphPos.values())
                posCombo.add(pos.label);

            numberLabel = new Label(area, SWT.NONE);
            numberLabel.setText("Число:"); //$NON-NLS-1$
            numberLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
            numberCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
            numberCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            for (MorphNumber number : MorphNumber.values())
                numberCombo.add(number.label);

            genderLabel = new Label(area, SWT.NONE);
            genderLabel.setText("Род:"); //$NON-NLS-1$
            genderLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
            genderCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
            genderCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            for (MorphGender gender : MorphGender.values())
                genderCombo.add(gender.label);

            declensionLabel = new Label(area, SWT.NONE);
            declensionLabel.setText("Склонение:"); //$NON-NLS-1$
            declensionCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
            declensionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            declensionCombo.setToolTipText(
                "Тип задаёт словарное окончание леммы. При морфологии лемма должна "
                    + "оканчиваться соответственно: -ка, -а/-я, -ый/-ий/-ой, -ть/-ться "
                    + "и т.п. Иначе формы не строятся."); //$NON-NLS-1$

            Label prevTitle = new Label(area, SWT.NONE);
            prevTitle.setText("Формы:"); //$NON-NLS-1$
            prevTitle.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            previewLabel = new Label(area, SWT.WRAP);
            GridData prevGd = new GridData(SWT.FILL, SWT.TOP, true, false);
            prevGd.widthHint = 320;
            previewLabel.setLayoutData(prevGd);

            typeHintLabel = new Label(area, SWT.WRAP);
            GridData typeHintGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            typeHintGd.horizontalSpan = 2;
            typeHintGd.widthHint = 400;
            typeHintLabel.setLayoutData(typeHintGd);
            typeHintLabel.setForeground(
                area.getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE));

            replaceWarningLabel = new Label(area, SWT.WRAP);
            GridData warnGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            warnGd.horizontalSpan = 2;
            warnGd.widthHint = 400;
            replaceWarningLabel.setLayoutData(warnGd);
            replaceWarningLabel.setForeground(
                area.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));

            MorphGuess guess = guessMorphology(seedWord);
            updating = true;
            lemmaText.setText(guess.lemma != null ? guess.lemma : seedWord);
            morphCheck.setSelection(guess.morphology);
            selectPos(guess.pos);
            selectNumber(guess.number);
            selectGender(guess.gender);
            refillDeclensions(guess.pos, guess.number, guess.gender, guess.declension);
            updating = false;

            ModifyListener refresh = e ->
            {
                if (!updating)
                    refreshPreviewAndWarning();
            };
            lemmaText.addModifyListener(refresh);
            SelectionAdapter morphListener = new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    if (updating)
                        return;
                    updating = true;
                    if (!morphCheck.getSelection())
                    {
                        lemmaText.setText(seedWord);
                    }
                    else
                    {
                        MorphGuess g = guessMorphology(seedWord);
                        lemmaText.setText(g.lemma != null ? g.lemma : seedWord);
                        selectPos(g.pos);
                        selectNumber(g.number);
                        selectGender(g.gender);
                        refillDeclensions(g.pos, g.number, g.gender, g.declension);
                    }
                    updating = false;
                    updateMorphEnabled();
                    updateNounControlsVisible();
                    if (selectedPos() == MorphPos.NOUN)
                        declensionLabel.setText("Склонение:"); //$NON-NLS-1$
                    else
                        declensionLabel.setText("Тип:"); //$NON-NLS-1$
                    refreshPreviewAndWarning();
                }
            };
            morphCheck.addSelectionListener(morphListener);
            posCombo.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    if (updating)
                        return;
                    if (morphCheck.getSelection())
                        applyHeuristicFromSeed(selectedPos());
                    updateNounControlsVisible();
                    updateMorphEnabled();
                    if (selectedPos() == MorphPos.NOUN)
                        declensionLabel.setText("Склонение:"); //$NON-NLS-1$
                    else
                        declensionLabel.setText("Тип:"); //$NON-NLS-1$
                    refreshPreviewAndWarning();
                }
            });
            genderCombo.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    if (updating)
                        return;
                    refillDeclensions(selectedPos(), selectedNumber(), selectedGender(), null);
                    refreshPreviewAndWarning();
                }
            });
            numberCombo.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    if (updating)
                        return;
                    updateMorphEnabled();
                    refillDeclensions(selectedPos(), selectedNumber(), selectedGender(), null);
                    refreshPreviewAndWarning();
                }
            });
            declensionCombo.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    if (!updating)
                        refreshPreviewAndWarning();
                }
            });

            updateMorphEnabled();
            updateNounControlsVisible();
            refreshPreviewAndWarning();
            return area;
        }

        private void updateNounControlsVisible()
        {
            setNounFieldsVisible(selectedPos() == MorphPos.NOUN);
        }

        /** Пересчёт леммы/склонения от {@link #seedWord} под выбранную часть речи. */
        private void applyHeuristicFromSeed(MorphPos pos)
        {
            MorphGuess g = guessMorphologyForPos(seedWord, pos);
            updating = true;
            lemmaText.setText(g.lemma != null ? g.lemma : seedWord);
            if (pos == MorphPos.NOUN)
            {
                selectNumber(g.number);
                selectGender(g.gender);
            }
            refillDeclensions(pos,
                pos == MorphPos.NOUN ? g.number : null,
                pos == MorphPos.NOUN ? g.gender : null,
                g.declension);
            updating = false;
        }

        private void setNounFieldsVisible(boolean noun)
        {
            if (numberLabel == null || numberLabel.isDisposed())
                return;
            numberLabel.setVisible(noun);
            numberCombo.setVisible(noun);
            genderLabel.setVisible(noun);
            genderCombo.setVisible(noun);
            ((GridData) numberLabel.getLayoutData()).exclude = !noun;
            ((GridData) numberCombo.getLayoutData()).exclude = !noun;
            ((GridData) genderLabel.getLayoutData()).exclude = !noun;
            ((GridData) genderCombo.getLayoutData()).exclude = !noun;
            if (dialogArea != null && !dialogArea.isDisposed())
                dialogArea.layout(true, true);
            // Не сжимаем shell при скрытии полей сущ. — высота от макс. компоновки.
            Shell shell = getShell();
            if (shell != null && !shell.isDisposed() && preferredShellHeight > 0)
            {
                Point size = shell.getSize();
                if (size.y < preferredShellHeight)
                    shell.setSize(size.x, preferredShellHeight);
            }
        }

        private void selectPos(MorphPos pos)
        {
            MorphPos target = pos != null ? pos : MorphPos.NOUN;
            MorphPos[] values = MorphPos.values();
            for (int i = 0; i < values.length; i++)
            {
                if (values[i] == target)
                {
                    posCombo.select(i);
                    return;
                }
            }
            posCombo.select(0);
        }

        private MorphPos selectedPos()
        {
            int idx = posCombo.getSelectionIndex();
            MorphPos[] values = MorphPos.values();
            if (idx < 0 || idx >= values.length)
                return MorphPos.NOUN;
            return values[idx];
        }

        private void selectGender(MorphGender gender)
        {
            MorphGender target = gender != null ? gender : MorphGender.MASC;
            MorphGender[] values = MorphGender.values();
            for (int i = 0; i < values.length; i++)
            {
                if (values[i] == target)
                {
                    genderCombo.select(i);
                    return;
                }
            }
            genderCombo.select(0);
        }

        private MorphGender selectedGender()
        {
            int idx = genderCombo.getSelectionIndex();
            MorphGender[] values = MorphGender.values();
            if (idx < 0 || idx >= values.length)
                return MorphGender.MASC;
            return values[idx];
        }

        private void selectNumber(MorphNumber number)
        {
            MorphNumber target = number != null ? number : MorphNumber.SING;
            MorphNumber[] values = MorphNumber.values();
            for (int i = 0; i < values.length; i++)
            {
                if (values[i] == target)
                {
                    numberCombo.select(i);
                    return;
                }
            }
            numberCombo.select(0);
        }

        private MorphNumber selectedNumber()
        {
            int idx = numberCombo.getSelectionIndex();
            MorphNumber[] values = MorphNumber.values();
            if (idx < 0 || idx >= values.length)
                return MorphNumber.SING;
            return values[idx];
        }

        private void refillDeclensions(MorphPos pos, MorphNumber number, MorphGender gender,
            MorphDeclension prefer)
        {
            List<MorphDeclension> list = declensionsFor(pos, number, gender);
            declensionCombo.removeAll();
            int select = 0;
            for (int i = 0; i < list.size(); i++)
            {
                MorphDeclension d = list.get(i);
                declensionCombo.add(d.label);
                if (prefer != null && prefer == d)
                    select = i;
            }
            if (!list.isEmpty())
                declensionCombo.select(select);
        }

        private MorphDeclension selectedDeclension()
        {
            MorphPos pos = selectedPos();
            MorphNumber number = pos == MorphPos.NOUN ? selectedNumber() : null;
            MorphGender gender = pos == MorphPos.NOUN ? selectedGender() : null;
            List<MorphDeclension> list = declensionsFor(pos, number, gender);
            int idx = declensionCombo.getSelectionIndex();
            if (idx < 0 || idx >= list.size())
                return list.isEmpty() ? MorphDeclension.ZERO : list.get(0);
            return list.get(idx);
        }

        private MorphParadigm selectedParadigm()
        {
            MorphPos pos = selectedPos();
            MorphDeclension decl = selectedDeclension();
            if (pos == MorphPos.NOUN)
            {
                MorphParadigm p = findParadigm(pos, selectedGender(), selectedNumber(), decl);
                if (p != null)
                    return p;
                return paradigmById("noun_masc_sing_zero"); //$NON-NLS-1$
            }
            MorphParadigm p = findParadigm(pos, null, null, decl);
            return p != null ? p : paradigmById("adj_yi"); //$NON-NLS-1$
        }

        private String selectedFlagOrNull()
        {
            return morphCheck.getSelection() ? selectedParadigm().flag : null;
        }

        private void updateMorphEnabled()
        {
            boolean on = morphCheck.getSelection();
            posCombo.setEnabled(on);
            numberCombo.setEnabled(on);
            genderCombo.setEnabled(on);
            declensionCombo.setEnabled(on);
            previewLabel.setEnabled(on);
        }

        private void refreshPreviewAndWarning()
        {
            String lemma = lemmaText.getText() != null ? lemmaText.getText().trim() : ""; //$NON-NLS-1$
            if (!morphCheck.getSelection())
            {
                previewLabel.setText(lemma.isEmpty() ? "—" : lemma); //$NON-NLS-1$
            }
            else
            {
                MorphParadigm p = selectedParadigm();
                List<String> forms = previewMorphForms(lemma, p.flag);
                String formsText = forms.isEmpty() ? "—" : String.join(", ", forms); //$NON-NLS-1$ //$NON-NLS-2$
                previewLabel.setText(formsText);
            }
            refreshReplaceWarning(lemma, selectedFlagOrNull());
            updateAddButtonAndTypeHint();
        }

        /**
         * @return текст подсказки при несоответствии леммы типу, иначе {@code null}
         */
        private String morphTypeMismatchMessage()
        {
            if (!morphCheck.getSelection())
                return null;
            String lemma = lemmaText.getText() != null ? lemmaText.getText().trim() : ""; //$NON-NLS-1$
            if (lemma.isEmpty())
                return "Укажите лемму."; //$NON-NLS-1$
            MorphDeclension decl = selectedDeclension();
            if (lemmaMatchesDeclension(lemma, decl))
                return null;
            String ending = declensionRequiredEndingLabel(decl);
            if (ending == null)
                return null;
            return "Лемма должна оканчиваться на " + ending //$NON-NLS-1$
                + " — словарная форма для типа «" + decl.label + "». " //$NON-NLS-1$ //$NON-NLS-2$
                + "Сейчас «" + lemma + "» этому не соответствует, формы не строятся."; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static boolean lemmaMatchesDeclension(String lemma, MorphDeclension decl)
        {
            if (lemma == null || lemma.isEmpty() || decl == null)
                return false;
            String w = lemma.toLowerCase(Locale.ROOT);
            switch (decl)
            {
            case KA:
                return w.endsWith("ка"); //$NON-NLS-1$
            case A_YA:
                return w.endsWith("а") || w.endsWith("я"); //$NON-NLS-1$ //$NON-NLS-2$
            case ZERO:
                return true;
            case ADJ_YI:
                return w.endsWith("ый"); //$NON-NLS-1$
            case ADJ_II:
                return w.endsWith("ий"); //$NON-NLS-1$
            case ADJ_OI:
                return w.endsWith("ой"); //$NON-NLS-1$
            case VERB_T:
                return w.endsWith("ть") && !w.endsWith("ться"); //$NON-NLS-1$ //$NON-NLS-2$
            case VERB_TSYA:
                return w.endsWith("ться"); //$NON-NLS-1$
            default:
                return true;
            }
        }

        private static String declensionRequiredEndingLabel(MorphDeclension decl)
        {
            if (decl == null)
                return null;
            switch (decl)
            {
            case KA:
                return "-ка"; //$NON-NLS-1$
            case A_YA:
                return "-а/-я"; //$NON-NLS-1$
            case ZERO:
                return null;
            case ADJ_YI:
                return "-ый"; //$NON-NLS-1$
            case ADJ_II:
                return "-ий"; //$NON-NLS-1$
            case ADJ_OI:
                return "-ой"; //$NON-NLS-1$
            case VERB_T:
                return "-ть (инфинитив)"; //$NON-NLS-1$
            case VERB_TSYA:
                return "-ться"; //$NON-NLS-1$
            default:
                return null;
            }
        }

        private void updateAddButtonAndTypeHint()
        {
            String mismatch = morphTypeMismatchMessage();
            if (typeHintLabel != null && !typeHintLabel.isDisposed())
            {
                String text = mismatch != null ? mismatch : ""; //$NON-NLS-1$
                typeHintLabel.setText(text);
                GridData gd = (GridData) typeHintLabel.getLayoutData();
                gd.exclude = text.isEmpty();
                typeHintLabel.setVisible(!text.isEmpty());
                if (dialogArea != null && !dialogArea.isDisposed())
                    dialogArea.layout(true, true);
            }
            Button add = getButton(IDialogConstants.OK_ID);
            if (add != null && !add.isDisposed())
            {
                String lemma = lemmaText.getText() != null ? lemmaText.getText().trim() : ""; //$NON-NLS-1$
                add.setEnabled(!lemma.isEmpty() && mismatch == null);
            }
        }

        private void refreshReplaceWarning(String lemma, String flagOrNull)
        {
            String text = ""; //$NON-NLS-1$
            boolean projectScoped = isProjectDictSelected();
            if (lemma != null && !lemma.isEmpty() && morphTypeMismatchMessage() == null)
            {
                MorphLineParsed existing = findUserMorphEntry(lemma, projectScoped);
                if (existing != null)
                {
                    if (flagsEqual(existing.flag, normalizeMorphFlag(flagOrNull)))
                    {
                        text = "Эта лемма уже есть в словаре с такими же настройками."; //$NON-NLS-1$
                    }
                    else
                    {
                        text = "Внимание: будет замещена существующая запись «" //$NON-NLS-1$
                            + describeMorphEntry(existing) + "»."; //$NON-NLS-1$
                    }
                }
            }
            replaceWarningLabel.setText(text);
            GridData warnGd = (GridData) replaceWarningLabel.getLayoutData();
            warnGd.exclude = text.isEmpty();
            replaceWarningLabel.setVisible(!text.isEmpty());
            replaceWarningLabel.getParent().layout(true, true);
        }

        @Override
        protected void okPressed()
        {
            String lemma = lemmaText.getText() != null ? lemmaText.getText().trim() : ""; //$NON-NLS-1$
            if (lemma.isEmpty())
                return;
            if (morphTypeMismatchMessage() != null)
                return;
            boolean morph = morphCheck.getSelection();
            String flag = morph ? selectedParadigm().flag : null;
            boolean projectScoped = isProjectDictSelected();
            if (projectDictCheck != null && !projectDictCheck.isDisposed()
                && projectDictCheck.getEnabled())
            {
                ComfortSettings.setSpellingAddToProjectDictionary(
                    projectDictCheck.getSelection());
            }
            MorphLineParsed conflict = findMorphReplaceConflict(lemma, flag, projectScoped);
            if (conflict != null)
            {
                String message = "Лемма «" + lemma //$NON-NLS-1$
                    + "» уже есть в словаре как «" //$NON-NLS-1$
                    + describeMorphEntry(conflict)
                    + "». Заменить запись новыми настройками?"; //$NON-NLS-1$
                if (!MessageDialog.openConfirm(getShell(),
                    Global.withPluginWindowTitle("Замещение леммы"), //$NON-NLS-1$
                    message))
                    return;
            }
            else
            {
                MorphLineParsed same = findUserMorphEntry(lemma, projectScoped);
                if (same != null && flagsEqual(same.flag, normalizeMorphFlag(flag)))
                {
                    MessageDialog.openInformation(getShell(),
                        Global.withPluginWindowTitle("Добавить в словарь"), //$NON-NLS-1$
                        "Эта лемма уже есть в словаре с такими же настройками."); //$NON-NLS-1$
                    return;
                }
            }
            result = new MorphAddResult(lemma, morph, flag, projectScoped);
            super.okPressed();
        }
    }
}
