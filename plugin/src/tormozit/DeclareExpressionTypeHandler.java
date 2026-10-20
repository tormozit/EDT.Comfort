package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslDocCommentSerializer;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
import com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection;
import com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection.TypeDefinition;
import com._1c.g5.v8.dt.bsl.documentation.comment.TextPart;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.ui.contentassist.BslCommentUiUtils;
import com._1c.g5.v8.dt.bsl.ui.contentassist.BslProposalProvider;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.lcore.ui.texteditor.IndentTextEditorProvider;
import com._1c.g5.v8.dt.mcore.Environmental;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Объявление типа выражения или параметра/результата метода, с приоритетом подключённого ИР.
 */
public final class DeclareExpressionTypeHandler
{
    public static final String COMMAND_ID = "tormozit.IrDeclareExpressionType"; //$NON-NLS-1$
    static final String MENU_LABEL = "Объявить тип выражения"; //$NON-NLS-1$

    private DeclareExpressionTypeHandler() {}

    public static void declareExpressionType(BslXtextEditor editor)
    {
        if (editor == null)
        {
            toast(MENU_LABEL, "Откройте модуль BSL и повторите команду"); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        IRSession irSession = IRApplication.peekConnectedSession(dtProject);
        if (irSession == null || irSession.executor == null)
        {
            LocalDeclaration.execute(editor);
            return;
        }

        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() -> declareOnComThread(irSession, editor));
    }

    /** COM-поток {@link IRSession#executor}: без вложенного {@link IRSession#executeOnComThread}. */
    private static void declareOnComThread(IRSession irSession, BslXtextEditor editor)
    {
        try
        {
            ensureCodeEditor(irSession);
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            long language = ComBridge.toLong(
                ComBridge.getProperty(irSession.codeEditor, "мЯзыкПрограммы")); //$NON-NLS-1$
            if (language != 0)
            {
                toast(MENU_LABEL, "Команда доступна только в модуле"); //$NON-NLS-1$
                return;
            }

            boolean declared = ComBridge.toBoolean(
                irSession.invokeCodeEditor("ОбъявитьТипВыражения")); //$NON-NLS-1$
            if (!declared)
                return;

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> irSession.syncCodeEditorFromIR(editor));
        }
        catch (Exception e)
        {
            Global.log("DeclareExpressionTypeHandler: " + e.getMessage()); //$NON-NLS-1$
            toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    /** Локальное объявление через AST и парсер комментариев EDT. Все offset — модельные. */
    private static final class LocalDeclaration
    {
        static void execute(BslXtextEditor editor)
        {
            var viewer = editor.getInternalSourceViewer();
            var document = editor.getDocument();
            if (viewer == null || document == null || !editor.isEditable())
                return;
            int caret = viewer.getSelectedRange().x;
            try
            {
                Edit edit = document.readOnly(resource -> plan(resource, document, caret));
                if (edit == null)
                {
                    toast(MENU_LABEL, "Не найдено присвоение выражению в текущей ветви или объявление параметра/результата");
                    return;
                }
                if (!edit.text().isEmpty() || edit.replaced() > 0)
                    document.replace(edit.offset(), edit.replaced(), edit.text());
                if (viewer instanceof ITextViewerExtension5 extension)
                    extension.exposeModelRange(new Region(edit.selection(), edit.length()));
                viewer.setSelectedRange(edit.selection(), edit.length());
                viewer.revealRange(edit.selection(), edit.length());
            }
            catch (Exception e)
            {
                toast(MENU_LABEL, "Не удалось объявить тип: " + e.getMessage(), 5_000);
            }
        }

        private static Edit plan(XtextResource resource, IDocument document, int caret)
            throws BadLocationException
        {
            if (resource == null || resource.getParseResult() == null || document.getLength() == 0)
                return null;
            int lookup = Math.min(caret, document.getLength() - 1);
            if (caret > 0 && Character.isJavaIdentifierPart(document.getChar(caret - 1))
                && (caret == document.getLength() || !Character.isJavaIdentifierPart(document.getChar(caret))))
                lookup = caret - 1;
            ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), lookup);
            if (leaf == null)
                return null;
            EObject object = NodeModelUtils.findActualSemanticObjectFor(leaf);
            Method method = EcoreUtil2.getContainerOfType(object, Method.class);
            FormalParam parameter = EcoreUtil2.getContainerOfType(object, FormalParam.class);
            Statement statement = EcoreUtil2.getContainerOfType(object, Statement.class);
            if (method != null && statement == null && inMethodHeader(method, caret))
                return methodEdit(resource, document, method, parameter);
            Expression expression = EcoreUtil2.getContainerOfType(object, Expression.class);
            if (!(expression instanceof FeatureAccess) && !(expression instanceof IndexAccess))
                return null;
            while (expression.eContainer() instanceof IndexAccess index && index.getSource() == expression)
                expression = index;
            String key = expressionKey(expression);
            for (EObject cursor = expression; cursor != null && !(cursor instanceof Method);
                cursor = cursor.eContainer())
            {
                if (!(cursor instanceof Statement))
                    continue;
                if (cursor instanceof SimpleStatement assignment && matches(assignment, key))
                    return withComputedType(sideCommentEdit(document, assignment), assignment.getRight(), resource);
                EObject parent = cursor.eContainer();
                if (parent == null || !cursor.eContainingFeature().isMany())
                    continue;
                // Не заходим в тела предыдущих инструкций: соседняя ветвь не является текущей.
                Object value = parent.eGet(cursor.eContainingFeature());
                if (value instanceof List<?> siblings)
                    for (int i = siblings.indexOf(cursor) - 1; i >= 0; i--)
                        if (siblings.get(i) instanceof SimpleStatement assignment && matches(assignment, key))
                            return withComputedType(sideCommentEdit(document, assignment), assignment.getRight(), resource);
            }
            // Ссылка на параметр в теле метода: его описание служит объявлением типа,
            // если в текущей ветви нет более близкого присвоения.
            if (method != null && expression instanceof StaticFeatureAccess access)
                for (var entry : access.getFeatureEntries())
                    if (entry.getFeature() instanceof FormalParam referenced
                        && EcoreUtil2.getContainerOfType(referenced, Method.class) == method)
                        return methodEdit(resource, document, method, referenced);
            return null;
        }

