package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellDictionary;
import org.eclipse.jdt.internal.ui.text.spelling.engine.RankedWordProposal;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.spelling.SpellingService;

/**
 * Виртуальный Platform dictionary {@code ru_RU} /
 * «Русский/Английский (Комфорт-HUNSPELL)» для штатного Default spelling engine:
 * Hunspell RU+EN через {@code registerDictionary}, пункт в combo через кэш locales
 * + переименование подписи на странице «Орфография».
 * Отдельный spelling engine плагин больше не регистрирует.
 */
public final class SpellCheckHook implements IStartup
{
    private static final String DEFAULT_SPELLING_ENGINE_ID =
        "org.eclipse.jdt.internal.ui.text.spelling.DefaultSpellingEngine"; //$NON-NLS-1$

    private static final Locale PLATFORM_LOCALE = new Locale("ru", "RU"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String USER_DICT_LINK_TEXT = "Пользовательский словарь"; //$NON-NLS-1$
    private static final String LINK_INSTALLED_KEY = "tormozit.spellCheck.userDictLink"; //$NON-NLS-1$
    private static final int MAX_ENHANCE_ATTEMPTS = 40;
    private static final int ENHANCE_RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingEnhance = new WeakHashMap<>();
    private static final WeakHashMap<PreferenceDialog, Boolean> pageListenersAttached =
        new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        registerComfortPlatformDictionary();
        bootstrapOnce();
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> installPreferencePageEnhancements(display));
        logDiagnostics();
    }

    private static void registerComfortPlatformDictionary()
    {
        try
        {
            List<HunspellDictionary> dicts = ComfortSpellingEngine.sharedDictionaries();
            if (dicts.isEmpty())
            {
                Global.tempLog("spellCheck", "registerDictionary: нет загруженных Hunspell-словарей"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            ISpellCheckEngine engine = SpellCheckEngine.getInstance();
            engine.registerDictionary(PLATFORM_LOCALE, new HunspellSpellDictionary(dicts));
            boolean inCombo = publishLocaleInPlatformDictionaryCombo(PLATFORM_LOCALE);
            Global.tempLog("spellCheck", "registerDictionary: locale=" + PLATFORM_LOCALE //$NON-NLS-1$ //$NON-NLS-2$
                + " dicts=" + dicts.size() + " inCombo=" + inCombo); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception | Error e)
        {
            Global.tempLog("spellCheck", "registerDictionary ИСКЛЮЧЕНИЕ: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Combo Platform dictionary заполняется из
     * {@link SpellCheckEngine#getLocalesWithInstalledDictionaries()} — добавляем locale в кэш.
     */
    private static boolean publishLocaleInPlatformDictionaryCombo(Locale locale)
    {
        try
        {
            Set<Locale> locales = SpellCheckEngine.getLocalesWithInstalledDictionaries();
            if (locales != null && locales.contains(locale))
                return true;
            if (locales != null)
            {
                try
                {
                    locales.add(locale);
                    if (locales.contains(locale))
                        return true;
                }
                catch (UnsupportedOperationException ignored)
                {
                    // emptySet / unmodifiable
                }
            }
            Field cache = SpellCheckEngine.class.getDeclaredField("fgLocalesWithInstalledDictionaries"); //$NON-NLS-1$
            cache.setAccessible(true);
            clearFinalIfNeeded(cache);
            Set<Locale> mutable = new HashSet<>();
            if (locales != null)
                mutable.addAll(locales);
            mutable.add(locale);
            cache.set(null, mutable);
            Set<Locale> after = SpellCheckEngine.getLocalesWithInstalledDictionaries();
            boolean ok = after != null && after.contains(locale);
            Global.tempLog("spellCheck", "publishLocale: reflect ok=" + ok + " locales=" + after); //$NON-NLS-1$ //$NON-NLS-2$
            return ok;
        }
        catch (Exception e)
        {
            Global.tempLog("spellCheck", "publishLocale ИСКЛЮЧЕНИЕ: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static void clearFinalIfNeeded(Field field) throws Exception
    {
        int mod = field.getModifiers();
        if (!Modifier.isFinal(mod))
            return;
        try
        {
            Field modifiers = Field.class.getDeclaredField("modifiers"); //$NON-NLS-1$
            modifiers.setAccessible(true);
            modifiers.setInt(field, mod & ~Modifier.FINAL);
        }
        catch (NoSuchFieldException ignored)
        {
            // Java 12+ — у fgLocales поле и так без final
        }
    }

    private static void bootstrapOnce()
    {
        if (ComfortSettings.isSpellingBootstrapped())
        {
            Global.tempLog("spellCheck", "bootstrap уже выполнен, prefs не трогаем"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        EditorsUI.getPreferenceStore().setValue(SpellingService.PREFERENCE_SPELLING_ENABLED, true);
        EditorsUI.getPreferenceStore().setValue(SpellingService.PREFERENCE_SPELLING_ENGINE,
            DEFAULT_SPELLING_ENGINE_ID);
        PreferenceConstants.getPreferenceStore().setValue(PreferenceConstants.SPELLING_LOCALE,
            ComfortSettings.SPELLING_PLATFORM_LOCALE);
        ComfortSettings.setSpellingBootstrapped(true);
        Global.tempLog("spellCheck", "bootstrap: spellingEnabled=true, engine=Default, locale=" //$NON-NLS-1$ //$NON-NLS-2$
            + ComfortSettings.SPELLING_PLATFORM_LOCALE);
    }

    /**
     * Страница «Орфография»: переименовать Platform dictionary и поставить ссылку
     * «Пользовательский словарь» на место пустого placeholder справа от combo.
     * Ретраи — страница создаётся лениво при выборе в дереве параметров.
     */
    private static void installPreferencePageEnhancements(Display display)
    {
        if (display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            attachPageChangedListener(dialog, display, shell);
            scheduleEnhanceOnce(display, shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void attachPageChangedListener(PreferenceDialog dialog, Display display,
        Shell shell)
    {
        if (!(dialog instanceof IPageChangeProvider provider))
            return;
        synchronized (pageListenersAttached)
        {
            if (Boolean.TRUE.equals(pageListenersAttached.get(dialog)))
                return;
            pageListenersAttached.put(dialog, Boolean.TRUE);
        }
        IPageChangedListener pageListener = event ->
        {
            Shell target = shell;
            if (target == null || target.isDisposed())
                target = dialog.getShell();
            if (target != null && !target.isDisposed())
                scheduleEnhanceOnce(display, target);
        };
        provider.addPageChangedListener(pageListener);
    }

    private static void scheduleEnhanceOnce(Display display, Shell shell)
    {
        synchronized (pendingEnhance)
        {
            if (Boolean.TRUE.equals(pendingEnhance.get(shell)))
                return;
            pendingEnhance.put(shell, Boolean.TRUE);
        }
        scheduleEnhance(display, shell, 0);
    }

    private static void scheduleEnhance(Display display, Shell shell, int attempt)
    {
        if (shell == null || shell.isDisposed() || attempt >= MAX_ENHANCE_ATTEMPTS)
        {
            pendingEnhance.remove(shell);
            return;
        }
        boolean done = enhanceSpellingPage(shell);
        if (done)
        {
            pendingEnhance.remove(shell);
            return;
        }
        display.timerExec(ENHANCE_RETRY_MS, () -> scheduleEnhance(display, shell, attempt + 1));
    }

    /** @return {@code true}, если combo найден, подпись Comfort и ссылка на месте */
    private static boolean enhanceSpellingPage(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return false;
        Combo combo = findPlatformDictionaryCombo(shell);
        if (combo == null || combo.isDisposed())
            return false;
        relabelPlatformDictionaryCombo(combo);
        boolean linkOk = installUserDictionaryLink(combo);
        String target = ComfortSettings.SPELLING_PLATFORM_DICT_LABEL;
        boolean labeled = false;
        for (String item : combo.getItems())
        {
            if (target.equals(item))
            {
                labeled = true;
                break;
            }
        }
        return labeled && linkOk;
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            Object data = current.getData();
            if (data instanceof PreferenceDialog dialog)
                return dialog;
            if (current.getParent() instanceof Shell parent)
                current = parent;
            else
                break;
        }
        return null;
    }

    private static Combo findPlatformDictionaryCombo(Shell shell)
    {
        Combo[] found = { null };
        walkControls(shell, control ->
        {
            if (found[0] != null || !(control instanceof Combo combo) || combo.isDisposed())
                return;
            if (isPlatformDictionaryCombo(combo))
                found[0] = combo;
        });
        return found[0];
    }

    private static boolean isPlatformDictionaryCombo(Combo combo)
    {
        String[] items = combo.getItems();
        if (items == null || items.length < 2)
            return false;
        String target = ComfortSettings.SPELLING_PLATFORM_DICT_LABEL;
        boolean hasComfortOrRussian = false;
        boolean hasNone = false;
        for (String item : items)
        {
            if (item == null)
                continue;
            if (target.equals(item) || isStockRussianPlatformLabel(item))
                hasComfortOrRussian = true;
            String lower = item.toLowerCase(Locale.ROOT);
            if ("none".equals(lower) || lower.contains("нет") || lower.contains("none")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                hasNone = true;
        }
        if (!hasComfortOrRussian)
            return false;
        // значения кодов locale в ControlData (OptionsConfigurationBlock)
        Object data = combo.getData();
        if (data != null)
        {
            String dataText = data.toString();
            if (dataText.contains(ComfortSettings.SPELLING_PLATFORM_LOCALE)
                || dataText.contains("ru_RU")) //$NON-NLS-1$
                return true;
        }
        return hasNone;
    }

    private static void relabelPlatformDictionaryCombo(Combo combo)
    {
        String target = ComfortSettings.SPELLING_PLATFORM_DICT_LABEL;
        String[] items = combo.getItems();
        if (items == null)
            return;
        boolean changed = false;
        for (int i = 0; i < items.length; i++)
        {
            String item = items[i];
            if (item == null || target.equals(item))
                continue;
            if (isStockRussianPlatformLabel(item))
            {
                combo.setItem(i, target);
                changed = true;
            }
        }
        if (changed)
            Global.tempLog("spellCheck", "relabel Platform dictionary → " + target); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Штатная подпись {@code Locale("ru","RU").getDisplayName()} — «русский (Россия)» /
     * «Russian (Russia)» / короткое «Русский» в зависимости от JVM и момента создания combo.
     */
    private static boolean isStockRussianPlatformLabel(String item)
    {
        if (item == null || item.isEmpty())
            return false;
        String target = ComfortSettings.SPELLING_PLATFORM_DICT_LABEL;
        if (target.equals(item))
            return false;
        String lower = item.toLowerCase(Locale.ROOT);
        if (lower.contains("комфорт") || lower.contains("hunspell")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        String stockLabel = PLATFORM_LOCALE.getDisplayName();
        String stockLabelEn = PLATFORM_LOCALE.getDisplayName(Locale.ENGLISH);
        if (item.equals(stockLabel) || item.equals(stockLabelEn))
            return true;
        if ("русский".equals(lower) || "russian".equals(lower)) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        return lower.contains("русск") //$NON-NLS-1$
            && (lower.contains("росси") || lower.contains("russia") || !lower.contains("(")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean installUserDictionaryLink(Combo combo)
    {
        Composite parent = combo.getParent();
        if (parent == null || parent.isDisposed())
            return false;
        if (Boolean.TRUE.equals(parent.getData(LINK_INSTALLED_KEY)))
            return findExistingUserDictLink(parent) != null;

        Control placeholder = findPlaceholderAfterCombo(combo);
        Control belowAnchor = null;
        if (placeholder != null && !placeholder.isDisposed())
        {
            Control[] children = parent.getChildren();
            for (int i = 0; i < children.length - 1; i++)
            {
                if (children[i] == placeholder)
                {
                    belowAnchor = children[i + 1];
                    break;
                }
            }
            placeholder.dispose();
        }
        else if (findExistingUserDictLink(parent) != null)
        {
            parent.setData(LINK_INSTALLED_KEY, Boolean.TRUE);
            return true;
        }
        else
            return false;

        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>" + USER_DICT_LINK_TEXT + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        link.setToolTipText(
            "Файл слов, добавленных через «Добавить в словарь» при проверке орфографии Comfort.\n"
            + "Открыть в проводнике ОС."); //$NON-NLS-1$
        // Без grab/END — иначе 4-я колонка GridLayout растягивается и появляется «дырка».
        link.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        link.addListener(SWT.Selection, e ->
        {
            if (!USER_DICT_LINK_TEXT.equals(e.text))
                return;
            NavigatorShowInExplorerHandler.showInExplorer(
                ComfortSpellingEngine.getUserDictionaryFile(), combo.getShell());
        });
        if (belowAnchor != null && !belowAnchor.isDisposed())
            link.moveAbove(belowAnchor);
        else
            link.moveBelow(combo);
        parent.setData(LINK_INSTALLED_KEY, Boolean.TRUE);
        parent.layout(true, true);
        Global.tempLog("spellCheck", "user-dict link installed on Spelling page"); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    private static Link findExistingUserDictLink(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Link link && !link.isDisposed()
                && link.getText() != null && link.getText().contains(USER_DICT_LINK_TEXT))
                return link;
        }
        return null;
    }

    /** Пустой {@link Label}-placeholder справа от Platform dictionary combo (GridLayout 4). */
    private static Control findPlaceholderAfterCombo(Combo combo)
    {
        Composite parent = combo.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length - 1; i++)
        {
            if (children[i] != combo)
                continue;
            Control next = children[i + 1];
            if (next instanceof Label label && !label.isDisposed())
            {
                String text = label.getText();
                if (text == null || text.isEmpty())
                    return label;
            }
            break;
        }
        return null;
    }

    private static void walkControls(Control root, java.util.function.Consumer<Control> visitor)
    {
        if (root == null || root.isDisposed())
            return;
        visitor.accept(root);
        if (root instanceof Composite composite)
        {
            Control[] children = composite.getChildren();
            if (children == null)
                return;
            for (Control child : children)
                walkControls(child, visitor);
        }
    }

    private static void logDiagnostics()
    {
        IPreferenceStore editorsStore = EditorsUI.getPreferenceStore();
        IPreferenceStore jdtStore = PreferenceConstants.getPreferenceStore();
        Set<Locale> locales = SpellCheckEngine.getLocalesWithInstalledDictionaries();
        Global.tempLog("spellCheck", "spellingEnabled=" //$NON-NLS-1$ //$NON-NLS-2$
            + editorsStore.getBoolean(SpellingService.PREFERENCE_SPELLING_ENABLED)
            + " engine=" + editorsStore.getString(SpellingService.PREFERENCE_SPELLING_ENGINE) //$NON-NLS-1$
            + " locale=" + jdtStore.getString(PreferenceConstants.SPELLING_LOCALE) //$NON-NLS-1$
            + " bootstrapped=" + ComfortSettings.isSpellingBootstrapped() //$NON-NLS-1$
            + " platformLocales=" + locales //$NON-NLS-1$
            + " label=" + ComfortSettings.SPELLING_PLATFORM_DICT_LABEL //$NON-NLS-1$
            + " comfortActive=" + isComfortPlatformSpellingActive()); //$NON-NLS-1$
    }

    /**
     * Орфография включена и выбран Platform dictionary Comfort ({@code ru_RU} /
     * «Русский/Английский (Комфорт-HUNSPELL)»), словари Hunspell загружены.
     * Без этого панель «Свойства» не должна цеплять оверлеи / волны / Ctrl+1.
     */
    static boolean isComfortPlatformSpellingActive()
    {
        try
        {
            if (!EditorsUI.getPreferenceStore().getBoolean(SpellingService.PREFERENCE_SPELLING_ENABLED))
                return false;
            String locale = PreferenceConstants.getPreferenceStore()
                .getString(PreferenceConstants.SPELLING_LOCALE);
            if (!isComfortPlatformLocale(locale))
                return false;
            return !ComfortSpellingEngine.sharedDictionaries().isEmpty();
        }
        catch (Exception | Error e)
        {
            return false;
        }
    }

    private static boolean isComfortPlatformLocale(String locale)
    {
        if (locale == null || locale.isEmpty())
            return false;
        String normalized = locale.trim().replace('-', '_');
        if (ComfortSettings.SPELLING_PLATFORM_LOCALE.equalsIgnoreCase(normalized))
            return true;
        // на всякий случай — Locale.toString() / toLanguageTag варианты
        return "ru_ru".equalsIgnoreCase(normalized); //$NON-NLS-1$
    }

    private static final class HunspellSpellDictionary implements ISpellDictionary
    {
        private final List<HunspellDictionary> dicts;
        private boolean loaded = true;

        HunspellSpellDictionary(List<HunspellDictionary> dicts)
        {
            this.dicts = dicts;
        }

        @Override
        public boolean isCorrect(String word)
        {
            return ComfortSpellingEngine.isCorrect(word);
        }

        @Override
        public Set<RankedWordProposal> getProposals(String word, boolean sentence)
        {
            List<String> suggestions = ComfortSpellingEngine.suggest(word, 20);
            if (suggestions.isEmpty())
                return Collections.emptySet();
            Set<RankedWordProposal> result = new HashSet<>();
            int rank = suggestions.size() * 10;
            for (String suggestion : suggestions)
            {
                result.add(new RankedWordProposal(suggestion, rank));
                rank -= 10;
            }
            return result;
        }

        @Override
        public boolean acceptsWords()
        {
            return true;
        }

        @Override
        public void addWord(String word)
        {
            ComfortSpellingEngine.addUserWord(word);
        }

        @Override
        public boolean isLoaded()
        {
            return loaded;
        }

        @Override
        public void unload()
        {
            loaded = false;
        }

        @Override
        public void setStripNonLetters(boolean strip)
        {
        }
    }
}
