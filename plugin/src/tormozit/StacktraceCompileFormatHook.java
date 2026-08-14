package tormozit;

import java.util.regex.Pattern;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

import com.google.inject.Injector;

import com._1c.g5.v8.dt.stacktraces.model.IStacktrace;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceParser;

/**
 * Штатный {@code IStacktraceParser} EDT узнаёт кадр ошибки только в виде
 * {@code {Модуль(строка)}:} — без колонки. Ошибки компиляции встроенного языка приходят как
 * {@code {Модуль(строка,колонка)}:}, из-за этого кнопка «Анализировать» в окне ошибки ничего не
 * добавляет в панель трассировок, а диалог «Анализ трассировки стека» показывает «Неизвестный
 * формат». Перед разбором колонка отбрасывается, номер строки сохраняется.
 */
public final class StacktraceCompileFormatHook implements IStartup
{
    private static final String STACKTRACES_BUNDLE = "com._1c.g5.v8.dt.stacktraces"; //$NON-NLS-1$
    private static final String STACKTRACES_PLUGIN =
            "com._1c.g5.v8.dt.internal.stacktraces.StacktracesPlugin"; //$NON-NLS-1$
    private static final String ANALYZE_BUNDLE = "com._1c.g5.v8.dt.stacktraces.analyze"; //$NON-NLS-1$
    private static final String ANALYZER_IFACE =
            "com._1c.g5.v8.dt.stacktraces.analyze.IStacktraceAnalyzer"; //$NON-NLS-1$
    private static final String ANALYZE_DIALOG =
            "com._1c.g5.v8.dt.internal.stacktraces.ui.dialogs.AnalyzeStacktraceDialog"; //$NON-NLS-1$
    private static final String ERROR_DIALOG =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointErrorDialog"; //$NON-NLS-1$
    private static final String ANALYZE_BUTTON = "Анализировать"; //$NON-NLS-1$
    private static final String DIALOG_PATCHED_KEY = "tormozit.stacktraceCompileFormatPatched"; //$NON-NLS-1$

