package tormozit;

/**
 * Порт блока нормализации ссылки МД из {@code ПерейтиПоСсылкеМД} (RDT, ~2221–2264).
 * Без привязки к окнам конфигуратора — только преобразование строки и побочные поля.
 */
public final class MdLinkNormalizer
{
    private static final String MARKER_STANDARD = ".Стандартный."; //$NON-NLS-1$

    private MdLinkNormalizer() {}

    /**
     * Результат нормализации: строка для резолва/открытия и побочные поля RDT.
     */
    public record Result(
        String normalizedRef,
        String formElementPath,
        String templateElementPath,
        String standardAttributeName,
        String xdtoTypeName,
        String propertyName,
        String predefinedLeafName)
    {
        static Result plain(String ref)
        {
            return new Result(ref, null, null, null, null, null, null);
        }
    }

    public static Result normalize(String ref)
    {
        return normalize(ref, null);
    }

    /**
     * @param session уже подключённая сессия ИР ({@link IRApplication#getConnectedSession});
     *                иначе ветки формы/макета пропускаются
     */
    public static Result normalize(String ref, IRSession session)
    {
        if (ref == null || ref.isBlank())
            return Result.plain(ref);
        final String linkRef = ref.strip();

        if (session != null && session.state == IRApplication.State.CONNECTED)
        {
            Result ir = session.executeOnComThread(() ->
            {
                try
                {
                    return tryIrFormOrTemplate(linkRef, session);
                }
                catch (Exception e)
                {
                    if (Global.isLogEnabled())
                        Global.log("MdLinkNormalizer", "IR: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    return null;
                }
            });
            if (ir != null)
                return ir;
        }
        return normalizeWithoutIr(linkRef);
    }

    private static Result tryIrFormOrTemplate(String ref, IRSession session)
    {
        Object codeEditor = session.codeEditor;
        if (codeEditor == null)
        {
            Object irCache = session.getModule("ирКэш"); //$NON-NLS-1$
            codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", "//"); //$NON-NLS-1$ //$NON-NLS-2$
            if (codeEditor == null)
                codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
        }

        Object formParts = ComBridge.invoke(codeEditor, "ЧастиПолногоИмениЭлементаФормы", ref); //$NON-NLS-1$
        String formName = ComBridge.structureField(formParts, "ИмяФормы"); //$NON-NLS-1$
        if (formName != null && !formName.isEmpty())
        {
            String path = ComBridge.structureField(formParts, "ПутьКЭлементу"); //$NON-NLS-1$
            return new Result(formName, path, null, null, null, null, null);
        }

        Object maketParts = ComBridge.invoke(codeEditor, "ЧастиПолногоИмениЭлементаМакета", ref); //$NON-NLS-1$
        String maketName = ComBridge.structureField(maketParts, "ИмяМакета"); //$NON-NLS-1$
        if (maketName != null && !maketName.isEmpty())
        {
            String path = ComBridge.structureField(maketParts, "ПутьКЭлементу"); //$NON-NLS-1$
            return new Result(maketName, null, path, null, null, null, null);
        }
        return null;
    }

    /** Ветки без ИР — порядок как в RDT (if-elif). */
    static Result normalizeWithoutIr(String ref)
    {
        String link = ref;

        if (MdTypeMapping.countDots(link) == 2 && link.startsWith("Перечисление.")) //$NON-NLS-1$
        {
            String[] fragments = link.split("\\.", -1); //$NON-NLS-1$
            String[] expanded  = new String[fragments.length + 1];
            System.arraycopy(fragments, 0, expanded, 0, 2);
            expanded[2] = "ЗначениеПеречисления"; //$NON-NLS-1$
            System.arraycopy(fragments, 2, expanded, 3, fragments.length - 2);
            link = String.join(".", expanded); //$NON-NLS-1$
            return Result.plain(link);
        }

        if (MdTypeMapping.countDots(link) == 2
                && MdTypeMapping.isReferenceTypeWithPredefined(MdTypeMapping.firstSegment(link)))
        {
            String[] fragments = link.split("\\.", -1); //$NON-NLS-1$
            String leaf        = fragments[2];
            link = fragments[0] + "." + fragments[1]; //$NON-NLS-1$
            return new Result(link, null, null, null, null, null, leaf);
        }

        int stdIdx = link.indexOf(MARKER_STANDARD);
        if (stdIdx > 0)
        {
            String attribute = link.substring(stdIdx + MARKER_STANDARD.length());
            link = link.substring(0, stdIdx);
            return new Result(link, null, null, attribute, null, null, null);
        }

        if (link.startsWith("ПакетXDTO.")) //$NON-NLS-1$
        {
            int lastDot = link.lastIndexOf('.');
            String xdtoType = null;
            if (lastDot > "ПакетXDTO.".length()) //$NON-NLS-1$
            {
                xdtoType = link.substring(lastDot + 1);
                link     = link.substring(0, lastDot);
            }
            return new Result(link, null, null, null, xdtoType, null, null);
        }

        if (link.startsWith("ПодпискаНаСобытие.")) //$NON-NLS-1$
            return normalizeWithProperty(link, "Обработчик"); //$NON-NLS-1$

        if (link.startsWith("РегламентноеЗадание.")) //$NON-NLS-1$
            return normalizeWithProperty(link, "Имя метода"); //$NON-NLS-1$

        return Result.plain(link);
    }

    private static Result normalizeWithProperty(String link, String propertyName)
    {
        String[] fragments = link.split("\\."); //$NON-NLS-1$
        if (fragments.length > 2)
            link = joinSkippingIndex(fragments, 2);
        return new Result(link, null, null, null, null, propertyName, null);
    }

    private static String joinSkippingIndex(String[] fragments, int skipIndex)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fragments.length; i++)
        {
            if (i == skipIndex)
                continue;
            if (sb.length() > 0)
                sb.append('.');
            sb.append(fragments[i]);
        }
        return sb.toString();
    }
}
