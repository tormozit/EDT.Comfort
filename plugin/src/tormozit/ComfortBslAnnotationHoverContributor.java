package tormozit;

import java.util.Collection;

import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.text.source.Annotation;

import com._1c.g5.v8.dt.bsl.ui.hover.IBslHoverContributor;

/**
 * Sticky annotation hover BSL: «Назад/Вперёд» между маркерами и кнопка с иконкой
 * синтакс-помощника (штатная подсказка с описанием идентификатора).
 */
public final class ComfortBslAnnotationHoverContributor implements IBslHoverContributor
{
    @Override
    public void fillToolBar(IToolBarManager manager, Collection<Annotation> annotations)
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
            return;
        BslModuleSpellCheckHook.installAnnotationNavigationActions(manager, annotations);
    }
}