    /** {@code {путь(строка, колонка)}} → {@code {путь(строка)}} для штатного парсера EDT. */
    private static final Pattern LOCATION_WITH_COLUMN =
            Pattern.compile("\\{([^{}()]+)\\((\\d+)\\s*,\\s*\\d+\\)\\}"); //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            scheduleAnalyzerWrap(display, 0);
            installDialogFilter(display);
            installAnalyzeButtonFilter(display);
        });
    }

    static IStacktraceParser wrapParser(IStacktraceParser parser)
    {
        if (parser == null || parser instanceof LocationNormalizingParser)
            return parser;
        return new LocationNormalizingParser(parser);
    }

    static String normalizeLocations(String text)
    {
        if (text == null || text.isEmpty())
            return text;
        return LOCATION_WITH_COLUMN.matcher(text).replaceAll("{$1($2)}"); //$NON-NLS-1$
    }

    private static void scheduleAnalyzerWrap(Display display, int attempt)
    {
        if (attempt > 12)
            return;
        int delay = attempt == 0 ? 0 : 250;
        display.timerExec(delay, () ->
        {
            if (display.isDisposed())
                return;
            if (wrapAnalyzerParser())
                return;
            scheduleAnalyzerWrap(display, attempt + 1);
        });
    }

    private static boolean wrapAnalyzerParser()
    {
        boolean shared = wrapSharedAnalyzer();
        wrapBreakpointManagerAnalyzer();
        return shared;
    }

    /** Синглтон {@code IStacktraceAnalyzer}, его же держит кнопка «Анализировать». */
    private static boolean wrapSharedAnalyzer()
    {
        try
        {
            Bundle stBundle = Platform.getBundle(STACKTRACES_BUNDLE);
            Bundle analyzeBundle = Platform.getBundle(ANALYZE_BUNDLE);
            if (stBundle == null || analyzeBundle == null)
                return false;
            Class<?> pluginClass = stBundle.loadClass(STACKTRACES_PLUGIN);
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            Object injectorObj = plugin != null ? Global.invoke(plugin, "getInjector") : null; //$NON-NLS-1$
            if (!(injectorObj instanceof Injector injector))
                return false;
            Object analyzer = injector.getInstance(analyzeBundle.loadClass(ANALYZER_IFACE));
            return wrapParserFields(analyzer);
        }
        catch (Exception e)
        {
            Global.log("StacktraceCompileFormat", "wrapSharedAnalyzer: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static boolean wrapBreakpointManagerAnalyzer()
    {
        try
        {
            Object manager = injectorInstance(
                    "com._1c.g5.v8.dt.debug.ui", //$NON-NLS-1$
                    "com._1c.g5.v8.dt.internal.debug.ui.DebugUiPlugin", //$NON-NLS-1$
                    "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointManager"); //$NON-NLS-1$
            return wrapParserFields(Global.getField(manager, "stacktraceAnalyzer")); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log("StacktraceCompileFormat", "wrapBreakpointManagerAnalyzer: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static Object injectorInstance(String bundleId, String pluginClassName, String typeName)
            throws Exception
    {
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null)
            return null;
        Class<?> pluginClass = bundle.loadClass(pluginClassName);
        Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
        Object injectorObj = plugin != null ? Global.invoke(plugin, "getInjector") : null; //$NON-NLS-1$
        if (!(injectorObj instanceof Injector injector))
            return null;
        return injector.getInstance(bundle.loadClass(typeName));
    }

    private static void installDialogFilter(Display display)
    {
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            Object data = shell.getData();
            if (data == null || !ANALYZE_DIALOG.equals(data.getClass().getName()))
                return;
            wrapAnalyzerParser();
            if (shell.getData(DIALOG_PATCHED_KEY) != null)
                return;
            if (!wrapParserFields(data))
                return;
            shell.setData(DIALOG_PATCHED_KEY, Boolean.TRUE);
            Global.invokeVoid(data, "validate"); //$NON-NLS-1$
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    /**
     * Кнопка «Анализировать» передаёт в парсер поле {@code presentation} как есть. Нормализуем его
     * в фильтре {@code SWT.Selection} до {@code buttonPressed}, даже если обёртка парсера на
     * анализаторе ещё не встала.
     */
    private static void installAnalyzeButtonFilter(Display display)
    {
        display.addFilter(SWT.Selection, event ->
        {
            if (!(event.widget instanceof Button button) || button.isDisposed())
                return;
            String label = button.getText();
            if (label == null || !ANALYZE_BUTTON.equals(label.replace("&", ""))) //$NON-NLS-1$ //$NON-NLS-2$
                return;
            Shell shell = button.getShell();
            if (shell == null || shell.isDisposed())
                return;
            Object data = shell.getData();
            if (data == null || !ERROR_DIALOG.equals(data.getClass().getName()))
                return;
            Object presentation = Global.getField(data, "presentation"); //$NON-NLS-1$
            if (!(presentation instanceof String text))
                return;
            String normalized = normalizeLocations(text);
            if (!normalized.equals(text))
                Global.setFieldForce(data, "presentation", normalized); //$NON-NLS-1$
        });
    }

    private static boolean wrapParserFields(Object host)
    {
        if (host == null)
            return false;
        boolean wrapped = wrapNamedParserField(host, "parser"); //$NON-NLS-1$
        wrapped |= wrapNamedParserField(host, "stacktraceParser"); //$NON-NLS-1$
        return wrapped;
    }

    private static boolean wrapNamedParserField(Object host, String fieldName)
    {
        Object current = Global.getField(host, fieldName);
        if (!(current instanceof IStacktraceParser parser))
            return false;
        IStacktraceParser wrapped = wrapParser(parser);
        if (wrapped == parser)
            return true;
        return Global.setFieldForce(host, fieldName, wrapped);
    }

    private static final class LocationNormalizingParser implements IStacktraceParser
    {
        private final IStacktraceParser delegate;

        LocationNormalizingParser(IStacktraceParser delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public IStacktrace parse(String text, String name, String detail)
        {
            return delegate.parse(normalizeLocations(text), name, detail);
        }
    }
}
