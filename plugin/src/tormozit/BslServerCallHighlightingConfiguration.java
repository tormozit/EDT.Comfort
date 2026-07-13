package tormozit;

import org.eclipse.swt.graphics.RGB;
import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfiguration;
import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfigurationAcceptor;
import org.eclipse.xtext.ui.editor.utils.TextStyle;
import org.osgi.framework.Bundle;

import org.eclipse.core.runtime.Platform;

/**
 * Регистрирует стиль подсветки серверных вызовов в BSL-редакторе.
 * Делегирует нативную BSL-конфигурацию через рефлексию, чтобы
 * сохранить стандартные стили EDT.
 */
public final class BslServerCallHighlightingConfiguration
    implements IHighlightingConfiguration
{
    private static final String TAG = "ServerCallCfg"; //$NON-NLS-1$
    public static final String SERVER_CALL_ID = Activator.PLUGIN_ID + ".serverCall"; //$NON-NLS-1$
    /** Серверный вызов "с контекстом" (&НаСервере) — в отличие от &НаСервереБезКонтекста. */
    public static final String SERVER_CALL_CONTEXT_ID = Activator.PLUGIN_ID + ".serverCallContext"; //$NON-NLS-1$

    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String BSL_HIGHLIGHTING_CONFIGURATION =
        "com._1c.g5.v8.dt.bsl.ui.syntaxcoloring.BslHighlightingConfiguration"; //$NON-NLS-1$
    private static final String SERVER_CALL_LABEL =
        "Серверные вызовы из клиентского кода"; //$NON-NLS-1$
    private static final String SERVER_CALL_CONTEXT_LABEL =
        "Серверные вызовы из клиентского кода (с контекстом)"; //$NON-NLS-1$

    private final IHighlightingConfiguration delegate = createNativeDelegate();

    @Override
    public void configure(IHighlightingConfigurationAcceptor acceptor)
    {
        Global.log(TAG, "configure called"); //$NON-NLS-1$
        if (delegate != null)
            delegate.configure(acceptor);

        acceptor.acceptDefaultHighlighting(SERVER_CALL_ID, SERVER_CALL_LABEL, serverCallTextStyle());
        acceptor.acceptDefaultHighlighting(SERVER_CALL_CONTEXT_ID, SERVER_CALL_CONTEXT_LABEL, serverCallContextTextStyle());
        Global.log(TAG, "configure: SERVER_CALL_ID=" + SERVER_CALL_ID //$NON-NLS-1$
            + " SERVER_CALL_CONTEXT_ID=" + SERVER_CALL_CONTEXT_ID); //$NON-NLS-1$
    }

    /** Стиль всегда обычный — только цвет, без модификации шрифта (жирный/курсив). */
    private TextStyle serverCallTextStyle()
    {
        TextStyle textStyle = new TextStyle();
        textStyle.setColor(parseColor(ComfortSettings.getServerCallHighlightingColor()));
        return textStyle;
    }

    private TextStyle serverCallContextTextStyle()
    {
        TextStyle textStyle = new TextStyle();
        textStyle.setColor(parseColor(ComfortSettings.getServerCallContextHighlightingColor()));
        return textStyle;
    }

    private static RGB parseColor(String value)
    {
        if (value == null || value.isEmpty())
            value = ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR;
        try
        {
            String[] parts = value.split(","); //$NON-NLS-1$
            return new RGB(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        }
        catch (Exception e)
        {
            // Настоящий "аварийный" фолбэк — сюда попадаем только если даже
            // ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR не парсится.
            // Цвет по умолчанию задаётся только в ComfortSettings — не дублировать здесь.
            return new RGB(0, 0, 0);
        }
    }

    private static IHighlightingConfiguration createNativeDelegate()
    {
        Bundle bundle = Platform.getBundle(BSL_UI_BUNDLE);
        if (bundle == null)
            return null;

        try
        {
            Object instance = bundle.loadClass(BSL_HIGHLIGHTING_CONFIGURATION)
                .getDeclaredConstructor().newInstance();
            if (instance instanceof IHighlightingConfiguration)
                return (IHighlightingConfiguration)instance;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            Activator.getDefault().getLog().error(
                "Failed to create native BSL highlighting configuration.", e); //$NON-NLS-1$
        }
        return null;
    }
}