        private static boolean inMethodHeader(Method method, int caret)
        {
            int depth = 0;
            for (ILeafNode leaf : NodeModelUtils.findActualNodeFor(method).getLeafNodes())
            {
                if (leaf.isHidden())
                    continue;
                if (leaf.getText().equals("("))
                    depth++;
                else if (leaf.getText().equals(")") && --depth == 0)
                    return caret <= leaf.getEndOffset();
            }
            return false;
        }

        private static Edit withComputedType(Edit edit, EObject target, XtextResource resource)
        {
            if (edit == null || edit.length() > 0)
                return edit;
            Environmental owner = EcoreUtil2.getContainerOfType(target, Environmental.class);
            if (owner == null || owner.environments() == null)
                return edit;
            var types = resource.getResourceServiceProvider().get(TypesComputer.class)
                .computeTypes(target, owner.environments());
            String computed = SmartContentAssistProcessor.ReceiverTypeLabel.formatTypeItems(types);
            if (computed.isBlank())
                return edit;
            return insertComputedType(edit, computed);
        }

        /** План всё ещё является одной вставкой: форматирование и тип отменяются одним действием. */
        private static Edit insertComputedType(Edit edit, String computed)
        {
            if (edit.text().isEmpty())
                return new Edit(edit.selection(), computed, edit.selection(), computed.length());
            int relative = edit.selection() - edit.offset();
            String text = edit.text().substring(0, relative) + computed + edit.text().substring(relative);
            return new Edit(edit.offset(), edit.replaced(), text, edit.selection(), computed.length());
        }

        private static boolean matches(SimpleStatement assignment, String key)
        {
            return assignment.getRight() != null && assignment.getLeft() != null
                && expressionKey(assignment.getLeft()).equals(key);
        }

        private static String expressionKey(Expression expression)
        {
            INode node = NodeModelUtils.findActualNodeFor(expression);
            StringBuilder key = new StringBuilder();
            if (node != null)
                for (ILeafNode leaf : node.getLeafNodes())
                    if (!leaf.isHidden())
                    {
                        String token = leaf.getText();
                        key.append(token.startsWith("\"") ? token
                            : token.toLowerCase(java.util.Locale.ROOT)).append('\0');
                    }
            return key.toString();
        }

