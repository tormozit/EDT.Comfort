package tormozit;

import java.util.Collection;

import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.text.source.Annotation;

import com._1c.g5.v8.dt.bsl.ui.hover.IBslHoverContributor;

/**
 * Sticky annotation hover BSL: кнопки «Назад/Вперёд» между маркерами на одной позиции
 * (ошибка валидации ↔ орфография Comfort).
 */
public final class ComfortBslAnnotationHoverContributor implements IBslHoverContributor
{
    @Override
    public void fillToolBar(IToolBarManager manager, Collection<Annotation> annotations)
    {
        BslModuleSpellCheckHook.installAnnotationNavigationActions(manager, annotations);
    }
}
