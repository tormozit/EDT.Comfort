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
        if (delegate != null)
            delegate.configure(acceptor);

        acceptor.acceptDefaultHighlighting(SERVER_CALL_ID, SERVER_CALL_LABEL, serverCallTextStyle());
        acceptor.acceptDefaultHighlighting(SERVER_CALL_CONTEXT_ID, SERVER_CALL_CONTEXT_LABEL, serverCallContextTextStyle());
    }

    /** Стиль всегда обычный — только цвет, без модификации шрифта (жирный/курсив). */
    private TextStyle serverCallTextStyle()
    {
        TextStyle textStyle = new TextStyle();
        textStyle.setColor(effectiveServerCallColor(ComfortSettings.getServerCallHighlightingColor(),
            ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR));
        return textStyle;
    }

    private TextStyle serverCallContextTextStyle()
    {
        TextStyle textStyle = new TextStyle();
        textStyle.setColor(effectiveServerCallColor(ComfortSettings.getServerCallContextHighlightingColor(),
            ComfortSettings.DEFAULT_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR));
        return textStyle;
    }

    /** Store держит RGB светлой темы; в UI редактора — эффективный цвет текущей темы. */
    private static RGB effectiveServerCallColor(String stored, String fallback)
    {
        RGB light = ComfortSettings.parseRgb(stored, fallback);
        return SmartMatchHighlight.toEffectiveRgb(light);
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