        private static Edit sideCommentEdit(IDocument document, SimpleStatement assignment)
            throws BadLocationException
        {
            int end = NodeModelUtils.findActualNodeFor(assignment).getEndOffset();
            IRegion line = document.getLineInformationOfOffset(end);
            int limit = line.getOffset() + line.getLength();
            // SEMICOLON — разделитель списка инструкций, в SimpleStatement не входит.
            int tail = end;
            while (tail < limit && Character.isWhitespace(document.getChar(tail)))
                tail++;
            if (tail < limit && document.getChar(tail) == ';')
                end = tail + 1;
            String suffix = document.get(end, limit - end);
            int comment = suffix.indexOf("//");
            if (comment < 0)
                return suffix.isBlank() ? new Edit(limit, " // ", limit + 4, 0) : null;
            if (!suffix.substring(0, comment).isBlank())
                return null;
            int start = end + comment;
            BslDocumentationComment parsed = new BslDocumentationComment();
            parsed.parseOldFormat(List.of("// Возвращаемое значение:", document.get(start, limit - start)), List.of());
            TypeDefinition type = targetType(parsed, null);
            if (type != null)
                return new Edit(0, "", start + type.getNameOffset(), type.getTypeName().length());
            int insert = start + 2;
            while (insert < limit && Character.isWhitespace(document.getChar(insert)))
                insert++;
            if (insert == limit)
            {
                // После // уже есть место ввода: повторный вызов не меняет документ.
                String addition = insert == start + 2 ? " " : "";
                return new Edit(insert, addition, insert + addition.length(), 0);
            }
            return new Edit(insert, " - ", insert + 1, 0);
        }

