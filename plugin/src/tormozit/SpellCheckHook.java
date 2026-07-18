package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellDictionary;
import org.eclipse.jdt.internal.ui.text.spelling.engine.RankedWordProposal;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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

    @Override
    public void earlyStartup()
    {
        registerComfortPlatformDictionary();
        bootstrapOnce();
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> installPreferenceLabelFix(display));
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

    /** Переименовать «русский (Россия)» → подпись Comfort в combo Platform dictionary. */
    private static void installPreferenceLabelFix(Display display)
    {
        if (display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (findPreferenceDialog(shell) == null)
                return;
            scheduleRelabel(display, shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void scheduleRelabel(Display display, Shell shell)
    {
        Runnable once = () -> relabelPlatformDictionaryCombos(shell);
        display.asyncExec(once);
        display.timerExec(150, once);
        display.timerExec(400, once);
        display.timerExec(1000, once);
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

    private static void relabelPlatformDictionaryCombos(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        String stockLabel = PLATFORM_LOCALE.getDisplayName();
        String stockLabelEn = PLATFORM_LOCALE.getDisplayName(Locale.ENGLISH);
        String target = ComfortSettings.SPELLING_PLATFORM_DICT_LABEL;
        walkControls(shell, control ->
        {
            if (!(control instanceof Combo combo) || combo.isDisposed())
                return;
            String[] items = combo.getItems();
            if (items == null || items.length == 0)
                return;
            boolean changed = false;
            for (int i = 0; i < items.length; i++)
            {
                String item = items[i];
                if (item == null)
                    continue;
                if (target.equals(item))
                    continue;
                if (item.equals(stockLabel) || item.equals(stockLabelEn)
                    || isRussianRussiaLabel(item))
                {
                    combo.setItem(i, target);
                    changed = true;
                }
            }
            if (changed)
                Global.tempLog("spellCheck", "relabel Platform dictionary → " + target); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    private static boolean isRussianRussiaLabel(String item)
    {
        String lower = item.toLowerCase(Locale.ROOT);
        return lower.contains("русск") && (lower.contains("росси") || lower.contains("russia")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
            + " label=" + ComfortSettings.SPELLING_PLATFORM_DICT_LABEL); //$NON-NLS-1$
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
            if (word == null || word.isEmpty())
                return true;
            for (HunspellDictionary dict : dicts)
            {
                if (dict.isCorrect(word))
                    return true;
            }
            return false;
        }

        @Override
        public Set<RankedWordProposal> getProposals(String word, boolean sentence)
        {
            return Collections.emptySet();
        }

        @Override
        public boolean acceptsWords()
        {
            return false;
        }

        @Override
        public void addWord(String word)
        {
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
