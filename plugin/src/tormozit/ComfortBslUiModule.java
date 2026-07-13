package tormozit;

import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.service.AbstractGenericModule;
import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfiguration;

/**
 * Guice-модуль для расширения BSL-редактора.
 * Регистрирует подсветку серверных вызовов через
 * точку расширения {@code com._1c.g5.v8.dt.bsl.ui.bslUiModuleExtension}.
 */
public class ComfortBslUiModule extends AbstractGenericModule
{
    public Class<? extends ISemanticHighlightingCalculator> bindISemanticHighlightingCalculator()
    {
        return BslServerCallHighlightingCalculator.class;
    }

    public Class<? extends IHighlightingConfiguration> bindIHighlightingConfiguration()
    {
        return BslServerCallHighlightingConfiguration.class;
    }
}