        private static Edit methodEdit(XtextResource resource, IDocument document, Method method,
            FormalParam parameter) throws BadLocationException
        {
            if (parameter == null && !(method instanceof Function))
                parameter = method.getFormalParams().isEmpty() ? null : method.getFormalParams().get(0);
            if (parameter == null && !(method instanceof Function))
                return null;
            var services = resource.getResourceServiceProvider();
            var provider = services.get(BslMultiLineCommentDocumentationProvider.class);
            var manager = services.get(IV8ProjectManager.class);
            var project = manager.getProject(resource.getURI());
            boolean old = project == null || project.getProject() == null
                || services.get(IBslPreferences.class).getDocumentCommentProperties(project.getProject()).oldCommentFormat();
            List<INode> nodes = provider.getDocumentationNodes(method);
            String parameterIndent = services.get(IndentTextEditorProvider.class).getIndent();
            if (nodes.isEmpty())
            {
                int offset = NodeModelUtils.findActualNodeFor(method).getOffset();
                String separator = services.get(BslProposalProvider.class)
                    .getLineFormatter((org.eclipse.xtext.ui.editor.model.IXtextDocument) document, offset);
                boolean russian = BslUtil.isRussian(method, manager);
                String template;
                if (ComfortSettings.isEconomicalMethodCommentEnabled())
                {
                    template = BslCommentUiUtils.createTemplateTextForMethod(method, ".", separator,
                        parameterIndent, services.get(TypesComputer.class), manager, provider, old, russian);
                    template = emptyGeneratedDescriptions(template, method, parameter, separator, parameterIndent,
                        old, russian);
                }
                else
                    template = BslCommentUiUtils.createOrUpdateTemplateForMethod(document, offset, method,
                        separator, parameterIndent, services.get(TypesComputer.class), provider, manager, old).getContent();
                String text = template + separator;
                org.eclipse.jface.text.Document generated = new org.eclipse.jface.text.Document(text);
                List<Integer> starts = new ArrayList<>();
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < generated.getNumberOfLines(); i++)
                {
                    IRegion line = generated.getLineInformation(i);
                    String raw = generated.get(line.getOffset(), line.getLength());
                    int commentStart = raw.indexOf("//");
                    if (commentStart >= 0)
                    {
                        starts.add(line.getOffset() + commentStart);
                        lines.add(raw.substring(commentStart));
                    }
                }
                BslDocumentationComment parsed = BslCommentUtils.parseTemplateComment(lines, method, old);
                String delimiter = generated.getLineDelimiter(0);
                if (delimiter == null)
                    delimiter = "\r\n";
                String indent = separator.substring(delimiter.length());
                Edit target = commentEdit(generated, parsed, parameter, starts,
                    text.length() - separator.length(), delimiter, indent, parameterIndent, old);
                return withComputedType(new Edit(offset, text.substring(0, target.offset()) + target.text()
                    + text.substring(target.offset()), offset + target.selection(), target.length()),
                    parameter == null ? method : parameter, resource);
            }
            // В SL_COMMENT входит перевод строки. Парсеру передаём ровно одну строку на узел;
            // вставку делаем перед её переводом, чтобы не превратить заголовок метода в комментарий.
            BslDocumentationComment parsed = BslCommentUtils.parseTemplateComment(
                nodes.stream().map(node -> node.getText().stripTrailing()).toList(), method, old);
            IRegion lastLine = document.getLineInformationOfOffset(nodes.get(nodes.size() - 1).getOffset());
            int end = lastLine.getOffset() + lastLine.getLength();
            String separator = document.getLineDelimiter(document.getLineOfOffset(end));
            if (separator == null)
                separator = "\r\n";
            IRegion line = document.getLineInformationOfOffset(nodes.get(0).getOffset());
            String indent = document.get(line.getOffset(), nodes.get(0).getOffset() - line.getOffset());
            return withComputedType(commentEdit(document, parsed, parameter, nodes.stream().map(INode::getOffset).toList(),
                end, separator, indent, parameterIndent, old), parameter == null ? method : parameter, resource);
        }

        /**
         * Только новый полный комментарий: типы сохраняем, описания из имён не переносим.
         * Параметры без типа убираем (кроме {@code target} — ему команда сейчас объявит тип),
         * опустевший раздел «Параметры:» — тоже.
         */
        private static String emptyGeneratedDescriptions(String template, Method method, FormalParam target,
            String separator, String parameterIndent, boolean old, boolean russian)
        {
            List<String> lines = template.lines().filter(line -> line.stripLeading().startsWith("//"))
                .map(line -> line.substring(line.indexOf("//"))).toList();
            BslDocumentationComment comment = BslCommentUtils.parseTemplateComment(lines, method, old);
            var description = comment.getDescription();
            description.getParts().clear();
            description.addPart(new TextPart(description, -1, "."));
            if (comment.getParametersSection() != null)
                for (var field : comment.getParametersSection().getParameterDefinitions())
                {
                    emptyGeneratedTypeDescriptions(field.getTypeSections());
                    field.getDescription().getParts().clear();
                }
            if (comment.getParametersSection() != null)
            {
                var fields = comment.getParametersSection().getParameterDefinitions();
                fields.removeIf(field -> (target == null || !field.getName().equalsIgnoreCase(target.getName()))
                    && field.getTypeSections().stream().allMatch(section -> section.getTypeDefinitions().isEmpty()));
                if (fields.isEmpty())
                    comment.setParametersSection(null);
            }
            if (comment.getReturnSection() != null)
                emptyGeneratedTypeDescriptions(comment.getReturnSection().getReturnTypes());
            String delimiter = separator.startsWith("\r\n") ? "\r\n" : separator.substring(0, 1);
            String text = BslDocCommentSerializer.newBuilder().setOldFormat(old).setScriptVariant(russian)
                .lineSeparator(delimiter).ignoreLineNumbers().build()
                .serialize(comment, separator, parameterIndent);
            return text.lines().filter(line -> !line.isBlank() && !line.strip().equals("//"))
                .collect(java.util.stream.Collectors.joining(delimiter));
        }

        private static void emptyGeneratedTypeDescriptions(List<TypeSection> sections)
        {
            for (TypeSection section : sections)
            {
                // Разбор типов ленивый: материализуем их до очистки описания.
                section.getTypeDefinitions();
                var description = section.getDescription();
                description.getParts().clear();
                description.addPart(new TextPart(description, -1, ""));
            }
        }

        private static TypeDefinition targetType(BslDocumentationComment comment, FormalParam parameter)
        {
            List<TypeSection> types;
            if (parameter != null)
            {
                var section = comment.getParametersSection();
                var field = section == null ? null : section.getParameterByName(parameter.getName());
                if (field == null)
                    return null;
                types = field.getTypeSections();
            }
            else
            {
                var section = comment.getReturnSection();
                if (section == null)
                    return null;
                types = section.getReturnTypes();
            }
            for (TypeSection section : types)
                for (TypeDefinition type : section.getTypeDefinitions())
                    if (type.getTypeName() != null && !type.getTypeName().isBlank())
                        return type;
            return null;
        }

        private static Edit commentEdit(IDocument document, BslDocumentationComment comment,
            FormalParam parameter, List<Integer> starts, int end, String separator, String indent,
            String parameterIndent, boolean old)
            throws BadLocationException
        {
            TypeDefinition type = targetType(comment, parameter);
            if (type != null)
                return new Edit(0, "", starts.get(type.getLineNumber()) + type.getNameOffset(),
                    type.getTypeName().length());
            var section = parameter == null ? comment.getReturnSection() : comment.getParametersSection();
            if (parameter != null && comment.getParametersSection() != null)
            {
                var field = comment.getParametersSection().getParameterByName(parameter.getName());
                if (field != null)
                {
                    int offset = starts.get(field.getLineNumber()) + field.getNameOffset() + field.getName().length();
                    IRegion line = document.getLineInformationOfOffset(offset);
                    int limit = line.getOffset() + line.getLength();
                    while (offset < limit && Character.isWhitespace(document.getChar(offset)))
                        offset++;
                    if (!old)
                    {
                        if (offset < limit && document.getChar(offset) == '-')
                        {
                            offset++;
                            while (offset < limit && Character.isWhitespace(document.getChar(offset)))
                                offset++;
                        }
                        if (offset < limit && document.getChar(offset) == '[')
                            return new Edit(0, "", offset + 1, 0);
                        return new Edit(offset, "[] ", offset + 1, 0);
                    }
                    if (offset < limit && document.getChar(offset) == '-')
                        return new Edit(offset + 1, " ", offset + 2, 0);
                    // Хвостовые пробелы после одного имени заменяем разделителем, а не оставляем перед ним.
                    int nameEnd = starts.get(field.getLineNumber()) + field.getNameOffset() + field.getName().length();
                    if (offset == limit)
                        return new Edit(nameEnd, limit - nameEnd, " - ", nameEnd + 3, 0);
                    return new Edit(offset, " - ", offset + 3, 0);
                }
            }
            if (section != null)
            {
                IRegion line = document.getLineInformationOfOffset(starts.get(section.getLineNumber()));
                end = line.getOffset() + line.getLength();
                if (parameter == null && section.getLineNumber() + 1 < starts.size())
                {
                    int next = starts.get(section.getLineNumber() + 1);
                    IRegion nextLine = document.getLineInformationOfOffset(next);
                    String raw = document.get(next, nextLine.getOffset() + nextLine.getLength() - next);
                    String content = raw.substring(2).trim();
                    if (old && content.isEmpty())
                    {
                        if (raw.equals("//") || raw.equals("// "))
                        {
                            String addition = (raw.equals("//") ? " " : "") + parameterIndent;
                            int insert = next + raw.length();
                            return new Edit(insert, addition, insert + addition.length(), 0);
                        }
                        return new Edit(0, "", next + raw.length(), 0);
                    }
                    if (!old && content.equals("[]"))
                        return new Edit(0, "", next + raw.indexOf('[') + 1, 0);
                }
            }
            String prefix = separator + indent + "// ";
            String entryPrefix = "// " + parameterIndent;
            if (parameter != null)
            {
                if (comment.getParametersSection() != null)
                    for (var existing : comment.getParametersSection().getParameterDefinitions())
                    {
                        int start = starts.get(existing.getLineNumber());
                        String rawPrefix = document.get(start, existing.getNameOffset());
                        String candidate = parameterLinePrefix(rawPrefix);
                        if (candidate != null)
                        {
                            entryPrefix = candidate;
                            break;
                        }
                    }
            }
            if (section == null && parameter != null && comment.getReturnSection() != null)
            {
                // «Параметры:» по стандарту идут перед «Возвращаемое значение:».
                int insert = starts.get(comment.getReturnSection().getLineNumber());
                String text = "// Параметры:" + separator + indent + entryPrefix + parameter.getName() + " - ";
                int selection = insert + text.length();
                if (!old)
                {
                    text += "[]";
                    selection++;
                }
                return new Edit(insert, text + separator + indent, selection, 0);
            }
            String text = separator + indent + entryPrefix;
            if (section == null)
                text = prefix + (parameter == null ? "Возвращаемое значение:" : "Параметры:")
                    + separator + indent + entryPrefix;
            if (parameter != null)
                text += parameter.getName() + " - ";
            int selection = end + text.length();
            if (!old)
            {
                text += "[]";
                selection++;
            }
            return new Edit(end, text, selection, 0);
        }

        /** Копируем пробелы и табуляции после // без пересчёта в экранные колонки. */
        private static String parameterLinePrefix(String prefix)
        {
            return prefix.startsWith("//") && prefix.substring(2).isBlank() ? prefix : null;
        }

        /** {@code replaced} — длина заменяемого с {@code offset} текста; {@code selection} — уже после замены. */
        private static record Edit(int offset, int replaced, String text, int selection, int length)
        {
            Edit(int offset, String text, int selection, int length)
            {
                this(offset, 0, text, selection, length);
            }
        }
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static void toast(String title, String message)
    {
        toast(title, message, 3_000);
    }

    private static void toast(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, durationMs));
    }
}
