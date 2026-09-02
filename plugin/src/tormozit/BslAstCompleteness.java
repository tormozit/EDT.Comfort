package tormozit;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * Целиком ли разобран модуль после синтаксической ошибки.
 *
 * <p>Обычно Xtext после ошибки восстанавливается и строит дерево всего модуля — на этом
 * построен {@link BslCompareParseErrorSuppressor} (и так же ведёт себя конфигуратор 1С).
 * Но бывают ошибки, после которых восстановление не срабатывает и дерево обрывается: остаток
 * модуля уходит в узел ошибки, методы после места ошибки в AST не попадают. Подавлять такие
 * ошибки нельзя — сравнение по огрызку структуры хуже честного сравнения текстом.
 *
 * <p>Известный пример — препроцессорная директива внутри условия (не внутри тела):
 *
 * <pre>
 * Если Истина
 * #Если Сервер Тогда
 *     И 1=1
 * #КонецЕсли
 * Тогда
 * КонецЕсли
 * </pre>
 *
 * В грамматике ({@code Bsl.xtext}) препроцессор — это <b>оператор</b>
 * ({@code PreprocessorStatement returns Statement}), объявление модуля или элемент уровня
 * методов; частью выражения он быть не может, а условие разбирается правилом
 * {@code Conditional: predicate = Expression ('Then'|'Тогда') …}. Встретив {@code #Если} на
 * месте продолжения выражения, парсер даёт ошибку — и попадает в ловушку: {@code Тогда},
 * которого он ждёт, есть и у самой директивы ({@code PreprocessorIfConditional…: predicate
 * ('Then'|'Тогда') …}). Восстановление «закрывает» условие на чужом {@code Тогда}, дальше
 * идёт непарный {@code #КонецЕсли} и висящий {@code Тогда} — ошибки идут каскадом, парсер
 * выходит из вложенных правил, и весь остаток текста оказывается в узле ошибки.
 *
 * <p>Признаки обрыва (любого достаточно):
 * <ul>
 * <li>AST нет вовсе;</li>
 * <li>лексем {@code Процедура}/{@code Функция} в потоке разбора больше, чем методов в AST
 * ({@link #countMethodKeywordLeaves}) — часть методов при восстановлении не создана;</li>
 * <li>объявление метода целиком внутри узла синтаксической ошибки.</li>
 * </ul>
 *
 * <p>Счёт лексем — по <b>листьям узловой модели</b> и их грамматическому элементу
 * ({@link #countMethodKeywordLeaves}), не посимвольным сканом текста. Скан всего текста
 * расходился с {@code module.allMethods()} из-за слова «Функция» в комментарии/строке или
 * из-за {@code Обработчик.Процедура} — имя свойства теми же ключевыми словами. По листу это
 * отличимо: объявление — {@code Keyword} правила {@code Procedure}/{@code Function}, имя
 * свойства — правила {@code ExtName}, потерянный при обрыве метод — лист с {@code null}.
 *
 * <p>ANTLR лексирует поток целиком независимо от восстановления, поэтому потерянный метод
 * <b>всегда</b> оставляет свой лист {@code Функция}/{@code Процедура} — сверка числа лексем с
 * числом методов ловит и обрыв на {@code #Если} в условии (там ANTLR ставит несколько
 * <b>однострочных</b> узлов ошибок, большого поглощающего узла нет). Признак «многострочный
 * узел ошибки» из-за этого лишний и был убран: реальный обрыв он не ловил (ловит счёт
 * лексем), зато давал ложное срабатывание на однострочной ошибке «Неверный ввод ";"», чей
 * узел ANTLR растянул на пустую строку ниже — дерево при этом целое.
 *
 * <p>Восстановление ANTLR двух видов. <b>Вставка одной лексемы:</b> EDT требует {@code ;}
 * перед {@code КонецФункции} (1С — нет), синтезирует недостающую и продолжает — дерево целое,
 * лексем ровно столько же, сколько методов. <b>Выход из правил:</b> методы теряются — лексем
 * больше.
 */
public final class BslAstCompleteness
{
    private static final String[] METHOD_KEYWORDS = { "Процедура", "Функция", "Procedure", "Function" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private BslAstCompleteness()
    {
    }

    /** @return {@code true}, если после ошибки дерево модуля построено не полностью */
    public static boolean isTruncated(Resource resource)
    {
        if (!(resource instanceof XtextResource xtextResource))
            return true;
        return isTruncated(xtextResource.getParseResult());
    }

    /** То же по результату разбора — для мест, где ресурса под рукой нет (панель «Структура»). */
    public static boolean isTruncated(IParseResult parseResult)
    {
        try
        {
            if (parseResult == null)
                return true;
            /*
             * Дешёвая отсечка обычного случая: без синтаксических ошибок обрыва не бывает.
             * Ниже идёт обход узлов синтаксических ошибок (их единицы) с разбором их текста —
             * незачем делать это на каждый пересчёт в модуле без ошибок.
             */
            if (!parseResult.hasSyntaxErrors())
                return false;
            EObject root = parseResult.getRootASTElement();
            if (!(root instanceof Module))
                return true;

            int keywordLeaves = countMethodKeywordLeaves(parseResult.getRootNode());
            int methods = ((Module)root).allMethods().size();
            return keywordLeaves > methods
                || errorNodeSwallowingDeclaration(parseResult) != null;
        }
        catch (Exception | LinkageError e)
        {
            return true; // не смогли убедиться в целости дерева — не подавляем
        }
    }

    /**
     * Сколько объявлений метода в потоке разбора: листья {@code Процедура}/{@code Функция}
     * (и англ. синонимы) по <b>грамматическому элементу</b>, а не по тексту.
     *
     * <p>Лексер даёт лист с текстом «Функция» и там, где это имя свойства
     * ({@code Обработчик.Процедура = …}) — {@code Функция}/{@code Процедура} в BSL не полностью
     * зарезервированы. Грамматический элемент такого листа — тоже {@link org.eclipse.xtext.Keyword}
     * (правило {@code ExtName} перечисляет ключевые слова как допустимые имена свойств), поэтому
     * различаем по <b>объемлющему правилу</b>: {@code Procedure}/{@code Function} — объявление,
     * {@code ExtName} и прочее — нет.
     *
     * <p>В зоне обрыва, где метод не разобрался, парсер грамматический элемент листу не
     * проставляет — {@code getGrammarElement() == null}. Такой лист тоже считаем объявлением
     * (текст ровно «Функция»/«Процедура», не имя свойства): именно эти листья и дают
     * {@code count > module.allMethods()}.
     */
    private static int countMethodKeywordLeaves(ICompositeNode rootNode)
    {
        if (rootNode == null)
            return 0;
        int count = 0;
        for (org.eclipse.xtext.nodemodel.ILeafNode leaf : rootNode.getLeafNodes())
            if (isMethodDeclarationLeaf(leaf))
                count++;
        return count;
    }

    /**
     * Лист узловой модели — начало объявления метода: текст ровно {@code Процедура}/
     * {@code Функция} (регистр не важен) и грамматический элемент — {@code Keyword} правила
     * {@code Procedure}/{@code Function}, либо {@code null} (зона обрыва). Доступ к свойству
     * {@code Обработчик.Процедура} сюда не попадает: там {@code Keyword} правила {@code ExtName}.
     */
    private static boolean isMethodDeclarationLeaf(org.eclipse.xtext.nodemodel.ILeafNode leaf)
    {
        if (leaf == null || leaf.isHidden())
            return false;

        EObject ge = leaf.getGrammarElement();
        if (ge instanceof org.eclipse.xtext.Keyword keyword)
        {
            /*
             * Быстрый путь без getText(): значение лексемы берём прямо из грамматики. «Процедура»/
             * «Функция» — это Keyword и в объявлении метода (правила Procedure/Function), и в имени
             * свойства (Обработчик.Процедура — правило ExtName перечисляет ключевые слова как имена).
             * Отличаем по объемлющему правилу.
             */
            if (!isMethodKeyword(keyword.getValue()))
                return false;
            org.eclipse.xtext.AbstractRule rule = org.eclipse.xtext.GrammarUtil.containingRule(keyword);
            String ruleName = rule != null ? rule.getName() : null;
            return "Procedure".equals(ruleName) || "Function".equals(ruleName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (ge != null)
            return false;
        /*
         * Грамматический элемент не проставлен — зона обрыва, где метод не разобрался. Здесь
         * getText() неизбежен, но такие листья единичны (только внутри оборванного участка).
         */
        String text = leaf.getText();
        return text != null && isMethodKeyword(text.trim());
    }

    private static boolean isMethodKeyword(String value)
    {
        for (String keyword : METHOD_KEYWORDS)
            if (keyword.equalsIgnoreCase(value))
                return true;
        return false;
    }

    private static INode errorNodeSwallowingDeclaration(IParseResult parseResult)
    {
        Iterable<INode> errors = parseResult != null ? parseResult.getSyntaxErrors() : null;
        if (errors == null)
            return null;
        for (INode error : errors)
            if (error != null && countDeclarations(error.getText()) > 0)
                return error;
        return null;
    }

    /**
     * Та ошибка, из-за которой дерево оборвалось, — не обязательно первая в тексте.
     *
     * <p>В модуле их обычно несколько: сначала идут безобидные, после которых разбор
     * восстановился, и лишь одна обрывает дерево. Показывать пользователю нужно именно её,
     * иначе он идёт править не то место (первая по тексту ошибка может быть вообще в другом
     * методе).
     *
     * <p>Порядок отбора: узел ошибки, поглотивший объявление метода (именно он съел остаток
     * модуля) → первая ошибка, начиная с начала последнего разобранного метода (на нём разбор
     * и остановился, см. {@link #lastParsedMethodStart}) → последняя ошибка (дерево оборвано,
     * но точнее определить нечем). Если в AST нет ни одного метода, берётся первая ошибка —
     * разбор не пережил и её.
     *
     * @return узел синтаксической ошибки либо {@code null}, если ошибок нет
     */
    public static INode truncatingError(IParseResult parseResult)
    {
        INode swallowing = errorNodeSwallowingDeclaration(parseResult);
        if (swallowing != null)
            return swallowing;

        Iterable<INode> errors = parseResult != null ? parseResult.getSyntaxErrors() : null;
        if (errors == null)
            return null;

        int parsedStart = lastParsedMethodStart(parseResult);
        INode last = null;
        for (INode error : errors)
        {
            if (error == null)
                continue;
            last = error;
            if (parsedStart < 0 || error.getOffset() >= parsedStart)
                return error;
        }
        return last;
    }

    /**
     * Начало последнего метода, попавшего в AST; {@code -1}, если методов нет.
     *
     * <p>Именно начало, а не конец: ошибка, оборвавшая разбор, чаще всего лежит <b>внутри</b>
     * этого метода — он последний, который парсер сумел собрать, а на ошибке внутри него всё и
     * закончилось (проверено на модуле, где после такой ошибки в AST не попали два следующих
     * метода). Отбор по концу метода такую ошибку не видел и уходил на каскадную ошибку
     * где-нибудь у {@code КонецПроцедуры}.
     */
    private static int lastParsedMethodStart(IParseResult parseResult)
    {
        EObject root = parseResult.getRootASTElement();
        if (!(root instanceof Module module))
            return -1;
        int start = -1;
        for (EObject method : module.allMethods())
        {
            ICompositeNode node = NodeModelUtils.getNode(method);
            if (node != null)
                start = Math.max(start, node.getOffset());
        }
        return start;
    }

    /**
     * Объявления методов в тексте: слово {@code Процедура}/{@code Функция} (и англоязычные
     * синонимы) в начале слова, вне строк и однострочных комментариев. Внутри
     * {@code КонецПроцедуры}/{@code КонецФункции} не считается — перед словом обязателен
     * не-буквенный символ.
     *
     * <p>Применяется только к тексту узла синтаксической ошибки (см.
     * {@link #errorNodeSwallowingDeclaration}), а не ко всему модулю: там объём мал и цена
     * ложного совпадения — не «весь модуль как текст», а всего лишь непоказанная пометка.
     */
    private static int countDeclarations(String text)
    {
        if (text == null || text.isEmpty())
            return 0;
        int count = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (inString)
            {
                if (c == '"')
                    inString = false;
                continue;
            }
            if (c == '"')
            {
                inString = true;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')
            {
                int lineEnd = text.indexOf('\n', i);
                if (lineEnd < 0)
                    break;
                i = lineEnd;
                continue;
            }
            if (!isWordStart(text, i))
                continue;
            for (String keyword : METHOD_KEYWORDS)
                if (text.regionMatches(true, i, keyword, 0, keyword.length())
                    && isWordEnd(text, i + keyword.length()))
                {
                    count++;
                    i += keyword.length() - 1;
                    break;
                }
        }
        return count;
    }

    private static boolean isWordStart(String text, int index)
    {
        return index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1)) && text.charAt(index - 1) != '_';
    }

    private static boolean isWordEnd(String text, int index)
    {
        return index >= text.length()
            || !Character.isLetterOrDigit(text.charAt(index)) && text.charAt(index) != '_';
    }
}
