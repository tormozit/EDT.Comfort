package tormozit;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellDictionary;
import org.eclipse.jdt.internal.ui.text.spelling.engine.RankedWordProposal;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
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
    private static final WeakHashMap<IEditorPart, Boolean> userDictEditorsHooked =
        new WeakHashMap<>();

    private static final String HOVER_COPY_FILTER_KEY = "tormozit.spellHover.copyFilter.v4"; //$NON-NLS-1$
    private static volatile boolean reloadingUserDictEditor;

    /** Последнее выделение в annotation-hover (к моменту Ctrl+C live-selection часто уже пуст). */
    private static volatile String hoverSelectionText;
    private static volatile Shell hoverSelectionShell;

    @Override
    public void earlyStartup()
    {
        registerComfortPlatformDictionary();
        bootstrapOnce();
        bootstrapIgnoreMixedOnce();
        bootstrapIgnoreDigitsOnce();
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() ->
            {
                installPreferencePageEnhancements(display);
                installAnnotationHoverCopyFilter(display);
            });
        }
        logDiagnostics();
    }

    /**
     * В annotation-hover заголовок — READ_ONLY {@link StyledText}: выделение мышью есть,
     * а штатный Copy не берёт этот текст. Запоминаем selection по мыши и копируем
     * по Ctrl+C через Display filter (без {@code activateHandler} — иначе конфликт
     * с {@code WidgetMethodHandler} в WorkbenchContext).
     */
    static void installAnnotationHoverCopyFilter(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        if (Boolean.TRUE.equals(display.getData(HOVER_COPY_FILTER_KEY)))
            return;
        display.setData(HOVER_COPY_FILTER_KEY, Boolean.TRUE);

        display.addFilter(SWT.MouseUp, e -> rememberHoverSelectionFromEvent(e));
        display.addFilter(SWT.MouseDown, e -> rememberHoverSelectionFromEvent(e));

        display.addFilter(SWT.KeyDown, e ->
        {
            if (display.isDisposed() || !isCopyKey(e))
                return;
            if (copyHoverSelectionIfAny(display))
                e.doit = false;
        });
    }

    private static void rememberHoverSelectionFromEvent(org.eclipse.swt.widgets.Event e)
    {
        if (!(e.widget instanceof StyledText st) || st.isDisposed())
            return;
        Shell shell = st.getShell();
        if (!isAnnotationHoverShell(shell))
            return;
        String sel = st.getSelectionText();
        if (sel == null || sel.isEmpty())
            return;
        hoverSelectionText = sel;
        hoverSelectionShell = shell;
        if (shell.getData(HOVER_COPY_FILTER_KEY) == null)
        {
            shell.setData(HOVER_COPY_FILTER_KEY, Boolean.TRUE);
            shell.addDisposeListener(ev ->
            {
                if (hoverSelectionShell == shell)
                {
                    hoverSelectionShell = null;
                    hoverSelectionText = null;
                }
            });
        }
    }

    private static boolean copyHoverSelectionIfAny(Display display)
    {
        String sel = resolveHoverTextToCopy(display);
        if (sel == null || sel.isEmpty())
            return false;
        copyTextToClipboard(display, sel);
        return true;
    }

    private static boolean isCopyKey(org.eclipse.swt.widgets.Event e)
    {
        if ((e.stateMask & (SWT.CTRL | SWT.MOD1)) == 0)
            return false;
        // Ctrl+Insert — copy; Ctrl+Shift+Insert — не трогаем
        if (e.keyCode == SWT.INSERT)
            return (e.stateMask & SWT.SHIFT) == 0;
        if ((e.stateMask & SWT.SHIFT) != 0)
            return false;
        int ch = e.character;
        int code = e.keyCode;
        return code == 'c' || code == 'C' || code == 0x43 || code == 0x63
            || ch == 3 || ch == 'c' || ch == 'C'
            || ch == 'с' || ch == 'С'; // русская раскладка, физическая клавиша C
    }

    private static String resolveHoverTextToCopy(Display display)
    {
        StyledText live = findAnnotationHoverStyledTextWithSelection(display);
        if (live != null)
        {
            String sel = live.getSelectionText();
            if (sel != null && !sel.isEmpty())
                return sel;
        }
        String cached = hoverSelectionText;
        Shell shell = hoverSelectionShell;
        if (cached == null || cached.isEmpty())
            return null;
        if (shell == null || shell.isDisposed() || !shell.isVisible())
            return null;
        return cached;
    }

    private static void copyTextToClipboard(Display display, String text)
    {
        Clipboard cb = new Clipboard(display);
        try
        {
            cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
    }

    private static boolean isAnnotationHoverShell(Shell shell)
    {
        if (shell == null || shell.isDisposed() || !shell.isVisible())
            return false;
        if (workbenchShells().contains(shell))
            return false;
        boolean hasLink = false;
        boolean hasStyledText = false;
        for (Control c : flattenControls(shell))
        {
            if (c instanceof Link)
                hasLink = true;
            if (c instanceof StyledText)
                hasStyledText = true;
        }
        // annotation quick-fix: заголовок StyledText + ссылки предложений
        if (hasLink && hasStyledText)
            return true;
        // до появления ссылок / без proposals — TOOL/ON_TOP shell с StyledText
        int style = shell.getStyle();
        return hasStyledText && ((style & SWT.ON_TOP) != 0 || (style & SWT.TOOL) != 0);
    }

    private static StyledText findAnnotationHoverStyledTextWithSelection(Display display)
    {
        StyledText fallback = null;
        for (Shell shell : display.getShells())
        {
            if (!isAnnotationHoverShell(shell))
                continue;
            for (Control c : flattenControls(shell))
            {
                if (!(c instanceof StyledText st) || st.isDisposed())
                    continue;
                if (st.getSelectionCount() <= 0)
                    continue;
                if (hasLinkChild(shell))
                    return st;
                if (fallback == null)
                    fallback = st;
            }
        }
        return fallback;
    }

    private static boolean hasLinkChild(Shell shell)
    {
        for (Control c : flattenControls(shell))
        {
            if (c instanceof Link)
                return true;
        }
        return false;
    }

    private static Set<Shell> workbenchShells()
    {
        Set<Shell> shells = new HashSet<>();
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return shells;
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                if (window == null)
                    continue;
                Shell shell = window.getShell();
                if (shell != null && !shell.isDisposed())
                    shells.add(shell);
            }
        }
        catch (Exception ignored)
        {
            // ignore
        }
        return shells;
    }

    private static List<Control> flattenControls(Control root)
    {
        List<Control> result = new ArrayList<>();
        collectControls(root, result);
        return result;
    }

    private static void collectControls(Control control, List<Control> out)
    {
        if (control == null || control.isDisposed())
            return;
        out.add(control);
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                collectControls(child, out);
        }
    }

    private static void registerComfortPlatformDictionary()
    {
        try
        {
            List<HunspellDictionary> dicts = ComfortSpellingEngine.sharedDictionaries();
            if (dicts.isEmpty())
            {
                return;
            }
            ISpellCheckEngine engine = SpellCheckEngine.getInstance();
            engine.registerDictionary(PLATFORM_LOCALE, new HunspellSpellDictionary(dicts));
            boolean inCombo = publishLocaleInPlatformDictionaryCombo(PLATFORM_LOCALE);
        }
        catch (Exception | Error e)
        {
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
            return ok;
        }
        catch (Exception e)
        {
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
            return;
        }
        EditorsUI.getPreferenceStore().setValue(SpellingService.PREFERENCE_SPELLING_ENABLED, true);
        EditorsUI.getPreferenceStore().setValue(SpellingService.PREFERENCE_SPELLING_ENGINE,
            DEFAULT_SPELLING_ENGINE_ID);
        PreferenceConstants.getPreferenceStore().setValue(PreferenceConstants.SPELLING_LOCALE,
            ComfortSettings.SPELLING_PLATFORM_LOCALE);
        ComfortSettings.setSpellingBootstrapped(true);
    }

    /**
     * Однократно выключить «Ignore mixed case words», чтобы CamelCase можно было
     * дробить по сегментам. Отдельный флаг — для установок, где platformDict уже
     * прогнан. Дальше выбор пользователя не перезаписываем.
     */
    private static void bootstrapIgnoreMixedOnce()
    {
        if (ComfortSettings.isSpellingIgnoreMixedBootstrapped())
            return;
        PreferenceConstants.getPreferenceStore().setValue(
            PreferenceConstants.SPELLING_IGNORE_MIXED, false);
        ComfortSettings.setSpellingIgnoreMixedBootstrapped(true);
    }

    /**
     * Однократно выключить «Ignore words with digits», чтобы цифры считались
     * разделителями сегментов. Дальше выбор пользователя не перезаписываем.
     */
    private static void bootstrapIgnoreDigitsOnce()
    {
        if (ComfortSettings.isSpellingIgnoreDigitsBootstrapped())
            return;
        PreferenceConstants.getPreferenceStore().setValue(
            PreferenceConstants.SPELLING_IGNORE_DIGITS, false);
        ComfortSettings.setSpellingIgnoreDigitsBootstrapped(true);
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
        translateSpellingPageLabels(shell);
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

    /**
     * Русификация видимых надписей страницы «Орфография» (JDT/EditorsUI NLS).
     * Сравнение без {@code &} и лишних пробелов; идемпотентно.
     * У флажков OptionsConfigurationBlock ставит узкий {@code widthHint} под EN —
     * после смены текста расширяем hint по {@code computeSize}.
     * В тултип — оригинальная английская подпись (без суффикса «Комфорт»).
     */
    private static void translateSpellingPageLabels(Shell shell)
    {
        Map<String, SpellingPageLabel> map = spellingPageLabels();
        walkControls(shell, control ->
        {
            if (control == null || control.isDisposed())
                return;
            if (control instanceof Label label)
            {
                SpellingPageLabel lab = map.get(normalizeSpellingLabel(label.getText()));
                if (lab == null)
                    return;
                if (!lab.ru.equals(label.getText()))
                    label.setText(lab.ru);
                applySpellingOriginalTooltip(label, lab.en);
            }
            else if (control instanceof Button button)
            {
                SpellingPageLabel lab = map.get(normalizeSpellingLabel(button.getText()));
                if (lab != null)
                {
                    if (!lab.ru.equals(button.getText()))
                        button.setText(lab.ru);
                    applySpellingOriginalTooltip(button, lab.en);
                }
                // JDT OptionsConfigurationBlock: узкий widthHint под EN — расширить под текущий текст
                expandSpellingButtonWidthHint(button);
            }
            else if (control instanceof Group group)
            {
                SpellingPageLabel lab = map.get(normalizeSpellingLabel(group.getText()));
                if (lab == null)
                    return;
                if (!lab.ru.equals(group.getText()))
                    group.setText(lab.ru);
                // Группам тултип не нужен (и перекрывает подсказки флажков); сброс со старых сборок
                if (lab.en.equals(group.getToolTipText()))
                    group.setToolTipText(null);
            }
            else if (control instanceof Link link)
            {
                String plain = link.getText();
                if (plain == null)
                    return;
                // Link может быть в виде <a>text</a>
                String inner = plain.replace("<a>", "").replace("</a>", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                SpellingPageLabel lab = map.get(normalizeSpellingLabel(inner));
                if (lab == null)
                    return;
                if (!lab.ru.equals(inner))
                    link.setText(plain.contains("<a>") ? "<a>" + lab.ru + "</a>" : lab.ru); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                applySpellingOriginalTooltip(link, lab.en);
            }
            else if (control instanceof Combo comboCtrl)
            {
                String[] items = comboCtrl.getItems();
                if (items == null)
                    return;
                boolean changed = false;
                SpellingPageLabel selectedLab = null;
                int sel = comboCtrl.getSelectionIndex();
                for (int i = 0; i < items.length; i++)
                {
                    SpellingPageLabel lab = map.get(normalizeSpellingLabel(items[i]));
                    if (lab == null)
                        continue;
                    if (!lab.ru.equals(items[i]))
                    {
                        items[i] = lab.ru;
                        changed = true;
                    }
                    if (i == sel)
                        selectedLab = lab;
                }
                if (changed)
                {
                    comboCtrl.setItems(items);
                    if (sel >= 0 && sel < items.length)
                        comboCtrl.select(sel);
                }
                if (selectedLab != null)
                    applySpellingOriginalTooltip(comboCtrl, selectedLab.en);
            }
        });
        if (!shell.isDisposed())
            shell.layout(true, true);
    }

    /** Английский оригинал в тултип; если тултип уже есть (ссылка Comfort) — дописать снизу. */
    private static void applySpellingOriginalTooltip(Control control, String en)
    {
        if (control == null || control.isDisposed() || en == null || en.isEmpty())
            return;
        String current = control.getToolTipText();
        if (current == null || current.isEmpty() || en.equals(current))
        {
            control.setToolTipText(en);
            return;
        }
        if (current.contains(en))
            return;
        control.setToolTipText(current + "\n\n" + en); //$NON-NLS-1$
    }

    /** Расширить {@code GridData.widthHint} флажка под фактическую ширину текста. */
    private static void expandSpellingButtonWidthHint(Button button)
    {
        if (button == null || button.isDisposed())
            return;
        Object layoutData = button.getLayoutData();
        if (!(layoutData instanceof GridData gd) || gd.widthHint <= 0)
            return;
        Point need = button.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        if (need == null || need.x <= gd.widthHint)
            return;
        gd.widthHint = need.x;
        Composite parent = button.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
    }

    /** RU-подпись + EN для тултипа; ключи — нормализованный EN, RU и устаревшие RU. */
    private static Map<String, SpellingPageLabel> spellingPageLabels()
    {
        Map<String, SpellingPageLabel> map = new LinkedHashMap<>();
        putSpellingLabel(map, "Enable spell checking", "Включить проверку орфографии"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Select spelling engine to use:", "Движок проверки орфографии:"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Default spelling engine", "Стандартный движок"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Options", "Параметры"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Dictionaries", "Словари"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Dictionary", "Словарь"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Advanced", "Дополнительно"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Platform dictionary:", "Системный словарь:"); //$NON-NLS-1$ //$NON-NLS-2$
        SpellingPageLabel userDict = putSpellingLabel(map, "User defined dictionary:", //$NON-NLS-1$
            "Пользовательский словарь:"); //$NON-NLS-1$
        aliasSpellingLabel(map, "User defined dictionary", userDict); //$NON-NLS-1$
        aliasSpellingLabel(map, "Пользовательский словарь", userDict); //$NON-NLS-1$
        putSpellingLabel(map, "Browse...", "Обзор..."); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Encoding:", "Кодировка:"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Variables...", "Переменные..."); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Make dictionary available to content assist", //$NON-NLS-1$
            "Словарь в автодополнении"); //$NON-NLS-1$
        putSpellingLabel(map,
            "The user dictionary is a text file with one word on each line", //$NON-NLS-1$
            "Пользовательский словарь — текст, по одному слову в строке"); //$NON-NLS-1$
        // Короткие подписи: у OptionsConfigurationBlock узкий widthHint под EN.
        putSpellingLabel(map, "Ignore words with digits", "Игнорировать слова с цифрами"); //$NON-NLS-1$ //$NON-NLS-2$
        SpellingPageLabel mixed = putSpellingLabel(map, "Ignore mixed case words", //$NON-NLS-1$
            "Игнорировать смешанный регистр"); //$NON-NLS-1$
        aliasSpellingLabel(map, "Игнорировать слова со смешанным регистром", mixed); //$NON-NLS-1$
        SpellingPageLabel sentence = putSpellingLabel(map, "Ignore sentence capitalization", //$NON-NLS-1$
            "Игнорировать регистр предложения"); //$NON-NLS-1$
        aliasSpellingLabel(map, "Игнорировать регистр начала предложения", sentence); //$NON-NLS-1$
        putSpellingLabel(map, "Ignore upper case words", "Игнорировать слова ЗАГЛАВНЫМИ"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Ignore internet addresses", "Игнорировать интернет-адреса"); //$NON-NLS-1$ //$NON-NLS-2$
        putSpellingLabel(map, "Ignore single letters", "Игнорировать одиночные буквы"); //$NON-NLS-1$ //$NON-NLS-2$
        SpellingPageLabel javaStr = putSpellingLabel(map, "Ignore Java string literals", //$NON-NLS-1$
            "Игнорировать строки Java"); //$NON-NLS-1$
        aliasSpellingLabel(map, "Игнорировать строковые литералы Java", javaStr); //$NON-NLS-1$
        SpellingPageLabel props = putSpellingLabel(map, "Ignore '&' in Java properties files", //$NON-NLS-1$
            "Игнорировать '&' в .properties"); //$NON-NLS-1$
        aliasSpellingLabel(map, "Ignore '&&' in &Java properties files", props); //$NON-NLS-1$
        aliasSpellingLabel(map, "Игнорировать '&&' в файлах свойств Java", props); //$NON-NLS-1$
        SpellingPageLabel nonLetters = putSpellingLabel(map, "Ignore non-letters at word boundaries", //$NON-NLS-1$
            "Игнорировать небуквы у границ"); //$NON-NLS-1$
        aliasSpellingLabel(map, "Игнорировать небуквы на границах слов", nonLetters); //$NON-NLS-1$
        putSpellingLabel(map, "Maximum number of correction proposals:", //$NON-NLS-1$
            "Макс. число вариантов исправления:"); //$NON-NLS-1$
        putSpellingLabel(map, "Maximum number of problems reported per file:", //$NON-NLS-1$
            "Макс. число проблем на файл:"); //$NON-NLS-1$
        putSpellingLabel(map, "none", "нет"); //$NON-NLS-1$ //$NON-NLS-2$
        aliasSpellingLabel(map, "Использовать словарь в автодополнении", //$NON-NLS-1$
            map.get(normalizeSpellingLabel("Словарь в автодополнении"))); //$NON-NLS-1$
        return map;
    }

    private static SpellingPageLabel putSpellingLabel(Map<String, SpellingPageLabel> map, String en,
        String ru)
    {
        SpellingPageLabel lab = new SpellingPageLabel(ru, en);
        map.put(normalizeSpellingLabel(en), lab);
        map.put(normalizeSpellingLabel(ru), lab);
        return lab;
    }

    private static void aliasSpellingLabel(Map<String, SpellingPageLabel> map, String otherKey,
        SpellingPageLabel lab)
    {
        if (lab != null)
            map.put(normalizeSpellingLabel(otherKey), lab);
    }

    /** Русская подпись настройки + английский оригинал для тултипа. */
    private static final class SpellingPageLabel
    {
        final String ru;
        final String en;

        SpellingPageLabel(String ru, String en)
        {
            this.ru = ru;
            this.en = en;
        }
    }

    /**
     * Убрать mnemonic {@code &X}, сохранить литерал {@code &&} → {@code &},
     * схлопнуть пробелы.
     */
    private static String normalizeSpellingLabel(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&')
            {
                sb.append('&');
                i++;
                continue;
            }
            if (c == '&')
                continue;
            sb.append(c);
        }
        return sb.toString().trim().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
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
        for (int i = 0; i < items.length; i++)
        {
            String item = items[i];
            if (item == null || target.equals(item))
                continue;
            if (isStockRussianPlatformLabel(item))
                combo.setItem(i, target);
        }
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
            "Словарь Comfort (spelling-user-morph.dic): лемма или лемма/флаг AOT "
            + "(например гиперссылка/15) — с морфологией; без «/» — только эта форма.\n"
            + "Пополняется через «Добавить в словарь». После сохранения в редакторе "
            + "словарь подхватывается сразу.\n\n"
            + "Поле ниже («Пользовательский словарь») — штатный словарь Eclipse: "
            + "текстовый файл, по одному слову в строке, без морфологии."); //$NON-NLS-1$
        // Без grab/END — иначе 4-я колонка GridLayout растягивается и появляется «дырка».
        link.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        link.addListener(SWT.Selection, e ->
        {
            if (!USER_DICT_LINK_TEXT.equals(e.text))
                return;
            openUserMorphDictionaryInEclipseEditor();
        });
        if (belowAnchor != null && !belowAnchor.isDisposed())
            link.moveAbove(belowAnchor);
        else
            link.moveBelow(combo);
        parent.setData(LINK_INSTALLED_KEY, Boolean.TRUE);
        parent.layout(true, true);
        return true;
    }

    /** Открыть {@code spelling-user-morph.dic} в системном текстовом редакторе Eclipse. */
    private static void openUserMorphDictionaryInEclipseEditor()
    {
        try
        {
            File file = ComfortSpellingEngine.ensureUserMorphDictionaryFile();
            if (file == null)
            {
                ToastNotification.show("Орфография", //$NON-NLS-1$
                    "Не удалось определить файл пользовательского словаря Comfort.", 5_000); //$NON-NLS-1$
                return;
            }
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return;
            IFileStore store = EFS.getLocalFileSystem().getStore(file.toURI());
            IEditorPart editor = IDE.openEditorOnFileStore(page, store);
            hookUserMorphDictionaryEditorSave(editor);
        }
        catch (Exception e)
        {
            ToastNotification.show("Орфография", //$NON-NLS-1$
                "Не удалось открыть словарь: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    /**
     * После Save (dirty → clean) перечитать morph-dic и синхронизировать буфер
     * редактора с нормализованной записью на диск.
     */
    private static void hookUserMorphDictionaryEditorSave(IEditorPart editor)
    {
        if (editor == null)
            return;
        synchronized (userDictEditorsHooked)
        {
            if (Boolean.TRUE.equals(userDictEditorsHooked.get(editor)))
                return;
            userDictEditorsHooked.put(editor, Boolean.TRUE);
        }
        final boolean[] wasDirty = { editor.isDirty() };
        IPropertyListener listener = (source, propId) ->
        {
            if (propId != IEditorPart.PROP_DIRTY || reloadingUserDictEditor)
                return;
            boolean dirty = editor.isDirty();
            if (wasDirty[0] && !dirty)
            {
                reloadingUserDictEditor = true;
                try
                {
                    ComfortSpellingEngine.reloadUserMorphDictionaryFromDisk();
                    refreshUserDictionaryEditorContent(editor);
                }
                finally
                {
                    reloadingUserDictEditor = false;
                    wasDirty[0] = editor.isDirty();
                }
            }
            else
                wasDirty[0] = dirty;
        };
        editor.addPropertyListener(listener);
    }

    private static void refreshUserDictionaryEditorContent(IEditorPart editor)
    {
        if (!(editor instanceof ITextEditor textEditor))
            return;
        try
        {
            IDocumentProvider provider = textEditor.getDocumentProvider();
            IEditorInput input = textEditor.getEditorInput();
            if (provider != null && input != null)
                provider.resetDocument(input);
        }
        catch (Exception e)
        {
        }
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

    /**
     * Единый интерактивный hover орфографии: UI как у Xtext
     * {@code AnnotationWithQuickFixesHover}, варианты — из {@link SpellingProblem}
     * и {@link ComfortSpellingEngine#suggest}.
     *
     * @return {@code true}, если hover установлен
     */
    static boolean installSpellingQuickFixHover(SourceViewer viewer,
        SourceViewerConfiguration configuration)
    {
        if (viewer == null)
            return false;
        try
        {
            ComfortSpellingQuickFixHover textHover = new ComfortSpellingQuickFixHover(viewer);
            if (configuration != null)
            {
                String[] types = configuration.getConfiguredContentTypes(viewer);
                if (types != null)
                {
                    for (String type : types)
                    {
                        if (type != null)
                            viewer.setTextHover(textHover, type);
                    }
                }
            }
            @SuppressWarnings("unchecked")
            Map<Object, ITextHover> hovers =
                (Map<Object, ITextHover>) Global.getField(viewer, "fTextHovers"); //$NON-NLS-1$
            if (hovers != null)
            {
                for (Map.Entry<Object, ITextHover> entry : hovers.entrySet())
                    entry.setValue(textHover);
            }
            // Сбросить кэш текущего hover в менеджере — иначе остаётся DefaultTextHover.
            Object manager = Global.getField(viewer, "fTextHoverManager"); //$NON-NLS-1$
            if (manager != null)
                Global.setField(manager, "fTextHover", null); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Как в {@link BslModuleSpellCheckHook.ModuleProblem#getMessage()}. */
    static String spellingErrorMessage(String word)
    {
        return "Орфографическая ошибка: " + (word != null ? word : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Hover орфографии: тот же {@code AnnotationInformationControl}, что в модуле BSL,
     * но proposals собираем сами (без XtextQuickAssistProcessor).
     */
    private static final class ComfortSpellingQuickFixHover
        implements ITextHover, ITextHoverExtension, ITextHoverExtension2
    {
        private final ISourceViewer viewer;
        private final IInformationControlCreator controlCreator;

        ComfortSpellingQuickFixHover(ISourceViewer viewer) throws Exception
        {
            this.viewer = viewer;
            Class<?> hoverClass = Class.forName(
                "org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover"); //$NON-NLS-1$
            Object xtextHover = hoverClass.getConstructor().newInstance();
            this.controlCreator = (IInformationControlCreator) hoverClass
                .getMethod("getHoverControlCreator").invoke(xtextHover); //$NON-NLS-1$
            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            installAnnotationHoverCopyFilter(display);
        }

        @Override
        public IRegion getHoverRegion(ITextViewer textViewer, int offset)
        {
            SpellingHit hit = findSpelling(textViewer, offset);
            if (hit != null)
                return new Region(hit.position.getOffset(), hit.position.getLength());
            return new Region(offset, 0);
        }

        @Override
        public String getHoverInfo(ITextViewer textViewer, IRegion hoverRegion)
        {
            Object info = getHoverInfo2(textViewer, hoverRegion);
            return info != null ? String.valueOf(info) : null;
        }

        @Override
        public Object getHoverInfo2(ITextViewer textViewer, IRegion hoverRegion)
        {
            if (hoverRegion == null)
                return null;
            SpellingHit hit = findSpelling(textViewer, hoverRegion.getOffset());
            if (hit == null)
                return null;
            String word = readWord(textViewer, hit.position);
            ICompletionProposal[] proposals = collectProposals(word, hit.position);
            try
            {
                // Русификация заголовка: AnnotationInformationControl берёт annotation.getText().
                Annotation displayAnnotation = new Annotation(hit.annotation.getType(), false,
                    spellingErrorMessage(word));
                Class<?> infoClass = Class.forName(
                    "org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover$AnnotationInfo"); //$NON-NLS-1$
                Constructor<?> ctor = infoClass.getConstructor(Annotation.class, Position.class,
                    ITextViewer.class, ICompletionProposal[].class);
                return ctor.newInstance(displayAnnotation, hit.position, textViewer, proposals);
            }
            catch (Exception e)
            {
                return spellingErrorMessage(word);
            }
        }

        @Override
        public IInformationControlCreator getHoverControlCreator()
        {
            return controlCreator;
        }

        private static ICompletionProposal[] collectProposals(String word, Position position)
        {
            List<ICompletionProposal> result = new ArrayList<>();
            if (word == null || word.isEmpty() || position == null)
                return new ICompletionProposal[0];
            // Свои варианты — штатный getProposals часто даёт только Add/Ignore без исправлений
            // или пустой список после sort, а в hover нужен тот же UX, что в модуле.
            result.add(new AddToDictionaryHoverProposal(word));
            List<String> sug = ComfortSpellingEngine.suggest(word, 20);
            for (String s : sug)
                result.add(new CompletionProposal(s, position.getOffset(), position.getLength(),
                    s.length(), null, s, null, null));
            return result.toArray(new ICompletionProposal[0]);
        }

        private SpellingHit findSpelling(ITextViewer textViewer, int offset)
        {
            ISourceViewer sv = viewer;
            if (textViewer instanceof ISourceViewer sourceViewer)
                sv = sourceViewer;
            if (sv == null)
                return null;
            IAnnotationModel model = sv.getAnnotationModel();
            if (model == null)
                return null;
            Iterator<?> it = model.getAnnotationIterator();
            while (it.hasNext())
            {
                Object next = it.next();
                if (!(next instanceof SpellingAnnotation ann))
                    continue;
                Position pos = model.getPosition(ann);
                if (pos != null && offset >= pos.getOffset()
                    && offset < pos.getOffset() + Math.max(pos.getLength(), 1))
                    return new SpellingHit(ann, pos);
            }
            return null;
        }

        private static String readWord(ITextViewer textViewer, Position position)
        {
            if (textViewer == null || position == null)
                return ""; //$NON-NLS-1$
            try
            {
                IDocument doc = textViewer.getDocument();
                if (doc == null)
                    return ""; //$NON-NLS-1$
                return doc.get(position.getOffset(), position.getLength());
            }
            catch (Exception e)
            {
                return ""; //$NON-NLS-1$
            }
        }
    }

    private static final class SpellingHit
    {
        final SpellingAnnotation annotation;
        final Position position;

        SpellingHit(SpellingAnnotation annotation, Position position)
        {
            this.annotation = annotation;
            this.position = position;
        }
    }

    private static final class AddToDictionaryHoverProposal implements ICompletionProposal
    {
        private final String word;

        AddToDictionaryHoverProposal(String word)
        {
            this.word = word;
        }

        @Override
        public void apply(IDocument document)
        {
            ComfortSpellingEngine.addUserWordFromUi(word);
        }

        @Override
        public org.eclipse.swt.graphics.Point getSelection(IDocument document)
        {
            return null;
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return null;
        }

        @Override
        public String getDisplayString()
        {
            return "Добавить в словарь: " + word; //$NON-NLS-1$
        }

        @Override
        public org.eclipse.swt.graphics.Image getImage()
        {
            return null;
        }

        @Override
        public org.eclipse.jface.text.contentassist.IContextInformation getContextInformation()
        {
            return null;
        }
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
            ComfortSpellingEngine.addUserWordFromUi(word);
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
