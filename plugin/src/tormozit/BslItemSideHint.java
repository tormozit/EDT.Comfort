package tormozit;

import org.eclipse.jface.text.IInformationControlCreator;

/**
 * Результат генерации боковой подсказки для строки списка (outline / content assist).
 */
public final class BslItemSideHint
{
    private final Object controlInput;
    private final IInformationControlCreator controlCreator;
    /** Смещение в модуле BSL; {@code -1} — не задано (content assist). */
    private final int sourceOffset;

    public BslItemSideHint(Object controlInput, IInformationControlCreator controlCreator)
    {
        this(controlInput, controlCreator, -1);
    }

    public BslItemSideHint(Object controlInput, IInformationControlCreator controlCreator, int sourceOffset)
    {
        this.controlInput = controlInput;
        this.controlCreator = controlCreator;
        this.sourceOffset = sourceOffset;
    }

    public Object getControlInput()
    {
        return controlInput;
    }

    public IInformationControlCreator getControlCreator()
    {
        return controlCreator;
    }

    public int getSourceOffset()
    {
        return sourceOffset;
    }

    public boolean isEmpty()
    {
        return controlInput == null || controlCreator == null;
    }
}

